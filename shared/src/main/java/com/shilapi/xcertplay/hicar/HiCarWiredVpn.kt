package com.shilapi.xcertplay.hicar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The TUN descriptor a `VpnService` establishes, read and written one IP packet at a time.
 *
 * A blocking read cannot be interrupted from another thread, and closing the descriptor underneath it
 * does not wake it — and the descriptor number can be reused immediately. So the read waits on the
 * tunnel **and** a wake pipe at once (`poll`), and [wakeup] writes a byte into the pipe. The stop
 * order is therefore: [wakeup], wait for the transport threads, then [close].
 *
 * Ported from `TunInterface` in GGBondLab/OpenHC (GPL-3.0-only).
 */
internal class HiCarTunInterface(private val descriptor: ParcelFileDescriptor) : PacketTunnel, Closeable {

    private val fd: FileDescriptor = descriptor.fileDescriptor
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor
    private val lock = Any()

    @Volatile private var woken = false
    private var wakeSignalled = false
    private var closed = false

    init {
        val pipe = try {
            Os.pipe()
        } catch (failure: ErrnoException) {
            throw IOException("wake pipe", failure)
        }
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
        val tunPoll = StructPollfd().apply {
            fd = this@HiCarTunInterface.fd
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wakeRead
            events = OsConstants.POLLIN.toShort()
        }
        val fds = arrayOf(tunPoll, wakePoll)
        while (!woken) {
            val ready = try {
                // The wake pipe could be waited on forever; the timeout is only a backstop in case the
                // wake byte is lost, so a transport thread can never hang past shutdown.
                Os.poll(fds, POLL_TIMEOUT_MS)
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.EINTR) continue
                return -1
            }
            if (woken || wakePoll.revents.toInt() != 0) return -1
            if (ready <= 0) continue
            val events = tunPoll.revents.toInt()
            if (events and OsConstants.POLLIN != 0) {
                try {
                    val n = Os.read(fd, buffer, offset, maxLength)
                    // A TUN never reads zero bytes; zero means the interface is gone.
                    return if (n > 0) n else -1
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.EAGAIN || failure.errno == OsConstants.EINTR) continue
                    return -1
                } catch (failure: InterruptedIOException) {
                    return -1
                }
            }
            if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return -1
        }
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (woken) return false
        return try {
            Os.write(fd, buffer, offset, length) == length
        } catch (failure: ErrnoException) {
            false
        } catch (failure: InterruptedIOException) {
            false
        }
    }

    override fun wakeup() {
        woken = true
        synchronized(lock) {
            if (closed || wakeSignalled) return
            wakeSignalled = true
            try {
                Os.write(wakeWrite, WAKE_BYTE, 0, 1)
            } catch (failure: ErrnoException) {
                // A lost wake byte only costs one poll timeout before the reader sees `woken`.
            } catch (failure: InterruptedIOException) {
                // Same.
            }
        }
    }

    /** Closes the tunnel and the wake pipe. Call after both transport threads have exited; repeatable. */
    override fun close() {
        wakeup()
        synchronized(lock) {
            if (closed) return
            closed = true
            closeQuietly(wakeRead)
            closeQuietly(wakeWrite)
            try {
                descriptor.close()
            } catch (failure: IOException) {
                // Nothing can be done about a failing close.
            }
        }
    }

    private fun closeQuietly(file: FileDescriptor) {
        try {
            Os.close(file)
        } catch (failure: ErrnoException) {
            // Same.
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 1000
        val WAKE_BYTE = byteArrayOf(1)
    }
}

/**
 * The `VpnService` that owns the TUN for a wired session.
 *
 * What is needed here is not a VPN but a network interface whose address this app chooses: a normal
 * app may not configure a system interface, and a TUN is the only supported way to get one.
 *
 * Ported from `WiredVpnService` in GGBondLab/OpenHC (GPL-3.0-only), including the three builder
 * arguments that matter on a head unit: only this app's traffic enters the tunnel
 * (`addAllowedApplication`), so the car's own maps and updater never see it; IPv6 is left on its
 * normal path (`allowFamily`), because an IPv4-only VPN otherwise blocks it; and only the phone's
 * subnet is routed, never a default route.
 *
 * The service is **bound**, not started: since Android 8 a background app may not `startService`, and
 * a car head unit is in the background most of the time. Binding is not restricted, and the service
 * dies with the binding, so it never lingers.
 */
class HiCarWiredVpnService : VpnService() {

    private val localBinder = LocalBinder()

    /** Called on the main thread when the system revokes the tunnel (another VPN took over). */
    @Volatile internal var revokeListener: (() -> Unit)? = null

    internal inner class LocalBinder : Binder() {
        val service: HiCarWiredVpnService get() = this@HiCarWiredVpnService
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL_BIND) localBinder else super.onBind(intent)

    /** The system revoked the tunnel: another VPN took over, or the driver turned it off. */
    override fun onRevoke() {
        log("VPN: the system revoked the tunnel; the wired link is gone")
        revokeListener?.invoke()
        super.onRevoke()
    }

