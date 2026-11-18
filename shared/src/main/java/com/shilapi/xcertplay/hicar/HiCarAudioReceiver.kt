package com.shilapi.xcertplay.hicar

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.Locale

/**
 * Receives the phone's audio on UDP and hands AAC frames to [HiCarAudioSink].
 *
 * The socket is opened when the DMSDP control connection is accepted and its port is what this side
 * advertises in the speaker `SETUP`, so it has to exist before the phone asks. Packets are accepted
 * only from the address and port the negotiation named: anything else on the access point is
 * another client, not this session.
 *
 * Ported from `AudioReceiver` in huducat586/hicar-android-receiver (MIT).
 */
internal class HiCarAudioReceiver(
    owner: String,
    private val peer: String,
    sessionKey: ByteArray,
    private val sink: HiCarAudioSink,
    private val active: () -> Boolean,
    private val onLog: (String) -> Unit,
) : Closeable {
    private val cipher = HiCarRecordCipher(sessionKey, MAX_DATAGRAM)
    private val media = HiCarAudioMedia()
    private val socket = DatagramSocket(InetSocketAddress(owner, 0))
    private val stateLock = Any()

    @Volatile private var closed = false
    @Volatile private var streaming = false
    @Volatile private var remotePort = 0
    private var epoch = 0
    private var received = 0L
    private var rejected = 0L
    private var wrongPeer = 0

    val port: Int get() = socket.localPort
    val ready: Boolean get() = !closed && sink.ready

    init {
        socket.soTimeout = RECEIVE_TIMEOUT_MILLIS
        Thread({ receive() }, "hicar-audio-udp").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** A new speaker session starts: drop the old UDP state, keep the socket. */
    fun begin() {
        synchronized(stateLock) {
            streaming = false
            remotePort = 0
            epoch++
            media.reset()
            sink.reset()
        }
    }

    /** The phone's `server_port`: from here on datagrams are expected from it. */
    fun startStreaming(target: Int) {
        synchronized(stateLock) {
            remotePort = target
            streaming = true
        }
        onLog("audio: UDP stream expected from $peer:$target")
    }

    fun stopStreaming() {
        synchronized(stateLock) {
            streaming = false
            epoch++
            media.reset()
            sink.reset()
        }
    }

    private fun receive() {
        val buffer = ByteArray(MAX_DATAGRAM)
        try {
            while (!closed && active()) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                if (!streaming) continue
                if (peer != datagram.address.hostAddress || datagram.port != remotePort) {
                    if (++wrongPeer <= 3) onLog("audio: datagram from an endpoint this session did not name; ignored")
                    continue
                }
                val acceptedEpoch = epoch
                received++
                var plain: ByteArray? = null
                try {
                    plain = HiCarAudioMedia.decrypt(Arrays.copyOf(buffer, datagram.length), cipher)
                    synchronized(stateLock) {
                        if (!streaming || epoch != acceptedEpoch) return@synchronized
                        for (frame in media.receive(plain)) sink.offer(frame)
                    }
                    if (received <= 3 || received % 500 == 0L) {
                        onLog(
                            "audio: $received datagram(s), ${media.frames} AAC frame(s), " +
                                "${media.lost} lost, ${media.duplicates} duplicate",
                        )
                    }
                } catch (failure: IOException) {
                    if (++rejected <= 3) onLog("audio: datagram rejected (${failure.message ?: "format"}); contents not logged")
                } catch (failure: RuntimeException) {
                    if (++rejected <= 3) onLog("audio: datagram rejected (${failure.javaClass.simpleName})")
                } finally {
                    plain?.fill(0)
                }
            }
        } catch (failure: IOException) {
            if (!closed && active()) onLog("audio: receiver ended (${failure.javaClass.simpleName})")
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        streaming = false
        synchronized(stateLock) {
            epoch++
            media.reset()
        }
        runCatching { socket.close() }
        cipher.clear()
        sink.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 65_536
        const val RECEIVE_TIMEOUT_MILLIS = 500
    }
}

/**
 * The `speaker1` text handler on the DMSDP control channel.
 *
 * A small RTSP-shaped exchange, distinct from HiSight: the phone triggers `OPTIONS`, this side
 * answers with `SETUP` naming the UDP port it listens on, the phone's `200` carries the port it will
 * send from, and a later `SET_PARAMETER` states the audio format. Every field is validated against
 * the shape the phone actually sends, and a mismatch ends the audio session rather than the whole
 * control channel — a broken speaker must not take the picture down with it.
 *
 * Ported from `SpeakerControl` (Aki894/CarLink `audio_wire.py`, MIT).
 */
internal class HiCarSpeakerControl(
    private val receiver: HiCarAudioReceiver,
    private val onLog: (String) -> Unit,
) : HiCarControlSession.TextHandler {
    private var session = -1
    private var sequence = 0
    private var setupSequence = 0

    override fun receive(startLine: String, headers: Map<String, String>, body: ByteArray): List<ByteArray>? {
        if (headers["serviceid"] != SERVICE_ID) return null
        val cseq = headers["cseq"]
        val sid = headers["datasessionid"]
        if (headers["servicetype"] != "3" || cseq == null || !CSEQ.matches(cseq) ||
            sid == null || !SESSION_ID.matches(sid) || sid.toLong() > Int.MAX_VALUE
        ) {
            throw HiCarWireException("speaker control fields")
        }
        val id = sid.toInt()
        val replies = ArrayList<ByteArray>()
        val text = String(body, Charsets.US_ASCII)
        val trigger = TRIGGER.find(text)
        if (trigger != null && trigger.range.first == 0 && trigger.range.last == text.length - 1) {
            if (!startLine.startsWith("SET_PARAMETER ")) throw HiCarWireException("speaker trigger state")
            when (trigger.groupValues[1]) {
                "OPTIONS" -> {
                    if (receiver.port !in 1..65535 || headers["simple"] != "1" || id == 0) {
                        throw HiCarWireException("speaker simple mode")
                    }
                    if (id == session) return replies
                    receiver.begin()
                    session = id
                    replies.add(
                        request(
                            "SETUP",
                            "Transport" to "RTP/AVP/UDP;unicast;client_port=${receiver.port}--1",
                            "Simple" to "1",
                            "CryptoVer" to "1",
                            "CryptoAbility" to "222",
                            "CryptoAlg" to "aes128-ctr",
                            "Fillp" to "0",
                            "TCP" to "0",
                            "Codec" to "0",
                        ),
                    )
                    setupSequence = sequence
                    onLog("speaker: SETUP sent (AAC over UDP, encrypted, RTCP off); no audio yet")
                }

                "TEARDOWN", "CLOSE" -> {
                    if (id == session) {
                        if (trigger.groupValues[1] == "TEARDOWN") replies.add(request("TEARDOWN"))
                        session = -1
                        receiver.stopStreaming()
                        onLog("speaker: the phone ended the audio session")
                    }
                }

                else -> throw HiCarWireException("speaker trigger")
            }
            return replies
        }
        if (startLine.startsWith("DMSDP/1.0 ")) {
            if (id != session || cseq.toIntOrNull() != setupSequence) return replies
            if (!startLine.startsWith("DMSDP/1.0 200 ")) throw HiCarWireException("speaker setup refused")
            val transport = headers["transport"] ?: ""
            val match = TRANSPORT.find(transport) ?: throw HiCarWireException("speaker transport")
            if (match.groupValues[1].toIntOrNull() != receiver.port) throw HiCarWireException("speaker transport")
            val port = match.groupValues[2].toIntOrNull() ?: throw HiCarWireException("speaker remote port")
            if (port !in 1..65535) throw HiCarWireException("speaker remote port")
            receiver.startStreaming(port)
            onLog("speaker: UDP transport negotiated")
            return replies
        }
        if (id != session) throw HiCarWireException("speaker session mismatch")
        when {
            startLine.startsWith("SET_PARAMETER ") -> {
                val parameters = headers["audioparameter"] ?: throw HiCarWireException("speaker audio format")
                val match = AUDIO_FORMAT.find(parameters)
                if (match == null || match.range.last != parameters.length - 1 || body.isNotEmpty()) {
                    throw HiCarWireException("speaker audio format")
                }
                replies.add(ack(cseq, id))
                onLog(
                    "speaker: phone audio is AAC ${match.groupValues[1]} Hz, " +
                        "${match.groupValues[2]} channel(s)",
                )
            }

            startLine.startsWith("TEARDOWN ") -> {
                replies.add(ack(cseq, id))
                session = -1
                receiver.stopStreaming()
            }

            startLine.startsWith("GET_PARAMETER ") && body.isEmpty() -> replies.add(ack(cseq, id))
            else -> throw HiCarWireException("speaker request")
        }
        return replies
    }

    private fun request(method: String, vararg extra: Pair<String, String>): ByteArray {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("ServiceID" to SERVICE_ID)
        fields.add("ServiceType" to "3")
        fields.add("DataSessionID" to session.toString())
        fields.add("CSeq" to (++sequence).toString())
        fields.addAll(extra)
        return HiCarControlSession.encodeMessage("$method * DMSDP/1.0", fields)
    }

    private fun ack(cseq: String, id: Int): ByteArray = HiCarControlSession.encodeMessage(
        "DMSDP/1.0 200 OK",
        listOf(
            "ServiceID" to SERVICE_ID,
            "ServiceType" to "3",
            "DataSessionID" to id.toString(),
            "CSeq" to cseq,
        ),
    )

    private companion object {
        const val SERVICE_ID = "speaker1"
        val CSEQ = Regex("[0-9]{1,9}")
        val SESSION_ID = Regex("[0-9]{1,10}")
        val TRIGGER = Regex("msdp_trigger_method:\\s*([A-Z]+)\\s*")
        val TRANSPORT = Regex("RTP/AVP/UDP;unicast;client_port=([0-9]+)--1;server_port=([0-9]+)--1")
        val AUDIO_FORMAT = Regex("SampleRate=(48000|44100|32000|24000|16000|8000);Format=16;ChannelNum=([12]);Codec=0")
    }
}
