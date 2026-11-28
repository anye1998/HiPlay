package com.shilapi.xcertplay.hicar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.hicarauth.HiChainNative
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** What the HiCar receiver is doing, for the driver and for diagnostics. */
sealed class HiCarStatus {
    data object Idle : HiCarStatus()
    data object PreparingNetwork : HiCarStatus()
    data class NetworkReady(val ssid: String, val address: String, val backend: String) : HiCarStatus()
    data object Advertising : HiCarStatus()
    data object WaitingForPhone : HiCarStatus()
    data object ChannelNegotiated : HiCarStatus()
    data object Authenticating : HiCarStatus()

    /** HiChain finished and the engine produced a session key. */
    data object Authenticated : HiCarStatus()

    /** The DMSDP control channel answered the crypto switch on the same socket. */
    data object ControlChannelReady : HiCarStatus()

    /** The phone's video port answered and the picture path is running. */
    data object Streaming : HiCarStatus()

    /** The cable is carrying packets at [address]; nothing has been authenticated over it yet. */
    data class Wired(val address: String) : HiCarStatus()

    /** The phone asked for HiChain, which this build cannot answer; see [blocker]. */
    data class Blocked(val blocker: HiCarAuthBlocker) : HiCarStatus()
    data class Failed(val reason: String) : HiCarStatus()
    data object Stopped : HiCarStatus()
}

/**
 * Everything the receiver needs from the car configuration.
 *
 * [model] is the eight hexadecimal digits advertised on 0xFDEE; [localIdentity] is the identity the
 * phone sees in the authentication exchange. Neither is a Huawei credential: the real accessory
 * credential is issued to a product and is what the side-loaded build is missing.
 */
data class HiCarRuntimeConfig(
    val model: String,
    val localIdentity: String,
    val deviceName: String = "HiCar receiver",
    val controlPort: Int = HiCarCodes.DEFAULT_CONTROL_PORT,
    /** The six digits shown on this screen; the phone asks the driver for the same code. */
    val pin: String = "",
    val pinConfigured: Boolean = pin.isNotEmpty(),
    val networkTimeoutMillis: Long = 30_000L,
    /** Decode the phone's picture. Off is only useful for diagnosing the pre-media chain. */
    val withVideo: Boolean = true,
    /** Offer the car's microphone. The runtime permission is still checked separately. */
    val withMicrophone: Boolean = false,
    /**
     * The head unit's classic Bluetooth address, as the settings page shows it.
     *
     * Needed by the wired path: it goes into the car-info block the phone turns into a device id and
     * an address to dial. Empty means "ask the framework", which on modern Android answers with the
     * placeholder `02:00:00:00:00:00`.
     */
    val bluetoothAddress: String = "",
)

/**
 * Drives the HiCar receiver connection chain on the car side.
 *
 * Order: bring the access point up first (the phone needs somewhere to go), then register the two
 * insecure RFCOMM records, then advertise 0xFDEE so the phone starts the flow. The control channel
 * negotiation and the Wi-Fi credential exchange are implemented; authentication stops at HiChain,
 * which reports [HiCarStatus.Blocked] instead of inventing a session key.
 *
 * Media (HiSight RTSP, video and audio) is deliberately not started before a key exists: see
 * `HiCarCapabilities`.
 */
