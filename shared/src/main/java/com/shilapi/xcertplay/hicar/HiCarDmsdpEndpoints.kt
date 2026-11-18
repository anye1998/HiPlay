package com.shilapi.xcertplay.hicar

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The three TCP endpoints the phone dials on the car's access point after authentication.
 *
 * Until this existed the chain ended at `END_SUCCESS` with the phone closing both RFCOMM channels
 * about thirty seconds later: it had been told (`hicarApIp` in the credential string) which address
 * to reach and which port to start at, and found nobody listening. The reference receiver served
 * exactly these three ports, which is what turned "authenticated" into "connected".
 *
 * The roles, from the reference implementation and confirmed by the port arithmetic it uses:
 *
 * | port             | framing                        | purpose                                     |
 * |------------------|--------------------------------|---------------------------------------------|
 * | `controlPort`    | nearby wire (`0x7F`, kind)      | Wi-Fi follow-up: capability, credentials    |
 * | `controlPort + 1`| DMSDP module (`0x7F`, module)   | entry handshake, answers with `+2`          |
 * | `controlPort + 2`| DMSDP record (`marker`, length) | control channel, AES-CTR, device module     |
 *
 * Every connection is checked twice: the car is only reachable from the address it handed out, and
 * nothing is served before authentication produced a key. A port that answers an unauthenticated
 * peer would hand the passphrase and the session key to anything on the block.
 */
