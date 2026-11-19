package com.shilapi.xcertplay.hicar

import android.Manifest
import android.content.Context
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
) : Closeable {
    private val appContext = context.applicationContext
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

    /** The H.264 sink, created on first use so the phone can be offered the display service. */
    private var video: HiCarVideoSink? = null

    /** Touches and keys for the phone; only the current HiSight session ever attaches to it. */
    private val input = HiCarInputRouter()

    /** At most one video session at a time, however many control connections arrive. */
    private val mediaBusy = AtomicBoolean(false)
    private var hisight: HiCarHiSightClient? = null

    @Volatile private var audioReady = false
    @Volatile private var microphoneReady = false
    @Volatile private var microphoneWanted = false

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
        val report = runCatching {
            HiChainNative.probeVendorAuthenticate(
                vendor?.absolutePath,
                CAR_ID.toByteArray(),
                PHONE_ID.toByteArray(),
                config.pin,
            )
        }.getOrNull()
        if (report == null) {
            onLog("HiChain second connection: the harness returned no report")
            return
        }
        report.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            onLog("HiChain second connection:$line")
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

    /** The sink the screen attaches its `TextureView` to; safe to call before [start]. */
    fun videoSink(): HiCarVideoSink = synchronized(this) {
        video ?: HiCarVideoSink(onLog).also { video = it }
    }

    /** Sends one touch in the 800×480 space the display service advertised. */
    fun sendTouch(action: Int, x: Int, y: Int): Boolean = input.touch(action, x, y)

    /**
     * Maps a touch from a view of [width]×[height] onto the advertised display and sends it.
     *
     * A down outside the window is dropped; a move or an up is clamped, so a finger that slides off
     * the edge still releases the one the phone believes is down.
     */
    fun sendTouchAt(x: Float, y: Float, width: Int, height: Int, action: Int): Boolean {
        val mapped = HiCarInputRouter.map(x, y, width, height, action != 0)
        if (mapped == null) {
            onLog("touch: dropped, (${x.toInt()},${y.toInt()}) is outside the ${width}x$height window")
            return false
        }
        val sent = input.touch(action, mapped[0], mapped[1])
        // The phone answered nothing at all on 2026-11-19 11:5x while 60 encrypted events went out,
        // so the numbers have to be visible: a wrong mapping and a refused event look identical
        // from the outside. Moves are summarised, presses are always named.
        if (action != 2 || touchMoves++ % 30 == 0) {
            val name = when (action) {
                0 -> "down"
                1 -> "up"
                else -> "move"
            }
            onLog(
                "touch: $name view=${width}x$height at (${x.toInt()},${y.toInt()}) " +
                    "-> (${mapped[0]},${mapped[1]}) of ${HiCarInputRouter.DISPLAY_WIDTH}x" +
                    "${HiCarInputRouter.DISPLAY_HEIGHT} sent=$sent",
            )
        }
        return sent
    }

    private var touchMoves = 0

    /** Sends the home or back key (Android key codes 3 and 4). */
    fun sendKey(key: Int): Boolean = input.key(key)

    val touchesReady: Boolean get() = input.ready()

    /** Whether the microphone may be offered at all; checked again when the phone asks for it. */
    fun microphoneAllowed(): Boolean = config.withMicrophone && HiCarMicrophone.permitted(appContext)

    fun start() {        if (!running.compareAndSet(false, true)) {
            onLog("HiCar receiver is already running")
            return
        }
        report(HiCarStatus.PreparingNetwork)
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
        releaseChain(keepVideo = false)
        setup.shutdownNow()
        accept.shutdownNow()
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
        runCatching { hotspot?.close() }
        hotspot = null
        liveNetwork?.authenticated = false
        liveNetwork = null
        liveOffer = null
        runCatching { setup.shutdownNow() }
        runCatching { accept.shutdownNow() }
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
                if (!running.get()) return@Thread
                releaseChain(keepVideo = true)
                if (!running.get()) return@Thread
                setup = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hicar-setup") }
                accept = Executors.newCachedThreadPool { runnable -> Thread(runnable, "hicar-accept") }
                report(HiCarStatus.Advertising)
                startChain()
                if (running.get()) onLog("HiCar: listening again; the phone may reconnect on its own")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                report(HiCarStatus.Failed(failure.javaClass.simpleName))
                onLog("HiCar restart failed: ${failure.javaClass.simpleName}")
            } finally {
                synchronized(reconnectLock) { reconnectPending = false }
            }
        }, "hicar-reconnect").apply { isDaemon = true }.start()
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
        val networkOffer = prepareNetwork() ?: return
        liveOffer = networkOffer
        liveNetwork = HiCarNetworkNegotiator(networkOffer, onLog = onLog).also { it.authenticated = false }
        startDmsdpEndpoints(networkOffer)
        if (config.withVideo) prepareVideo()
        val rfcomm = HiCarRfcommServices.open(adapter, onLog)
        if (rfcomm == null) {
            report(HiCarStatus.Failed("rfcomm"))
            return
        }
        services = rfcomm
        onLog("HiCar records ready: business channel ${rfcomm.businessChannel}, control on 0xFE35")

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
     * Binds the three TCP endpoints as soon as the access point is up.
     *
     * They listen from the start even though nothing is served before authentication: the phone
     * dials them the moment it joins, and a refused connect on a closed port is indistinguishable
     * from a port that was never ours. Binding early means the log can say which of the two happened.
     */
    private fun startDmsdpEndpoints(offer: HiCarNetworkOffer) {
        val network = liveNetwork ?: return
        val business = HiCarDeviceBusiness(
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
        )
        val endpoints = HiCarDmsdpEndpoints(
            offer = offer,
            summary = config.localIdentity.toByteArray(Charsets.US_ASCII),
            network = network,
            business = business,
            sessionKey = { liveKey },
            active = { running.get() },
            onLog = onLog,
            mediaFactory = { key -> buildMedia(offer, key) },
        )
        dmsdp = endpoints
        endpoints.start()
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
        val sink = HiCarAudioSink(appContext, onLog)
        var receiver: HiCarAudioReceiver? = null
        if (sink.ready) {
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
                audioReady = true
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
                runCatching { audio?.close() }
                runCatching { mic?.close() }
            }
        }
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
            onVideoConnected = { report(HiCarStatus.Streaming) },
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
                            onLog(
                                "HiChain finished: ${key.size}-byte session key; TCP endpoints are " +
                                    "serving this key, so DMSDP can start on the access point",
                            )
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
                    val network = liveNetwork
                    val isNetwork = frame.kind == KIND_NETWORK || frame.kind == KIND_NETWORK_COMMAND
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

    private companion object {
        const val KIND_NETWORK = 4
        const val KIND_NETWORK_COMMAND = 33
        const val PREFERENCES = "hicar-receiver"
        const val KEY_SEQUENCE = "discovery_sequence"

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
 * A TCP listener on the car's access point address.
 *
 * Reserved for the media stage: the phone dials `controlPort`, `+1` (data) and `+2`. Nothing calls
 * this yet, because the chain cannot pass authentication in this build; it is kept next to the
 * controller so the next step does not have to rediscover the binding rules.
 */
internal fun openListener(offer: HiCarNetworkOffer, port: Int): ServerSocket =
    ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(offer.ownerAddress, port), 4)
    }