class HiCarController(
    context: Context,
    private val config: HiCarRuntimeConfig,
    private val report: (HiCarStatus) -> Unit,
    private val onLog: (String) -> Unit = {},
    /**
     * Whether the car's media keys can be sent right now.
     *
     * True needs both halves: the phone selected key events in its capability answer, and the input
     * socket exists. The screen uses it to create (and later close) the media-button session, so the
     * announcement is pushed rather than polled.
     */
    private val onKeysReady: (Boolean) -> Unit = {},
    /** Whether the speaker holds media focus, which decides if the media-button session is raised. */
    private val onMediaFocus: (Boolean) -> Unit = {},
    /**
     * Which speed source is answering, for the settings page.
     *
     * Pushed about once a second while the feature runs, and reset when the session ends, so the page
     * never shows a reading that stopped arriving. Delivered from a handler thread; the UI hops to
     * the main thread itself.
     */
    private val onSpeedStatus: (HiCarSpeedStatus) -> Unit = {},
    /**
     * Sharpness and frame rate.
     *
     * Read once per connection: `his_video_formats` is answered during the RTSP handshake, so this is
     * a value the controller is constructed with rather than one it can change mid-session.
     */
    videoSettings: HiCarVideoSettings = HiCarVideoSettings(),
    /**
     * Raises the system's VPN consent dialog for the cable.
     *
     * The tunnel is what makes the phone's IP packets reach this receiver, and Android only allows
     * one after the driver answers. The activity owns the launcher; the answer comes back through
     * [onVpnConsentResult].
     */
    private val onVpnConsent: (Intent) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext

    @Volatile private var videoOffer = videoSettings

    /**
     * The panel's refresh rate.
     *
     * Read from the window manager rather than `DisplayMetrics`, which reports the density-derived
     * rate on some vendor ROMs. A head unit with no window service (the emulator's older images)
     * falls back to 60, which is what the fixed 30 fps code assumed anyway.
     */
    private val refreshHz: Float by lazy {
        val hz = runCatching {
            val manager = appContext.getSystemService(android.view.WindowManager::class.java)
            @Suppress("DEPRECATION")
            manager?.defaultDisplay?.refreshRate
        }.getOrNull()
        HiCarVideoSettings.refreshOf(hz)
    }
    private val running = AtomicBoolean(false)
    private var setup = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hicar-setup") }
    private var accept = Executors.newCachedThreadPool { runnable -> Thread(runnable, "hicar-accept") }
    private val sockets = CopyOnWriteArrayList<Closeable>()
    private val workers = CopyOnWriteArrayList<Thread>()

    /**
     * Identifies one "round": one access point, one advertisement, one pair of listeners.
     *
     * A round ends either when the driver stops the receiver or when the phone goes away. In the
     * second case the same controller starts a new round, and everything that belongs to the old one
     * — accept loops, heartbeat, connections — has to notice that it is stale and stop touching
     * shared state. A generation counter is cheaper and more reliable than trying to interrupt each
     * of them, and it is what the reference receiver does.
     */
    @Volatile private var generation = 0

    /** One restart per round: the control and business channels usually close together. */
    private var reconnectPending = false
    private val reconnectLock = Any()

    /** One restart at a time, whichever thread asked for it (phone, cable, driver). */
    private val restartLock = Any()
    @Volatile private var restarting = false

    /**
     * A restart was asked for while one was already running.
     *
     * The wireless flag can change during a restart — a cable arriving while the driver presses
     * "停止无线" is exactly that — and dropping the second request would leave the radio doing
     * something the screen no longer shows.
     */
    private var restartAgain = false

    /** How many RFCOMM connections arrived; the waiting heartbeat reports it. */
    private val connections = AtomicInteger(0)

    private var hotspot: WirelessHotspotManager? = null
    private var services: HiCarRfcommServices? = null
    private var advertiser: HiCarBleAdvertiser? = null
    private var probe: HiCarAdvertisementProbe? = null

    /** Created only after the access point is up, so it can never answer with a placeholder offer. */
    private var liveNetwork: HiCarNetworkNegotiator? = null

    /** The three TCP endpoints the phone dials after authentication; null until the AP is ready. */
    private var dmsdp: HiCarDmsdpEndpoints? = null

    /** What the phone was told about the access point; the media clients bind to [ownerAddress]. */
    private var liveOffer: HiCarNetworkOffer? = null

    /**
     * Guards the access point coming up late.
     *
     * The hotspot used to be opened before advertising, which meant every "Start" dropped the head
     * unit's own Wi-Fi and held the radio whether or not a phone ever appeared. It is now opened when
     * the phone finishes HiChain — the first moment it is actually needed — and the Wi-Fi negotiation
     * that arrives immediately afterwards waits here for it.
     */
    private val networkLock = Object()

    /** One access point per round: a second request would fight the first for the radio. */
    @Volatile private var networkStarted = false

    /** The H.264 sink, created on first use so the phone can be offered the display service. */
    private var video: HiCarVideoSink? = null

    /**
     * What this receiver asks the phone for. 1080p unless the screen cannot show it.
     *
     * A copy is kept here as well as in [input] because a session can start before any sink exists,
     * and the wire needs it at the moment the capability is answered.
     */
    @Volatile
    private var displaySize: HiCarDisplaySize = HiCarDisplaySize.BASE

    /** Touches and keys for the phone; only the current HiSight session ever attaches to it. */
    private val input = HiCarInputRouter()

    /** At most one video session at a time, however many control connections arrive. */
    private val mediaBusy = AtomicBoolean(false)
    private var hisight: HiCarHiSightClient? = null

    /** Whether the phone's `his_uibc_capability` answer included key events. */
    @Volatile private var keyInputSelected = false

    @Volatile private var audioReady = false
    @Volatile private var microphoneReady = false
    @Volatile private var microphoneWanted = false

    /** The speaker of the current session; kept here so navigation focus can move its route. */
    private var speaker: HiCarAudioSink? = null

    /**
     * The speed-linked music gain of the current session, if it is on.
     *
     * Kept at controller level rather than inside the media object because the settings page can be
     * opened *during* a connection: [refreshSpeedVolume] has to reach the running audio path, and
     * the media object is not reachable from the screen.
     */
    private var speedMonitor: HiCarSpeedMonitor? = null

    /**
     * Watches the USB host port while the receiver runs.
     *
     * Wired HiCar arrives over a network interface the phone brings up on the cable, so the first
     * thing that has to exist is that interface - everything above it can be built on evidence.
     */
    private var usb: HiCarUsbLink? = null

    /** The wired session, when the driver has turned it on; the diagnostic probe otherwise. */
    private var wired: HiCarWiredLink? = null

    /**
     * Whether the wireless half of this run is wanted.
     *
     * The screen's start/stop button controls only this: a cable session is driven by the cable, and
     * stopping "the wireless" must not stop a projection that runs over the cable. Off means no
     * advertisement and no access point, which is the honest state for a run that only exists because
     * a cable was plugged in.
     */
    @Volatile private var wirelessWanted = true

    /** Wired-priority rule; see [HiCarTransportArbiter]. */
    private val arbiter = HiCarTransportArbiter()

    /** The DMSDP listeners bound to the tunnel's address, when the cable is carrying the session. */
    private var wiredEndpoints: HiCarDmsdpEndpoints? = null

    /** Dials a known phone when its Bluetooth link comes up; owned by the run, not the round. */
    private var redial: HiCarAutoReconnect? = null

    /**
     * The head unit's navigation stream type.
     *
     * This unit gives guidance its own channel - 14 - so a prompt is not attenuated with the music
     * it plays over. It is a vendor number the platform does not know, which is why it goes through
     * the legacy track constructor; a ROM that refuses it says so once and keeps the media route.
     */
    private val navigationStreamType: Int
        get() = HiCarDebug.number(PROP_NAV_STREAM, DEFAULT_NAV_STREAM)

    /** Channel choices in force; the sink takes them when it builds a track. */
    @Volatile private var audioSettings = HiCarAudioSettings()

    /**
     * Whether the head unit's own Wi-Fi was on before this run took the radio.
     *
     * Read once per run, used once when the receiver stops — not between rounds, because a phone
     * that drops and reconnects would lose its access point if the radio were handed back then.
     */
    @Volatile private var wifiWasOn = false

    /**
     * A copy of the authenticated session key, for the TCP side.
     *
     * The authenticator's own copy is cleared with its session, which happens on the RFCOMM thread as
     * soon as that channel closes — and by then the phone is usually already talking DMSDP. The copy
     * is wiped in [close].
     */
    private var liveKey: ByteArray? = null

    /**
     * Runs the key store's self-test and writes every line to the log.
     *
     * This exists because the exchange phase's failures are invisible from the car: it needs a
     * phone, a PIN and a working discovery chain before anything signs anything. The same calls can
     * be exercised on their own, on any device, which is the difference between "the car cannot
     * authenticate" and "signing returns -134".
     */
    fun cryptoSelfTest() {
        probeVendorSdk()
        if (!HiChainNative.available) {
            onLog("HiChain self-test: the native engine is not in this build")
            return
        }
        val prepared = HiChainNative.storeDirectory(appContext.getDir("hicar-huks", Context.MODE_PRIVATE))
        if (!prepared.isDirectory) {
            onLog("HiChain self-test: the store directory could not be created")
            return
        }
        val initialized = HiChainNative.init(prepared.absolutePath, config.localIdentity)
        if (initialized != 0) {
            onLog("HiChain self-test: engine init failed ($initialized)")
            return
        }
        val report = HiChainNative.selfTest()
        if (report == null) {
            onLog("HiChain self-test: the engine returned no report")
            return
        }
        report.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            onLog("HiChain self-test: $line")
        }
        loopbackHandshake()
        secondConnection()
    }

    /**
     * Binds inside this process and then connects again, the way a phone that already trusts this
     * car does.
     *
     * This is the one part of the protocol that could not be exercised without the car: a second
     * connection can only be started by the centre, which is the phone's role. The engine does
     * implement `authenticate_peer`, so both roles can be played here and the exchange becomes
     * readable. The firmware's SDK is tried as a second phone when it was extracted, which is what
     * separates "we refuse a correct request" from "its request differs from ours".
     */
    private fun secondConnection() {
        if (!PIN_DIGITS.matches(config.pin)) {
            onLog("HiChain second connection: skipped, the connection code is not six digits")
            return
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val vendor = File(appContext.filesDir, "vendor-$abi").takeIf { it.isDirectory }
        // Two identity shapes: this test's own short ids, and the phone's, whose auth id is the
        // 64 characters of its deviceId. Everything else about the request is the same, so a
        // difference between the two runs is a difference the id length causes.
        val shapes = listOf(
            "short ids" to (CAR_ID.toByteArray() to PHONE_ID.toByteArray()),
            "phone-shaped" to ("car-test".toByteArray() to PHONE_SHAPED_ID.toByteArray()),
        )
        for ((label, ids) in shapes) {
            val report = runCatching {
                HiChainNative.probeVendorAuthenticate(
                    vendor?.absolutePath,
                    ids.first,
                    ids.second,
                    config.pin,
                )
            }.getOrNull()
            if (report == null) {
                onLog("HiChain second connection [$label]: the harness returned no report")
                continue
            }
            report.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                onLog("HiChain second connection [$label]:$line")
            }
        }
    }

    /**
     * Extracts the head unit's own HiCar authentication libraries and reports whether they load.
     *
     * The vendor app keeps Huawei's SDK as uncompressed members of its APK, and that APK is world
     * readable, so nothing has to be shipped with this one: the members are copied into this app's
     * private directory (reachable only by this app) and handed to the loader. What matters there
     * is `authenticate_peer` and `is_trust_peer` — entries the OpenHarmony engine does not have at
     * all, and the ones a trusted reconnect depends on.
     */
    private fun probeVendorSdk() {
        if (!HiChainNative.available) {
            return
        }
        val apk = File(VENDOR_APK)
        if (!apk.isFile || !apk.canRead()) {
            onLog("Vendor SDK: $VENDOR_APK is not readable; nothing to probe")
            return
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val directory = File(appContext.filesDir, "vendor-$abi").apply { mkdirs() }
        val members = listOf("libsecurec.so", "libHwKeystoreSDK.so", "libHwDeviceAuthSDK.so")
        try {
            ZipFile(apk).use { zip ->
                for (name in members) {
                    val entry = zip.getEntry("lib/$abi/$name")
                    if (entry == null) {
                        onLog("Vendor SDK: the firmware APK has no lib/$abi/$name")
                        return
                    }
                    val target = File(directory, name)
                    if (target.length() != entry.size) {
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    runCatching {
                        target.setReadable(false, false)
                        target.setReadable(true, true)
                    }
                }
            }
        } catch (failure: Exception) {
            onLog("Vendor SDK: could not extract (${failure.javaClass.simpleName})")
            return
        }
        val report = runCatching { HiChainNative.probeVendorSdk(directory.absolutePath) }.getOrNull()
        if (report == null) {
            onLog("Vendor SDK: the loader probe returned nothing")
            return
        }
        report.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            onLog("Vendor SDK: $line")
        }
    }

    /**
     * Runs a complete bind between two sessions inside this process: one acting as the car, one as
     * the phone.
     *
     * The key-store self-test proves each call in isolation; this proves the exchange phase, which
     * is the part that used to end in `END_FAILED` on the car. Both sessions are the same engine
     * with opposite roles and distinct identities, so a pass means the packet flow, the alias
     * handling, the signatures and the shared key all agree — the one thing it cannot prove is that
     * a real phone's implementation agrees, which is what the car test is for.
     */
    private fun loopbackHandshake() {
        if (!PIN_DIGITS.matches(config.pin)) {
            onLog("HiChain loopback: skipped, the connection code is not six digits")
            return
        }
        var car = 0L
        var phone = 0L
        try {
            car = HiChainNative.create(CAR_ID.toByteArray(), PHONE_ID.toByteArray(), config.pin, ROLE_ACCESSORY)
            phone = HiChainNative.create(PHONE_ID.toByteArray(), CAR_ID.toByteArray(), config.pin, ROLE_CENTRE)
            if (car == 0L || phone == 0L) {
                onLog("HiChain loopback: could not create both sessions (car=$car, phone=$phone)")
                return
            }
            val toCar = ArrayDeque<ByteArray>()
            val toPhone = ArrayDeque<ByteArray>()
            val firstStep = HiChainNative.stepDecoded(phone, null, KEY_BYTES)
            if (firstStep == null) {
                onLog("HiChain loopback: the phone side produced no first packet")
                return
            }
            // Tracked as numbers rather than as Step objects: the type lives inside the binding's
            // companion, and only these four values are ever read.
            var phoneResult = firstStep.result
            var phoneKeyLength = firstStep.keyLength
            var phoneError = firstStep.error
            var phoneCompleted = firstStep.completed
            var carResult = -1
            var carKeyLength = 0
            var carError = -1
            var carCompleted = false
            toCar.addAll(firstStep.packets)
            var exchanges = 0
            while (exchanges < MAX_EXCHANGES && (toCar.isNotEmpty() || toPhone.isNotEmpty())) {
                if (toCar.isNotEmpty()) {
                    val step = HiChainNative.stepDecoded(car, toCar.removeFirst(), KEY_BYTES) ?: break
                    carResult = step.result
                    carKeyLength = step.keyLength
                    carError = step.error
                    carCompleted = step.completed
                    toPhone.addAll(step.packets)
                } else {
                    val step = HiChainNative.stepDecoded(phone, toPhone.removeFirst(), KEY_BYTES) ?: break
                    phoneResult = step.result
                    phoneKeyLength = step.keyLength
                    phoneError = step.error
                    phoneCompleted = step.completed
                    toCar.addAll(step.packets)
                }
                exchanges++
            }
            onLog(
                "HiChain loopback: $exchanges exchange(s); car=" +
                    "${HiChainNative.describeResult(carResult)} key=${carKeyLength}B error=$carError, " +
                    "phone=${HiChainNative.describeResult(phoneResult)} key=${phoneKeyLength}B " +
                    "error=$phoneError",
            )
            val carKey = HiChainNative.sessionKey(car)
            val phoneKey = HiChainNative.sessionKey(phone)
            when {
                carKey == null || phoneKey == null ->
                    onLog(
                        "HiChain loopback: no session key on the " +
                            (if (carKey == null) "car" else "phone") + " side",
                    )
                carKey.contentEquals(phoneKey) ->
                    onLog("HiChain loopback: both sides derived the same ${carKey.size}-byte key")
                else -> onLog("HiChain loopback: the two sides derived DIFFERENT keys")
            }
            onLog(
                "HiChain loopback: operation complete car=$carCompleted phone=$phoneCompleted",
            )
        } catch (failure: RuntimeException) {
            onLog("HiChain loopback: threw ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            // Both sides stored a trust entry for the other; neither may survive the test. The
            // statuses are reported because "cleaned up" is a claim, and a silent failure here
            // would leave a permanently trusted fake peer in the car's key store.
            if (car != 0L && phone != 0L) {
                val carForgot = HiChainNative.forgetPeer(car, PHONE_ID, USER_TYPE_CONTROLLER)
                val phoneForgot = HiChainNative.forgetPeer(phone, CAR_ID, USER_TYPE_ACCESSORY)
                onLog(
                    "HiChain loopback: trust cleanup car=$carForgot phone=$phoneForgot (0 = removed)",
                )
            }
            if (car != 0L) {
                HiChainNative.close(car)
            }
            if (phone != 0L) {
                HiChainNative.close(phone)
            }
        }
    }

    /**
     * The sink the screen attaches its `TextureView` to; safe to call before [start].
     *
     * The sink is told the geometry this session offers, and reports back what the phone actually
     * encodes; the touch space follows that report, so a phone that ignores the offer still maps
     * correctly.
     */
    fun videoSink(): HiCarVideoSink = synchronized(this) {
        video ?: HiCarVideoSink(onLog, displaySize).also { video = it }
    }

    /**
     * Registers what happens when the picture's real size becomes known.
     *
     * The touch space is updated here rather than by the screen: a caller that only wants to lay out
     * its window must not be able to leave touches mapping onto the wrong geometry by forgetting to
     * forward the size.
     */
    fun onPictureSize(listener: (Int, Int) -> Unit) {
        videoSink().onDisplaySize = { width, height ->
            input.setDisplaySize(width, height)
            listener(width, height)
        }
    }

    /**
     * The geometry offered to the phone.
     *
     * Set from the screen this receiver is presented on, before [start]. 1080p is the ceiling; the
     * decoder's output is authoritative afterwards, so this only decides what is asked for.
     */
    fun setDisplaySize(size: HiCarDisplaySize) {
        synchronized(this) {
            displaySize = size
            input.setDisplaySize(size.width, size.height)
        }
    }

    /** Sends one touch in the picture's coordinate space, whatever size that turned out to be. */
    fun sendTouch(action: Int, x: Int, y: Int): Boolean = input.touch(action, x, y)

    /**
     * Maps a touch from a view of [width]×[height] onto the advertised display and sends it.
     *
     * A down outside the window is dropped; a move or an up is clamped, so a finger that slides off
     * the edge still releases the one the phone believes is down.
     */
    fun sendTouchAt(x: Float, y: Float, width: Int, height: Int, action: Int): Boolean {
        val mapped = input.map(x, y, width, height, action != 0)
        if (mapped == null) {
            onLog("touch: dropped, (${x.toInt()},${y.toInt()}) is outside the ${width}x$height window")
            return false
        }
        return sendTouches(action, listOf(HiCarTouchPoint(0, mapped[0], mapped[1])))
    }

    /**
     * Sends a snapshot of every finger on the picture.
     *
     * The log line is the only way to tell a wrong mapping from an event the phone refused: on
     * 2026-11-19 the phone answered nothing at all while 60 encrypted events went out, and both
     * causes looked identical from the car. Moves are summarised, presses are always named.
     */
    fun sendTouches(action: Int, points: List<HiCarTouchPoint>): Boolean {
        val sent = input.touches(action, points)
        if (action != HiCarInputEvent.ACTION_MOVE || touchMoves++ % 30 == 0) {
            val name = when (action) {
                HiCarInputEvent.ACTION_DOWN -> "down"
                HiCarInputEvent.ACTION_UP -> "up"
                else -> "move"
            }
            val where = points.sortedBy { it.id }.joinToString(",") { "(${it.x},${it.y})" }
            onLog(
                "touch: $name ${points.size} point(s) $where of " +
                    "${input.displayWidth}x${input.displayHeight} sent=$sent",
            )
        }
        return sent
    }

    private var touchMoves = 0

    /** Maps view pixels onto the picture; null when the point is outside a window of that size. */
    fun mapTouch(x: Float, y: Float, width: Int, height: Int): IntArray? =
        input.map(x, y, width, height, clamp = true)

    /** Ends the gesture the phone believes is in progress; see [HiCarInputRouter.cancelTouch]. */
    fun cancelTouch(): Boolean = input.cancelTouch()

    /** Sends the home or back key (Android key codes 3 and 4). */
    fun sendKey(key: Int): Boolean = input.key(key)

    val touchesReady: Boolean get() = input.ready()

    /** Whether the microphone may be offered at all; checked again when the phone asks for it. */
    fun microphoneAllowed(): Boolean = config.withMicrophone && HiCarMicrophone.permitted(appContext)

    fun start(wireless: Boolean = true) {
        // The channel opens on the HiSight thread and closes with it; the screen posts to its own.
        input.onReadyChanged = { notifyKeysReady() }
        if (!running.compareAndSet(false, true)) {
            onLog("HiCar receiver is already running")
            return
        }
        wirelessWanted = wireless
        wifiWasOn = HiCarWifiRestore.snapshot(appContext)
        // Registered once per run: the filter is protected, so it can only be registered while we
        // are alive, and the cooldown lives with it.
        redial = HiCarAutoReconnect(
            context = appContext,
            onLog = onLog,
            running = { running.get() },
            onSocket = ::adoptOutgoing,
        ).also { it.start() }
        report(if (wireless) HiCarStatus.PreparingNetwork else HiCarStatus.WaitingForPhone)
        onLog(
            if (wireless) "receiver: wireless on; the cable is watched as well and wins when it carries"
            else "receiver: wireless off for this run; only the cable can connect",
        )
        if (HiCarWiredSettings.enabled(appContext)) {
            // The wired path drives the cable itself (NCM plus a TUN), so the diagnostic probe would
            // only fight it for the same device.
            wired = HiCarWiredLink(
                context = appContext,
                onLog = onLog,
                model = config.model,
                bluetoothAddress = config.bluetoothAddress.ifEmpty { HiCarWiredLink.defaultBluetoothAddress() },
                // Same value the handshake presents: the block's 32-byte device id must be this one.
                localIdentity = config.localIdentity,
                controlPort = config.controlPort,
                onLinkUp = ::onCableUp,
                onLinkDown = ::onCableDown,
                requestVpnConsent = onVpnConsent,
            ).also { it.start() }
        } else {
            usb = HiCarUsbLink(
                context = appContext,
                onLog = onLog,
                model = config.model,
            ) { name, address ->
                onLog("USB: the wired link is $name at $address; a phone on this cable is reachable there")
            }.also { it.start() }
        }
        setup.execute {
            try {
                startChain()
            } catch (blocked: HiCarAuthException) {
                report(HiCarStatus.Blocked(blocked.blocker))
                onLog("HiCar authentication blocked: ${blocked.blocker.message}")
            } catch (failure: SecurityException) {
                report(HiCarStatus.Failed("permission"))
                // Name them: RFCOMM needs CONNECT while advertising needs ADVERTISE, and a bare
                // "permission" sends the reader to the settings page without knowing which switch.
                val missing = missingPermissions()
                onLog(
                    "HiCar needs the nearby-devices Bluetooth permission" +
                        if (missing.isEmpty()) " (all declared permissions are granted; " +
                            "${failure.message ?: "the platform refused the call"})"
                        else " (missing: ${missing.joinToString()})",
                )
            } catch (failure: Exception) {
                report(HiCarStatus.Failed(failure.javaClass.simpleName))
                onLog("HiCar start failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { redial?.close() }
        redial = null
        runCatching { wiredEndpoints?.close() }
        wiredEndpoints = null
        runCatching { wired?.close() }
        wired = null
        runCatching { usb?.close() }
        usb = null
        arbiter.reset()
        releaseChain(keepVideo = false)
        setup.shutdownNow()
        accept.shutdownNow()
        // After the access point is gone: with the radio still held by the group, asking for the
        // station connection back would be refused for the wrong reason.
        if (HiCarWifiSettings.restoreEnabled(appContext)) {
            HiCarWifiRestore.restore(appContext, wifiWasOn, onLog)
        }
        report(HiCarStatus.Stopped)
        // The count belongs on the stop line as well: a session shorter than one heartbeat ends
        // without ever printing "waiting for the phone", and then an exported log cannot tell a
        // phone that never dialled from one that connected and hung up.
        onLog("HiCar receiver stopped; connections=$connections")
    }

    /**
     * Lets go of everything one round owns, without deciding whether another round follows.
     *
     * [keepVideo] is what separates "the driver stopped" from "the phone went away": the reference
     * receiver keeps its decoder and window across a reconnect, so the next attempt can put a picture
     * on screen without the activity having to recreate its view. When the decoder cannot be
     * restarted it is dropped, because a sink that will never decode is worse than none: the display
     * service would be announced and then stay black.
     */
    private fun releaseChain(keepVideo: Boolean) {
        // Anything captured with the old generation stops at its next check.
        generation++
        input.cancel()
        hisight?.close()
        hisight = null
        mediaBusy.set(false)
        dmsdp?.close()
        dmsdp = null
        // Belt and braces: the media object closes this too, but a round that never built one (a
        // control socket refused after the speaker was opened) must not leave a 1 Hz tick behind.
        runCatching { speedMonitor?.close() }
        speedMonitor = null
        // The key outlives the RFCOMM channel by design; it stops existing with the round.
        liveKey?.fill(0)
        liveKey = null
        advertiser?.close()
        advertiser = null
        probe?.close()
        probe = null
        services?.close()
        services = null
        for (closeable in sockets) closeQuietly(closeable)
        sockets.clear()
        val self = Thread.currentThread()
        for (worker in workers) if (worker !== self) worker.interrupt()
        workers.clear()
        if (keepVideo) {
            val sink = video
            if (sink != null && !sink.restart()) {
                sink.close()
                video = null
            }
        } else {
            video?.close()
            video = null
        }
        audioReady = false
        microphoneReady = false
        keyInputSelected = false
        notifyKeysReady()
        runCatching { hotspot?.close() }
        hotspot = null
        liveNetwork?.authenticated = false
        liveNetwork = null
        liveOffer = null
        networkStarted = false
        // Wakes a negotiation thread that is waiting for an access point this round will not open.
        synchronized(networkLock) { networkLock.notifyAll() }
        runCatching { setup.shutdownNow() }
        runCatching { accept.shutdownNow() }
    }

    /**
     * Takes a socket this side dialled, and serves it like an incoming one.
     *
     * The reverse direction (car dials the phone's 0xFE35) is the only answer to a phone that
     * decides the car is already known and never dials. From here on the socket is
     * indistinguishable from an accepted one: same negotiator, same authenticator, same log.
     */
    internal fun adoptOutgoing(socket: android.bluetooth.BluetoothSocket): Boolean {
        if (!running.get()) return false
        val round = generation
        sockets.add(socket)
        connections.incrementAndGet()
        return try {
            accept.execute { serve(socket, control = true, round) }
            true
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            sockets.remove(socket)
            closeQuietly(socket)
            false
        }
    }

    /**
     * Ends this round because the phone left, then waits for it again.
     *
     * This is the difference between one attempt per "Start" press and a receiver that behaves like
     * the car's own: the phone drops both RFCOMM channels as soon as it decides to retry, and without
     * this the advertisement and listeners were gone by then, so every retry needed a human. The code
     * is not touched, the stored trust is not touched, and the delay keeps a phone that is bouncing
     * between attempts from turning this into a restart loop.
     */
    private fun scheduleReconnect(reason: String) {
        if (!running.get()) return
        if (arbiter.isWired()) {
            // The picture and the sound are on the cable: tearing the round down here would close
            // the RFCOMM records the phone is not using and wipe the session key the cable's DMSDP
            // listeners check, for a failure that happened on a path this session does not use.
            onLog("HiCar: $reason; the cable is carrying this session, so the round stays as it is")
            return
        }
        synchronized(reconnectLock) {
            if (reconnectPending) return
            reconnectPending = true
        }
        onLog(
            "HiCar: $reason; releasing this round and waiting for the phone again in " +
                "${RECONNECT_DELAY_MILLIS / 1000}s (the code and the pairing are unchanged)",
        )
        Thread({
            try {
                Thread.sleep(RECONNECT_DELAY_MILLIS)
                restartRound()
                if (running.get()) onLog("HiCar: listening again; the phone may reconnect on its own")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(reconnectLock) { reconnectPending = false }
            }
        }, "hicar-reconnect").apply { isDaemon = true }.start()
    }

    /**
     * Releases this round and starts a fresh one with the current wireless flag.
     *
     * The callers are the phone leaving, a cable appearing or going, and the driver turning the
     * wireless half on or off. A restart rather than an in-place edit because the advertisement, the
     * listeners and the generation counter are one unit: adding an advertiser to a round that was
     * built without one is where stale accept loops come from.
     */
    private fun restartRound() {
        if (!running.get()) return
        synchronized(restartLock) {
            if (restarting) {
                restartAgain = true
                return
            }
            restarting = true
        }
        try {
            releaseChain(keepVideo = wirelessWanted)
            if (!running.get()) return
            setup = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hicar-setup") }
            accept = Executors.newCachedThreadPool { runnable -> Thread(runnable, "hicar-accept") }
            report(HiCarStatus.Advertising)
            startChain()
        } catch (failure: Exception) {
            report(HiCarStatus.Failed(failure.javaClass.simpleName))
            onLog("HiCar restart failed: ${failure.javaClass.simpleName}")
        } finally {
            val again = synchronized(restartLock) {
                restarting = false
                restartAgain.also { restartAgain = false }
            }
            if (again && running.get()) {
                Thread({ restartRound() }, "hicar-restart-again").apply { isDaemon = true }.start()
            }
        }
    }

    /**
     * Turns the wireless half on or off, leaving the cable alone.
     *
     * The screen's start/stop button is this: "停止" means the Wi-Fi path stops being offered, not
     * that a projection over the cable ends. While the cable is carrying the session the flag is
     * only recorded — there is no round to rebuild, and rebuilding it would interrupt the picture.
     */
    fun setWireless(wanted: Boolean) {
        if (wirelessWanted == wanted) return
        wirelessWanted = wanted
        if (!running.get()) {
            onLog("wireless: ${if (wanted) "on" else "off"} for the next start")
            return
        }
        if (arbiter.isWired()) {
            onLog(
                "wireless: ${if (wanted) "will be offered when the cable goes" else "parked"}; " +
                    "the cable is carrying this session",
            )
            return
        }
        onLog("wireless: ${if (wanted) "on" else "off"}; the cable watcher is unaffected")
        // Off the caller's thread: releasing a round closes sockets and restarts the decoder, and
        // the caller here is the driver pressing a button.
        Thread({ restartRound() }, "hicar-wireless").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ the cable takes over

    /**
     * The cable is carrying packets: it wins.
     *
     * What gives way is the wireless *data* path — the access point, its DMSDP listeners, HiSight and
     * the audio socket. The Bluetooth round stays: the phone authenticates over RFCOMM before it
     * dials, whatever the network underneath turns out to be.
     */
    private fun onCableUp(address: String) {
        if (!running.get()) return
        onLog("wired: the cable has priority at $address")
        report(HiCarStatus.Wired(address))
        for (action in arbiter.wiredUp(address)) {
            when (action) {
                is HiCarTransportAction.DropWireless -> dropWirelessTransport(action.reason)
                is HiCarTransportAction.StartWired -> startWiredTransport(action.address)
                HiCarTransportAction.StopWired, HiCarTransportAction.ResumeWireless -> Unit
            }
        }
    }

    /** The cable is gone: close what was bound to it, and let the wireless path try again. */
    private fun onCableDown(reason: String) {
        if (arbiter.isWired()) onLog("wired: the link went down ($reason)")
        val actions = arbiter.wiredDown()
        if (actions.isEmpty()) return
        for (action in actions) {
            when (action) {
                HiCarTransportAction.StopWired -> {
                    runCatching { wiredEndpoints?.close() }
                    wiredEndpoints = null
                }
                HiCarTransportAction.ResumeWireless -> {
                    if (!running.get()) return
                    if (wirelessWanted) {
                        onLog("wired: the cable is gone; wireless takes over on the next round")
                        Thread({ restartRound() }, "hicar-cable-down").apply { isDaemon = true }.start()
                    } else {
                        onLog("wired: the cable is gone and wireless is off; nothing will connect")
                        report(HiCarStatus.WaitingForPhone)
                    }
                }
                is HiCarTransportAction.DropWireless, is HiCarTransportAction.StartWired -> Unit
            }
        }
    }

    /**
     * Closes everything that exists only because there is an access point.
     *
     * The session key is deliberately kept: the phone is about to dial the cable, and its DMSDP
     * listeners check the same key. The RFCOMM records and the advertisement are kept too — they are
     * how the next authentication arrives.
     */
    private fun dropWirelessTransport(reason: String) {
        val hadSomething = hisight != null || dmsdp != null || hotspot != null
        hisight?.close()
        hisight = null
        mediaBusy.set(false)
        dmsdp?.close()
        dmsdp = null
        runCatching { hotspot?.close() }
        hotspot = null
        liveOffer = null
        // The negotiator belongs to the access point; leaving it set would let a Wi-Fi frame be
        // answered with credentials for a group that is already gone.
        liveNetwork = null
        networkStarted = false
        synchronized(networkLock) { networkLock.notifyAll() }
        if (hadSomething) onLog("wireless: gave way ($reason)")
    }

    private fun startChain() {
        // Everything this round starts (accept loops, heartbeat, connections) is tagged with it, so a
        // loop from a previous round can tell that it is stale instead of competing for the socket.
        val round = ++generation
        if (!hasPermissions()) {
            report(HiCarStatus.Failed("permission"))
            val missing = missingPermissions()
            onLog(
                "HiCar cannot use Bluetooth on this build: missing ${missing.joinToString().ifEmpty { "(nothing; the stack refused)" }} " +
                    "— ${HiCarPermissions.explain(android.os.Build.VERSION.SDK_INT)}",
            )
            return
        }
        val adapter = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            report(HiCarStatus.Failed("bluetooth off"))
            onLog("HiCar needs Bluetooth switched on")
            return
        }
        // The access point is deliberately not opened here: HiChain runs on Bluetooth and needs no
        // Wi-Fi, so a receiver with no phone in the car no longer takes the radio (and the head
        // unit's own network) for nothing. See [requestNetwork].
        if (config.withVideo) prepareVideo()
        val rfcomm = HiCarRfcommServices.open(adapter, onLog)
        if (rfcomm == null) {
            report(HiCarStatus.Failed("rfcomm"))
            return
        }
        services = rfcomm
        onLog("HiCar records ready: business channel ${rfcomm.businessChannel}, control on 0xFE35")

        if (!wirelessWanted) {
            // A run that exists because a cable was plugged in still needs the Bluetooth records —
            // the phone authenticates over RFCOMM before it dials — but it must not put an
            // advertisement on the air or pretend a Wi-Fi session is coming.
            report(HiCarStatus.WaitingForPhone)
            onLog("wired: advertising is off for this run; the records are ready for the phone's dial")
            startAcceptLoops(rfcomm, round)
            return
        }
        val beacon = HiCarBleAdvertiser(appContext, onLog)
        advertiser = beacon
        report(HiCarStatus.Advertising)
        val serviceData = HiCarBeacon.serviceData(config.model, nextSequence())
        if (!beacon.start(serviceData)) {
            report(HiCarStatus.Failed("advertising"))
            return
        }
        // Listen to ourselves once: it is the only way to tell "on the air but ignored" from
        // "never transmitted", which look identical from the phone's silence.
        probe = HiCarAdvertisementProbe(appContext, onLog).also { it.start(serviceData) }
        report(HiCarStatus.WaitingForPhone)
        startAcceptLoops(rfcomm, round)
    }

    /**
     * Opens a 5 GHz access point, trying Wi-Fi Direct before LocalOnlyHotspot.
     *
     * HiCar only accepts the 5 GHz channels in its capability bitmap, and up to Android 12 the public
     * `startLocalOnlyHotspot` gives the caller no way to ask for a band: a HUAWEI MRX-AL09 tablet
     * reserved a 2.4 GHz group, which no HiCar phone will join. Wi-Fi Direct can name the frequency
     * (`setGroupOperatingFrequency`), which is also how the reference receiver does it on API < 33.
     * LocalOnlyHotspot stays as a fallback for systems that pick 5 GHz on their own, and every
     * attempt records why it was not used.
     */
    private fun openAccessPoint(): WirelessHotspotInfo? {
        val outcome = HiCarAccessPoints.open(
            backends = listOf(
                HiCarAccessPoints.Backend("Wi-Fi Direct") {
                    WifiP2pGroupManager(appContext, onLog, WifiP2pChannels.AUTO_5_GHZ)
                },
                HiCarAccessPoints.Backend("local-only hotspot") {
                    LocalOnlyHotspotManager(appContext, onLog)
                },
            ),
            timeoutMillis = config.networkTimeoutMillis,
            accepted = { frequency -> frequency != null && HiCarNetworkOffer.frequencyMask(frequency) != 0 },
            log = onLog,
        )
        val choice = outcome.choice
        if (choice == null) {
            report(HiCarStatus.Failed(if (outcome.bandRefused) "hotspot band" else "hotspot"))
            onLog("HiCar could not open a 5 GHz access point: " + outcome.reasons.joinToString("; "))
            return null
        }
        hotspot = choice.manager
        onLog("HiCar access point from ${choice.backend.name} at ${choice.info.frequencyMHz} MHz")
        return choice.info
    }

    private fun prepareNetwork(): HiCarNetworkOffer? {
        val info: WirelessHotspotInfo = openAccessPoint() ?: return null
        val frequency = requireNotNull(info.frequencyMHz)
        val owner = info.hostAddress?.hostAddress
        if (owner == null || !owner.contains('.')) {
            report(HiCarStatus.Failed("hotspot address"))
            onLog("HiCar access point has no IPv4 address")
            return null
        }
        val client = clientAddressFor(owner)
        if (client == null) {
            report(HiCarStatus.Failed("hotspot address"))
            onLog("HiCar access point address has no free peer address")
            return null
        }
        // The phone joins by BSSID, so a placeholder is worse than stopping: it produces a
        // phone-side "network not found" with no clue on this side.
        val attempt = HiCarAccessPointBssid.inspect(info)
        val bssid = attempt.resolution
        if (bssid == null) {
            report(HiCarStatus.Failed("hotspot bssid"))
            // Name every source that was tried: a bare "cannot read" costs another device run.
            onLog(
                "HiCar cannot read this group's BSSID (interface=${info.interfaceName ?: "none"}; " +
                    attempt.reasons.joinToString("; ") + "); refusing to send a placeholder",
            )
            runCatching { hotspot?.close() }
            return null
        }
        onLog("HiCar access point BSSID ${bssid.bssid} from ${bssid.source.label}")
        if (attempt.reasons.isNotEmpty()) {
            onLog("HiCar BSSID other sources: ${attempt.reasons.joinToString("; ")}")
        }
        val networkOffer = try {
            HiCarNetworkOffer(
                ssid = info.ssid,
                bssid = bssid.bssid,
                passphrase = info.passphrase,
                ownerAddress = owner,
                clientAddress = client,
                frequencyMhz = frequency,
                controlPort = config.controlPort,
            )
        } catch (invalid: IllegalArgumentException) {
            report(HiCarStatus.Failed("hotspot offer"))
            onLog("HiCar access point rejected: ${invalid.message}")
            return null
        }
        report(HiCarStatus.NetworkReady(info.ssid, owner, info.backend.label))
        onLog("HiCar access point ready on ${info.bandLabel}, control port ${networkOffer.controlPort}")
        return networkOffer
    }

    /**
     * Opens the access point, once, because the phone finished HiChain.
     *
     * Runs on its own thread: `openAccessPoint` blocks for up to the network timeout, and the
     * Bluetooth thread that called this has frames to read. The negotiation that is already arriving
     * waits in [awaitNetwork].
     */
    private fun requestNetwork() {
        if (arbiter.isWired()) {
            // The cable is the transport for this session. Opening a group as well would hold the
            // radio for a path the phone is not using, and the phone must not be handed credentials
            // for a network that belongs to a session that already has a working one.
            onLog("HiCar: the cable is carrying this session; the access point stays closed")
            return
        }
        if (!wirelessWanted) {
            onLog("HiCar: wireless is off for this run; not opening an access point")
            return
        }
        synchronized(networkLock) {
            if (networkStarted) return
            networkStarted = true
        }
        Thread({
            try {
                report(HiCarStatus.PreparingNetwork)
                val offer = prepareNetwork()
                if (offer == null) {
                    // Reported by prepareNetwork; the phone will give up on its own, and the round
                    // ends the way it does for any other failure.
                    onLog("HiCar: no access point this round, so the Wi-Fi exchange cannot be answered")
                    return@Thread
                }
                liveOffer = offer
                // Already authenticated: the group only exists because HiChain finished, which is
                // what used to be checked before the passphrase was handed over.
                liveNetwork = HiCarNetworkNegotiator(offer, onLog = onLog).also { it.authenticated = true }
                startDmsdpEndpoints(offer)
                onLog("HiCar: access point is up; the Wi-Fi negotiation can be answered now")
            } catch (failure: Exception) {
                onLog("HiCar: access point failed after authentication (${failure.javaClass.simpleName})")
            } finally {
                synchronized(networkLock) { networkLock.notifyAll() }
            }
        }, "hicar-network").apply { isDaemon = true }.start()
    }

    /**
     * The negotiator for a Wi-Fi frame, waiting for the access point if it is not up yet.
     *
     * Null means the group never came up within the same timeout the rest of the chain uses; the
     * caller logs it rather than answering a negotiation with credentials for a network that does
     * not exist.
     */
    private fun awaitNetwork(): HiCarNetworkNegotiator? {
        val deadline = System.currentTimeMillis() + config.networkTimeoutMillis
        synchronized(networkLock) {
            while (liveNetwork == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                runCatching { networkLock.wait(left) }
            }
            return liveNetwork
        }
    }

    /**
     * Binds the three TCP endpoints as soon as the access point is up.
     *
     * They listen from the start even though nothing is served before authentication: the phone
     * dials them the moment it joins, and a refused connect on a closed port is indistinguishable
     * from a port that was never ours. Binding early means the log can say which of the two happened.
     */
    private fun startDmsdpEndpoints(offer: HiCarNetworkOffer) {
        val network = liveNetwork ?: return
        val endpoints = HiCarDmsdpEndpoints(
            offer = offer,
            summary = config.localIdentity.toByteArray(Charsets.US_ASCII),
            network = network,
            business = deviceBusiness(offer),
            sessionKey = { liveKey },
            active = { running.get() },
            onLog = onLog,
            mediaFactory = { key -> buildMedia(offer, key) },
        )
        dmsdp = endpoints
        endpoints.start()
    }

    /**
     * The device/service description both paths answer with.
     *
     * The addresses are the only part that differs between Wi-Fi and the cable, so the services are
     * built from whichever offer is current rather than duplicated per transport.
     */
    private fun deviceBusiness(offer: HiCarNetworkOffer): HiCarDeviceBusiness = HiCarDeviceBusiness(
        deviceId = config.localIdentity,
        model = config.model,
        ownerAddress = offer.ownerAddress,
        clientAddress = offer.clientAddress,
        onLog = onLog,
        // Each service is announced only when it can actually be served: a display that never
        // produces a frame reads as "this car is broken", which is worse than a missing feature.
        displayReady = { video?.ready == true },
        speakerReady = { audioReady },
        microphoneReady = { microphoneReady },
        onMediaEndpoint = { port -> startHiSight(port) },
        onNavFocus = { active -> routeNavigation(active) },
    )

    /**
     * Binds the same three ports on the cable's address.
     *
     * The phone reaches this side at the address the car-info block named, which is the tunnel's own
     * address — not the access point's. Peer checking is loosened to "any host on that /24" because
     * the phone chooses its own address on this link and only ever announces it in ARP; the
     * authentication gate ([sessionKey]) is unchanged, so an unauthenticated peer is still refused.
     */
    private fun startWiredTransport(address: String) {
        runCatching { wiredEndpoints?.close() }
        wiredEndpoints = null
        val negotiator = HiCarNetworkNegotiator(
            HiCarNetworkOffer.cable(address, config.controlPort, wired?.phoneAddress),
            onLog = onLog,
        ).also { it.authenticated = true }
        val offer = HiCarNetworkOffer.cable(address, config.controlPort, wired?.phoneAddress)
        liveOffer = offer
        liveNetwork = negotiator
        val endpoints = HiCarDmsdpEndpoints(
            offer = offer,
            summary = config.localIdentity.toByteArray(Charsets.US_ASCII),
            network = negotiator,
            business = deviceBusiness(offer),
            sessionKey = { liveKey },
            active = { running.get() },
            onLog = onLog,
            mediaFactory = { key ->
                // The phone's address exists only after its first ARP, which is usually before this
                // control connection — but not always. Re-reading it here keeps the audio socket and
                // the HiSight dial pointed at where the phone really is.
                val current = HiCarNetworkOffer.cable(address, config.controlPort, wired?.phoneAddress)
                liveOffer = current
                buildMedia(current, key)
            },
            bindAddress = address,
            anyPeerOnSubnet = true,
            servesWifiFollowUp = false,
        )
        wiredEndpoints = endpoints
        endpoints.start()
        onLog(
            "wired: DMSDP bound to $address; phone address " +
                (wired?.phoneAddress ?: "not announced yet"),
        )
    }

    /** Creates the decoder now so the display service can be announced before the phone asks. */
    private fun prepareVideo() {
        val sink = videoSink()
        if (sink.prepare()) {
            onLog("HiSight: H.264 decoder ready; the display service can be announced")
        } else {
            onLog("HiSight: no usable H.264 decoder on this device; the display service stays unannounced")
        }
    }

    /**
     * Everything the control connection needs that talks to the phone's media streams.
     *
     * Built when the control socket is accepted, because the speaker's UDP port has to exist before
     * the phone can be told where to send audio. Each piece is optional: no AAC decoder means no
     * speaker, no microphone permission means no microphone, and in both cases the control channel
     * itself continues.
     */
    private fun buildMedia(offer: HiCarNetworkOffer, key: ByteArray): HiCarDmsdpEndpoints.Media? {
        val handlers = ArrayList<HiCarControlSession.TextHandler>()
        // The stored choice wins; on a fresh install the unit's own navigation channel (14 on this
        // head unit) is used for guidance, which is what the previous build did for every sound.
        val audioChannels = HiCarAudioSettingsStore.load(appContext).let { stored ->
            if (stored.navigationStream != HiCarAudioSettings.AUTO || navigationStreamType == 0) {
                stored
            } else {
                stored.copy(
                    navigationStream = HiCarAudioSettings.normalize(navigationStreamType),
                )
            }
        }
        audioSettings = audioChannels
        val sink = HiCarAudioSink(appContext, onLog, audioChannels)
        var receiver: HiCarAudioReceiver? = null
        if (sink.ready) {
            // The session that receives background buttons is only worth having while this holds.
            sink.onFocusChanged = { held -> runCatching { onMediaFocus(held) } }
            sink.start()
            try {
                val created = HiCarAudioReceiver(
                    owner = offer.ownerAddress,
                    peer = offer.clientAddress,
                    sessionKey = key,
                    sink = sink,
                    active = { running.get() },
                    onLog = onLog,
                )
                receiver = created
                handlers.add(HiCarSpeakerControl(created, onLog))
                speaker = sink
                audioReady = true
                // The gain is applied inside this sink, so the monitor lives exactly as long as the
                // audio path it drives. It never changes the head unit's or the phone's volume.
                refreshSpeedVolume()
            } catch (failure: IOException) {
                sink.close()
                onLog("audio: UDP receiver could not open (${failure.javaClass.simpleName}); no speaker this session")
            }
        } else {
            sink.close()
        }
        var microphone: HiCarMicrophone? = null
        if (microphoneAllowed()) {
            try {
                val created = HiCarMicrophone(
                    context = appContext,
                    owner = offer.ownerAddress,
                    peerAddress = offer.clientAddress,
                    sessionKey = key,
                    active = { running.get() },
                    onLog = onLog,
                )
                microphone = created
                handlers.add(HiCarMicrophoneControl(created))
                microphoneReady = true
            } catch (failure: IOException) {
                onLog("microphone: uplink could not open (${failure.javaClass.simpleName}); the picture continues")
            }
        }
        if (handlers.isEmpty()) return null
        val audio = receiver
        val mic = microphone
        return object : HiCarDmsdpEndpoints.Media, HiCarDmsdpEndpoints.Clocked {
            override fun textHandlers(): List<HiCarControlSession.TextHandler> = handlers

            override fun onClock(high: Long, low: Long) {
                mic?.clock(high, low)
            }

            override fun close() {
                audioReady = false
                microphoneReady = false
                if (speaker === sink) speaker = null
                runCatching { speedMonitor?.close() }
                speedMonitor = null
                runCatching { audio?.close() }
                runCatching { mic?.close() }
            }
        }
    }

    /** Applies channel choices to the audio path that is running now (new tracks only). */
    fun applyAudioSettings(next: HiCarAudioSettings) {
        audioSettings = next
        speaker?.updateSettings(next)
    }

    /** Plays a preview tone on the music channel; false when there is no decoder. */
    fun previewMedia(): Boolean = speaker?.previewMedia() == true

    /** Plays a preview tone on the navigation channel; false when there is no decoder. */
    fun previewNavigation(): Boolean = speaker?.previewNavigation() == true

    /**
     * Takes a new sharpness/frame-rate pair for the **next** connection.
     *
     * Nothing on the wire changes now: the phone was already told what to encode. Reporting it here
     * keeps the log honest, because otherwise the settings page would look like it had taken effect.
     */
    fun applyVideoSettings(next: HiCarVideoSettings) {
        val changed = next != videoOffer
        videoOffer = next
        if (changed) onLog("video: next connection offers ${next.quality.stored} at ${next.frameRate.stored}")
    }

    /** Whether a steering-wheel key can be delivered to the phone at this instant. */
    fun mediaKeysReady(): Boolean = running.get() && keyInputSelected && input.ready()

    /**
     * Sends one half of a media key press.
     *
     * The phone is what repeats a held key, so a down stays down until the matching up arrives. The
     * forwarder decides the pairing; this only refuses a key when nothing can carry it.
     */
    fun sendMediaKey(code: Int, down: Boolean): Boolean = input.mediaKey(code, down)

    private fun notifyKeysReady() {
        runCatching { onKeysReady(mediaKeysReady()) }
    }

    /**
     * Tells the cable whether the receiver's window is on screen.
     *
     * The USB permission prompt is a system activity, and this car's Android 10 drops a request made
     * while the caller has nothing visible — no dialog, no callback. The host activity reports
     * `onResume`/`onPause` here so the cable asks at the one moment the prompt can actually appear.
     */
    fun setHostVisible(visible: Boolean) {
        runCatching { wired?.setHostVisible(visible) }
    }

    /**
     * The driver's answer to the VPN prompt the cable raised.
     *
     * A grant carries on with the port that is already open — the phone has switched and its grant
     * is spent, so making it start over would cost another insertion.
     */
    fun onVpnConsentResult(granted: Boolean) {
        onLog("VPN: the driver ${if (granted) "allowed" else "declined"} the tunnel")
        runCatching { wired?.resumeAfterVpnConsent(granted) }
    }

    /**
     * Applies the saved speed-volume settings to the audio path that is running right now.
     *
     * The settings page is reachable while the phone is connected — that is why it exists — so a
     * change must not wait for the next connection. Rebuilding the monitor is deliberate: swapping a
     * curve and a smoother mid-tick is where "why did it jump to full gain" bugs come from, and a new
     * monitor always starts from 0 dB.
     */
    fun refreshSpeedVolume() {
        val sink = speaker
        runCatching { speedMonitor?.close() }
        speedMonitor = null
        if (sink == null) {
            onLog("speed volume: no speaker in this session; the setting is saved for the next one")
            onSpeedStatus(HiCarSpeedStatus())
            return
        }
        val settings = HiCarSpeedVolumeSettings.load(appContext)
        if (!settings.enabled) {
            sink.setSpeedGains(0.0, 0.0, 0.0)
            onSpeedStatus(HiCarSpeedStatus())
            onLog("speed volume: off; music is played at its own level")
            return
        }
        speedMonitor = HiCarSpeedMonitor(appContext, settings, onLog, onGainDb = { db ->
            sink.setSpeedGains(
                mediaDb = db,
                navigationDb = if (settings.navigationGain) db else 0.0,
                callDb = if (settings.callGain) db else 0.0,
            )
        }, onStatus = onSpeedStatus).also { it.start() }
    }

    /**
     * Sends the speaker to the navigation channel while guidance is talking, and back afterwards.
     *
     * The phone mixes guidance and music into one stream, so this is a routing decision made on the
     * only signal the phone gives: navigation focus. While it holds, the car plays the sound on its
     * navigation channel - the same one its own satnav uses - so a prompt is not attenuated with the
     * music it interrupts. Zero puts the track back on the usage-based media route.
     */
    private fun routeNavigation(active: Boolean) {
        val sink = speaker ?: return
        // Guidance is a separate lane when the phone sends it separately, and in that case the duck
        // is already happening; when it is mixed into the music this message is the only warning, and
        // the media lane has to make room.
        sink.setNavigationFocus(active)
    }

    /**
     * Dials the phone's video port and keeps the picture off the control thread.
     *
     * The session key is copied because the RFCOMM side wipes its own copy when that channel closes,
     * which usually happens right after the phone moves to TCP.
     */
    private fun startHiSight(port: Int) {
        val offer = liveOffer ?: return
        val sink = video
        if (sink == null || !sink.ready) {
            onLog("HiSight: no decoder is ready, so the video port $port is left alone")
            return
        }
        if (!mediaBusy.compareAndSet(false, true)) {
            onLog("HiSight: a video session is already running; the second endpoint is ignored")
            return
        }
        val key = liveKey?.copyOf()
        if (key == null) {
            mediaBusy.set(false)
            onLog("HiSight: no session key; refusing to dial the phone's video port")
            return
        }
        val client = HiCarHiSightClient(
            owner = offer.ownerAddress,
            peer = offer.clientAddress,
            sessionKey = key,
            video = sink,
            input = input,
            active = { running.get() },
            onLog = onLog,
            onVideoConnected = {
                report(HiCarStatus.Streaming)
                notifyKeysReady()
            },
            displaySize = displaySize,
            videoSettings = videoOffer,
            refreshHz = refreshHz,
            onKeyInputSelected = { selected ->
                keyInputSelected = selected
                notifyKeysReady()
            },
        )
        key.fill(0)
        hisight = client
        Thread({
            try {
                client.run(port)
            } finally {
                if (hisight === client) hisight = null
                mediaBusy.set(false)
            }
        }, "hicar-hisight").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun startAcceptLoops(rfcomm: HiCarRfcommServices, round: Int) {
        val control = thread("hicar-control") { acceptLoop(rfcomm::acceptControl, control = true, round) }
        val business = thread("hicar-business") { acceptLoop(rfcomm::acceptBusiness, control = false, round) }
        onLog("HiCar listeners started: ${control.name}, ${business.name}")
        startWaitingHeartbeat(round)
    }

    /**
     * Says, once every 30 seconds, that the receiver is up and nobody has connected.
     *
     * Without it an exported log ends at "listeners started" and reads the same whether the phone
     * never tried, tried and failed before RFCOMM, or the receiver had already died. Those need
     * different next steps, so the log has to tell them apart.
     */
    private fun startWaitingHeartbeat(round: Int) {
        thread("hicar-wait") {
            val startedAt = System.nanoTime()
            var hinted = false
            while (running.get() && generation == round) {
                try {
                    Thread.sleep(WAIT_HEARTBEAT_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
                if (!running.get() || generation != round) return@thread
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
                onLog(
                    "HiCar waiting for the phone: ${seconds}s, connections=$connections, " +
                        "advertising=${if (advertiser != null) "on" else "off"}, " +
                        "listeners=${if (services != null) "open" else "closed"}, " +
                        "discoverable=${discoverableState()}, " +
                        // Whether the car ever handed out the access point separates "the phone never
                        // asked" from "it asked, got credentials and still could not join".
                        "credentials=${liveNetwork?.credentialsSent == true}, " +
                        "network=${liveNetwork?.let { if (it.negotiable) "asked" else "silent" }}",
                )
                // A minute with the beacon up and not one dial is not a receiver fault, and saying so
                // here saves a round trip through the log: the advertisement, listeners and access
                // point are all up, so the next thing to look at is the phone.
                if (!hinted && connections.get() == 0 && seconds >= SILENT_PHONE_MILLIS / 1000) {
                    hinted = true
                    onLog(
                        "HiCar: the phone has not dialled at all, which is its side of the link. Check, " +
                            "in this order: the phone is unlocked with its HiCar screen open; the HiCar " +
                            "entry for this car was removed after the last failed attempt; then Start " +
                            "again — the code has not changed, Reset pairing is not needed",
                    )
                }
            }
        }
    }

    /**
     * Whether this device can still be found by inquiry.
     *
     * The system grants discoverability for a fixed, short window (a HUAWEI MRX-AL09 grants 120 s even
     * when 300 is requested), after which the advertisement is still visible over BLE but nothing can
     * reach the car over BR/EDR. Without this in the heartbeat, an expired window looks exactly like a
     * phone that ignored us.
     */
    private fun discoverableState(): String {
        val adapter = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            ?: return "unknown(no adapter)"
        val mode = try {
            adapter.scanMode
        } catch (failure: Exception) {
            // Saying only "unknown" hid which call failed and why; this is a diagnostic line, it has
            // to carry the reason even when the reason is a refused getter.
            return "unknown(${failure.javaClass.simpleName}: ${failure.message ?: "no detail"})"
        }
        return when (mode) {
            android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "yes"
            android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "no (connectable only)"
            android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE -> "no (not connectable)"
            else -> "unknown(scanMode=$mode)"
        }
    }

    private fun acceptLoop(acceptSocket: () -> android.bluetooth.BluetoothSocket, control: Boolean, round: Int) {
        while (running.get() && generation == round) {
            val socket = try {
                acceptSocket()
            } catch (closed: IOException) {
                if (running.get() && generation == round) {
                    // This is the listener itself failing, not one phone connection ending: the
                    // whole round is gone and only a restart brings discovery back. The reference
                    // receiver restarts here too. A release triggered by Stop or by a previous
                    // reconnect has already bumped `generation`, so it never lands here.
                    onLog("HiCar ${if (control) "control" else "business"} listener closed")
                    scheduleReconnect("the ${if (control) "control" else "business"} listener closed")
                }
                return
            } catch (denied: SecurityException) {
                onLog("HiCar listener needs the nearby-devices permission")
                return
            }
            // The listener may have been released while this thread was blocked in accept().
            if (generation != round) {
                closeQuietly(socket)
                return
            }
            sockets.add(socket)
            connections.incrementAndGet()
            try {
                accept.execute { serve(socket, control, round) }
            } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                // The round ended between the accept and the hand-off; the listener is already closed.
                sockets.remove(socket)
                closeQuietly(socket)
                return
            }
        }
    }

    private fun serve(socket: android.bluetooth.BluetoothSocket, control: Boolean, round: Int) {
        // The peer address makes a connection attempt identifiable in a report: a phone that dials is
        // the first hard evidence the discovery stage worked, and which phone it was matters when the
        // car is bonded to more than one.
        val peer = runCatching { socket.remoteDevice?.address }.getOrNull() ?: "unknown"
        onLog("HiCar ${if (control) "control" else "business"} channel connected (peer $peer)")
        if (!control) report(HiCarStatus.Authenticating)
        val negotiator = HiCarChannelNegotiator(
            summary = config.localIdentity.toByteArray(Charsets.US_ASCII),
            businessChannel = { services?.businessChannel ?: 0 },
        )
        val authenticator: HiCarAuthenticator = if (HiChainNative.available && config.pin.length == 6) {
            HiChainAuthenticator(
                storeDirectory = appContext.getDir("hicar-huks", Context.MODE_PRIVATE),
                seed = config.localIdentity,
                localIdentity = config.localIdentity,
                pin = config.pin,
                onLog = { message -> onLog(message) },
            )
        } else {
            // No engine or no code: the exchange still runs so the log shows how far the phone got,
            // and the session then fails with an explicit blocker instead of a fabricated key.
            HiCarUnavailableAuthenticator()
        }
        val identity = HiCarIdentityExchange(
            localIdentity = config.localIdentity,
            deviceName = config.deviceName,
            model = config.model,
            pinConfigured = config.pinConfigured,
            authenticator = authenticator,
            onLog = { message -> onLog(message) },
        )
        var session: HiCarControlSession? = null
        var announced = false
        val decoder = HiCarFrameDecoder()
        try {
            val input: InputStream = socket.inputStream
            val output: OutputStream = socket.outputStream
            val buffer = ByteArray(4096)
            while (running.get() && generation == round) {
                val read = input.read(buffer)
                if (read < 0) break
                if (!control) {
                    val active = session
                    if (active != null) {
                        for (reply in active.feed(buffer, read)) {
                            output.write(reply)
                            output.flush()
                        }
                        continue
                    }
                    // A key does not mean the phone has finished with this channel: at 11:29 it sent
                    // another JSON envelope (oper_type 3, message 3) right after the handshake, and
                    // answering that with an OPTIONS greeting is what killed the connection.
                    //
                    // The switch is decided by the record marker, not by "this chunk is not JSON":
                    // an envelope that spans two reads continues with a byte that is not `{`, and the
                    // run at 11:53 died on exactly such a chunk. Anything that is not a DMSDP record
                    // belongs to the envelope parser, which reports its own errors accurately.
                    if (identity.awaitingRest || !isControlRecord(buffer, read)) {
                        for (reply in identity.feed(buffer, read)) {
                            output.write(reply)
                            output.flush()
                        }
                        val key = authenticator.sessionKey
                        if (key != null && !announced) {
                            announced = true
                            report(HiCarStatus.Authenticated)
                            // The TCP side needs the key after this channel is gone: the phone closes
                            // both RFCOMM sockets once it has somewhere else to go, and the
                            // authenticator wipes its copy with the session.
                            liveKey = key.copyOf()
                            liveNetwork?.authenticated = true
                            // The phone is through the handshake on this access point, so the
                            // channel that carried it is worth remembering; a group that never got
                            // this far is forgotten when the round ends.
                            hotspot?.onLinkConfirmed()
                            // A phone that got this far is the only kind worth dialling later: an
                            // address seen on a failed attempt may be a phone that never completed.
                            HiCarPhoneRegistry.remember(appContext, peer)
                            onLog(
                                "HiChain finished: ${key.size}-byte session key; opening the access " +
                                    "point now, because that is what the phone asks for next",
                            )
                            // The phone's next message is the Wi-Fi negotiation. Opening the group
                            // after the handshake keeps the radio free until a phone is really there.
                            requestNetwork()
                        }
                        continue
                    }
                    val key = authenticator.sessionKey
                        ?: throw HiCarWireException("DMSDP record before authentication")
                    onLog("HiCar: the phone switched from envelopes to DMSDP records on the business channel")
                    session = openControlSession(output, key)
                    session?.let { opened ->
                        for (reply in opened.feed(buffer, read)) {
                            output.write(reply)
                            output.flush()
                        }
                    }
                    continue
                }
                for (frame in decoder.feed(buffer, read)) {
                    val isNetwork = frame.kind == KIND_NETWORK || frame.kind == KIND_NETWORK_COMMAND
                    // The negotiation can beat the access point by a second or two. Waiting here
                    // rather than dropping the frame is the difference between "hotspot lazy start"
                    // and "every connection fails just after it authenticated". On the cable there
                    // is nothing to wait for: no access point is coming, so waiting would only
                    // stall this thread for the full network timeout.
                    val network = when {
                        !isNetwork -> liveNetwork
                        arbiter.isWired() || !wirelessWanted -> {
                            onLog(
                                "HiCar network frame kind=${frame.kind} while the cable carries the " +
                                    "session; there is no access point to hand out",
                            )
                            null
                        }
                        else -> awaitNetwork()
                    }
                    if (isNetwork && network != null && !network.authenticated) {
                        // The phone only runs this exchange after it has a session key. Arriving
                        // earlier means our key is not visible yet, and answering would leak the
                        // access point to a peer that has not authenticated — so it is dropped,
                        // but loudly: a silent drop here looks exactly like a phone that gave up.
                        onLog(
                            "HiCar network frame kind=${frame.kind} arrived before the session key was " +
                                "visible; not answered",
                        )
                    }
                    val reply = when {
                        isNetwork -> network?.respond(frame)
                        else -> negotiator.respond(frame)
                    }
                    if (reply != null) {
                        output.write(reply)
                        output.flush()
                    }
                    // The negotiator answers most steps immediately but queues one command (kind 33
                    // tag 1) when it wants the phone's station address. That queue used to be
                    // flushed only on the TCP network port, so on this channel the phone waited for
                    // a message that was never written and closed both channels thirty seconds
                    // later — see docs/HICAR_MIGRATION.md 7.37.
                    val queued = network?.takePending().orEmpty()
                    for (command in queued) {
                        output.write(command)
                        output.flush()
                    }
                    onLog(
                        "HiCar control frame kind=${frame.kind}, ${frame.payload.size} bytes, " +
                            "reply ${reply?.size ?: 0}, queued ${queued.size}",
                    )
                    if (negotiator.businessAnnounced) report(HiCarStatus.ChannelNegotiated)
                }
            }
        } catch (blocked: HiCarAuthException) {
            report(HiCarStatus.Blocked(blocked.blocker))
            onLog("HiCar authentication blocked: ${blocked.blocker.message}")
        } catch (failure: HiCarWireException) {
            onLog("HiCar protocol stopped: ${failure.message}")
        } catch (failure: IOException) {
            onLog("HiCar channel closed: ${failure.javaClass.simpleName}")
        } finally {
            session?.close()
            authenticator.close()
            closeQuietly(socket)
            sockets.remove(socket)
            // A channel closing is not the round ending.
            //
            // The phone releases both RFCOMM channels as soon as it has authenticated and moved to
            // the access point: at that moment DMSDP and HiSight are still running and the picture
            // is on screen (2026-11-19 09:31: `channel closed` arrived while `decoder output
            // 800x480` was already flowing). Releasing the round here tore down the access point,
            // the TCP listeners and the video socket underneath a working session, so the phone
            // reconnected from scratch and did a fresh BIND — which is why it asked for the six
            // digit code again. The reference receiver only restarts on a DMSDP control socket
            // closing, a HiSight session ending, or a listener failure; RFCOMM going away is
            // expected and is not one of them.
            //
            // The listeners started by this round keep accepting, the advertisement keeps running,
            // and `Stop` still releases everything.
            onLog(
                "HiCar ${if (control) "control" else "business"} channel closed; " +
                    "the round stays up (DMSDP and video are independent of RFCOMM)",
            )
        }
    }

    /**
     * Starts the DMSDP control channel on the socket the phone already authenticated on.
     *
     * Returns null when the engine key cannot be used (a wrong length is a bug, not a peer error),
     * so the caller keeps reading and reports the failure through the normal channel.
     */
    private fun openControlSession(output: OutputStream, key: ByteArray): HiCarControlSession? {
        if (key.size != 16 && key.size != 32) {
            onLog("HiChain returned an unusable key length (${key.size})")
            return null
        }
        val cipher = HiCarRecordCipher(key)
        val events = object : HiCarControlSession.Events {
            override fun onLog(message: String) {
                this@HiCarController.onLog(message)
            }

            override fun onNegotiated() = report(HiCarStatus.ControlChannelReady)

            override fun onClock(high: Long, low: Long) = onLog("HiCar clock sample accepted")

            override fun onApplicationService(module: Long, type: Long, body: ByteArray): List<ByteArray> {
                // The media stage (HiSight) is not implemented yet; a business frame arriving here
                // means the phone got further than this build supports.
                onLog("HiCar application frame module=$module type=$type; media stage not implemented")
                return emptyList()
            }
        }
        val session = HiCarControlSession(cipher, events)
        val greeting = session.initial()
        output.write(greeting)
        output.flush()
        // Recorded so the next failure can be told apart by direction: a rejection that arrives
        // before this line is the phone speaking first, and one after it answers our OPTIONS.
        onLog("DMSDP: sent ${greeting.size}-byte OPTIONS greeting")
        return session
    }

    /**
     * True when the chunk begins with a DMSDP record marker (`0x00` plaintext, `0xAB encrypted`).
     *
     * Deliberately a positive test rather than "not JSON": between the two protocols there is a third
     * case — the continuation of a split envelope — and guessing it into the wrong branch costs a
     * whole pairing run.
     */
    private fun isControlRecord(data: ByteArray, count: Int): Boolean {
        for (index in 0 until count) {
            val byte = data[index].toInt() and 0xFF
            if (byte == ' '.code || byte == '\t'.code || byte == '\r'.code || byte == '\n'.code) continue
            return byte == HiCarCodes.RECORD_PLAINTEXT || byte == HiCarCodes.RECORD_ENCRYPTED
        }
        return false
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            workers.add(this)
            start()
        }

    /** Which declared permission is not granted; empty means the refusal came from somewhere else. */
    private fun missingPermissions(): List<String> = HiCarPermissions.missing(appContext)

    private fun hasPermissions(): Boolean =
        HiCarPermissions.canConnect(appContext) && HiCarPermissions.canAdvertise(appContext)

    private fun nextSequence(): Int = HiCarBeacon.nextSequence(
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(KEY_SEQUENCE, 0),
    ).also { next ->
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putInt(KEY_SEQUENCE, next).apply()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Already closed by the peer.
        }
    }

    companion object {
        // The three touch actions, mirrored here so the screen does not have to reach into the
        // encoder's internal event type to name them.
        const val TOUCH_DOWN = HiCarInputEvent.ACTION_DOWN
        const val TOUCH_UP = HiCarInputEvent.ACTION_UP
        const val TOUCH_MOVE = HiCarInputEvent.ACTION_MOVE

        /** The phone decodes at most ten contacts per packet. */
        const val MAX_TOUCH_POINTS = HiCarInputEvent.MAX_POINTS

        const val KIND_NETWORK = 4
        const val KIND_NETWORK_COMMAND = 33
        const val PREFERENCES = "hicar-receiver"
        const val KEY_SEQUENCE = "discovery_sequence"

        /** Where this head unit's navigation channel lives; `setprop debug.hicar.navstream` overrides. */
        const val PROP_NAV_STREAM = "debug.hicar.navstream"
        const val DEFAULT_NAV_STREAM = 14

        /** Roles and user types from the engine's own header; the car is always the accessory. */
        const val ROLE_CENTRE = 1
        const val ROLE_ACCESSORY = 2
        const val USER_TYPE_ACCESSORY = 0
        const val USER_TYPE_CONTROLLER = 1

        /**
         * Where the head unit keeps its own HiCar app.
         *
         * Read only for its uncompressed native libraries, into this app's private directory; the
         * vendor app itself is never started and nothing of it is redistributed.
         */
        const val VENDOR_APK = "/system/app/HiCar/HiCar.apk"

        /** The loopback's identities: distinct from anything a phone would present. */
        const val CAR_ID = "selftest-car"
        const val PHONE_ID = "selftest-phone"

        /**
         * A stand-in with the shape of a real phone's id: HiCar reports a 64-character deviceId and
         * signs over those very characters, so the length is what a mismatch would expose. Synthetic
         * on purpose - binding a real phone's id here would put a trust entry in this car's store.
         */
        const val PHONE_SHAPED_ID = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        const val KEY_BYTES = 32

        /** Generous: a bind is a handful of messages, so exceeding this means it is looping. */
        const val MAX_EXCHANGES = 24

        val PIN_DIGITS = Regex("[0-9]{6}")

        /** Long enough to stay out of the way, short enough that a 2-minute test shows progress. */
        const val WAIT_HEARTBEAT_MILLIS = 30_000L

        /** Two heartbeats with no dial at all: the silence is on the phone's side. */
        const val SILENT_PHONE_MILLIS = 60_000L

        /**
         * How long to wait before opening a fresh round.
         *
         * The phone closes its channels and immediately dials again when it is retrying, so this only
         * has to be longer than the teardown, and short enough that a retry finds the advertisement
         * already up. The reference receiver uses the same figure.
         */
        const val RECONNECT_DELAY_MILLIS = 1_500L

        /** The phone usually takes the next address after the access point itself. */
        fun clientAddressFor(owner: String): String? {
            val octets = owner.split(".")
            if (octets.size != 4) return null
            val last = octets[3].toIntOrNull() ?: return null
            val candidate = when {
                last in 1..253 -> last + 1
                last == 254 -> last - 1
                else -> return null
            }
            return "${octets[0]}.${octets[1]}.${octets[2]}.$candidate"
        }
    }
}

/**
 * A TCP listener on one of the car's own addresses.
 *
 * The phone dials `controlPort`, `+1` (data) and `+2` on whichever address it was told: the access
 * point's for a Wi-Fi session, the tunnel's for a cable session. Both are addresses of this device,
 * so the bind is the same call with a different host.
 */
internal fun openListener(address: String, port: Int): ServerSocket =
    ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, port), 4)
    }
