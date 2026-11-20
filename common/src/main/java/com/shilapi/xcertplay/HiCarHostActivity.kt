package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.SurfaceTexture
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.shilapi.xcertplay.hicar.HiCarAdvertisementProbe
import com.shilapi.xcertplay.hicar.HiCarController
import com.shilapi.xcertplay.hicar.HiCarPermissions
import com.shilapi.xcertplay.hicar.HiCarRuntimeConfig
import com.shilapi.xcertplay.hicar.HiCarVideoSink
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
 * Copy is Simplified Chinese on purpose, and hardcoded rather than taken from `strings.xml`: this is
 * a bring-up surface for the person holding the head unit, and the labels must read the same no matter
 * which locale the ROM is set to (a head unit that boots in English must not hide the one screen that
 * explains why pairing failed). Moving them into resources would also mean translating every new label
 * into the locale folders `JapaneseTranslationsTest` and `TraditionalChineseTranslationsTest` enforce.
 *
 * The log lines stay English: they are protocol evidence, quoted verbatim in
 * `docs/HICAR_MIGRATION.md`, and read by whoever parses the exported report.
 */
class HiCarHostActivity : ComponentActivity() {
    private var status by mutableStateOf<HiCarStatus>(HiCarStatus.Idle)
    private var model by mutableStateOf("")
    private var btAddress by mutableStateOf("")
    private var pin by mutableStateOf("")
    private var running by mutableStateOf(false)
    private val log = mutableStateListOf<String>()
    private var controller: HiCarController? = null

    /** A scan that only listens, used to read a real head unit's beacon. See [sniff]. */
    private var sniffer: HiCarAdvertisementProbe? = null

    /** The window the picture lands in; kept so a restart can attach it again without recomposition. */
    private var videoView: TextureView? = null
    private var videoTexture: SurfaceTexture? = null

    /** Whether the car's microphone is offered; off unless the driver turns it on. */
    private var microphoneEnabled by mutableStateOf(false)

    /** Longer than [log]: the screen keeps a readable tail, the export keeps the whole attempt. */
    private val history = ArrayList<String>()
    private var droppedFromHistory = 0
    private var exporting by mutableStateOf(false)
    private var probing by mutableStateOf(false)
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

