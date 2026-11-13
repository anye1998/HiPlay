package com.shilapi.xcertplay.hicar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the HiCar receiver is doing, for the driver and for diagnostics. */
sealed class HiCarStatus {
    data object Idle : HiCarStatus()
    data object PreparingNetwork : HiCarStatus()
    data class NetworkReady(val ssid: String, val address: String, val backend: String) : HiCarStatus()
    data object Advertising : HiCarStatus()
    data object WaitingForPhone : HiCarStatus()
    data object ChannelNegotiated : HiCarStatus()
    data object Authenticating : HiCarStatus()

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
    val pinConfigured: Boolean = false,
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

    private var hotspot: LocalOnlyHotspotManager? = null
    private var services: HiCarRfcommServices? = null
    private var advertiser: HiCarBleAdvertiser? = null
    private var offer: HiCarNetworkOffer? = null

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
                onLog("HiCar needs the nearby-devices Bluetooth permission")
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
        offer = networkOffer
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
        if (!beacon.start(HiCarBeacon.serviceData(config.model, nextSequence()))) {
            report(HiCarStatus.Failed("advertising"))
            return
        }
        report(HiCarStatus.WaitingForPhone)
        startAcceptLoops(rfcomm)
    }

    private fun prepareNetwork(): HiCarNetworkOffer? {
        val manager = runCatching { LocalOnlyHotspotManager(appContext, onLog) }.getOrNull()
        if (manager == null) {
            report(HiCarStatus.Failed("no hotspot manager"))
            return null
        }
        hotspot = manager
        val info: WirelessHotspotInfo = try {
            manager.start(config.networkTimeoutMillis)
        } catch (failure: Exception) {
            report(HiCarStatus.Failed("hotspot"))
            onLog("HiCar access point failed: ${failure.javaClass.simpleName}")
            return null
        }
        val frequency = info.frequencyMHz
        if (frequency == null || HiCarNetworkOffer.frequencyMask(frequency) == 0) {
            // The phone only accepts the 5 GHz channels in its capability bitmap; a 2.4 GHz group
            // would negotiate "not accepted" and look like a phone-side failure.
            report(HiCarStatus.Failed("hotspot band"))
            onLog("HiCar access point is not on an accepted 5 GHz channel (${frequency ?: "unknown"} MHz)")
            return null
        }
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
        val networkOffer = try {
            HiCarNetworkOffer(
                ssid = info.ssid,
                bssid = info.bssid.orEmpty(),
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
        val identity = HiCarIdentityExchange(
            localIdentity = config.localIdentity,
            deviceName = config.deviceName,
            model = config.model,
            pinConfigured = config.pinConfigured,
            onLog = { message -> onLog(message) },
        )
        val decoder = HiCarFrameDecoder()
        try {
            val input: InputStream = socket.inputStream
            val output: OutputStream = socket.outputStream
            val buffer = ByteArray(4096)
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (!control) {
                    for (reply in identity.feed(buffer, read)) {
                        output.write(reply)
                        output.flush()
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
            closeQuietly(socket)
            sockets.remove(socket)
        }
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            workers.add(this)
            start()
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

/** Wi-Fi helper retained for the media stage: the phone joins the access point we just created. */
internal fun wifiManagerOf(context: Context): WifiManager? =
    context.applicationContext.getSystemService(WifiManager::class.java)

/** Address the phone dials for the data socket; bound listeners must use the same one. */
internal fun controlEndpoint(offer: HiCarNetworkOffer): InetSocketAddress =
    InetSocketAddress(offer.ownerAddress, offer.controlPort)

/** A TCP listener on the car's access point address; used once authentication succeeds. */
internal fun openListener(offer: HiCarNetworkOffer, port: Int): ServerSocket =
    ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(offer.ownerAddress, port), 4)
    }