internal class HiCarDmsdpEndpoints(
    private val offer: HiCarNetworkOffer,
    private val summary: ByteArray,
    private val network: HiCarNetworkNegotiator,
    private val business: HiCarDeviceBusiness,
    private val sessionKey: () -> ByteArray?,
    private val active: () -> Boolean,
    private val onLog: (String) -> Unit,
    /**
     * Builds the per-session media bundle (audio receiver, microphone, service handlers).
     *
     * It runs when the control connection is accepted — before the phone's first service message —
     * because the speaker's UDP port has to exist before the phone can ask where to send audio.
     * Everything it returns is closed when the control connection ends.
     */
    private val mediaFactory: (ByteArray) -> Media? = { null },
) : Closeable {

    /** Audio and microphone live only as long as one control connection. */
    internal interface Media : Closeable {
        /** Handlers tried in order; the first non-null reply wins. */
        fun textHandlers(): List<HiCarControlSession.TextHandler>
    }
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val controlBusy = AtomicBoolean(false)
    private var closed = false

    val controlPort: Int get() = offer.controlPort
    val entryPort: Int get() = offer.controlPort + 1

    fun start() {
        listen(offer.controlPort, "network") { socket, input, output -> serveNetwork(socket, input, output) }
        listen(entryPort, "entry") { _, input, output -> serveEntry(input, output) }
        listen(offer.controlPort + 2, "control") { socket, input, output -> serveControl(socket, input, output) }
        onLog(
            "DMSDP: listening on ${offer.ownerAddress} ports ${offer.controlPort}, $entryPort, " +
                "${offer.controlPort + 2} (network, entry, control)",
        )
    }

    override fun close() {
        closed = true
        for (server in servers) runCatching { server.close() }
        servers.clear()
        for (client in clients) runCatching { client.close() }
        clients.clear()
    }

    private fun listen(port: Int, name: String, handler: (Socket, InputStream, OutputStream) -> Unit) {
        val server = try {
            openListener(offer, port)
        } catch (failure: IOException) {
            onLog("DMSDP: cannot bind $name port $port (${failure.javaClass.simpleName}: ${failure.message})")
            return
        }
        servers.add(server)
        val thread = Thread({
            while (active() && !closed) {
                val socket = try {
                    server.accept()
                } catch (failure: IOException) {
                    if (active() && !closed) onLog("DMSDP: $name listener closed (${failure.javaClass.simpleName})")
                    return@Thread
                }
                handleAccepted(socket, name, handler)
            }
        }, "hicar-dmsdp-$name")
        thread.isDaemon = true
        thread.start()
    }

    private fun handleAccepted(socket: Socket, name: String, handler: (Socket, InputStream, OutputStream) -> Unit) {
        val peer = socket.inetAddress?.hostAddress ?: "unknown"
        if (!active() || !network.authenticated || sessionKey() == null) {
            onLog("DMSDP: $name connection from $peer before authentication; refused")
            runCatching { socket.close() }
            return
        }
        if (peer != offer.clientAddress) {
            onLog("DMSDP: $name connection from $peer, not the $${offer.clientAddress} this session gave out; refused")
            runCatching { socket.close() }
            return
        }
        if (name == "control" && !controlBusy.compareAndSet(false, true)) {
            onLog("DMSDP: a control connection is already open; refusing a second from $peer")
            runCatching { socket.close() }
            return
        }
        clients.add(socket)
        onLog("DMSDP: $name connection accepted from $peer")
        val thread = Thread({
            try {
                socket.tcpNoDelay = true
                handler(socket, socket.getInputStream(), socket.getOutputStream())
            } catch (failure: HiCarWireException) {
                onLog("DMSDP: $name stopped (${failure.message})")
            } catch (failure: IOException) {
                onLog("DMSDP: $name ended (${failure.javaClass.simpleName})")
            } finally {
                if (name == "control") controlBusy.set(false)
                clients.remove(socket)
                runCatching { socket.close() }
            }
        }, "hicar-dmsdp-$name-rx")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Port `controlPort`: the Wi-Fi follow-up.
     *
     * The phone repeats the capability/credential exchange it started on 0xFE35. The reference serves
     * both with the same negotiator, and so does this: the RFCOMM copy is what let the phone join at
     * all, and this one is what it asks again after joining.
     */
    private fun serveNetwork(socket: Socket, input: InputStream, output: OutputStream) {
        val decoder = HiCarFrameDecoder()
        val negotiator = HiCarChannelNegotiator(summary, businessChannel = { 0 })
        val buffer = ByteArray(4096)
        while (active() && !closed) {
            val read = input.read(buffer)
            if (read < 0) return
            for (frame in decoder.feed(buffer, read)) {
                val reply = when (frame.kind) {
                    KIND_NETWORK, KIND_NETWORK_COMMAND -> network.respond(frame)
                    // The business-channel announcement belongs to 0xFE35, which already answered it;
                    // a repeat here is tolerated and logged rather than treated as a protocol error.
                    else -> runCatching { negotiator.respond(frame) }
                        .onFailure { onLog("DMSDP: network frame kind ${frame.kind} ignored (${it.message})") }
                        .getOrNull()
                }
                if (reply != null) {
                    output.write(reply)
                    output.flush()
                }
                for (queued in network.takePending()) {
                    output.write(queued)
                    output.flush()
                }
                onLog("DMSDP: network frame kind ${frame.kind}, ${frame.payload.size} bytes, reply ${reply?.size ?: 0}")
            }
        }
    }

    /** Port `controlPort + 1`: the entry handshake, whose only answer is "control lives on +2". */
    private fun serveEntry(input: InputStream, output: OutputStream) {
        val decoder = HiCarDmsdpDecoder()
        val buffer = ByteArray(4096)
        var answered = 0
        while (active() && !closed) {
            val read = input.read(buffer)
            if (read < 0) return
            for (packet in decoder.feed(buffer, read)) {
                if (++answered > MAX_ENTRY_REQUESTS) throw HiCarWireException("DMSDP request limit")
                val reply = hiCarDmsdpEntryReply(packet, offer.controlPort + 2)
                output.write(reply)
                output.flush()
                onLog("DMSDP entry: module ${packet.module} → control port ${offer.controlPort + 2}")
            }
        }
    }

    /**
     * Port `controlPort + 2`: the control channel.
     *
     * Plaintext `OPTIONS` first, `SET_PARAMETER CryptoSwitch` flips it to AES-CTR, then a heartbeat
     * every second keeps the phone's thirty-second idle timer from firing. The device module runs on
     * this socket as `$` payloads; everything else is text.
     */
    private fun serveControl(socket: Socket, input: InputStream, output: OutputStream) {
        val key = sessionKey() ?: throw HiCarWireException("missing authenticated key")
        val media = try {
            mediaFactory(key)
        } catch (failure: IOException) {
            onLog("DMSDP: media unavailable for this session (${failure.message ?: failure.javaClass.simpleName}); control continues")
            null
        }
        val cipher = HiCarRecordCipher(key, maxRecordBytes = CONTROL_RECORD_BYTES)
        var lastHeartbeat = System.nanoTime()
        var lastPeer = lastHeartbeat
        val handlers = media?.textHandlers().orEmpty()
        val session = HiCarControlSession(
            cipher,
            object : HiCarControlSession.Events {
                override fun onLog(message: String) {
                    this@HiCarDmsdpEndpoints.onLog("DMSDP: $message")
                }

                override fun onClock(high: Long, low: Long) {
                    (media as? Clocked)?.onClock(high, low)
                }

                override fun onApplicationService(module: Long, type: Long, body: ByteArray): List<ByteArray> =
                    business.handle(module, type, body)
            },
            textHandler = if (handlers.isEmpty()) {
                null
            } else {
                object : HiCarControlSession.TextHandler {
                    override fun receive(startLine: String, headers: Map<String, String>, body: ByteArray): List<ByteArray>? {
                        for (handler in handlers) {
                            val replies = handler.receive(startLine, headers, body)
                            if (replies != null) return replies
                        }
                        return null
                    }
                }
            },
        )
        try {
            output.write(session.initial())
            output.flush()
            onLog("DMSDP: control OPTIONS greeting sent (${CONTROL_RECORD_BYTES}-byte record limit)")
            socket.soTimeout = HEARTBEAT_POLL_MILLIS
            val buffer = ByteArray(4096)
            while (active() && !closed) {
                val read = try {
                    input.read(buffer)
                } catch (timeout: SocketTimeoutException) {
                    val now = System.nanoTime()
                    if (now - lastPeer > IDLE_TIMEOUT_NANOS) throw HiCarWireException("DMSDP idle timeout")
                    if (session.negotiated && now - lastHeartbeat > HEARTBEAT_PERIOD_NANOS) {
                        session.heartbeat()?.let {
                            output.write(it)
                            output.flush()
                        }
                        lastHeartbeat = now
                    }
                    continue
                }
                if (read < 0) return
                lastPeer = System.nanoTime()
                for (reply in session.feed(buffer, read)) {
                    output.write(reply)
                    output.flush()
                }
            }
        } finally {
            session.close()
            runCatching { media?.close() }
            cipher.clear()
        }
    }

    /** A media bundle that also wants the phone's clock samples (the microphone timestamps with them). */
    internal interface Clocked {
        fun onClock(high: Long, low: Long)
    }

    private companion object {
        const val MAX_ENTRY_REQUESTS = 3
        const val CONTROL_RECORD_BYTES = 1498
        const val HEARTBEAT_POLL_MILLIS = 250
        const val HEARTBEAT_PERIOD_NANOS = 1_000_000_000L
        const val IDLE_TIMEOUT_NANOS = 30_000_000_000L
        const val KIND_NETWORK = 4
        const val KIND_NETWORK_COMMAND = 33
    }
}