    /** Recording is a separate grant, asked for only when the microphone is switched on. */
    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            microphoneEnabled = true
            rememberMicrophone(true)
            appendLog("microphone: enabled; the phone can ask for the car's voice on the next connection")
        } else {
            appendLog("microphone: permission refused; the car stays without an uplink microphone")
        }
    }

    /**
     * The system's "allow other devices to find this device" prompt.
     *
     * Kept as a manual step, deliberately not part of Start. HiCar itself does not need it: the phone
     * dials the address it reads out of the advertisement, and never runs an inquiry (see 7.10 in
     * `docs/HICAR_MIGRATION.md`). It stays because a phone that already knows this device, and anyone
     * checking with a classic scanner, do work through inquiry — and because a system activity that
     * is missing or refuses the request used to take the whole receiver down with it.
     */
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val verdict = if (result.resultCode > 0) "granted for ${result.resultCode}s" else "declined"
        appendLog("Discoverable request $verdict; press Start to advertise")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        // Shown before the first attempt so the exported report carries it even if the next run works.
        HiCarCrashRecord.take(this)?.let { trace ->
            appendLog("--- previous run ended with an uncaught exception ---")
            trace.lineSequence().filter { it.isNotBlank() }.take(40).forEach { appendLog(it) }
            appendLog("--- end of previous exception ---")
        }
        model = storedModel()
        btAddress = storedBluetoothAddress()
        pin = storedPin()
        microphoneEnabled = storedMicrophone()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Content()
                }
            }
        }
    }

    override fun onDestroy() {
        sniffer?.close()
        sniffer = null
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
            Text("HiCar 接收端（实验）", style = MaterialTheme.typography.headlineSmall)
            Text(
                "连接链路：热点 → RFCOMM 记录 → 0xFDEE 广播 → 通道协商 → HiChain 认证 → " +
                    "DMSDP 控制通道 → 画面、声音与触控。方向盘按键和 HUD 尚未实现；" +
                    "画面与声音在真车上的效果仍待验证。",
                style = MaterialTheme.typography.bodyMedium,
            )
            // The reference receiver's README makes this a prerequisite on some devices, and it is
            // the opposite of the advice we had been giving (delete the phone's record / rotate the
            // identity). Put it above every button so a first-run driver cannot miss it.
            Text(
                "首次使用：先在车机和手机的「系统蓝牙设置」里完成一次普通配对，再回来点「开始」。\n" +
                    "手机弹出 HiCar 提示后连接，输入上面这 6 位即可。配好之后不要反复删配对记录或按「重置配对」——" +
                    "重置只清车机，清不掉手机里的记录，两边状态不一致会一直报认证失败。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { value ->
                    model = value.uppercase().take(8)
                    // Persisted as soon as it is a whole id: the value a phone accepts must survive a
                    // restart, or the next run silently advertises a different device.
                    if (MODEL_PATTERN.matches(model)) rememberModel(model)
                },
                label = { Text("广播机型码（8 位十六进制）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // The model id is the one payload field that can be varied without a rebuild, and it is
            // the first thing a phone can reject: Huawei allocates these ids per product, so a random
            // one is not a head unit any phone has ever seen. 00040A04 is the value a real Mate 80
            // prompts on, so it is the default rather than a button that has to be found first.
            TextButton(
                onClick = {
                    model = if (model == TEST_MODEL) experimentalModel() else TEST_MODEL
                    rememberModel(model)
                },
            ) {
                Text(
                    if (model == TEST_MODEL) {
                        "改用本次安装自己的机型码 ${experimentalModel()}"
                    } else {
                        "改用测试机型码 $TEST_MODEL"
                    },
                )
            }
            // The id the phone keys its stored trust against is derived from this model and the
            // head unit's own Bluetooth address. That address cannot be read from an app uid, so it
            // is typed once and remembered - the same trade the reference receiver makes.
            OutlinedTextField(
                value = btAddress,
                onValueChange = { value ->
                    btAddress = value.uppercase().take(17)
                    if (BLUETOOTH_PATTERN.matches(btAddress)) rememberBluetoothAddress(btAddress)
                },
                label = { Text("本机蓝牙地址（如 98:BB:1E:67:87:ED）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "本机标识 = SHA-256(机型码 + 蓝牙地址) 的 64 位大写十六进制，与手机自己的设备号同形。" +
                    "手机按这个字符串索引存下的公钥：长度少一半，绑定照样能过，但每次重连都会被拒（错误码 17）。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { applyBluetoothIdentity() }) {
                Text("按蓝牙地址派生本机标识")
            }
            Text(
                "当前标识：${identity()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "连接码：$pin",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            // The code is the only shared secret in the handshake, and it changes whenever the trust
            // store is wiped. Saying so next to the digits is cheaper than a failed pairing run.
            Text(
                "手机首次连接时会要求输入这 6 位数字。每按一次「重置配对」，连接码就会变，" +
                    "弹窗出现时以这里显示的为准。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::toggle, enabled = model.length == 8) {
                    Text(if (running) "停止" else "开始")
                }
                Button(onClick = ::clearLog) { Text("清空日志") }
                Button(onClick = ::resetPairing) { Text("重置配对") }
                Button(onClick = ::requestDiscoverable) { Text("允许被发现") }
                // The key store is what the bind exchange signs with, and a broken one is invisible
                // until a phone is present. This runs the same calls without a phone.
                Button(onClick = ::cryptoSelfTest) { Text("加密自检") }
            }
            // Drivers keep pressing this before every attempt. It is the one control that invalidates
            // a working pairing, so the cost belongs next to it rather than in a comment.
            Text(
                "只有配对出错时才按「重置配对」：它会清掉已保存的信任关系并换发一个新的连接码，" +
                    "之前已经配对成功的手机需要重新配对。连接码本身重启后仍保留。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The export survives the session: a failed attempt is usually over by the time the
                // driver decides to report it, and a screenshot loses the part above the fold.
                Button(onClick = ::exportLog, enabled = !exporting) {
                    Text(if (exporting) "导出中…" else "导出日志")
                }
                Button(onClick = ::shareLog, enabled = exportUri != null) { Text("分享") }
                // One press, sixty seconds, no advertisement of our own: the only way to read what a
                // real head unit actually puts on the air.
                Button(onClick = ::sniff) { Text("嗅探 0xFDEE（60 秒）") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The same block the export carries, printed on screen: on a head unit without a
                // file manager, reading it here and photographing it is faster than exporting.
                Button(onClick = ::showAudit) { Text("设备体检") }
                // Explicitly triggered, read-only, and it says so: reading bt_config.conf needs su.
                Button(onClick = ::rootProbe) { Text("root 探针") }
                // The escape hatch for a phone that still believes it is paired with us: a new
                // identity is a car it has no record of, so the next attempt binds from scratch.
                Button(onClick = ::rotateIdentity) { Text("换一个本机标识") }
            }
            Text(
                "只有手机反复报「认证失败」、而车机这边已经重置过配对时，才用「换一个本机标识」：" +
                    "手机认的就是这个标识，换了它，手机就会把这台车当成一台没连过的新车，重新问你一次连接码。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (exportNotice.isNotEmpty()) {
                Text(exportNotice, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text("状态：${describe(status)}", style = MaterialTheme.typography.titleSmall)
            // The picture sits directly under the status line: it is the one thing the driver looks
            // at, and on a head unit the log below is not worth scrolling past to find it.
            VideoPanel()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The two keys a phone screen cannot be driven without once it is mirrored: the
                // system back and home. They go through the same encrypted UIBC channel as touches.
                TextButton(onClick = { sendKey(KEY_BACK, "返回") }) { Text("返回") }
                TextButton(onClick = { sendKey(KEY_HOME, "主页") }) { Text("主页") }
                TextButton(onClick = ::toggleMicrophone) {
                    Text(if (microphoneEnabled) "麦克风：开" else "麦克风：关")
                }
            }
            Text(
                "画面上的触控会发送给手机。麦克风默认关闭：打开后手机会把车里的声音当作语音输入，" +
                    "并且每次连接都需要你在手机上允许一次。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("本机标识：${identity()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            // A plain column, not a lazy list: this screen already scrolls as a whole, and a nested
            // lazy list inside a vertical scroll would be measured with an infinite height.
            Column(modifier = Modifier.fillMaxWidth()) {
                for (line in log) {
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    /**
     * Listens for 0xFDEE advertisements without transmitting anything, so a real car can be read.
     *
     * Our own beacon cannot be checked against a phone that never reacts to it, but a car's can be
     * captured byte for byte — including the one field this app cannot set, its advertising address,
     * which a head unit keeps equal to its BR address. Nothing here advertises, opens an access point
     * or listens on RFCOMM, so it is harmless to run next to a working head unit.
     *
     * The permission prompt is not raised from here: its callback starts the receiver, which is not
     * what this button is for. Missing permissions are named instead, and Start grants them once.
     */
    private fun sniff() {
        val active = sniffer
        if (active != null && active.running) {
            active.close()
            sniffer = null
            appendLog("HiCar sniff stopped")
            return
        }
        if (running) {
            appendLog(
                "HiCar sniff: stop the receiver first — its advertisement and self-scan already hold " +
                    "the radio, and our own beacon is not what you are looking for",
            )
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            appendLog("HiCar sniff needs: ${missing.joinToString()}; press Start once to grant them")
            return
        }
        appendLog("HiCar sniff: listening only; export the log when the 60 s are up")
        val probe = HiCarAdvertisementProbe(this) { appendLog(it) }
        sniffer = probe
        // 60 s: long enough to walk around the car while the head unit advertises.
        probe.start(null, 60_000L)
    }

    /** Prints the same device block the export carries, without leaving the screen. */
    private fun showAudit() {
        val lines = runCatching { HiCarDeviceAudit.collect(this) }.getOrElse {
            listOf("device audit failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        lines.forEach(::appendLog)
    }

    /**
     * Runs the read-only root probe off the main thread.
     *
     * A separate button on purpose: this is the only code path that asks for superuser rights, and a
     * refusal has to be a visible answer of its own rather than a line buried inside a Start attempt.
     */
    private fun rootProbe() {
        if (probing) return
        probing = true
        appendLog("Root probe: asking su for a read-only look at bt_config.conf and dumpsys")
        Thread {
            val lines = runCatching { HiCarDeviceAudit.rootProbe() }.getOrElse {
                listOf("root probe failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            runOnUiThread {
                lines.forEach(::appendLog)
                probing = false
            }
        }.start()
    }

    /**
     * Runs the key store's self-test and prints every line.
     *
     * It needs no phone and no radio, so it can run on the bench: a bind's exchange phase signs with
     * a long-term Ed25519 key and verifies the peer's signature, and when either of those is broken
     * the phone can only say "wrong password". This says which call returned what.
     */
    private fun cryptoSelfTest() {
        val config = HiCarRuntimeConfig(
            model = model,
            localIdentity = identity(),
            deviceName = "HiCar receiver",
            pin = pin,
        )
        appendLog("HiChain self-test: running (no phone needed)")
        Thread({
            val tester = try {
                HiCarController(
                    context = this,
                    config = config,
                    report = {},
                    onLog = { line -> runOnUiThread { appendLog(line) } },
                )
            } catch (failure: RuntimeException) {
                runOnUiThread {
                    appendLog("HiChain self-test: could not build the receiver (${failure.javaClass.simpleName})")
                }
                return@Thread
            }
            try {
                tester.cryptoSelfTest()
            } catch (failure: RuntimeException) {
                runOnUiThread {
                    appendLog(
                        "HiChain self-test: threw ${failure.javaClass.simpleName}: ${failure.message}",
                    )
                }
            } finally {
                // The instance never started, so this only releases what it allocated.
                runCatching { tester.close() }
            }
        }, "hicar-selftest").start()
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

    /**
     * Asks for discoverability and reports the outcome, without touching the receiver.
     *
     * Every failure mode is caught and logged: a missing settings activity, a permission the ROM wants
     * but the app does not hold, and whatever else the vendor stack throws. None of them should turn a
     * diagnostic step into a crash.
     */
    private fun requestDiscoverable() {
        val mode = runCatching { bluetoothAdapter?.scanMode }.getOrNull()
        if (mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            appendLog("Already discoverable over Bluetooth")
            return
        }
        val request = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        try {
            discoverable.launch(request)
        } catch (missing: ActivityNotFoundException) {
            appendLog("No activity can make this device discoverable on this ROM")
        } catch (denied: SecurityException) {
            appendLog(
                "Discoverable request was refused by the system " +
                    "(${denied.javaClass.simpleName}: ${denied.message ?: "no detail"})",
            )
        } catch (failure: RuntimeException) {
            appendLog("Discoverable request failed (${failure.javaClass.simpleName}: ${failure.message})")
        }
    }

    private fun startReceiver() {
        if (running) return
        running = true
        // The screen starts empty for a new attempt, but the export keeps earlier attempts: a retry
        // usually follows a failure, and the failure is the part worth reporting.
        log.clear()
        // The code goes on the start line, not only in the report header: the header is written at
        // export time, so a reset between the attempt and the export would relabel the attempt with a
        // code that was never on screen. With this line every session says which code it offered, and
        // a "code refused" report can be checked against what the phone was actually asked for.
        appendLog(
            "--- receiver started ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} " +
                "with code $pin ---",
        )
        // This ROM grants discoverability for 120 s per request, so starting in a not-discoverable
        // state wastes the whole window: the advertisement is on air but nothing can page the car.
        if (runCatching { bluetoothAdapter?.scanMode }.getOrNull() !=
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        ) {
            appendLog(
                "Not discoverable yet: the advertisement will be visible but a phone cannot reach " +
                    // The button is named in Chinese because that is the label on screen now; the
                    // rest of the line stays English like every other log line.
                    "this device over BR/EDR. Press 「允许被发现」 first (the grant lasts 120 s).",
            )
        }
        val config = HiCarRuntimeConfig(
            model = model,
            localIdentity = identity(),
            deviceName = "HiCar receiver",
            pin = pin,
            withMicrophone = microphoneEnabled,
        )
        val next = try {
            HiCarController(
                context = this,
                config = config,
                report = { next -> runOnUiThread { status = next } },
                onLog = { line -> runOnUiThread { appendLog(line) } },
            )
        } catch (failure: RuntimeException) {
            // A constructor that refuses its configuration is a bug, not a device failure; say which
            // one instead of closing the screen.
            running = false
            appendLog("HiCar receiver could not start (${failure.javaClass.simpleName}: ${failure.message})")
            return
        }
        controller = next
        next.start()
        // The view already exists on this screen; attach it so the first frame has somewhere to go.
        attachVideo()
    }

    /**
     * Points the decoder's texture at the on-screen window.
     *
     * Called both when the window appears and when the receiver starts, because either order can
     * happen: the picture may begin before the activity has laid out, and the activity may be
     * recreated while a session is still alive.
     */
    private fun attachVideo() {
        val view = videoView ?: return
        val texture = videoTexture ?: return
        val sink: HiCarVideoSink = controller?.videoSink() ?: return
        sink.attach(view, texture)
        appendLog("video: window attached to the decoder (${sink.stats})")
    }

    /** Maps a touch on the window into the 800×480 space the display service advertised. */
    private fun videoTouch(view: View, event: MotionEvent): Boolean {
        val active = controller ?: return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1
            else -> return false
        }
        active.sendTouchAt(event.x, event.y, view.width, view.height, action)
        return true
    }

    private fun sendKey(key: Int, name: String) {
        val active = controller
        if (active == null) {
            appendLog("$name: the receiver is not running")
            return
        }
        if (!active.sendKey(key)) appendLog("$name: the phone has not offered an input channel")
    }

    private fun toggleMicrophone() {
        if (microphoneEnabled) {
            microphoneEnabled = false
            rememberMicrophone(false)
            appendLog("microphone: disabled; it stops with this connection")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        microphoneEnabled = true
        rememberMicrophone(true)
        appendLog("microphone: enabled; the phone can ask for the car's voice on the next connection")
    }

    private fun stopReceiver() {
        running = false
        controller?.close()
        controller = null
        status = HiCarStatus.Stopped
        appendLog("--- receiver stopped ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ---")
    }

    private fun appendLog(line: String) {
        // Mirrored into logcat: on a head unit with adb, `adb logcat -s HiCar` shows the same lines
        // live, and the export file is no longer the only way to read a failed attempt. The tag is
        // short on purpose — logcat truncates tags over 23 characters on older platforms.
        Log.i(LOG_TAG, line)
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
        exportNotice = "保存中…"
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
                            saved.savedInApp -> "已保存到应用私有目录，点「分享」取出"
                            saved.savedPath != null -> "已保存到 ${saved.savedPath}"
                            else -> "已保存到 Downloads/DiPlay/$fileName"
                        }
                        appendLog("Log exported: $exportNotice")
                    },
                    onFailure = { failure ->
                        exportNotice = "导出失败：${failure.javaClass.simpleName}"
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
        runCatching { startActivity(Intent.createChooser(intent, "分享 HiCar 日志")) }
            .onFailure {
                // Head units often have no share target; the file is already saved either way.
                exportNotice = "没有应用接受这次分享；文件仍在保存位置"
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
                // Collected at export time so the file always reflects the device as it is now, not
                // as it was when the screen was opened.
                audit = runCatching { HiCarDeviceAudit.collect(this) }.getOrDefault(emptyList()),
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
        // The Bluetooth half is version-dependent and lives in one place: on Android 11 and below
        // the API-31 names do not exist, and asking for them only produces a permanent DENIED.
        val needed = HiCarPermissions.runtimeRequests(Build.VERSION.SDK_INT).toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return needed
    }

    /**
     * Records an uncaught exception before the process dies, once.
     *
     * A static guard keeps a second instance (or a recreated activity) from stacking handlers, which
     * would record the same crash several times.
     */
    private fun installCrashRecorder() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            HiCarCrashRecord.record(
                this,
                failure,
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            )
            previous?.uncaughtException(thread, failure)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

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

    /**
     * The advertised model id, remembered across restarts.
     *
     * `00040A04` is the default rather than something to be re-entered: a Mate 80 prompts on that
     * value and stays silent on this install's own random id, so defaulting to the random one meant
     * every fresh launch advertised a device no phone has ever seen.
     */
    private fun storedMicrophone(): Boolean =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(KEY_MICROPHONE, false)

    private fun rememberMicrophone(enabled: Boolean) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_MICROPHONE, enabled).apply()
    }

    private fun storedModel(): String {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        preferences.getString(KEY_MODEL, null)?.takeIf { MODEL_PATTERN.matches(it) }?.let { return it }
        preferences.edit().putString(KEY_MODEL, TEST_MODEL).apply()
        return TEST_MODEL
    }

    private fun rememberModel(value: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_MODEL, value).apply()
    }

    /**
     * The identity the phone sees; derived from this installation, not a Huawei credential.
     *
     * An override wins once [rotateIdentity] has been pressed: the peer stores its trust against
     * this string, so a car that presents a different one is a car the phone has never paired with —
     * which is the only way to get back to a first-time bind when the phone's record is stale and
     * ours is gone. See 7.34 in `docs/HICAR_MIGRATION.md`.
     */
    private fun identity(): String {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_IDENTITY, null)
            ?.takeIf { IDENTITY_PATTERN.matches(it) }
            ?.let { return it }
        return derivedIdentity()
    }

    /**
     * The identity a phone will key its stored trust against.
     *
     * The car id is not arbitrary: it is the same 64-character upper-case hex the head unit's own
     * SDK produces, `SHA-256(model + bluetooth address without separators)`. A phone rebuilds the
     * signed message from the car id in its own device record, so a car presenting any other shape
     * binds fine and then fails every reconnect with `HC_VERIFY_PROOF_FAILED` - the same code a
     * wrong key gives, which is why it reads as a key-store fault. See 7.48.
     *
     * The address is asked for rather than read: `BluetoothAdapter.getAddress` returns a constant
     * to apps, the per-interface files under sys/class/net are not readable from an app uid, and
     * `bluetooth_address` in secure settings needs `BLUETOOTH_PRIVILEGED`. Without it the older
     * ANDROID_ID derivation still applies, so a build never told the address keeps working.
     */
    private fun derivedIdentity(): String {
        val mac = btAddress.uppercase()
        if (BLUETOOTH_PATTERN.matches(mac)) {
            return derivedIdentityFrom(model, mac)
        }
        val seed = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.copyOf(8).joinToString("") { "%02X".format(it) }
    }

    private fun derivedIdentityFrom(model: String, mac: String): String {
        val seed = model.uppercase() + mac.replace(":", "")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * Derives and stores the identity, saying what it costs.
     *
     * A new identity is a new car as far as the phone is concerned: the trust it holds is keyed
     * against the old string, so the next connection is a first-time bind (it asks for the code
     * again) and the stale record has to be deleted on the phone or it keeps answering for the id
     * this head unit no longer presents.
     */
    private fun applyBluetoothIdentity() {
        val mac = btAddress.uppercase()
        if (!BLUETOOTH_PATTERN.matches(mac)) {
            appendLog("A Bluetooth address looks like 98:BB:1E:67:87:ED - $mac does not")
            return
        }
        rememberBluetoothAddress(mac)
        val next = derivedIdentityFrom(model, mac)
        val current = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_IDENTITY, null)
        if (next == current) {
            appendLog("Identity is already what this address derives: $next")
            return
        }
        if (running) stopReceiver()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_IDENTITY, next).apply()
        appendLog("Identity from $model + $mac:")
        appendLog("  $next")
        appendLog("This car is new to the phone now: delete it in HiCar, then pair once.")
    }

    private fun storedBluetoothAddress(): String =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_BLUETOOTH_ADDRESS, "").orEmpty()

    private fun rememberBluetoothAddress(value: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit().putString(KEY_BLUETOOTH_ADDRESS, value).apply()
    }

    /**
     * Presents this install as a different car.
     *
     * The phone proved it still holds trust for the identity we used before (it asked to
     * authenticate, not to bind, and rejected our proof), while Reset pairing had already wiped our
     * half. Neither side can rebuild the other's, so the cheaper way out is to stop being that peer:
     * a new identity is a car the phone has no record of, and the next attempt is a plain first-time
     * bind that asks for the code on screen.
     */
    private fun rotateIdentity() {
        if (running) stopReceiver()
        // 32 bytes, so the string has the same 64-character shape the derivation produces. A shorter
        // random id is a shape no part of this protocol uses, and a phone that binds against it will
        // answer every later authenticate with 17 - the very trap 7.48 describes.
        val next = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02X".format(it) }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_IDENTITY, next).apply()
        appendLog("Identity rotated: $next (this car looks new to the phone; press Start, then pair once)")
    }

    /** Eight hexadecimal digits for the 0xFDEE payload; an experiment id, not a certified model. */
    private fun experimentalModel(): String =
        identity().take(8).uppercase().padEnd(8, '0')

    /**
     * The window the phone's picture lands in.
     *
     * `AndroidView` rather than a Compose canvas: the decoder writes into a `SurfaceTexture`, and a
     * `TextureView` is the one way to show that texture without copying frames through the GPU
     * pipeline. Touches are handled by the view itself so `MotionEvent` coordinates — not Compose
     * pointer positions — are what gets mapped into the phone's 800×480 space.
     */
    @Composable
    private fun VideoPanel() {
        Text("手机画面（可直接触控）", style = MaterialTheme.typography.bodySmall)
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            videoView = this@apply
                            videoTexture = texture
                            attachVideo()
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            // The decoder owns its texture: releasing it here would leave the next
                            // frame with nowhere to go. Only a texture this side never took is
                            // handed back to the view.
                            val owned = controller?.videoSink()?.detach(texture) == true
                            videoView = null
                            videoTexture = null
                            return !owned
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { view, event -> videoTouch(view, event) }
                }.also { videoView = it }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(800f / 480f)
                .background(Color.Black),
        )
    }

    private fun describe(status: HiCarStatus): String = when (status) {
        is HiCarStatus.Idle -> "空闲"
        is HiCarStatus.PreparingNetwork -> "正在准备热点"
        is HiCarStatus.NetworkReady -> "热点 ${status.ssid}，地址 ${status.address}（${status.backend}）"
        is HiCarStatus.Advertising -> "正在发出 0xFDEE 广播"
        is HiCarStatus.WaitingForPhone -> "等待手机接入"
        is HiCarStatus.ChannelNegotiated -> "业务通道已通告，等待认证"
        is HiCarStatus.Authenticating -> "正在认证"
        is HiCarStatus.Authenticated -> "认证通过，正在打开控制通道"
        is HiCarStatus.ControlChannelReady -> "控制通道已就绪，等待手机要画面"
        is HiCarStatus.Streaming -> "画面通道已连接，正在接收手机视频"
        is HiCarStatus.Blocked -> "受阻：${status.blocker.message}"
        is HiCarStatus.Failed -> "失败：${status.reason}"
        is HiCarStatus.Stopped -> "已停止"
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val MAX_HISTORY_LINES = 5_000

        /** `adb logcat -s HiCar` on a head unit: short, and stable across sessions. */
        const val LOG_TAG = "HiCar"

        /**
         * The model id a real phone prompts on.
         *
         * It came from the reference receiver as a test vector, but it is no longer unverified: with
         * this value a Mate 80 Pro Max shows its HiCar prompt and dials, and with this install's own
         * random id the same phone stays silent. That is why it is the default.
         */
        const val TEST_MODEL = "00040A04"
        const val PREFERENCES = "hicar-receiver"
        const val KEY_PIN = "connection_code"
        const val KEY_MODEL = "advertised_model"
        const val KEY_MICROPHONE = "microphone_enabled"

        /** Android key codes sent over UIBC; the phone only accepts these two. */
        const val KEY_HOME = 3
        const val KEY_BACK = 4
        const val KEY_IDENTITY = "local_identity"
        const val KEY_BLUETOOTH_ADDRESS = "bluetooth_address"
        val PIN_PATTERN = Regex("[0-9]{6}")
        val MODEL_PATTERN = Regex("[0-9A-F]{8}")

        /** Six colon-separated hex pairs, the only shape a Bluetooth address is written in. */
        val BLUETOOTH_PATTERN = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        /**
         * 16 or 64 upper-case hex characters.
         *
         * 64 is the shape the car's own HiCar SDK uses: `com.huawei.hicarsdk.DeviceId` formats
         * `byte2HexFormatted` for a 32-byte id and its `DEFAULT_DEVICE_ID` placeholder is exactly
         * 64 characters, the same length a phone reports. The 16-character form is kept because
         * every build before 7.48 advertised it.
         */
        val IDENTITY_PATTERN = Regex("[0-9a-fA-F]{16}|[0-9a-fA-F]{64}")

        /** The longest value the system accepts; bring-up sessions are short anyway. */
        const val DISCOVERABLE_SECONDS = 300

        /** Static: an activity recreated by a configuration change must not wrap the handler twice. */
        @Volatile private var crashHandlerInstalled = false
    }
}
