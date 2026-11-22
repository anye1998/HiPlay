package com.shilapi.xcertplay.hicar

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The HiSight client: RTSP control, video, and UIBC input against the address the phone named.
 *
 * Three sockets, all dialled from this side and all bound to the access point's own address, so the
 * phone sees the same interface it handed the ports to:
 *
 * - **control** — [HiCarHiSightWire]; carries the handshake and then only keepalives;
 * - **video** — length-prefixed RTP records, decrypted by [HiCarHiSightMedia] into access units;
 * - **input** — encrypted UIBC events, opened only when the phone offers `remotectl_port`.
 *
 * Nothing is dialled before the phone has named a port, and the session ends as soon as the
 * controller says it is no longer current: a stale client must not keep a socket on a phone that
 * has moved on.
 *
 * Ported from `HiSightClient` in huducat586/hicar-android-receiver (MIT).
 */
internal class HiCarHiSightClient(
    private val owner: String,
    private val peer: String,
    sessionKey: ByteArray,
    private val video: HiCarVideoSink,
    private val input: HiCarInputRouter,
    private val active: () -> Boolean,
    private val onLog: (String) -> Unit,
    /** Fired once the media socket is up, which is the first moment a picture is possible. */
    private val onVideoConnected: () -> Unit = {},
    /** The geometry offered to the phone; the decoder's output overrides it once frames arrive. */
    private val displaySize: HiCarDisplaySize = HiCarDisplaySize.BASE,
    /** Fired when the phone's capability answer says whether it will accept key events. */
    private val onKeyInputSelected: (Boolean) -> Unit = {},
    /** Sharpness and frame rate offered for this session. */
    private val videoSettings: HiCarVideoSettings = HiCarVideoSettings(),
    /** The panel's refresh rate; read by the controller, which has the window manager. */
    private val refreshHz: Float = 60f,
) : AutoCloseable {
    private var key: ByteArray? = sessionKey.copyOf()
    private var control: Socket? = null
    private var media: Socket? = null
    private var inputChannel: HiCarInputChannel? = null

    @Volatile private var closed = false
    @Volatile private var lastMediaRead = System.nanoTime()
    @Volatile private var mediaIdle = false

    val videoPort: Int get() = port
    private var port = 0

    fun run(videoPort: Int) {
        port = videoPort
        var protocol: HiCarHiSightWire? = null
        try {
            val sessionKey = synchronized(this) { key?.copyOf() } ?: throw HiCarWireException("video key unavailable")
            protocol = try {
                // Always offered: the capability question is asked during ANNOUNCE/GET_PARAMETER,
                // long before the input socket exists, so asking the router here is guaranteed to
                // answer false. That was the defect - the phone was never told this receiver can
                // take touches, so it offered `remotectl_port` (it always does) and then dropped
                // every encrypted event written to it. The router is consulted per touch instead.
                HiCarHiSightWire(
                    sessionKey,
                    onLog,
                    withInput = true,
                    size = displaySize,
                    onInputSelection = onKeyInputSelected,
                    video = videoSettings,
                    refreshHz = refreshHz,
                )
            } finally {
                sessionKey.fill(0)
            }
            if (!video.ready) throw HiCarWireException("video sink unavailable")
            val socket = connect(videoPort)
            control = socket
            val output = socket.getOutputStream()
            val inbound = socket.getInputStream()
            output.write(protocol.initial())
            output.flush()
            onLog("HiSight: ANNOUNCE sent to $peer:$videoPort; waiting for the phone's video negotiation")
            val buffer = ByteArray(4096)
            var lastPeer = System.nanoTime()
            while (!closed && active() && video.ready && !protocol.ended) {
                val read = try {
                    inbound.read(buffer)
                } catch (timeout: SocketTimeoutException) {
                    if (media == null && System.nanoTime() - lastPeer > CONTROL_IDLE_NANOS) throw timeout
                    continue
                }
                if (read < 0) break
                lastPeer = System.nanoTime()
                val replies = protocol.feed(buffer, read).toMutableList()
                if (protocol.mediaPort != 0 && media == null) {
                    openMedia(protocol)
                    replies.add(protocol.play())
                }
                for (reply in replies) {
                    output.write(reply)
                    output.flush()
                }
            }
            onLog("HiSight: control session ended (${if (protocol.ended) "phone tore down" else "local stop"})")
        } catch (failure: IOException) {
            if (active()) onLog("HiSight: session ended (${failure.javaClass.simpleName}${failure.message?.let { ": $it" } ?: ""})")
        } catch (failure: RuntimeException) {
            if (active()) onLog("HiSight: session ended (${failure.javaClass.simpleName})")
        } finally {
            if (protocol != null) {
                runCatching {
                    val teardown = protocol.stop()
                    val output = control?.takeIf { !it.isClosed }?.getOutputStream()
                    if (teardown != null && output != null) {
                        output.write(teardown)
                        output.flush()
                    }
                }
                protocol.close()
            }
            close()
        }
    }

    private fun openMedia(protocol: HiCarHiSightWire) {
        val socket = connect(protocol.mediaPort)
        media = socket
        if (protocol.inputPort != 0) {
            val inputKey = synchronized(this) { key?.copyOf() }
            if (inputKey != null) {
                try {
                    val channel = HiCarInputChannel(
                        connect(protocol.inputPort), inputKey, input, active, onLog,
                    )
                    inputKey.fill(0)
                    channel.start()
                    inputChannel = channel
                    onLog("HiSight: UIBC input connected to $peer:${protocol.inputPort}")
                } catch (failure: IOException) {
                    inputKey.fill(0)
                    onLog("HiSight: input channel unavailable (${failure.javaClass.simpleName}); the picture continues")
                }
            }
        }
        Thread({ receiveMedia(socket) }, "hicar-hisight-media").also {
            it.isDaemon = true
            it.start()
        }
        onLog("HiSight: video socket connected to $peer:${protocol.mediaPort}; PLAY follows")
        runCatching { onVideoConnected() }
    }

    private fun receiveMedia(socket: Socket) {
        val sessionKey = synchronized(this) { key?.copyOf() } ?: return
        try {
            HiCarHiSightMedia(sessionKey).use { decoder ->
                val inbound = BufferedInputStream(socket.getInputStream(), 65_536)
                lastMediaRead = System.nanoTime()
                mediaIdle = false
                onLog("HiSight: waiting for encrypted H.264 data")
                while (!closed && active() && video.ready) {
                    val header = ByteArray(4)
                    readFully(inbound, header)
                    var length = 0L
                    for (byte in header) length = (length shl 8) or (byte.toLong() and 0xFF)
                    if (length < 1 || length > MAX_RECORD_BYTES) throw HiCarWireException("media record size")
                    val packet = ByteArray(length.toInt())
                    readFully(inbound, packet)
                    try {
                        decoder.packet(packet) { kind, timestamp, data ->
                            if (closed || !active()) throw HiCarWireException("stale video session")
                            video.offer(kind, timestamp, data)
                        }
                    } finally {
                        packet.fill(0)
                    }
                }
            }
        } catch (failure: IOException) {
            if (active() && !closed) onLog("HiSight: video receive ended (${failure.javaClass.simpleName})")
        } catch (failure: RuntimeException) {
            if (active() && !closed) onLog("HiSight: video receive ended (${failure.javaClass.simpleName})")
        } finally {
            sessionKey.fill(0)
            close()
        }
    }

    /**
     * Reads a full record, tolerating a paused sender.
     *
     * A phone that is locked (or busy) can stop writing mid-record while its control heartbeat keeps
     * the session alive. Treating that read timeout as end-of-session would drop a link that is
     * still healthy, so partial bytes are preserved and the wait continues until the control
     * channel says otherwise.
     */
    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            if (closed || !active() || !video.ready) throw HiCarWireException("stale video session")
            val count = try {
                input.read(target, offset, target.size - offset)
            } catch (timeout: SocketTimeoutException) {
                if (!mediaIdle && System.nanoTime() - lastMediaRead >= MEDIA_IDLE_NANOS) {
                    mediaIdle = true
                    onLog("HiSight: video paused; keeping the session, the control heartbeat decides liveness")
                }
                continue
            }
            if (count < 0) throw EOFException("media closed")
            if (count == 0) continue
            offset += count
            lastMediaRead = System.nanoTime()
            if (mediaIdle) {
                mediaIdle = false
                onLog("HiSight: video data resumed")
            }
        }
    }

    private fun connect(target: Int): Socket {
        if (target !in 1..65535 || closed || !active()) throw HiCarWireException("video endpoint state")
        val socket = Socket()
        try {
            socket.bind(InetSocketAddress(owner, 0))
            socket.connect(InetSocketAddress(peer, target), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.tcpNoDelay = true
            return socket
        } catch (failure: IOException) {
            runCatching { socket.close() }
            throw failure
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            inputChannel?.close()
            inputChannel = null
            for (socket in listOfNotNull(control, media)) runCatching { socket.close() }
            control = null
            media = null
            key?.fill(0)
            key = null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 1_000
        const val MAX_RECORD_BYTES = 2L * 1024 * 1024
        const val CONTROL_IDLE_NANOS = 30_000_000_000L
        const val MEDIA_IDLE_NANOS = 15_000_000_000L
    }
}
