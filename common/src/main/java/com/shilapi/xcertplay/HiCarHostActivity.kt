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
import android.view.KeyEvent
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hicar.HiCarAdvertisementProbe
import com.shilapi.xcertplay.hicar.HiCarController
import com.shilapi.xcertplay.hicar.HiCarAudioSettings
import com.shilapi.xcertplay.hicar.HiCarAudioSettingsStore
import com.shilapi.xcertplay.hicar.HiCarBluetoothAddress
import com.shilapi.xcertplay.hicar.HiCarDisplaySettings
import com.shilapi.xcertplay.hicar.HiCarTouchPoint
import com.shilapi.xcertplay.hicar.HiCarKeySettings
import com.shilapi.xcertplay.hicar.HiCarMediaKeyForwarder
import com.shilapi.xcertplay.hicar.HiCarMediaKeySession
import com.shilapi.xcertplay.hicar.HiCarPairingCode
import com.shilapi.xcertplay.hicar.HiCarReconnectSettings
import com.shilapi.xcertplay.hicar.HiCarVideoSettings
import com.shilapi.xcertplay.hicar.HiCarVideoSettingsStore
import com.shilapi.xcertplay.hicar.HiCarWifiSettings
import com.shilapi.xcertplay.hicar.HiCarWiredSettings
import com.shilapi.xcertplay.hicar.HiCarPermissions
import com.shilapi.xcertplay.hicar.HiCarRuntimeConfig
import com.shilapi.xcertplay.hicar.HiCarDisplaySize
import com.shilapi.xcertplay.hicar.HiCarVideoSink
import com.shilapi.xcertplay.hicar.HiCarSpeedStatus
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

    /**
     * Whether this window is on screen, tracked here because a receiver started from the button is
     * built *after* the last `onResume` — the controller has to be told the state it missed.
     */
    private var windowVisible = false

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

    /**
     * Which speed source is answering right now.
     *
     * The setting says what is allowed; this says what is actually being used, which is the question
     * a driver asks when the music does not get louder in a tunnel. Pushed from the monitor about
     * once a second and reset when the session ends.
     */
    private var speedStatus by mutableStateOf(HiCarSpeedStatus())

    /**
     * Whether this app may use the head unit's own adbd (`127.0.0.1:5555`).
     *
     * The car-bus speed source runs `service call` through that shell, so without this the feature
     * can only fall back to GPS. Null until the first check finishes, which is what makes the row
     * say "检测中" instead of guessing.
     */
    private var adbState by mutableStateOf<BydAdbAccess.State?>(null)

    /** True while the approval handshake is in flight; the dialog can take up to a minute. */
    private var adbBusy by mutableStateOf(false)

    /** The last result, in words, for the line under the button. */
    private var adbMessage by mutableStateOf("")

    /** Whether the app has ever been told the adbd status in this run, so it is logged once. */
    private var adbLogged by mutableStateOf(false)

    /**
     * The address read off the car, in words, under the address field.
     *
     * Kept apart from [adbMessage]: the same adb channel answers both, but a driver reading one row
     * must not see it overwritten by the other.
     */
    private var btReadMessage by mutableStateOf("")

    /** True while the address is being read over adb; the two commands take a moment. */
    private var btReading by mutableStateOf(false)

    /** Whether the log already says the cable found a session running, so six re-enumerations say it once. */
    private var cableNoted = false

    /** Sharpness and frame rate: answered once per connection, so they apply to the next one. */
    private var videoSettings by mutableStateOf(HiCarVideoSettings())

    /** Which head unit channel music and guidance go to. */
    private var audioSettings by mutableStateOf(HiCarAudioSettings())

    /** Whether the cable is driven: on by default, and it wins over Wi-Fi whenever it carries. */
    private var wiredEnabled by mutableStateOf(true)

    /** Whether an inserted cable may start the app by itself, without touching the screen. */
    private var cableAutoStart by mutableStateOf(true)

    /**
     * Whether the Wi-Fi half of the session is on.
     *
     * The setup page's button toggles only this. A cable session is started by the cable, so
     * "停止" must not mean "stop the projection that is currently on screen".
     */
    private var wirelessOn by mutableStateOf(true)

    /** Whether a known phone is dialled when its Bluetooth link comes up. */
    private var redialEnabled by mutableStateOf(true)

    /** Whether the head unit's own Wi-Fi is asked back after the receiver stops. */
    private var restoreWifiEnabled by mutableStateOf(true)

    /** The session overlay's log panel starts closed: the picture is the point of that screen. */
    private var sessionLogVisible by mutableStateOf(false)

    /**
     * Steering-wheel and media buttons, forwarded to the phone.
     *
     * Two entries feed the same forwarder: this window's key events (the receiver is on screen) and a
     * media-button session (it is not). Both are needed — a driver who switches to the car's own
     * screen still expects the wheel to control the phone's music.
     */
    private val mediaKeys = HiCarMediaKeyForwarder()
    private var mediaKeySession: HiCarMediaKeySession? = null

    /** Whether the phone and the input socket are both ready for a key. */
    private var keysReady by mutableStateOf(false)

    /** Whether the speaker holds media focus, which is what makes the whole unit's buttons ours. */
    private var mediaFocus by mutableStateOf(false)

    /** Whether the driver wants the wheel handed to the phone at all. */
    private var mediaKeysEnabled by mutableStateOf(true)

    /** Longer than [log]: the screen keeps a readable tail, the export keeps the whole attempt. */
    private val history = ArrayList<String>()
    private var droppedFromHistory = 0
    private var exporting by mutableStateOf(false)
    private var probing by mutableStateOf(false)
    private var exportUri by mutableStateOf<Uri?>(null)
    private var exportNotice by mutableStateOf("")

    /** Whether the receiver that follows a permission prompt should offer Wi-Fi. */
    private var pendingWireless = true

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val refused = granted.filterValues { !it }.keys
        if (refused.isEmpty()) {
            if (running) {
                // The receiver was already up (a cable session, typically) and the driver asked for
                // the wireless half: only that half changes.
                wirelessOn = true
                controller?.setWireless(true)
                appendLog("button: wireless on; the cable is not affected")
            } else {
                startReceiver(pendingWireless)
            }
        } else {
            // Name them: the access point needs Wi-Fi Direct, which fails without these, and a bare
            // "permissions were not granted" hides which step is about to fail.
            appendLog("Permissions refused: ${refused.joinToString()}")
            if (refused.any { it == Manifest.permission.ACCESS_FINE_LOCATION }) {
                appendLog(
                    "the access point and the self-scan both need Location; until it is granted every " +
                        "connection stops after authentication with no network to hand the phone",
                )
            }
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

    /**
     * The system's "allow a VPN connection?" prompt, raised by the cable.
     *
     * The wired path moves the phone's IP packets through a TUN, and Android only hands one out after
     * the driver answers. The answer goes straight back to the cable, which continues with the port
     * that is already open — the phone has switched by then, so restarting would cost an insertion.
     */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller?.onVpnConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
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
        mediaKeysEnabled = HiCarKeySettings.mediaKeysEnabled(this)
        videoSettings = HiCarVideoSettingsStore.load(this)
        audioSettings = HiCarAudioSettingsStore.load(this)
        wiredEnabled = HiCarWiredSettings.enabled(this)
        cableAutoStart = HiCarWiredSettings.autoStart(this)
        redialEnabled = HiCarReconnectSettings.enabled(this)
        restoreWifiEnabled = HiCarWifiSettings.restoreEnabled(this)
        speedSettings = HiCarSpeedVolumeSettings.load(this)
        // The "HiCar 设置" launcher icon exists so the driver can change a setting without hunting
        // for the small entry on the session overlay. It is the same activity, so arriving this way
        // only means "start with the settings page open".
        if (openedFromSettingsShortcut(intent)) settingsOpen = true
        // A read-only check, without offering the key: it must never raise the car's dialog on its
        // own, and it tells the settings page whether the speed source has a shell to use.
        refreshAdbState(logResult = false)
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
        // After the content, so the status and the log it writes have somewhere to land.
        handleCableIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        windowVisible = true
        // The cable's USB permission prompt is a system activity: asking while this window is not
        // visible is dropped on this car without a dialog and without a callback. This is the
        // "now you may ask" signal, and it also re-asks if the first prompt was lost while the
        // window was still coming up.
        controller?.setHostVisible(true)
    }

    override fun onPause() {
        windowVisible = false
        controller?.setHostVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        closeMediaKeySession("screen closed")
        sniffer?.close()
        sniffer = null
        controller?.close()
        controller = null
        super.onDestroy()
    }

    /**
     * A second launch while the receiver is already on screen.
     *
     * The activity is `singleTask`, so pressing the "HiCar 设置" icon mid-session lands here instead
     * of building a second receiver: the picture keeps running, and the settings page is drawn over
     * it. Without this override the driver would tap the icon and see nothing happen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (openedFromSettingsShortcut(intent)) settingsOpen = true
        handleCableIntent(intent)
    }

    /**
     * Starts the receiver because a cable woke the app.
     *
     * The wireless half is deliberately left off: the cable is what the driver just plugged in, and
     * turning the access point on as well would hold the radio for a path nobody asked for. If the
     * system refused the background launch, this never runs and the notification stays tappable.
     */
    private fun handleCableIntent(intent: Intent?) {
        if (intent?.action != ACTION_CABLE) return
        HiCarUsbAttachNotifier.cancel(this)
        // Consumed: a configuration change reuses this intent, and it must not start a second run.
        intent.action = null
        appendLog("cable: the data line woke the receiver")
        if (running) {
            // A phone that switches USB mode re-enumerates several times, and each one re-delivers
            // this intent. Repeating the line six times only pushes the interesting lines out of a
            // bounded log; the state it describes has not changed.
            if (!cableNoted) {
                cableNoted = true
                appendLog("cable: a session is already running; the cable will take over by itself")
            }
            return
        }
        if (missingPermissions().isNotEmpty()) {
            appendLog("cable: permissions are missing, so the cable cannot be used yet")
            pendingWireless = false
            permissions.launch(missingPermissions())
            return
        }
        startReceiver(wireless = false)
    }

    /**
     * Whether this launch came from the `HiCar 设置` icon.
     *
     * That icon is a doorway activity ([HiCarSettingsLaunchActivity]) rather than an alias, so it
     * arrives as an action. The action is consumed here: a configuration change reuses the same
     * intent, and re-opening the settings page after the driver closed it would be a surprise.
     */
    private fun openedFromSettingsShortcut(intent: Intent?): Boolean {
        if (intent?.action != ACTION_SETTINGS) return false
        intent.action = null
        return true
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
                    inSession = inSession,
                    onReapplyDisplay = ::reapplyDisplay,
                    speed = speedSettings,
                    onSpeed = ::applySpeedSettings,
                    speedStatus = speedStatus,
                    mediaKeys = mediaKeysEnabled,
                    onMediaKeys = ::applyMediaKeysSetting,
                    video = videoSettings,
                    onVideo = ::applyVideoSettings,
                    audio = audioSettings,
                    onAudio = ::applyAudioSettings,
                    onPreviewMedia = { previewAudio(media = true) },
                    onPreviewNavigation = { previewAudio(media = false) },
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
            // Only once the wireless half is actually on air: a QR code offered while the receiver is
            // idle (or while a cable-only run is waiting) sends the phone to a car that is not
            // advertising, and the failure looks like a bad code rather than a switched-off radio.
            if (running && wirelessOn) PairingCode()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::toggle, enabled = model.length == 8) {
                    Text(
                        when {
                            !running -> "开始"
                            wirelessOn -> "停止无线"
                            else -> "开始无线"
                        },
                    )
                }
            }
            val missingNow = missingPermissions()
            if (missingNow.isNotEmpty()) {
                // The one grant this build cannot work without, said plainly next to the button that
                // needs it: without it the access point refuses and the phone is left after HiChain
                // with nowhere to dial. Named as the platform names it, because the name changes with
                // the Android version (Location below 13, Nearby devices from 13).
                Text(
                    "还差权限：${missingNow.joinToString { it.substringAfterLast('.') }}。" +
                        "开热点和扫蓝牙都要它，没有它手机能连上但拿不到网络。" +
                        "点上面的按钮会弹授权，选「允许」；如果已经点过拒绝，就点下面这个进系统设置打开。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = ::openAppPermissionSettings) { Text("打开应用权限") }
            }
            Text(
                if (running) {
                    "这个按钮只管无线：插着数据线时，有线连接由数据线自己维持，按「停止无线」不会中断画面。"
                } else {
                    "插上数据线会自动启动接收端并优先走有线；不插线时点「开始」走无线。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
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
     * The QR code a phone scans instead of typing the code.
     *
     * It carries the head unit's own Bluetooth address, so the phone dials it directly instead of
     * finding it on the air — the one path that does not depend on the 0xFDEE advertisement being
     * recognised. The code is rebuilt whenever the address, the model or the PIN changes, because a
     * QR that disagrees with the screen is worse than none.
     */
    @Composable
    private fun PairingCode() {
        val name = pairName()
        val link = HiCarPairingCode.buildOrNull(
            model = model,
            bluetoothAddress = btAddress,
            pin = pin,
            deviceName = name,
            supportsUsb = true,
        )
        val bitmap = remember(link) { link?.let { HiCarQrCode.bitmap(it, QR_PIXELS) } }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "配对二维码",
                modifier = Modifier
                    .size(QR_SIZE)
                    .background(Color.White)
                    .padding(4.dp),
            )
            Text(
                "手机扫描这辆车：扫码会带上车型、蓝牙地址和这 6 位连接码，不用手输。" +
                    "码里不包含手机的任何信息，换一台手机重扫即可。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val why = HiCarPairingCode.problem(model, btAddress, pin, name)
            if (why != null) {
                Text("二维码暂不可用：$why", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    /** The name the phone shows for this unit; the model tail tells two of them apart. */
    private fun pairName(): String = "HiCar-" + model.takeLast(4)

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::readBluetoothAddress, enabled = !btReading) {
                    Text(if (btReading) "正在读取…" else "从系统读取本机蓝牙地址")
                }
            }
            if (btReadMessage.isNotEmpty()) {
                Text(btReadMessage, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "「从系统读取」走本应用自己的 adb 通道（要先在上面「获取 ADB 授权」）：" +
                    "先读系统设置里存的地址，没有再问蓝牙服务。普通应用自己问不到——" +
                    "Android 6 起只给 02:00:00:00:00:00 这个占位值，所以读不到时才需要手输。" +
                    "地址一改，本机标识就跟着变，手机要重新配对一次。",
                style = MaterialTheme.typography.bodySmall,
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

            SettingSwitch(
                title = "数据线优先（有线连接）",
                detail = "由本应用自己驱动 USB 网卡，不需要车机内核支持 NCM。插上线就断开无线改走数据线，" +
                    "拔线后自动回到无线。这条链路还没有在实车上跑过。",
                checked = wiredEnabled,
                onChange = ::applyWiredSetting,
            )
            SettingSwitch(
                title = "插线自动启动",
                detail = "插上数据线时自动打开接收端并直接走有线，不用先点桌面图标。车机若禁止后台启动界面，" +
                    "会改为弹一条通知，点一下即可进入。",
                checked = cableAutoStart,
                onChange = ::applyCableAutoStart,
            )
            SettingSwitch(
                title = "手机蓝牙连上时自动回连",
                detail = "只对连过一次的手机生效。手机进了车里、蓝牙连上但 HiCar 不弹窗时，" +
                    "由车机主动拨它，省掉一次手动开始",
                checked = redialEnabled,
                onChange = ::applyRedialSetting,
            )
            SettingSwitch(
                title = "结束后恢复车机自己的 WiFi",
                detail = "开热点会把车机的 WiFi 关掉。Android 10 起普通应用没有权限再打开它，" +
                    "所以这里只能尝试，被系统拒绝时会提示你手动打开",
                checked = restoreWifiEnabled,
                onChange = ::applyRestoreWifiSetting,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "无障碍设置")
                    },
                ) { Text("USB 授权自动确认") }
                TextButton(
                    onClick = { openSystemSettings(Settings.ACTION_WIFI_SETTINGS, "WiFi 设置") },
                ) { Text("打开 WiFi 设置") }
            }
            Text(
                "「USB 授权自动确认」需要你在系统的无障碍设置里启用「HiCar USB 授权」：" +
                    "插线时系统会弹一个「允许访问 USB 设备」，启用后自动点掉。它只认同时含 USB 字样" +
                    "和允许/确定按钮的窗口，别的一律不碰。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("车速总线（ADB 授权）", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::requestAdb, enabled = !adbBusy) {
                    Text(if (adbBusy) "等待车机确认…" else "获取 ADB 授权")
                }
                TextButton(onClick = { refreshAdbState() }, enabled = !adbBusy) { Text("重新检测") }
            }
            MonoLine("ADB：${adbStateText(adbState)}")
            if (adbMessage.isNotEmpty()) {
                Text(adbMessage, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "随速增益的「车辆总线」来源要读车机自己的 autoservice，只有 adb shell 能访问。" +
                    "这个按钮把本应用生成的 RSA 公钥交给车机 adbd：已经信任时立即显示已授权；" +
                    "没信任时车机会弹一次「允许 USB 调试吗」，点允许后公钥写进车机，" +
                    "重启应用和升级应用都不会丢（卸载会清掉本应用私钥，需要重新授权）。" +
                    "无线 adb 常开时不需要插数据线；有些车机 adbd 不校验密钥（ro.adb.secure=0），" +
                    "会直接通过，不弹窗。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** A titled switch with its explanation; the settings page has five of these now. */
    @Composable
    private fun SettingSwitch(
        title: String,
        detail: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
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
        appendLog(
            "display: window mode ${mode.stored} (${mode.title}); the phone's geometry was fixed at " +
                "its handshake, so this session keeps its shape and the next connection measures " +
                "the visible area again",
        )
    }

    /**
     * Reconnects so the phone encodes for the area that is visible now.
     *
     * `his_video_formats` is answered once, in the HiSight handshake, and the phone's encoder keeps
     * that geometry until the session ends. There is no message in this protocol for "encode a
     * different size now", and the video port itself is handed out per DMSDP negotiation, so the only
     * honest way to get a new shape is a new session. That is disruptive, which is why it is a button
     * with the cost written on it, not something a bar toggle does behind the driver's back.
     */
    private fun reapplyDisplay() {
        if (!running) {
            appendLog("display: not connected; the next connection already uses the current area")
            return
        }
        stopReceiver()
        // The controller releases its listeners and adapter on Stop; starting from inside Stop's own
        // call stack would race that cleanup, so the restart is a posted turn of the main thread.
        window.decorView.postDelayed({
            if (!running && missingPermissions().isEmpty()) {
                // The same half that was running before: a cable session must not come back as a
                // wireless one just because the picture needed a new geometry.
                startReceiver(wirelessOn)
                appendLog("display: reconnected on request so the phone re-encodes for the visible area")
            }
        }, RESTART_DELAY_MILLIS)
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
                "source ${next.source.name.lowercase()}, " +
                "navigation ${if (next.navigationGain) "on" else "off"}, " +
                "call ${if (next.callGain) "on" else "off"}",
        )
    }

    /** Stores the picture settings; a running session keeps what it negotiated and says so. */
    private fun applyVideoSettings(next: HiCarVideoSettings) {
        val changed = next != videoSettings
        videoSettings = next
        HiCarVideoSettingsStore.save(this, next)
        controller?.applyVideoSettings(next)
        if (changed) {
            appendLog(
                "video: ${next.quality.stored} at ${next.frameRate.stored}; " +
                    "the phone was already told what to encode, so this lands on the next connection",
            )
        }
    }

    /** Stores the channel choices; a track that is already playing keeps its channel. */
    private fun applyAudioSettings(next: HiCarAudioSettings) {
        val changed = next != audioSettings
        audioSettings = next
        HiCarAudioSettingsStore.save(this, next)
        controller?.applyAudioSettings(next)
        if (changed) {
            appendLog(
                "audio: music ${HiCarAudioSettings.label(next.mediaStream)}, " +
                    "navigation ${HiCarAudioSettings.label(next.navigationStream)}",
            )
        }
    }

    /** Plays the short tone that tells the driver which speaker a channel number means. */
    private fun previewAudio(media: Boolean) {
        val active = controller
        if (active == null) {
            appendLog("audio: preview needs a running receiver (the decoder lives in it)")
            return
        }
        val played = if (media) active.previewMedia() else active.previewNavigation()
        if (!played) appendLog("audio: no decoder in this build, so the preview cannot play")
    }

    /** Turns the wired path on or off; it is picked up when the receiver next starts. */
    private fun applyWiredSetting(enabled: Boolean) {
        wiredEnabled = enabled
        HiCarWiredSettings.setEnabled(this, enabled)
        appendLog(
            "wired: ${if (enabled) "on" else "off"}; " +
                if (enabled) {
                    "the cable wins over wireless; takes effect on the next start of the receiver"
                } else {
                    "the cable is only probed for diagnosis; takes effect on the next start"
                },
        )
    }

    private fun applyCableAutoStart(enabled: Boolean) {
        cableAutoStart = enabled
        HiCarWiredSettings.setAutoStart(this, enabled)
        appendLog("cable: starting from the data line ${if (enabled) "on" else "off"}")
    }

    private fun applyRedialSetting(enabled: Boolean) {
        redialEnabled = enabled
        HiCarReconnectSettings.setEnabled(this, enabled)
        appendLog("redial: ${if (enabled) "on" else "off"}")
    }

    private fun applyRestoreWifiSetting(enabled: Boolean) {
        restoreWifiEnabled = enabled
        HiCarWifiSettings.setRestoreEnabled(this, enabled)
        appendLog("wifi: restoring the head unit's own Wi-Fi ${if (enabled) "on" else "off"}")
    }

    /** Opens the system page where the driver turns the USB assist on, or Wi-Fi back on. */
    private fun openSystemSettings(action: String, why: String) {
        val opened = runCatching { startActivity(Intent(action)) }.isSuccess
        appendLog(if (opened) "settings: opened $why" else "settings: this ROM has no screen for $why")
    }

    /**
     * The app's own permission page, for a grant that was refused before.
     *
     * `permissions.launch` stops showing a dialog once "don't ask again" is ticked — it answers
     * refused immediately — so the only way left is the system screen. Deep-linking to this app's
     * page avoids asking a driver to find it through three levels of settings while parked.
     */
    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val opened = runCatching { startActivity(intent) }.isSuccess
        appendLog(
            if (opened) "settings: opened this app's permission screen"
            else "settings: this ROM has no app permission screen",
        )
    }

    /**
     * Reads the adbd state without offering the key.
     *
     * A check, not a request: `mayAsk = false` means the car's "allow debugging?" dialog can never
     * appear from here, which matters because this runs on start and may run while driving.
     */
    private fun refreshAdbState(logResult: Boolean = true) {
        if (adbBusy) return
        Thread({
            val state = runCatching {
                BydAdbAccess.checkState(this@HiCarHostActivity, mayAsk = false)
            }.getOrNull()
            runOnUiThread {
                adbState = state
                if (logResult || !adbLogged) {
                    adbLogged = true
                    adbMessage = adbStateText(state)
                    appendLog("adb: ${adbStateText(state)}")
                }
            }
        }, "hicar-adb-check").start()
    }

    /**
     * Offers this app's ADB key to the head unit's own adbd, in one press.
     *
     * The car bus (wheel speed) is only reachable through `adb shell` on `service call`, so the app
     * needs adbd to trust the RSA key it generated. Sent as `AUTH_PUBLIC_KEY`, which is exactly what
     * a PC does; on a secure adbd the car shows its "允许 USB 调试吗" dialog once, and the key is
     * written to `adb_keys` when the driver allows it. A unit built with `ro.adb.secure=0` accepts
     * the connection without any dialog, which is why the same button can return READY immediately.
     *
     * Run on a worker because the approval wait is up to a minute ([LocalAdb.APPROVAL_TIMEOUT_MS]);
     * the UI keeps showing "等待车机确认".
     */
    private fun requestAdb() {
        if (adbBusy) return
        adbBusy = true
        adbMessage = "正在把本应用的密钥交给车机 adbd（127.0.0.1:5555）…如果车机弹出「允许 USB 调试吗」，点「允许」即可"
        appendLog("adb: offering this app's key to the local adbd at 127.0.0.1:5555")
        Thread({
            val state = runCatching {
                BydAdbAccess.checkState(this@HiCarHostActivity, mayAsk = true)
            }.getOrNull()
            // A successful handshake is proven by a command, not by the handshake alone: a shell that
            // opens but returns nothing would still leave the speed source without a reading.
            val proof = if (state == BydAdbAccess.State.READY) {
                runCatching {
                    LocalAdb(AdbKeys.load(this@HiCarHostActivity)).use { it.shell("id") }
                }.getOrNull()
            } else {
                null
            }
            runOnUiThread {
                adbBusy = false
                adbState = state
                adbMessage = when (state) {
                    BydAdbAccess.State.READY -> {
                        // `id` answers with several groups on one long line; the first token is the one
                        // that matters here and keeps the row readable.
                        val who = proof?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
                        "shell 可用" + (who?.let { "（$it）" } ?: "")
                    }
                    BydAdbAccess.State.NOT_APPROVED ->
                        "还没有被允许：再点一次按钮，并在车机屏幕上点「允许」"
                    BydAdbAccess.State.ADB_OFF ->
                        "连不上 127.0.0.1:5555：请确认车机的 adb 调试/无线 adb 已开启"
                    BydAdbAccess.State.PAIRING_ONLY ->
                        "这个 adbd 只接受无线配对（TLS），本应用走 5555 明文通道，用不了"
                    null -> "握手失败（详见日志）"
                }
                appendLog("adb: ${adbStateText(state)}${if (proof != null) "; shell id → ${proof.trim()}" else ""}")
                if (state == BydAdbAccess.State.READY) {
                    // The monitor may be backing off from a failed channel; rebuild it so the car bus
                    // is used now rather than at the next retry.
                    controller?.refreshSpeedVolume()
                }
            }
        }, "hicar-adb-authorize").start()
    }

    /** The one-line state the settings page prints. */
    private fun adbStateText(state: BydAdbAccess.State?): String = when (state) {
        null -> "检测中…"
        BydAdbAccess.State.READY -> "已授权：adb shell 可用（车辆总线要靠它读）"
        BydAdbAccess.State.NOT_APPROVED -> "未授权：点「获取 ADB 授权」，在车机弹窗里点允许"
        BydAdbAccess.State.ADB_OFF -> "连不上 127.0.0.1:5555：请确认车机的 adb 调试（无线 adb）已开启"
        BydAdbAccess.State.PAIRING_ONLY -> "该 adbd 只接受无线配对（TLS），本应用走 5555 明文通道"
    }

    /**
     * Fills the address field from the car itself, over the same adb channel as 获取 ADB 授权.
     *
     * The platform will not answer `BluetoothAdapter.getAddress()` for an ordinary app, so the two
     * sources a PC would read are used instead: the value stored in secure settings, then the
     * Bluetooth service's own `dumpsys` line. The identity is *not* re-derived here: changing it is a
     * pairing-level event (the phone keeps the old string), so the driver keeps that second press for
     * themselves — this function only says what would follow.
     */
    private fun readBluetoothAddress() {
        if (btReading) return
        btReading = true
        btReadMessage = "正在通过 adb 读取（先查系统设置，再问蓝牙服务）…"
        appendLog("identity: reading the car's own Bluetooth address over adb")
        Thread({
            val reading = HiCarBluetoothAddress.read(this@HiCarHostActivity) { line ->
                runOnUiThread { appendLog(line) }
            }
            runOnUiThread {
                btReading = false
                val address = reading.address
                if (address == null) {
                    btReadMessage = reading.detail
                    return@runOnUiThread
                }
                val previous = btAddress
                btAddress = address
                rememberBluetoothAddress(address)
                // Against what is *saved*, not against identity(): see storedIdentity().
                val alreadyApplied = storedIdentity() == derivedIdentityFrom(model, address)
                btReadMessage = when {
                    alreadyApplied ->
                        "读到的就是当前地址（${reading.source.label}）：$address，标识已经是它派生的值"
                    previous.equals(address, ignoreCase = true) ->
                        "地址本来就是 $address（${reading.source.label}），但标识不是按它派生的：" +
                            "按下面「按蓝牙地址派生本机标识」应用它"
                    else ->
                        "已填入 $address（${reading.source.label}）" +
                            (if (previous.isNotBlank()) "，替换了原先的 $previous" else "") +
                            "。标识还没变：按下面「按蓝牙地址派生本机标识」，手机需要重新配对一次。"
                }
            }
        }, "hicar-bt-address").start()
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

    /**
     * The one button on the setup page.
     *
     * Before a run it starts the receiver with Wi-Fi on. During a run it owns **only** the wireless
     * half: a projection that arrives over the cable is not something this button is allowed to end,
     * so "停止" parks the access point and the advertisement instead of closing the session.
     */
    private fun toggle() {
        if (!running) {
            pendingWireless = true
            val missing = missingPermissions()
            if (missing.isNotEmpty()) {
                // Say it in the log too: the dialog can be dismissed or drawn behind the receiver, and
                // then the only trace of "pressed Start, nothing happened" would be missing.
                appendLog("start: asking for ${missing.joinToString()} before opening anything")
                permissions.launch(missing)
            } else {
                startReceiver(wireless = true)
            }
            return
        }
        val next = !wirelessOn
        // Turning the wireless half back on has the same requirement as starting it: without the
        // location grant the access point never opens, and the refusal only shows up after the phone
        // has already finished HiChain. Ask here so the dialog appears before that wasted round.
        if (next) {
            val missing = missingPermissions()
            if (missing.isNotEmpty()) {
                pendingWireless = true
                appendLog("button: wireless on needs ${missing.joinToString()}; asking for it first")
                permissions.launch(missing)
                return
            }
        }
        wirelessOn = next
        controller?.setWireless(next)
        appendLog("button: wireless ${if (next) "on" else "off"}; the cable is not affected")
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

    /**
     * Starts the receiver.
     *
     * [wireless] is false only when a cable woke the app: the Bluetooth records are opened either
     * way, because that is where the phone authenticates, but no advertisement and no access point
     * are offered until the driver turns the wireless half on.
     */
    private fun startReceiver(wireless: Boolean = true) {
        if (running) return
        // Last gate before anything opens a radio. Every caller is supposed to ask first, but this
        // one path is what a cable broadcast and a restored activity also take, and a run without
        // the location grant fails a whole HiChain later with nothing to hand the phone.
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingWireless = wireless
            appendLog(
                "receiver: not starting; ${missing.joinToString()} is missing " +
                    "(the access point and the self-scan both need it)",
            )
            permissions.launch(missing)
            return
        }
        running = true
        wirelessOn = wireless
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
        if (wireless && runCatching { bluetoothAdapter?.scanMode }.getOrNull() !=
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
            bluetoothAddress = btAddress,
        )
        val next = try {
            HiCarController(
                context = this,
                config = config,
                report = { next -> runOnUiThread { status = next } },
                onLog = { line -> runOnUiThread { appendLog(line) } },
                onSpeedStatus = { next -> runOnUiThread { speedStatus = next } },
                videoSettings = videoSettings,
                onVpnConsent = { consent -> runOnUiThread { vpnConsent.launch(consent) } },
            )
        } catch (failure: RuntimeException) {
            // A constructor that refuses its configuration is a bug, not a device failure; say which
            // one instead of closing the screen.
            running = false
            appendLog("HiCar receiver could not start (${failure.javaClass.simpleName}: ${failure.message})")
            return
        }
        controller = next
        // The area the picture will really occupy, not the whole panel: with a status or navigation
        // bar left visible the window is shorter, and offering the panel's 16:9 would make the phone
        // encode a picture this window then has to letterbox. The phone may still ignore the offer;
        // the decoder's output stays authoritative and the window follows it.
        val screen = realScreenPixels()
        val content = contentPixels()
        val requested = videoSettings.offerSize(HiCarDisplaySize(content[0], content[1]))
        next.setDisplaySize(requested)
        videoAspect = requested.aspect
        val window = resources.displayMetrics
        appendLog(
            "display: offering ${requested.width}×${requested.height} at " +
                "${HiCarDisplaySize.densityFor(requested.width)} dpi " +
                "(panel ${screen[0]}×${screen[1]}, content ${content[0]}×${content[1]}, " +
                "window ${window.widthPixels}×${window.heightPixels})",
        )
        next.start(wireless)
        // A run started from the button begins while this window is already up; the cable is told so
        // once, because a USB permission prompt needs a visible caller on this car.
        next.setHostVisible(windowVisible)
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
     * The pixels the picture may occupy under the current display mode.
     *
     * `HiCarWindowMode` decides which bars stay visible, and a visible bar is *not* drawn over the
     * picture: `Content()` pads the layout by the same inset, so the image is laid out in the smaller
     * rectangle. That rectangle is what the phone has to encode for, which is why this is measured
     * separately from the panel.
     *
     * The insets come from the window, in pixels, so a landscape unit whose navigation bar sits on
     * the side is handled by subtracting left/right as well as top/bottom. Before the first layout
     * the insets are unknown and the panel is returned; a session cannot start before the start
     * button exists, so in practice the measurement is always available.
     */
    private fun contentPixels(): IntArray {
        val panel = realScreenPixels()
        val insets = runCatching { ViewCompat.getRootWindowInsets(window.decorView) }.getOrNull()
        val status = if (windowMode.showsStatusBar) {
            insets?.getInsets(WindowInsetsCompat.Type.statusBars())
        } else {
            null
        }
        val navigation = if (windowMode.showsNavigationBar) {
            insets?.getInsets(WindowInsetsCompat.Type.navigationBars())
        } else {
            null
        }
        val horizontal = (status?.left ?: 0) + (status?.right ?: 0) +
            (navigation?.left ?: 0) + (navigation?.right ?: 0)
        val vertical = (status?.top ?: 0) + (status?.bottom ?: 0) +
            (navigation?.top ?: 0) + (navigation?.bottom ?: 0)
        return intArrayOf(
            (panel[0] - horizontal).coerceAtLeast(2),
            (panel[1] - vertical).coerceAtLeast(2),
        )
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
    /**
     * Every finger on the picture, forwarded as one snapshot per event.
     *
     * The four `MotionEvent` cases do not map one-to-one onto the three UIBC actions, and the
     * difference is the whole reason a pinch works: `ACTION_POINTER_DOWN` (a second finger) and
     * `ACTION_POINTER_UP` (one of several leaving) are still the same *gesture*, so they go out as
     * moves carrying the new set — only `ACTION_DOWN` starts a gesture and only `ACTION_UP` ends it.
     * Sending a multi-finger set as a down, or an up with two contacts, is how a phone ends up with
     * a finger pressed forever.
     */
    private fun videoTouch(view: View, event: MotionEvent): Boolean {
        val active = controller ?: return false
        val width = view.width
        val height = view.height
        if (width < 1 || height < 1) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = touchPoint(active, event, 0, width, height) ?: return false
                active.sendTouches(HiCarController.TOUCH_DOWN, listOf(point))
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                val points = touchSnapshot(active, event, width, height, skipIndex = -1)
                // An empty set would be read as "everything lifted", which would end the gesture the
                // driver is still making; a move with nothing to report is simply not sent.
                if (points.isEmpty()) return true
                active.sendTouches(HiCarController.TOUCH_MOVE, points)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // The finger that left is still in this event; the set the phone should now hold is
                // the other ones.
                val points = touchSnapshot(active, event, width, height, skipIndex = event.actionIndex)
                if (points.isEmpty()) return true
                active.sendTouches(HiCarController.TOUCH_MOVE, points)
            }

            MotionEvent.ACTION_UP -> {
                val point = touchPoint(active, event, 0, width, height) ?: return false
                active.sendTouches(HiCarController.TOUCH_UP, listOf(point))
            }

            MotionEvent.ACTION_CANCEL -> {
                // Whatever the window took the gesture for, the phone must not keep a finger down.
                active.cancelTouch()
            }

            else -> return false
        }
        return true
    }

    private fun touchPoint(
        active: HiCarController,
        event: MotionEvent,
        index: Int,
        width: Int,
        height: Int,
    ): HiCarTouchPoint? {
        if (index >= event.pointerCount) return null
        val mapped = active.mapTouch(event.getX(index), event.getY(index), width, height) ?: return null
        return HiCarTouchPoint(event.getPointerId(index), mapped[0], mapped[1])
    }

    private fun touchSnapshot(
        active: HiCarController,
        event: MotionEvent,
        width: Int,
        height: Int,
        skipIndex: Int,
    ): List<HiCarTouchPoint> {
        val points = ArrayList<HiCarTouchPoint>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            if (index == skipIndex) continue
            touchPoint(active, event, index, width, height)?.let { points.add(it) }
        }
        return points
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
        // Before the controller: a session left open would keep sending keys to a closed channel.
        closeMediaKeySession("receiver stopped")
        keysReady = false
        mediaFocus = false
        running = false
        cableNoted = false
        controller?.close()
        controller = null
        status = HiCarStatus.Stopped
        appendLog("--- receiver stopped ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ---")
    }

    /**
     * Steering-wheel keys, seen before dispatch.
     *
     * While the receiver is on screen this is the only entry: the key arrives here, and consuming it
     * is what stops the head unit from acting on it too — a "next" that skips both the phone's track
     * and the car's own radio is worse than either alone.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleMediaKey(event, "window")) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleMediaKey(event: KeyEvent, source: String): Boolean {
        if (!mediaKeysEnabled) return false
        if (event.keyCode !in HiCarMediaKeyForwarder.KEY_CODES) return false
        // Re-checked here as well as pushed by the controller: the input socket can appear between two
        // status reports, and a key pressed in that gap must not be dropped silently.
        mediaKeys.ready = keysReady && mediaKeysEnabled
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val result = mediaKeys.onKey(event.keyCode, down, event.repeatCount, event.eventTime)
        if (event.repeatCount == 0) {
            appendLog(
                "keys: ${HiCarMediaKeyForwarder.label(event.keyCode)} " +
                    "${if (down) "down" else "up"} from $source; ${result.note}",
            )
        }
        deliverKeys(result)
        return result.handled
    }

    private fun deliverKeys(result: HiCarMediaKeyForwarder.Result) {
        if (result.keys.isEmpty()) return
        val active = controller
        for (key in result.keys) {
            val sent = active?.sendMediaKey(key.code, key.down) == true
            if (!sent) {
                appendLog(
                    "keys: ${HiCarMediaKeyForwarder.label(key.code)} " +
                        "${if (key.down) "down" else "up"} was not sent; no input channel this session",
                )
            }
        }
    }

    /**
     * Keeps the media-button session in step with the two things it depends on.
     *
     * It exists only while keys are usable **and** the speaker holds media focus: without focus some
     * other app owns the unit's media buttons, and a session of ours would take them from it.
     */
    private fun updateMediaKeySession() {
        val available = keysReady && mediaKeysEnabled
        mediaKeys.ready = available
        val want = available && mediaFocus
        val open = mediaKeySession
        if (want && open == null) {
            mediaKeySession = runCatching { HiCarMediaKeySession(this, KEY_SESSION_TAG, keySessionListener) }
                .onFailure {
                    appendLog(
                        "keys: no media-button session (${it.javaClass.simpleName}); " +
                            "wheel keys work only while the receiver is on screen",
                    )
                }
                .getOrNull()
            if (mediaKeySession != null) {
                appendLog("keys: media-button session open; wheel keys reach the phone in the background too")
            }
        } else if (!want && open != null) {
            closeMediaKeySession(if (!available) "keys unavailable" else "media focus released")
        }
    }

    private fun closeMediaKeySession(why: String) {
        val open = mediaKeySession
        mediaKeySession = null
        mediaKeys.ready = false
        if (open == null) return
        runCatching { open.close() }
        appendLog("keys: media-button session closed ($why); the wheel belongs to the car again")
    }

    private val keySessionListener = object : HiCarMediaKeySession.Listener {
        override fun onMediaKey(event: KeyEvent): Boolean = handleMediaKey(event, "media session")

        override fun onCommand(command: HiCarMediaKeyForwarder.Command) {
            val result = mediaKeys.onCommand(command)
            appendLog(
                "keys: ${HiCarMediaKeyForwarder.label(command.code)} command from the media session; " +
                    result.note,
            )
            deliverKeys(result)
        }
    }

    /** Persists the wheel switch and applies it immediately, session included. */
    private fun applyMediaKeysSetting(enabled: Boolean) {
        mediaKeysEnabled = enabled
        HiCarKeySettings.setMediaKeysEnabled(this, enabled)
        appendLog("keys: steering wheel forwarding ${if (enabled) "on" else "off"}")
        updateMediaKeySession()
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
        // Up to Android 12L the access point is gated on Location, and on 12/12L the Bluetooth
        // runtime names above do not include it — asking only for those leaves Wi-Fi Direct and
        // LocalOnlyHotspot unopenable without a dialog ever being shown for the missing grant.
        if (Build.VERSION.SDK_INT < 33) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.distinct()
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
    private fun identity(): String = storedIdentity() ?: derivedIdentity()

    /**
     * The identity this build has *written down*, or null when nothing is saved yet.
     *
     * Deliberately not [identity]: that one falls back to deriving on the fly, which looks identical
     * on screen but is not what a phone was given last time. Comparing against a live derivation
     * would call an unsaved identity "already applied" and leave the old string in the preferences.
     */
    private fun storedIdentity(): String? =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_IDENTITY, null)
            ?.takeIf { IDENTITY_PATTERN.matches(it) }

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
        is HiCarStatus.Wired -> "数据线 ${status.address}，等待手机认证"
        is HiCarStatus.Blocked -> "受阻：${status.blocker.message}"
        is HiCarStatus.Failed -> failureText(status.reason)
        is HiCarStatus.Stopped -> "已停止"
    }

    /**
     * A failure the driver can act on, not a protocol token.
     *
     * "hotspot" is nearly always the location grant: Wi-Fi Direct and LocalOnlyHotspot both refuse
     * without it, and the refusal only surfaces after the phone has finished HiChain — which reads
     * like an authentication fault even though the handshake succeeded. Naming the permission here is
     * the difference between a driver who fixes it in ten seconds and one who reports a protocol bug.
     */
    private fun failureText(reason: String): String = when {
        reason.startsWith("hotspot") && missingPermissions().isNotEmpty() ->
            "开不了热点：缺少 ${missingPermissions().joinToString { it.substringAfterLast('.') } }" +
                " 权限（蓝牙扫描也用它）。点下面的「打开应用权限」授予后再点开始"
        reason.startsWith("hotspot") ->
            "开不了热点（$reason）。日志里 Wi-Fi P2P / LocalOnlyHotspot 那两行写着具体原因"
        else -> "失败：$reason"
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val MAX_HISTORY_LINES = 5_000

        /** How much of the log each surface shows; the export always carries all of it. */
        const val SETUP_LOG_LINES = 12
        const val SESSION_LOG_LINES = 6
        const val SETTINGS_LOG_LINES = 40

        /** Rendered large and scaled down by Compose: a 512 px code survives a 150 dp box. */
        const val QR_PIXELS = 512
        val QR_SIZE = 150.dp

        /** `adb logcat -s HiCar` on a head unit: short, and stable across sessions. */
        const val LOG_TAG = "HiCar"

        /**
         * How long Stop gets to release the adapter and listeners before Start runs again.
         *
         * The reference receiver's own reconnect path waits about this long; starting immediately
         * lands while the previous round's sockets are still closing.
         */
        const val RESTART_DELAY_MILLIS = 1_200L

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
        /** MediaSession tag; appears in `dumpsys media_session` next to the car's own players. */
        const val KEY_SESSION_TAG = "HiCar"

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
