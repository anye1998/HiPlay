package com.shilapi.xcertplay

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.pm.PackageInfoCompat
import com.shilapi.xcertplay.hicar.HiCarController
import com.shilapi.xcertplay.hicar.HiCarRuntimeConfig
import com.shilapi.xcertplay.hicar.HiCarStatus
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Experimental HiCar receiver screen.
 *
 * It is deliberately a separate, non-launcher activity: the chain has no media stage yet, so it is a
 * diagnostic surface rather than a feature the driver should reach from the CarPlay home screen.
 * The log can be written to a file from here, because the run that fails is usually over before
 * anyone can read the screen. Start it from a shell during bring-up:
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
    private var pin by mutableStateOf("")
    private var running by mutableStateOf(false)
    private val log = mutableStateListOf<String>()
    private var controller: HiCarController? = null

    /** Longer than [log]: the screen keeps a readable tail, the export keeps the whole attempt. */
    private val history = ArrayList<String>()
    private var droppedFromHistory = 0
    private var exporting by mutableStateOf(false)
    private var exportUri by mutableStateOf<Uri?>(null)
    private var exportNotice by mutableStateOf("")

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val refused = granted.filterValues { !it }.keys
        if (refused.isEmpty()) {
            startReceiver()
        } else {
            // Name them: the access point needs Wi-Fi Direct, which fails without these, and a bare
            // "permissions were not granted" hides which step is about to fail.
            appendLog("Permissions refused: ${refused.joinToString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = experimentalModel()
        pin = storedPin()
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
                    "channel negotiation, HiChain authentication, DMSDP control channel. " +
                    "Video, audio, steering-wheel and HUD are not implemented, and no HiChain exchange " +
                    "has been verified against a real phone yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it.uppercase().take(8) },
                label = { Text("Advertised model (8 hex digits)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Connection code: $pin   (the phone asks for these six digits the first time)",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::toggle, enabled = model.length == 8) {
                    Text(if (running) "Stop" else "Start")
                }
                Button(onClick = ::clearLog) { Text("Clear log") }
                Button(onClick = ::resetPairing) { Text("Reset pairing") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The export survives the session: a failed attempt is usually over by the time the
                // driver decides to report it, and a screenshot loses the part above the fold.
                Button(onClick = ::exportLog, enabled = !exporting) {
                    Text(if (exporting) "Exporting…" else "Export log")
                }
                Button(onClick = ::shareLog, enabled = exportUri != null) { Text("Share") }
            }
            if (exportNotice.isNotEmpty()) {
                Text(exportNotice, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
        } else if (missingPermissions().isNotEmpty()) {
            permissions.launch(missingPermissions())
        } else {
            startReceiver()
        }
    }

    private fun startReceiver() {
        if (running) return
        running = true
        // The screen starts empty for a new attempt, but the export keeps earlier attempts: a retry
        // usually follows a failure, and the failure is the part worth reporting.
        log.clear()
        appendLog("--- receiver started ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ---")
        val config = HiCarRuntimeConfig(
            model = model,
            localIdentity = identity(),
            deviceName = "HiCar receiver",
            pin = pin,
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
        appendLog("--- receiver stopped ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ---")
    }

    private fun appendLog(line: String) {
        // The list is capped: a reconnect loop must not grow without bound on a head unit.
        if (log.size >= MAX_LOG_LINES) log.removeAt(0)
        log.add(line)
        // The exported history is capped separately and much higher, because the line that explains
        // a failure is often far above the last two hundred.
        if (history.size >= MAX_HISTORY_LINES) {
            history.removeAt(0)
            droppedFromHistory++
        }
        history.add(line)
    }

    private fun clearLog() {
        log.clear()
        // An explicit clear is a request for a clean export as well; the pairing reset below only
        // clears the screen, because the earlier lines still explain the new code.
        history.clear()
        droppedFromHistory = 0
    }

    /**
     * Writes the session log to a file the driver can retrieve.
     *
     * Storage order is the app's existing diagnostic one (Downloads/DiPlay through MediaStore, then
     * the public folder, then app-specific external storage, then internal), so this screen gains no
     * new permission and no document-picker dependency.
     */
    private fun exportLog() {
        if (exporting) return
        exporting = true
        exportUri = null
        exportNotice = "saving…"
        val report = buildReportText()
        val fileName = logFileName()
        val context = applicationContext
        Thread({
            val outcome = runCatching { DiagnosticExportStore.saveWithoutPicker(context, fileName, report) }
            runOnUiThread {
                exporting = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = { saved ->
                        exportUri = saved.uri
                        exportNotice = when {
                            saved.savedInApp -> "saved in app storage; use Share to retrieve it"
                            saved.savedPath != null -> "saved to ${saved.savedPath}"
                            else -> "saved to Downloads/DiPlay/$fileName"
                        }
                        appendLog("Log exported: $exportNotice")
                    },
                    onFailure = { failure ->
                        exportNotice = "export failed: ${failure.javaClass.simpleName}"
                        appendLog("Log export failed: ${failure.message ?: failure.javaClass.simpleName}")
                    },
                )
            }
        }, "hicar-log-export").start()
    }

    private fun shareLog() {
        val uri = exportUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("HiCar log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share HiCar log")) }
            .onFailure {
                // Head units often have no share target; the file is already saved either way.
                exportNotice = "no app accepted the share; the file stays where it was saved"
            }
    }

    private fun logFileName(): String =
        "hicar-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    /** Collects the device facts the exported report needs; the text itself lives in HiCarLogReport.kt. */
    private fun buildReportText(): String {
        val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        return hicarLogReport(
            environment = HiCarLogEnvironment(
                generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()),
                applicationId = packageName,
                versionName = packageInfo?.versionName ?: "?",
                // longVersionCode needs API 28 and minSdk here is 24.
                versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                advertisedModel = model,
                identity = identity(),
                connectionCode = pin,
                running = running,
                status = describe(status),
            ),
            lines = history.toList(),
            droppedLines = droppedFromHistory,
        )
    }

    /**
     * Everything the chain needs, not only Bluetooth.
     *
     * The access point is a Wi-Fi Direct group because HiCar wants 5 GHz (LocalOnlyHotspot cannot be
     * asked for a band before Android 13). Wi-Fi Direct on API 31 is gated by location access, and on
     * API 33+ by NEARBY_WIFI_DEVICES, so both cases are requested up front.
     */
    private fun requiredPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed
    }

    private fun missingPermissions(): Array<String> = requiredPermissions()
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    /**
     * Issues a new six-digit code and forgets the stored trust.
     *
     * The code is only meaningful together with the key material in `files/hicar-huks`: keeping the
     * old store would leave the phone paired to a code this screen no longer shows.
     */
    private fun resetPairing() {
        if (running) stopReceiver()
        val store = getDir("hicar-huks", MODE_PRIVATE)
        store.listFiles()?.forEach { file -> file.delete() }
        pin = String.format("%06d", java.security.SecureRandom().nextInt(1_000_000))
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_PIN, pin).apply()
        log.clear()
        appendLog("Pairing reset: new code $pin, stored trust removed")
    }

    private fun storedPin(): String {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        preferences.getString(KEY_PIN, null)?.takeIf { PIN_PATTERN.matches(it) }?.let { return it }
        val generated = String.format("%06d", java.security.SecureRandom().nextInt(1_000_000))
        preferences.edit().putString(KEY_PIN, generated).apply()
        return generated
    }

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
        is HiCarStatus.Authenticated -> "authenticated, opening the control channel"
        is HiCarStatus.ControlChannelReady -> "control channel ready; the media stage is not implemented"
        is HiCarStatus.Blocked -> "blocked: ${status.blocker.message}"
        is HiCarStatus.Failed -> "failed: ${status.reason}"
        is HiCarStatus.Stopped -> "stopped"
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val MAX_HISTORY_LINES = 5_000
        const val PREFERENCES = "hicar-receiver"
        const val KEY_PIN = "connection_code"
        val PIN_PATTERN = Regex("[0-9]{6}")
    }
}
