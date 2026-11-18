package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays

/**
 * Turns the video socket's RTP packets back into H.264 access units.
 *
 * The stream is one H.264 elementary stream cut into PES packets, cut again into RTP packets, with
 * the PES payload encrypted and the RTP header left in the clear. So the path is: strip the RTP
 * header, collect the payload until the marker bit, validate the PES header, decrypt the body, and
 * hand the result to MediaCodec as one access unit.
 *
 * A sequence or timestamp break throws the partial access unit away: feeding half an access unit
 * into the decoder produces a picture that is wrong in a way nobody can diagnose from the screen.
 *
 * Ported from `HiSightMedia` in huducat586/hicar-android-receiver (MIT), from Aki894/CarLink
 * `hisight_media.py` (MIT).
 */
internal class HiCarHiSightMedia(sessionKey: ByteArray) : AutoCloseable {
    private val cipher = HiCarRecordCipher(sessionKey, LIMIT)
    private val parts = ByteArrayOutputStream()
    private var sequence = -1
    private var timestamp = -1L

    /** Set when a packet was dropped; the decoder is fed the next keyframe normally. */
    var discontinuities: Long = 0
        private set

    /**
     * [onFrame] receives `(payloadType, rtpTimestamp, accessUnit)`; the array belongs to the caller
     * only for the duration of the call.
     */
    fun packet(packet: ByteArray, onFrame: (Int, Long, ByteArray) -> Unit) {
        if (packet.size < RTP_HEADER_BYTES || (packet[0].toInt() and 0xFF) ushr 6 != 2) {
            throw HiCarWireException("media RTP header")
        }
        if (packet[0].toInt() and 0x20 != 0) throw HiCarWireException("media RTP header")
        val kind = packet[1].toInt() and 0x7F
        if (kind !in 96..98) throw HiCarWireException("media RTP kind")
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        var stamp = 0L
        for (index in 4..7) stamp = (stamp shl 8) or (packet[index].toLong() and 0xFF)
        if ((sequence >= 0 && seq != (sequence + 1) and 0xFFFF) || (timestamp >= 0 && timestamp != stamp)) {
            parts.reset()
            discontinuities++
        }
        sequence = seq
        timestamp = stamp
        var offset = RTP_HEADER_BYTES + (packet[0].toInt() and 0x0F) * 4
        if (packet[0].toInt() and 0x10 != 0) {
            if (kind == 96) {
                if (offset + 4 > packet.size) throw HiCarWireException("media extension")
                offset += 4 + ((packet[offset + 2].toInt() and 0xFF) shl 8) + (packet[offset + 3].toInt() and 0xFF)
            } else {
                offset++
            }
        }
        if (offset >= packet.size || parts.size() + packet.size - offset > LIMIT) {
            throw HiCarWireException("media payload limit")
        }
        parts.write(packet, offset, packet.size - offset)
        if (packet[1].toInt() and 0x80 == 0) return
        val pes = parts.toByteArray()
        parts.reset()
        try {
            if (pes.size < PES_HEADER_BYTES || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) {
                throw HiCarWireException("media PES header")
            }
            if (pes[6].toInt() and 0xFF != 0x84) throw HiCarWireException("media PES header")
            val size = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
            if (size != 0 && size + 6 != pes.size) throw HiCarWireException("media PES length")
            var start = PES_HEADER_BYTES
            if (pes[7].toInt() and 1 != 0 && pes[8].toInt() != 0) start += (pes[8].toInt() and 0xFF) + 1
            if (start + 16 >= pes.size) throw HiCarWireException("media encrypted payload")
            val data = cipher.decrypt(Arrays.copyOfRange(pes, start, pes.size))
            try {
                onFrame(kind, stamp, data)
            } finally {
                data.fill(0)
            }
        } finally {
            pes.fill(0)
        }
    }

    override fun close() {
        cipher.clear()
        parts.reset()
    }

    private companion object {
        const val LIMIT = 2 * 1024 * 1024
        const val RTP_HEADER_BYTES = 12
        const val PES_HEADER_BYTES = 14
    }
}
