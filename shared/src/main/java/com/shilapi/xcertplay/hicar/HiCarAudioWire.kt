package com.shilapi.xcertplay.hicar

import java.io.IOException
import java.util.Arrays

/** One AAC access unit recovered from the phone's audio stream. */
internal class HiCarAudioFrame(
    val data: ByteArray,
    val sampleRate: Int,
    val rateIndex: Int,
    val channels: Int,
    val samples: Int,
    val stream: Int,
    /** What the phone says this sound is for, from the RTP SSRC. */
    val route: HiCarAudioRoute = HiCarAudioRoute.MEDIA,
) {
    fun wipe() = data.fill(0)
}

/**
 * The phone's audio stream: encrypted UDP records carrying RTP carrying ADTS.
 *
 * Each datagram is `0xAB | length(2) | AES-CTR record`; the decrypted part is a normal RTP packet
 * whose payload is an ADTS stream. ADTS frames are decoded here rather than in the sink because the
 * sample rate and channel count live in their headers, and the sink needs both before it can build
 * an `AudioTrack`.
 *
 * Reordering is handled per SSRC with a small window: audio that arrives out of order is held until
 * its turn, and a gap wider than [MAX_REORDER] is accepted as a loss instead of stalling playback
 * forever.
 *
 * Ported from `AudioMedia` in huducat586/hicar-android-receiver (MIT), from Aki894/CarLink
 * `audio_media.py` (MIT).
 */
internal class HiCarAudioMedia(private val onLog: (String) -> Unit = {}) {
    private class Packet(val timestamp: Long, val frames: List<HiCarAudioFrame>?)
    private class Stream(var expected: Int) {
        var timestamp = -1L
        var rate = 0
        val pending = HashMap<Int, Packet>()
    }

    private val streams = HashMap<Int, Stream>()
    private var probes = 0

    var packets: Long = 0
        private set
    var frames: Long = 0
        private set
    var lost: Long = 0
        private set
    var duplicates: Long = 0
        private set

