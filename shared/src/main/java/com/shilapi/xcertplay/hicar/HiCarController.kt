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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val setup = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hicar-setup") }
    private val accept = Executors.newCachedThreadPool { runnable -> Thread(runnable, "hicar-accept") }
    private val sockets = CopyOnWriteArrayList<Closeable>()
    private val workers = CopyOnWriteArrayList<Thread>()

    /** How many RFCOMM connections arrived; the waiting heartbeat reports it. */
    private val connections = AtomicInteger(0)

    private var hotspot: WirelessHotspotManager? = null
    private var services: HiCarRfcommServices? = null
    private var advertiser: HiCarBleAdvertiser? = null
    private var probe: HiCarAdvertisementProbe? = null

    /** Created only after the access point is up, so it can never answer with a placeholder offer. */
    private var liveNetwork: HiCarNetworkNegotiator? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
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
        advertiser?.close()
        advertiser = null
        probe?.close()
        probe = null
        services?.close()
        services = null
        for (closeable in sockets) closeQuietly(closeable)
        sockets.clear()
        for (worker in workers) worker.interrupt()
        workers.clear()
        runCatching { hotspot?.close() }
        hotspot = null
        setup.shutdownNow()
        accept.shutdownNow()
        report(HiCarStatus.Stopped)
        onLog("HiCar receiver stopped")
    }

    private fun startChain() {
        if (!hasPermissions()) {
            report(HiCarStatus.Failed("permission"))
            onLog("HiCar needs BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE on this Android version")
            return
        }
        val adapter = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            report(HiCarStatus.Failed("bluetooth off"))
            onLog("HiCar needs Bluetooth switched on")
            return
        }
        val networkOffer = prepareNetwork() ?: return
        liveNetwork = HiCarNetworkNegotiator(networkOffer).also { it.authenticated = false }
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
        startAcceptLoops(rfcomm)
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

    private fun startAcceptLoops(rfcomm: HiCarRfcommServices) {
        val control = thread("hicar-control") { acceptLoop(rfcomm::acceptControl, control = true) }
        val business = thread("hicar-business") { acceptLoop(rfcomm::acceptBusiness, control = false) }
        onLog("HiCar listeners started: ${control.name}, ${business.name}")
        startWaitingHeartbeat()
    }

    /**
     * Says, once every 30 seconds, that the receiver is up and nobody has connected.
     *
     * Without it an exported log ends at "listeners started" and reads the same whether the phone
     * never tried, tried and failed before RFCOMM, or the receiver had already died. Those need
     * different next steps, so the log has to tell them apart.
     */
    private fun startWaitingHeartbeat() {
        thread("hicar-wait") {
            val startedAt = System.nanoTime()
            while (running.get()) {
                try {
                    Thread.sleep(WAIT_HEARTBEAT_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
                if (!running.get()) return@thread
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
                onLog(
                    "HiCar waiting for the phone: ${seconds}s, connections=$connections, " +
                        "advertising=${if (advertiser != null) "on" else "off"}, " +
                        "listeners=${if (services != null) "open" else "closed"}, " +
                        "discoverable=${discoverableState()}",
                )
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

    private fun acceptLoop(acceptSocket: () -> android.bluetooth.BluetoothSocket, control: Boolean) {
        while (running.get()) {
            val socket = try {
                acceptSocket()
            } catch (closed: IOException) {
                if (running.get()) onLog("HiCar ${if (control) "control" else "business"} listener closed")
                return
            } catch (denied: SecurityException) {
                onLog("HiCar listener needs the nearby-devices permission")
                return
            }
            sockets.add(socket)
            connections.incrementAndGet()
            accept.execute { serve(socket, control) }
        }
    }

    private fun serve(socket: android.bluetooth.BluetoothSocket, control: Boolean) {
        onLog("HiCar ${if (control) "control" else "business"} channel connected")
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
        val decoder = HiCarFrameDecoder()
        try {
            val input: InputStream = socket.inputStream
            val output: OutputStream = socket.outputStream
            val buffer = ByteArray(4096)
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (!control) {
                    val active = session
                    if (active == null) {
                        for (reply in identity.feed(buffer, read)) {
                            output.write(reply)
                            output.flush()
                        }
                        val key = authenticator.sessionKey
                        if (key != null) {
                            report(HiCarStatus.Authenticated)
                            onLog("HiChain finished: ${key.size}-byte session key; opening DMSDP")
                            liveNetwork?.authenticated = true
                            session = openControlSession(output, key)
                        }
                    } else {
                        for (reply in active.feed(buffer, read)) {
                            output.write(reply)
                            output.flush()
                        }
                    }
                    continue
                }
                for (frame in decoder.feed(buffer, read)) {
                    val reply = when {
                        frame.kind == KIND_NETWORK || frame.kind == KIND_NETWORK_COMMAND ->
                            liveNetwork?.respond(frame)

                        else -> negotiator.respond(frame)
                    }
                    if (reply != null) {
                        output.write(reply)
                        output.flush()
                    }
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
        output.write(session.initial())
        output.flush()
        return session
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            workers.add(this)
            start()
        }

    /** Which declared permission is not granted; empty means the refusal came from somewhere else. */
    private fun missingPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .filter { appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val connect = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val advertise = appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        return connect == PackageManager.PERMISSION_GRANTED && advertise == PackageManager.PERMISSION_GRANTED
    }

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

        /** Long enough to stay out of the way, short enough that a 2-minute test shows progress. */
        const val WAIT_HEARTBEAT_MILLIS = 30_000L

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
