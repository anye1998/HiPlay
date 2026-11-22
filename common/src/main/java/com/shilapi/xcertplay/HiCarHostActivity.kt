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
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.shilapi.xcertplay.hicar.HiCarAdvertisementProbe
import com.shilapi.xcertplay.hicar.HiCarController
import com.shilapi.xcertplay.hicar.HiCarDisplaySettings
import com.shilapi.xcertplay.hicar.HiCarPermissions
import com.shilapi.xcertplay.hicar.HiCarRuntimeConfig
import com.shilapi.xcertplay.hicar.HiCarDisplaySize
import com.shilapi.xcertplay.hicar.HiCarVideoSink
import com.shilapi.xcertplay.hicar.HiCarSpeedVolumeSettings
import com.shilapi.xcertplay.hicar.HiCarStatus
import com.shilapi.xcertplay.hicar.HiCarVideoFit
import com.shilapi.xcertplay.hicar.HiCarWindowMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The HiCar receiver: the only screen this app has.
 *
 * Three surfaces, one activity:
 *
 * - the **setup page** — the connection code, Start, and the tail of the log, for the moments before
 *   a phone is present;
 * - the **session page** — the phone's picture, fitted rather than stretched, with Stop, the two
 *   navigation keys, the microphone and a settings entry that works *while* connected;
 * - the **settings page** — a full-screen overlay over whichever of the two is behind it, so opening
 *   it never tears the session down. Connection details, display mode, speed-linked music gain and
 *   the diagnostic probes live here.
 *
 * Everything is drawn by this activity rather than by a second one: a settings activity in front
 * would pause this one, and a paused surface is where a mirrored picture goes black.
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

    /**
     * The picture's real aspect ratio, used to lay out its window.
     *
     * Follows the decoder's output rather than the geometry offered to the phone: a handset may
     * ignore the offer, and a window at the wrong ratio both looks wrong and puts every touch off.
     */
    private var videoAspect by mutableStateOf(16f / 9f)
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

    /** Whether the settings page is drawn over the receiver; the session keeps running either way. */
    private var settingsOpen by mutableStateOf(false)

    /** The window mode in force; loaded once and re-applied whenever the settings page changes it. */
    private var windowMode by mutableStateOf(HiCarWindowMode.FULLSCREEN)

    /** The speed-volume values the settings page edits; the controller gets them on every change. */
    private var speedSettings by mutableStateOf(HiCarSpeedVolumeSettings())

    /** The session overlay's log panel starts closed: the picture is the point of that screen. */
    private var sessionLogVisible by mutableStateOf(false)

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
        windowMode = HiCarDisplaySettings.load(this)
        speedSettings = HiCarSpeedVolumeSettings.load(this)
        // Before the first frame, so the activity never appears with the wrong bars and then jumps.
        applyWindowMode()
        setContent {
            // A head unit is read at night as often as in daylight, and the session page is black
            // already: the whole receiver follows that, including the pages the driver reads before
            // a connection.
            MaterialTheme(colorScheme = darkColorScheme()) {
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

    /**
     * Which layout the screen shows.
     *
     * The diagnostic page is what a driver needs before a connection (the code, the start button);
     * once the phone is authenticated the picture is what they need, and a settings entry has to stay
     * reachable without stopping the session. So the two are different layouts, and the settings page
     * is an overlay on top of either.
     */
    private val inSession: Boolean
        get() = running && when (status) {
            is HiCarStatus.Authenticated,
            is HiCarStatus.ControlChannelReady,
            is HiCarStatus.Streaming,
            -> true
            else -> false
        }

    @Composable
    private fun Content() {
        // With `decorFitsSystemWindows` off, the padding that keeps content out from under a bar is
        // ours to add. It follows the mode exactly: a hidden bar must not leave a gap, and a visible
        // one must not overlap the picture.
        val insets = when {
            !windowMode.showsStatusBar && !windowMode.showsNavigationBar -> WindowInsets(0, 0, 0, 0)
            windowMode.showsNavigationBar -> WindowInsets.systemBars
            else -> WindowInsets.statusBars
        }
        // Back closes the settings page first: leaving the receiver by accident, mid-session, is the
        // more expensive mistake of the two.
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(insets)
                .background(if (inSession) Color.Black else MaterialTheme.colorScheme.background),
        ) {
            if (inSession) SessionScreen() else SetupScreen()
            // Drawn over the running picture rather than instead of it: the activity, its decoder
            // and the session all stay alive while the driver changes a setting.
            if (settingsOpen) {
                HiCarSettingsScreen(
                    windowMode = windowMode,
                    onWindowMode = ::selectWindowMode,
                    speed = speedSettings,
                    onSpeed = ::applySpeedSettings,
                    onClose = { settingsOpen = false },
                    connection = { ConnectionSection() },
                    diagnostics = { DiagnosticsSection() },
                )
            }
        }
    }

    /** The page a driver sees before connecting: the code, the start button, and what happened. */
    @Composable
    private fun SetupScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("HiCar 接收端", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { settingsOpen = true }) { Text("设置") }
            }
            Text("状态：${describe(status)}", style = MaterialTheme.typography.titleSmall)
            Text("连接码", style = MaterialTheme.typography.bodyMedium)
            Text(
                pin,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "手机弹出 HiCar 提示后输入这 6 位。每按一次「重置配对」连接码就会换新，" +
                    "以这里显示的为准。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::toggle, enabled = model.length == 8) {
                    Text(if (running) "停止" else "开始")
                }
            }
            // The reference receiver's README makes this a prerequisite on some devices, and it is
            // the opposite of the advice we had been giving (delete the phone's record / rotate the
            // identity), so it goes where a first-run driver cannot miss it.
            Text(
                "首次使用：先在车机和手机的「系统蓝牙设置」里完成一次普通配对，再回来点「开始」。" +
                    "配对成功之后不要反复删记录或按「重置配对」——重置只清车机，清不掉手机里的记录，" +
                    "两边状态不一致会一直报认证失败。",
                style = MaterialTheme.typography.bodySmall,
            )
            Section("最近日志")
            // A plain column, not a lazy list: this screen already scrolls as a whole, and a nested
            // lazy list inside a vertical scroll would be measured with an infinite height.
            for (line in log.takeLast(SETUP_LOG_LINES)) {
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    /**
     * The connected screen: the phone's picture, with the controls that have to stay reachable.
     *
     * The picture is fitted into the space the chosen window mode leaves ([HiCarVideoFit]) and the
     * rest of the screen stays black. Stretching it to fill would both look wrong and put every touch
     * off, because the touch mapping uses the view's own size.
     */
    @Composable
    private fun SessionScreen() {
        Box(modifier = Modifier.fillMaxSize()) {
            FitVideo(modifier = Modifier.fillMaxSize())
            Text(
                describe(status),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                // The entry the driver needs *during* a connection: the page is an overlay, so the
                // session keeps running while a setting is changed.
                TextButton(onClick = { settingsOpen = true }) { Text("设置", color = Color.White) }
                TextButton(onClick = ::stopReceiver) { Text("停止", color = Color.White) }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.45f)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (sessionLogVisible) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        for (line in log.takeLast(SESSION_LOG_LINES)) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
                Row {
                    // The two keys a phone screen cannot be driven without once it is mirrored: the
                    // system back and home. They go through the same encrypted UIBC channel as
                    // touches.
                    TextButton(onClick = { sendKey(KEY_BACK, "返回") }) { Text("返回", color = Color.White) }
                    TextButton(onClick = { sendKey(KEY_HOME, "主页") }) { Text("主页", color = Color.White) }
                    TextButton(onClick = ::toggleMicrophone) {
                        Text(if (microphoneEnabled) "麦克风：开" else "麦克风：关", color = Color.White)
                    }
                    TextButton(onClick = { sessionLogVisible = !sessionLogVisible }) {
                        Text(if (sessionLogVisible) "隐藏日志" else "日志", color = Color.White)
                    }
                }
            }
        }
    }

    /** The picture, sized to the largest rectangle with the stream's own shape that fits. */
    @Composable
    private fun FitVideo(modifier: Modifier = Modifier) {
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
            val fit = HiCarVideoFit.fit(
                videoAspect = videoAspect,
                containerWidth = constraints.maxWidth.toFloat(),
                containerHeight = constraints.maxHeight.toFloat(),
            )
            if (!fit.isEmpty) {
                val width = with(LocalDensity.current) { fit.width.toDp() }
                val height = with(LocalDensity.current) { fit.height.toDp() }
                VideoSurface(
                    modifier = Modifier
                        .width(width)
                        .height(height)
                        .background(Color.Black),
                )
            }
        }
    }

    /**
     * The connection block of the settings page.
     *
     * Everything a phone keys its trust against: the advertised model, the head unit's Bluetooth
     * address (the identity is derived from both), and the code. Each sentence next to a destructive
     * button says what it costs, because the driver presses these before every attempt.
     */
    @Composable
    private fun ConnectionSection() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            // one is not a head unit any phone has ever seen.
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
                "本机标识 = SHA-256(机型码 + 蓝牙地址) 的 64 位大写十六进制。" +
                    "手机按这个字符串索引存下的公钥：长度少一半，绑定照样能过，但每次重连都会被拒（错误码 17）。",
                style = MaterialTheme.typography.bodySmall,
            )
            MonoLine("当前标识：${identity()}")
            TextButton(onClick = { applyBluetoothIdentity() }) { Text("按蓝牙地址派生本机标识") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::resetPairing) { Text("重置配对") }
                Button(onClick = ::requestDiscoverable) { Text("允许被发现") }
                Button(onClick = ::rotateIdentity) { Text("换一个本机标识") }
            }
            Text(
                "「重置配对」只清车机这一侧的信任关系和连接码，清不掉手机里的记录：两边都清干净才重新配对。" +
                    "只有手机反复报「认证失败」、而车机已经重置过时，才用「换一个本机标识」——" +
                    "手机认的就是这个标识，换了它就等于一台没连过的新车。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** The diagnostic block: every probe the bring-up needs, on the page a driver can reach while connected. */
    @Composable
    private fun DiagnosticsSection() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The key store is what the bind exchange signs with, and a broken one is invisible
                // until a phone is present. This runs the same calls without a phone.
                Button(onClick = ::cryptoSelfTest, enabled = !probing) { Text("加密自检") }
                Button(onClick = ::showAudit) { Text("设备体检") }
                // Explicitly triggered, read-only, and it says so: reading bt_config.conf needs su.
                Button(onClick = ::rootProbe, enabled = !probing) { Text("root 探针") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The export survives the session: a failed attempt is usually over by the time the
                // driver decides to report it, and a screenshot loses the part above the fold.
                Button(onClick = ::exportLog, enabled = !exporting) {
                    Text(if (exporting) "导出中…" else "导出日志")
                }
                // Only offered once there is a file: a permanently grey button reads as broken.
                if (exportUri != null) Button(onClick = ::shareLog) { Text("分享") }
                // One press, sixty seconds, no advertisement of our own: the only way to read what a
                // real head unit actually puts on the air.
                Button(onClick = ::sniff) { Text("嗅探 0xFDEE") }
            }
            if (exportNotice.isNotEmpty()) MonoLine(exportNotice)
            TextButton(onClick = ::clearLog) { Text("清空屏幕日志") }
            Text(
                "运行日志（最近 ${SETTINGS_LOG_LINES} 行；完整内容用「导出日志」）",
                style = MaterialTheme.typography.bodySmall,
            )
            for (line in log.takeLast(SETTINGS_LOG_LINES)) {
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    /** Applies a window mode to the live window, not just to preferences. */
    private fun selectWindowMode(mode: HiCarWindowMode) {
        if (windowMode == mode) return
        windowMode = mode
        HiCarDisplaySettings.save(this, mode)
        applyWindowMode()
        appendLog("display: window mode ${mode.stored} (${mode.title})")
    }

    /** Persists the speed-volume values and pushes them into the audio path that is running now. */
    private fun applySpeedSettings(next: HiCarSpeedVolumeSettings) {
        speedSettings = next
        HiCarSpeedVolumeSettings.save(this, next)
        // A session may be running: the monitor is rebuilt so the new curve is in force immediately.
        controller?.refreshSpeedVolume()
        appendLog(
            "speed volume: ${if (next.enabled) "on" else "off"}, " +
                "${next.startKmh.toInt()}–${next.fullKmh.toInt()} km/h, +${next.maxGainDb.toInt()} dB, " +
                "source ${next.source.name.lowercase()}",
        )
    }

    /**
     * Turns [windowMode] into visible and hidden system bars.
     *
     * `decorFitsSystemWindows = false` in every mode, so the bars never resize the content behind our
     * back: the content's own inset padding (in [Content]) decides how much space a visible bar gets.
     * Full screen still allows a swipe to reveal the bars — a head unit without a home button must
     * never be left with no way out.
     */
    private fun applyWindowMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val mode = windowMode
        if (mode.showsStatusBar) {
            bars.show(WindowInsetsCompat.Type.statusBars())
        } else {
            bars.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (mode.showsNavigationBar) {
            bars.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            bars.hide(WindowInsetsCompat.Type.navigationBars())
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
        // Ask for the sharpest geometry this panel can actually show; the phone may answer with a
        // different one, and the window ratio follows whatever it really sends.
        val screen = realScreenPixels()
        val requested = HiCarDisplaySize.best(screen[0], screen[1])
        next.setDisplaySize(requested)
        videoAspect = requested.aspect
        val window = resources.displayMetrics
        appendLog(
            "display: offering ${requested.width}×${requested.height} at " +
                "${HiCarDisplaySize.densityFor(requested.width)} dpi " +
                "(panel ${screen[0]}×${screen[1]}, window ${window.widthPixels}×${window.heightPixels})",
        )
        next.start()
        // The view already exists on this screen; attach it so the first frame has somewhere to go.
        attachVideo()
    }

    /**
     * The panel's real pixels, not the window's.
     *
     * On this head unit the activity is laid out on a smaller logical canvas than the panel - 1600×900
     * for a 1920×1080 screen, a 1.2 compatibility scale - so `resources.displayMetrics` answers with
     * the canvas and asking the phone for that size would throw away a fifth of the panel. The
     * density is scaled with the width, so the phone's dp geometry is unchanged either way: only the
     * number of pixels behind each dp differs, which is exactly the sharpness being asked for.
     */
    private fun realScreenPixels(): IntArray {
        val metrics = DisplayMetrics()
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        manager?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return intArrayOf(metrics.widthPixels, metrics.heightPixels)
        }
        val fallback = resources.displayMetrics
        return intArrayOf(fallback.widthPixels, fallback.heightPixels)
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
        // The picture's real shape decides the window's shape. Without this a 1080p session would be
        // squeezed into the 5:3 box that 800×480 happened to fit, and every touch would land off.
        controller?.onPictureSize { width, height ->
            runOnUiThread {
                videoAspect = width.toFloat() / height.toFloat()
                appendLog("video: window follows the picture, ${width}×${height}")
            }
        }
        sink.attach(view, texture)
        appendLog("video: window attached to the decoder (${sink.stats})")
    }

    /** Maps a touch on the window into the picture's own coordinate space. */
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
     * pointer positions — are what gets mapped into the phone's picture space, which is only correct
     * because the view is exactly the picture ([FitVideo] sizes it that way, never stretching it).
     */
    @Composable
    private fun VideoSurface(modifier: Modifier = Modifier) {
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
            modifier = modifier,
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

        /** How much of the log each surface shows; the export always carries all of it. */
        const val SETUP_LOG_LINES = 12
        const val SESSION_LOG_LINES = 6
        const val SETTINGS_LOG_LINES = 40

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