    @Synchronized
    fun receive(rtp: ByteArray): List<HiCarAudioFrame> {
        if (rtp.size < RTP_HEADER_BYTES || (rtp[0].toInt() and 0xFF) ushr 6 != 2) {
            throw HiCarWireException("audio RTP header")
        }
        if (rtp[1].toInt() and 0x7F != 96) throw HiCarWireException("audio RTP header")
        val sequence = ((rtp[2].toInt() and 0xFF) shl 8) or (rtp[3].toInt() and 0xFF)
        var timestamp = 0L
        for (index in 4..7) timestamp = (timestamp shl 8) or (rtp[index].toLong() and 0xFF)
        // The whole SSRC carries two fields the phone fills in: the audio bus in the low half and
        // the Android usage in the high half. The old code kept only twelve bits for reordering,
        // which was enough to split streams but threw the "what is this sound for" part away.
        var ssrc = 0L
        for (index in 8..11) ssrc = (ssrc shl 8) or (rtp[index].toLong() and 0xFF)
        val bus = HiCarAudioBus.busOf(ssrc)
        val route = HiCarAudioRoute.of(HiCarAudioBus.usageOf(ssrc), bus)
        // The native stream key is the low twelve bits of the SSRC.
        val streamId = ((rtp[10].toInt() and 0x0F) shl 8) or (rtp[11].toInt() and 0xFF)
        var start = RTP_HEADER_BYTES + (rtp[0].toInt() and 0x0F) * 4
        var end = rtp.size
        if (start > end) throw HiCarWireException("audio CSRC")
        if (rtp[0].toInt() and 0x10 != 0) {
            if (start + 4 > end) throw HiCarWireException("audio extension")
            start += 4 + 4 * (((rtp[start + 2].toInt() and 0xFF) shl 8) or (rtp[start + 3].toInt() and 0xFF))
        }
        if (rtp[0].toInt() and 0x20 != 0) {
            val padding = rtp[rtp.size - 1].toInt() and 0xFF
            if (padding == 0 || padding > end - start) throw HiCarWireException("audio padding")
            end -= padding
        }
        if (start >= end) throw HiCarWireException("audio empty RTP")
        if (probes < PROBE_PACKETS && HiCarDebug.enabled("debug.hicar.audio")) {
            probes++
            // The one place a per-packet purpose marker could hide is the RTP header itself, and it
            // is thrown away by every other line of this parser.
            onLog(
                "audio rtp: ssrc=$streamId csrc=${rtp[0].toInt() and 0x0F} " +
                    "extension=${rtp[0].toInt() and 0x10 != 0} marker=${rtp[1].toInt() and 0x80 != 0} " +
                    "payloadType=${rtp[1].toInt() and 0x7F}",
            )
        }
        val decoded = adts(rtp, start, end, streamId, route)
        val state = streams.getOrPut(streamId) {
            if (streams.size >= MAX_STREAMS) throw HiCarWireException("audio stream limit")
            Stream(sequence)
        }
        val distance = (sequence - state.expected) and 0xFFFF
        if (distance >= 32_768 || state.pending.containsKey(sequence)) {
            duplicates++
            decoded.forEach { it.wipe() }
            return emptyList()
        }
        if (distance > MAX_REORDER) {
            lost += distance
            state.expected = sequence
            state.pending.values.forEach { packet -> packet.frames?.forEach { it.wipe() } }
            state.pending.clear()
        }
        state.pending[sequence] = Packet(timestamp, decoded)
        if (state.pending.size >= MAX_REORDER && !state.pending.containsKey(state.expected)) {
            // The missing packet is not coming: jump to the nearest one and count the gap.
            val nearest = state.pending.keys.minByOrNull { (it - state.expected) and 0xFFFF } ?: sequence
            lost += (nearest - state.expected) and 0xFFFF
            state.expected = nearest
        }
        val ready = ArrayList<HiCarAudioFrame>()
        while (true) {
            val packet = state.pending.remove(state.expected) ?: break
            packets++
            val inPacket = packet.frames ?: continue
            for (frame in inPacket) {
                if (state.timestamp != packet.timestamp || state.rate != frame.sampleRate) {
                    state.timestamp = packet.timestamp
                    state.rate = frame.sampleRate
                }
                ready.add(frame)
                frames++
            }
            state.expected = (state.expected + 1) and 0xFFFF
        }
        return ready
    }

    /**
     * The SSRC-derived stream ids this session has carried, for diagnostics.
     *
     * The phone can send several: navigation guidance and music are separate streams on the same
     * UDP port, told apart only by the low twelve bits of the RTP SSRC. Nothing in the session
     * setup says which is which, so the ids have to be observed before they can be routed.
     */
    @Synchronized
    fun streamIds(): List<Int> = streams.keys.sorted()

    @Synchronized
    fun reset() {
        for (stream in streams.values) {
            for (packet in stream.pending.values) packet.frames?.forEach { it.wipe() }
        }
        streams.clear()
    }

    private fun adts(
        payload: ByteArray,
        start: Int,
        end: Int,
        streamId: Int,
        route: HiCarAudioRoute,
    ): List<HiCarAudioFrame> {
        val output = ArrayList<HiCarAudioFrame>()
        var at = start
        while (at < end) {
            if (end - at < 7 || payload[at].toInt() and 0xFF != 0xFF || (payload[at + 1].toInt() and 0xF6) != 0xF0) {
                throw HiCarWireException("audio ADTS header")
            }
            val profile = ((payload[at + 2].toInt() ushr 6) and 3) + 1
            val rate = (payload[at + 2].toInt() ushr 2) and 0x0F
            val channels = ((payload[at + 2].toInt() and 1) shl 2) or ((payload[at + 3].toInt() ushr 6) and 3)
            val length = ((payload[at + 3].toInt() and 3) shl 11) or
                ((payload[at + 4].toInt() and 0xFF) shl 3) or
                ((payload[at + 5].toInt() ushr 5) and 7)
            val header = if (payload[at + 1].toInt() and 1 != 0) 7 else 9
            if (profile != 2 || rate >= RATES.size || (channels != 1 && channels != 2) ||
                (payload[at + 6].toInt() and 3) != 0 || length <= header || length > 8191 || at + length > end
            ) {
                throw HiCarWireException("audio ADTS format")
            }
            output.add(
                HiCarAudioFrame(
                    data = Arrays.copyOfRange(payload, at + header, at + length),
                    sampleRate = RATES[rate],
                    rateIndex = rate,
                    channels = channels,
                    samples = 1024,
                    stream = streamId,
                    route = route,
                ),
            )
            at += length
        }
        if (output.isEmpty()) throw HiCarWireException("audio empty payload")
        return output
    }