    /** Builds the TUN. Null when the app has no consent; a rejected spec throws. */
    internal fun establish(address: String): ParcelFileDescriptor? {
        val octets = address.split('.')
        val network = if (octets.size == 4) {
            "${octets[0]}.${octets[1]}.${octets[2]}.0"
        } else {
            address
        }
        val builder = Builder()
            .setSession("HiCar cable")
            .setMtu(MTU)
            .addAddress(address, PREFIX)
            .addRoute(network, PREFIX)
            // Only this app's traffic: the head unit's own network must not be routed to a phone.
            .addAllowedApplication(packageName)
            .allowFamily(OsConstants.AF_INET6)
            // The reader polls; a blocking descriptor would make that poll pointless.
            .setBlocking(false)
        return builder.establish()
    }

    companion object {
        internal const val ACTION_LOCAL_BIND = "com.shilapi.xcertplay.hicar.BIND_WIRED_VPN"
        private const val MTU = 1500
        private const val PREFIX = 24
        private const val TAG = "HiCarWired"

        /**
         * Where the service's own lines go.
         *
         * A `VpnService` is created by the system and has no reference to the session, but its
         * failures — "establish returned null" above all — are exactly what the exported report
         * needs. Without this they only reached `android.util.Log`, which the report never carries.
         */
        @Volatile internal var logSink: ((String) -> Unit)? = null

        private fun log(message: String) {
            android.util.Log.i(TAG, message)
            runCatching { logSink?.invoke(message) }
        }
    }
}

/**
 * The wired tunnel, as the session sees it: a bound [HiCarWiredVpnService] and the TUN it built.
 *
 * One wired session at a time (the USB side enforces that too), so the binding lives here rather than
 * in a handle passed through five layers.
 */
internal object HiCarWiredVpn {

    private var binding: ServiceConnection? = null
    private var tunnel: HiCarTunInterface? = null

    /** The system's consent dialog, or null when it has already been accepted. */
    fun consentIntent(context: Context): Intent? =
        runCatching { VpnService.prepare(context.applicationContext) }.getOrNull()

    /** True when the app already holds the consent, i.e. a tunnel can be built without a dialog. */
    fun consented(context: Context): Boolean = consentIntent(context) == null

    /**
     * Binds the service and builds the TUN. Blocking: call it off the main thread.
     *
     * Returns null with a line in the log saying which step failed — no consent, binding refused,
     * service never connected, or `establish` refused the spec.
     */
    fun open(
        context: Context,
        address: String,
        onLog: (String) -> Unit = {},
        onRevoked: () -> Unit = {},
    ): HiCarTunInterface? {
        tunnel?.let { return it }
        val app = context.applicationContext
        HiCarWiredVpnService.logSink = onLog
        if (consentIntent(app) != null) {
            onLog("VPN: the system still wants the driver to allow a VPN connection")
            return null
        }
        val connected = CountDownLatch(1)
        val serviceRef = AtomicReference<HiCarWiredVpnService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set((binder as? HiCarWiredVpnService.LocalBinder)?.service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The system killed the service; the tunnel is gone with it and the data path ends.
            }
        }
        val intent = Intent(app, HiCarWiredVpnService::class.java)
            .setAction(HiCarWiredVpnService.ACTION_LOCAL_BIND)
        val bound = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            onLog("VPN: could not bind the tunnel service (is it still declared in the manifest?)")
            return null
        }
        if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            onLog("VPN: the tunnel service did not connect within $BIND_TIMEOUT_MS ms")
            unbind(app, connection)
            return null
        }
        val service = serviceRef.get() ?: run {
            onLog("VPN: the binder was not this module's tunnel service")
            unbind(app, connection)
            return null
        }
        val descriptor = try {
            service.establish(address)
        } catch (failure: RuntimeException) {
            // Address or route refused, another VPN holding the lock, or a device policy blocking VPNs.
            onLog(
                "VPN: the system refused the tunnel (${failure.javaClass.simpleName}: ${failure.message})",
            )
            unbind(app, connection)
            return null
        } ?: run {
            onLog("VPN: no tunnel — consent missing, or another VPN app has taken it over")
            unbind(app, connection)
            return null
        }
        val tun = try {
            HiCarTunInterface(descriptor)
        } catch (failure: IOException) {
            runCatching { descriptor.close() }
            onLog("VPN: the tunnel descriptor could not be read (${failure.message})")
            unbind(app, connection)
            return null
        }
        service.revokeListener = {
            onLog("VPN: the driver or the system revoked the tunnel")
            onRevoked()
        }
        binding = connection
        tunnel = tun
        onLog("VPN: tunnel at $address/24, mtu 1500; only this app and only that subnet")
        return tun
    }

    /** Stops the tunnel and drops the binding. Call after the transport threads are stopped. */
    fun close(context: Context) {
        tunnel?.let {
            it.wakeup()
            it.close()
        }
        tunnel = null
        binding?.let { unbind(context.applicationContext, it) }
        binding = null
        HiCarWiredVpnService.logSink = null
    }

    private fun unbind(context: Context, connection: ServiceConnection) {
        runCatching { context.unbindService(connection) }
    }

    private const val BIND_TIMEOUT_MS = 5_000L
}
