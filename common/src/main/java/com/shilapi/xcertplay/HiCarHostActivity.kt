package com.shilapi.xcertplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.hicar.HiCarController
import com.shilapi.xcertplay.hicar.HiCarRuntimeConfig
import com.shilapi.xcertplay.hicar.HiCarStatus
import java.security.MessageDigest

/**
 * Experimental HiCar receiver screen.
 *
 * It is deliberately a separate, non-launcher activity: the receiver stops at HiChain, so it is a
 * diagnostic surface for the connection chain rather than a feature the driver should reach from
 * the CarPlay home screen. Start it from a shell during bring-up:
 *
 * ```
 * adb shell am start -n com.shihab.diplay/com.shilapi.xcertplay.HiCarHostActivity
 * ```
 *
 * Copy is not localized yet: every string here is a bring-up label, and adding it to the resource
 * files would require the twelve locale variants the Settings tests enforce. See the TODO in
 * `docs/HICAR_MIGRATION.md`.
 */
class HiCarHostActivity : ComponentActivity() {
    private var status by mutableStateOf<HiCarStatus>(HiCarStatus.Idle)
    private var model by mutableStateOf("")
    private var running by mutableStateOf(false)
    private val log = mutableStateListOf<String>()
    private var controller: HiCarController? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) startReceiver() else appendLog("Bluetooth permissions were not granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = experimentalModel()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Content()
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.close()
        controller = null
        super.onDestroy()
    }

    @Composable
    private fun Content() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("HiCar receiver (experimental)", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Connection chain: access point, insecure RFCOMM records, 0xFDEE advertisement, " +
                    "channel negotiation, DMSDP control channel. Authentication stops at HiChain, " +
                    "which needs Huawei's native engine and a provisioned accessory credential. " +
                    "No video, audio, steering-wheel or HUD capability is available in this build.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it.uppercase().take(8) },
                label = { Text("Advertised model (8 hex digits)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::toggle, enabled = model.length == 8) {
                    Text(if (running) "Stop" else "Start")
                }
                Button(onClick = { log.clear() }) { Text("Clear log") }
            }
            Text("Status: ${describe(status)}", style = MaterialTheme.typography.titleSmall)
            Text("Identity: ${identity()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            // A plain column, not a lazy list: this screen already scrolls as a whole, and a nested
            // lazy list inside a vertical scroll would be measured with an infinite height.
            Column(modifier = Modifier.fillMaxWidth()) {
                for (line in log) {
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    private fun toggle() {
        if (running) {
            stopReceiver()
        } else if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermissions()) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            startReceiver()
        }
    }

    private fun startReceiver() {
        if (running) return
        running = true
        log.clear()
        val config = HiCarRuntimeConfig(
            model = model,
            localIdentity = identity(),
            deviceName = "HiCar receiver",
        )
        val next = HiCarController(
            context = this,
            config = config,
            report = { next -> runOnUiThread { status = next } },
            onLog = { line -> runOnUiThread { appendLog(line) } },
        )
        controller = next
        next.start()
    }

    private fun stopReceiver() {
        running = false
        controller?.close()
        controller = null
        status = HiCarStatus.Stopped
    }

    private fun appendLog(line: String) {
        // The list is capped: a reconnect loop must not grow without bound on a head unit.
        if (log.size >= MAX_LOG_LINES) log.removeAt(0)
        log.add(line)
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    /** The identity the phone sees; derived from this installation, not a Huawei credential. */
    private fun identity(): String {
        val seed = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.copyOf(8).joinToString("") { "%02x".format(it) }
    }

    /** Eight hexadecimal digits for the 0xFDEE payload; an experiment id, not a certified model. */
    private fun experimentalModel(): String =
        identity().take(8).uppercase().padEnd(8, '0')

    private fun describe(status: HiCarStatus): String = when (status) {
        is HiCarStatus.Idle -> "idle"
        is HiCarStatus.PreparingNetwork -> "preparing the access point"
        is HiCarStatus.NetworkReady -> "access point ${status.ssid} at ${status.address} (${status.backend})"
        is HiCarStatus.Advertising -> "starting the 0xFDEE advertisement"
        is HiCarStatus.WaitingForPhone -> "waiting for the phone"
        is HiCarStatus.ChannelNegotiated -> "business channel announced, waiting for authentication"
        is HiCarStatus.Authenticating -> "authenticating"
        is HiCarStatus.Blocked -> "blocked: ${status.blocker.message}"
        is HiCarStatus.Failed -> "failed: ${status.reason}"
        is HiCarStatus.Stopped -> "stopped"
    }

    private companion object {
        const val MAX_LOG_LINES = 200
    }
}