    companion object {
        const val RTP_HEADER_BYTES = 12
        private const val MAX_STREAMS = 8
        private const val PROBE_PACKETS = 3
        private const val MAX_REORDER = 16

        /** ADTS sample-rate table, indexed by the four bits in the header. */
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** Strips the UDP record: `0xAB | length(2) | ciphertext`. */
        fun decrypt(record: ByteArray, cipher: HiCarRecordCipher): ByteArray {
            if (record.size < 20 || record[0].toInt() and 0xFF != 0xAB) throw HiCarWireException("audio UDP record")
            val size = ((record[1].toInt() and 0xFF) shl 8) or (record[2].toInt() and 0xFF)
            if (size != record.size - 3) throw HiCarWireException("audio UDP record")
            return cipher.decrypt(Arrays.copyOfRange(record, 3, record.size))
        }

        /** Packs one encrypted UDP datagram out of an RTP packet. */
        fun record(rtp: ByteArray, cipher: HiCarRecordCipher): ByteArray {
            val encrypted = cipher.encrypt(rtp)
            return join(
                byteArrayOf(0xAB.toByte(), ((encrypted.size ushr 8) and 0xFF).toByte(), (encrypted.size and 0xFF).toByte()),
                encrypted,
            )
        }

        /** RTP timestamp for a 48 kHz AAC frame, in the phone's clock domain. */
        fun timestamp(peerMicros: Long, ptsMicros: Long): Long =
            ((peerMicros / 100 + ptsMicros / 100) * 9 + (peerMicros % 100 + ptsMicros % 100) * 9 / 100) and 0xFFFF_FFFFL
    }
}

/**
 * The phone's microphone uplink: AAC access units in an RTP packet, then an encrypted UDP record.
 *
 * The header is fixed apart from the sequence number and timestamp: payload type 96, SSRC 1100
 * (the phone's "virtual microphone" stream), and a six-byte fragment header the phone uses to place
 * each unit. Ported from `MicrophoneWire` (MIT).
 */
internal class HiCarMicrophoneWire {
    fun packet(raw: ByteArray, sequence: Int, timestamp: Long): ByteArray {
        if (raw.isEmpty() || raw.size > 8184) throw HiCarWireException("microphone AAC size")
        val length = raw.size + 7
        val out = ByteArray(12 + length)
        out[0] = 0x80.toByte()
        out[1] = 96
        out[2] = (sequence ushr 8).toByte()
        out[3] = sequence.toByte()
        for (index in 0..3) out[4 + index] = (timestamp ushr (24 - index * 8)).toByte()
        out[10] = 4
        out[11] = 76
        var at = 12
        out[at] = 0xFF.toByte()
        out[at + 1] = 0xF1.toByte()
        out[at + 2] = 76
        out[at + 3] = (0x80 or (length ushr 11)).toByte()
        out[at + 4] = (length ushr 3).toByte()
        out[at + 5] = (((length and 7) shl 5) or 31).toByte()
        out[at + 6] = 0xFC.toByte()
        System.arraycopy(raw, 0, out, 19, raw.size)
        return out
    }
}

/** Builds the ADTS-ish AAC packets the phone expects on the microphone uplink. */
internal object HiCarAacUplink {
    private val wire = HiCarMicrophoneWire()

    fun packet(raw: ByteArray, sequence: Int, timestamp: Long): ByteArray = wire.packet(raw, sequence, timestamp)
}
