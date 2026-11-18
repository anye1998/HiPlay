package com.shilapi.xcertplay.hicar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale

/**
 * Sends the car's microphone to the phone as AAC over an encrypted UDP stream.
 *
 * Off unless the caller says it is allowed: recording audio is a permission and a privacy decision,
 * not a default. Everything is bounded and wiped — the PCM buffer, the encoded frame, the RTP packet
 * and the datagram — because this is the one path that carries live room audio.
 *
 * The phone drives it: its `mic1` control exchange decides when capture starts and where the packets
 * go, and its time-server messages give the RTP timestamp a clock that matches the phone's.
 *
 * Ported from `MicrophoneSender` in huducat586/hicar-android-receiver (MIT).
 */
internal class HiCarMicrophone(
    private val context: Context,
    owner: String,
    peerAddress: String,
    sessionKey: ByteArray,
    private val active: () -> Boolean,
    private val onLog: (String) -> Unit,
) : AutoCloseable {
    private val cipher = HiCarRecordCipher(sessionKey, MAX_DATAGRAM)
    private val peer = InetAddress.getByName(peerAddress)
    private val socket = DatagramSocket(java.net.InetSocketAddress(owner, 0))

    @Volatile private var closed = false
    @Volatile private var recording = false
    @Volatile private var generation = 0
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var peerMicros = 0L
    @Volatile private var peerNanos = 0L

    /** What the UI shows; null while capture has not been attempted. */
    @Volatile var status: String = "麦克风待命"
        private set

    val port: Int get() = socket.localPort
    val allowed: Boolean get() = !closed && active()
    val ready: Boolean get() = allowed

    /** The phone's clock sample, used to timestamp the uplink. */
    fun clock(high: Long, low: Long) {
        val value = ((high and 0xFFFF_FFFFL) shl 32) or (low and 0xFFFF_FFFFL)
        if (value > 0) {
            peerMicros = value
            peerNanos = System.nanoTime()
        }
    }

    fun start(remotePort: Int) {
        if (!allowed || remotePort !in 1..65535) throw HiCarWireException("microphone endpoint")
        stop()
        val token = ++generation
        Thread({ capture(token, remotePort) }, "hicar-microphone").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        generation++
        val current = recorder
        recording = false
        if (current != null) runCatching { current.stop() }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        runCatching { socket.close() }
        cipher.clear()
    }

    private fun nowPeerMicros(): Long =
        if (peerMicros > 0) peerMicros + (System.nanoTime() - peerNanos) / 1_000 else System.nanoTime() / 1_000

    private fun capture(token: Int, remotePort: Int) {
        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        val pcm = ShortArray(1024)
        var raw: ByteArray? = null
        try {
            if (!allowed || generation != token) return
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("microphone format unavailable")
            val captureBuffer = maxOf(minimum * 2, 8192)
            @Suppress("MissingPermission")
            val created = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                captureBuffer,
            )
            record = created
            recorder = created
            if (created.state != AudioRecord.STATE_INITIALIZED) throw IOException("microphone initialization")
            encoder = MediaCodec.createEncoderByType(MIME)
            val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            if (!allowed || generation != token) return
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("microphone capture refused")
            recording = true
            status = "麦克风正在采集"
            onLog("microphone: capturing mono PCM, sending AAC 48 kHz stereo to the current phone only")
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            var packets = 0L
            var sequence = 0
            while (generation == token && allowed) {
                val read = created.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) throw IOException("microphone read")
                val input = encoder.dequeueInputBuffer(10_000)
                if (input < 0) continue
                val buffer = encoder.getInputBuffer(input) ?: throw IOException("microphone encoder input")
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                // The encoder is configured for stereo; the capture is mono, so each sample is
                // written twice rather than being resampled.
                for (index in 0 until read) {
                    buffer.putShort(pcm[index])
                    buffer.putShort(pcm[index])
                }
                encoder.queueInputBuffer(input, 0, read * 4, samples * 1_000_000L / SAMPLE_RATE, 0)
                samples += read
                Arrays.fill(pcm, 0)
                for (attempt in 0 until 8) {
                    val output = encoder.dequeueOutputBuffer(info, 0)
                    if (output < 0) break
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val encoded = encoder.getOutputBuffer(output) ?: continue
                            if (info.size > 8184) throw IOException("microphone encoder output")
                            raw = ByteArray(info.size)
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            encoded.get(raw)
                            val timestamp = HiCarAudioMedia.timestamp(
                                nowPeerMicros(),
                                maxOf(0L, info.presentationTimeUs),
                            )
                            val rtp = HiCarAacUplink.packet(raw, sequence++, timestamp)
                            val packet = HiCarAudioMedia.record(rtp, cipher)
                            try {
                                if (generation != token || !allowed) break
                                socket.send(DatagramPacket(packet, packet.size, peer, remotePort))
                                packets++
                                if (packets == 1L || packets % 500 == 0L) {
                                    onLog("microphone: $packets encrypted AAC packet(s) sent")
                                }
                            } finally {
                                raw?.fill(0)
                                raw = null
                                rtp.fill(0)
                                packet.fill(0)
                            }
                        }
                    } finally {
                        encoder.releaseOutputBuffer(output, false)
                    }
                }
            }
        } catch (failure: IOException) {
            if (generation == token && allowed) {
                status = "麦克风暂不可用，请关闭后重新启用"
                onLog("microphone: capture ended (${failure.message ?: failure.javaClass.simpleName}); the picture continues")
            }
        } catch (failure: RuntimeException) {
            if (generation == token && allowed) {
                status = "麦克风暂不可用，请关闭后重新启用"
                onLog("microphone: capture ended (${failure.javaClass.simpleName}); the picture continues")
            }
        } finally {
            Arrays.fill(pcm, 0)
            raw?.fill(0)
            record?.let { current ->
                runCatching { current.stop() }
                runCatching { current.release() }
            }
            if (recorder === record) recorder = null
            encoder?.let { current ->
                runCatching { current.stop() }
                runCatching { current.release() }
            }
            if (generation == token) {
                recording = false
                if (!status.startsWith("麦克风暂")) status = "麦克风待命"
            }
        }
    }

    companion object {
        private const val MIME = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 48_000
        private const val MAX_DATAGRAM = 65_536

        /** Whether this build may record at all: a runtime permission plus the caller's own switch. */
        fun permitted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * The `mic1` control exchange: the phone decides when the car's microphone opens.
 *
 * Same RTSP shape as the speaker, opposite direction. `403` is answered when microphone support is
 * off, which is a clean refusal rather than a silent stall, and each step checks the previous one so
 * a `PLAY` cannot start capture for a session that never negotiated.
 *
 * Ported from `MicrophoneControl` (MIT).
 */
internal class HiCarMicrophoneControl(
    private val microphone: HiCarMicrophone,
) : HiCarControlSession.TextHandler {
    private var session = -1
    private var sequence = 0
    private var phase = 0
    private var remote = 0
    private val retired = ArrayDeque<Int>()

    override fun receive(startLine: String, headers: Map<String, String>, body: ByteArray): List<ByteArray>? {
        if (headers["serviceid"] != SERVICE_ID) return null
        val cseq = headers["cseq"]
        val sid = headers["datasessionid"]
        if (headers["servicetype"] != "2" || cseq == null || !CSEQ.matches(cseq) ||
            sid == null || !SESSION_ID.matches(sid) || sid.toLong() > Int.MAX_VALUE
        ) {
            throw HiCarWireException("microphone control fields")
        }
        val id = sid.toInt()
        val replies = ArrayList<ByteArray>()
        val text = String(body, Charsets.US_ASCII)
        val trigger = TRIGGER.find(text)
        if (trigger != null && trigger.range.first == 0 && trigger.range.last == text.length - 1) {
            if (!startLine.startsWith("SET_PARAMETER ")) throw HiCarWireException("microphone trigger")
            when (trigger.groupValues[1]) {
                "OPTIONS" -> {
                    if (id == session || retired.contains(id)) return replies
                    if (!microphone.allowed) {
                        replies.add(response("403 Forbidden", cseq, id))
                        return replies
                    }
                    if (id == 0 || microphone.port !in 1..65535 || headers["simple"] !in setOf("0", "1")) {
                        throw HiCarWireException("microphone setup mode")
                    }
                    retire()
                    session = id
                    phase = 1
                    replies.add(
                        HiCarControlSession.encodeMessage(
                            "OPTIONS * DMSDP/1.0",
                            listOf(
                                "ServiceID" to SERVICE_ID,
                                "ServiceType" to "2",
                                "DataSessionID" to sid,
                                "CSeq" to (++sequence).toString(),
                                "Simple" to "0",
                                "CryptoVer" to "1",
                                "CryptoAbility" to "222",
                                "CryptoAlg" to "aes128-ctr",
                                "Fillp" to "0",
                                "TCP" to "0",
                                "Codec" to "0",
                            ),
                        ),
                    )
                }

                "CLOSE", "TEARDOWN" -> {
                    if (id == session) {
                        if (trigger.groupValues[1] == "TEARDOWN") {
                            replies.add(
                                HiCarControlSession.encodeMessage(
                                    "TEARDOWN * DMSDP/1.0",
                                    listOf(
                                        "ServiceID" to SERVICE_ID,
                                        "ServiceType" to "2",
                                        "DataSessionID" to sid,
                                        "CSeq" to (++sequence).toString(),
                                    ),
                                ),
                            )
                        }
                        retire()
                    }
                }

                else -> throw HiCarWireException("microphone trigger")
            }
            return replies
        }
        if (startLine.startsWith("DMSDP/1.0 ")) return replies
        if (id != session) return replies
        when {
            startLine.startsWith("TEARDOWN ") -> {
                replies.add(response("200 OK", cseq, id))
                retire()
            }

            !microphone.allowed -> {
                retire()
                replies.add(response("403 Forbidden", cseq, id))
            }

            startLine.startsWith("SET_PARAMETER ") && headers.containsKey("cryptoswitch") -> {
                if (phase == 0 || headers["cryptover"] != "1" || headers["cryptoswitch"] != "222" ||
                    headers["cryptoalg"] != "aes128-ctr" || body.isNotEmpty()
                ) {
                    throw HiCarWireException("microphone crypto")
                }
                replies.add(response("200 OK", cseq, id, "CryptoVer" to "1"))
                if (phase == 1) phase = 2
            }

            startLine.startsWith("SETUP ") -> {
                if (phase < 2 || body.isNotEmpty()) throw HiCarWireException("microphone setup order")
                val transport = headers["transport"] ?: ""
                val match = TRANSPORT.find(transport) ?: throw HiCarWireException("microphone transport")
                val port = match.groupValues[1].toIntOrNull() ?: throw HiCarWireException("microphone endpoint")
                val rtcp = match.groupValues[2]
                if (port !in 1..65535 || rtcp == "0" || (rtcp != "-1" && (rtcp.toIntOrNull() ?: 0) !in 1..65535)) {
                    throw HiCarWireException("microphone endpoint")
                }
                if (phase > 2 && remote != port) throw HiCarWireException("microphone endpoint")
                remote = port
                if (phase == 2) phase = 3
                replies.add(
                    response(
                        "200 OK",
                        cseq,
                        id,
                        "Transport" to "$transport;server_port=${microphone.port}--1",
                    ),
                )
            }

            startLine.startsWith("PLAY ") -> {
                if (phase < 3 || body.isNotEmpty()) throw HiCarWireException("microphone play order")
                if (phase != 4) {
                    try {
                        microphone.start(remote)
                        phase = 4
                    } catch (failure: IOException) {
                        retire()
                        replies.add(response("503 Service Unavailable", cseq, id))
                        return replies
                    } catch (failure: RuntimeException) {
                        retire()
                        replies.add(response("503 Service Unavailable", cseq, id))
                        return replies
                    }
                }
                replies.add(response("200 OK", cseq, id))
            }

            startLine.startsWith("SET_PARAMETER ") &&
                headers["audioparameter"] == "SampleRate=48000;Format=16;ChannelNum=2;Codec=0" &&
                body.isEmpty() -> replies.add(response("200 OK", cseq, id))

            startLine.startsWith("GET_PARAMETER ") && body.isEmpty() -> replies.add(response("200 OK", cseq, id))
            else -> throw HiCarWireException("microphone request")
        }
        return replies
    }

    private fun response(result: String, seq: String, id: Int, vararg extra: Pair<String, String>): ByteArray {
        val fields = ArrayList<Pair<String, String>>()
        fields.add("ServiceID" to SERVICE_ID)
        fields.add("ServiceType" to "2")
        fields.add("DataSessionID" to id.toString())
        fields.add("CSeq" to seq)
        fields.addAll(extra)
        return HiCarControlSession.encodeMessage("DMSDP/1.0 $result", fields)
    }

    private fun retire() {
        microphone.stop()
        if (session > 0) {
            if (retired.size >= 16) retired.removeFirst()
            retired.addLast(session)
        }
        session = -1
        phase = 0
        remote = 0
    }

    private companion object {
        const val SERVICE_ID = "mic1"
        val CSEQ = Regex("[0-9]{1,9}")
        val SESSION_ID = Regex("[0-9]{1,10}")
        val TRIGGER = Regex("msdp_trigger_method:\\s*([A-Z]+)\\s*")
        val TRANSPORT = Regex("RTP/AVP/UDP;unicast;client_port=([0-9]{1,5})-(-1|[0-9]{1,5})")
    }
}
