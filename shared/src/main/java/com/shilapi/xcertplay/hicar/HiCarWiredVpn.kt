package com.shilapi.xcertplay.hicar

import android.content.Context
import android.content.Intent
import android.net.VpnService
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
 * It exists because `establish()` may only be called by a `VpnService`, and Android refuses to create
 * one without the user's consent: [HiCarWiredVpn.open] returns null when consent is still missing, and
 * the caller is expected to send the driver to the system dialog
 * ([HiCarWiredVpn.consentIntent]) rather than pretend the cable failed.
 *
 * Only the car's own subnet is routed into the tunnel. A full-tunnel VPN would take the head unit's
 * own traffic — its maps, its updater — and send it to a phone that has no idea what to do with it.
 */
class HiCarWiredVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (address != null && tunnel == null) {
            tunnel = try {
                establish(address)
            } catch (failure: Exception) {
                log("VPN: could not establish the tunnel (${failure.javaClass.simpleName}: ${failure.message})")
                null
            }
            ready.countDown()
        }
        return START_NOT_STICKY
    }

    /** The system revoked the tunnel: another VPN took over, or the driver turned it off. */
    override fun onRevoke() {
        log("VPN: the system revoked the tunnel; the wired link is gone")
        tunnel?.wakeup()
        super.onRevoke()
    }

    private fun establish(address: String): HiCarTunInterface {
        val builder = Builder()
            .setSession("HiCar cable")
            .setMtu(MTU)
            .addAddress(address, PREFIX)
            .addRoute(address, PREFIX)
            // The reader polls; a blocking descriptor would make that poll pointless.
            .setBlocking(false)
        val descriptor = builder.establish() ?: throw IOException("establish returned null")
        log("VPN: tunnel at $address/$PREFIX, mtu $MTU; only that subnet is routed into it")
        return HiCarTunInterface(descriptor)
    }

    companion object {
        private const val EXTRA_ADDRESS = "com.shilapi.xcertplay.hicar.WIRED_ADDRESS"
        private const val MTU = 1500
        private const val PREFIX = 24
        private const val TAG = "HiCarWired"

        /**
         * The tunnel of the current session.
         *
         * Static because a `VpnService` is created by the system and the side that needs the tunnel is
         * the data path, not the service. One wired session at a time, which is also what the USB
         * side enforces.
         */
        @Volatile internal var tunnel: HiCarTunInterface? = null
            private set

        /** Counted down once `onStartCommand` has tried to establish (successfully or not). */
        @Volatile private var ready = CountDownLatch(1)

        /**
         * Where the service's own lines go.
         *
         * A `VpnService` is created by the system and has no reference to the session, but its
         * failures — "establish returned null" above all — are exactly what the exported report
         * needs. Without this they only reached `android.util.Log`, which the report never carries.
         */
        @Volatile internal var logSink: ((String) -> Unit)? = null

        /** What the driver has to accept before any tunnel can exist, or null when it already is. */
        internal fun consentIntent(context: Context): Intent? = prepare(context)

        /**
         * Starts the service and waits for the tunnel.
         *
         * Returns null when consent is missing (the caller should show [consentIntent]) or when the
         * service could not start; the log says which.
         */
        internal fun open(
            context: Context,
            address: String,
            timeoutMillis: Long = 5_000L,
            onLog: (String) -> Unit = {},
        ): HiCarTunInterface? {
            val app = context.applicationContext
            if (prepare(app) != null) {
                // The caller shows the system dialog; this line is what makes that visible in the
                // exported report, where `android.util.Log` never appears.
                onLog("VPN: the system still wants the driver to allow a VPN connection")
                return null
            }
            ready = CountDownLatch(1)
            logSink = onLog
            val intent = Intent(app, HiCarWiredVpnService::class.java).putExtra(EXTRA_ADDRESS, address)
            val started = runCatching { app.startService(intent) }.isSuccess
            if (!started) {
                onLog(
                    "VPN: the service would not start; on Android 8+ it has to be started from the " +
                        "foreground",
                )
                return null
            }
            if (!ready.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                onLog("VPN: the tunnel did not come up within $timeoutMillis ms")
                return null
            }
            return tunnel
        }

        internal fun close(context: Context) {
            tunnel?.wakeup()
            tunnel = null
            runCatching { context.applicationContext.stopService(Intent(context, HiCarWiredVpnService::class.java)) }
        }

        private fun log(message: String) {
            android.util.Log.i(TAG, message)
            runCatching { logSink?.invoke(message) }
        }
    }
}

/**
 * The wired tunnel, as the session sees it.
 *
 * A thin facade over [HiCarWiredVpnService]: the service is created by the system, and everything that
 * needs the tunnel is in this package, so the two are kept apart only so the service can stay a plain
 * `VpnService` with no logic of its own.
 */
internal object HiCarWiredVpn {
    /** The system's consent dialog, or null when it has already been accepted. */
    fun consentIntent(context: Context): Intent? = HiCarWiredVpnService.consentIntent(context)

    fun open(
        context: Context,
        address: String,
        onLog: (String) -> Unit = {},
    ): HiCarTunInterface? = HiCarWiredVpnService.open(context, address, onLog = onLog)

    fun close(context: Context) = HiCarWiredVpnService.close(context)
}
