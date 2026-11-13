package com.shilapi.xcertplay.hicar

import java.io.ByteArrayOutputStream
import java.io.IOException

/** A framing or negotiation violation. The message is a fixed category, never a payload dump. */
internal class HiCarWireException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * LEB128 with the terminator flag on the high bit of the last byte.
 *
 * This is not the protobuf encoding: every byte except the last has bit 7 clear, and the last byte
 * has bit 7 set. Values are limited to 28 bits, which is what the phone uses for kinds, lengths
 * and tags.
 */
internal object HiCarVarint {
    const val MAX_VALUE = 0x0FFF_FFFF

    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_VALUE) { "HiCar varint range" }
        val out = ByteArrayOutputStream(5)
        var remaining = value
        while (remaining >= 128) {
            out.write(remaining and 0x7F)
            remaining = remaining ushr 7
        }
        out.write(remaining or 0x80)
        return out.toByteArray()
    }
}

/** A cursor over a byte array that returns null instead of throwing when the input is truncated. */
internal class HiCarReader(private val data: ByteArray, var position: Int = 0) {
    val remaining: Int get() = data.size - position

    fun readVarint(): Int? {
        var value = 0
        var shift = 0
        while (shift <= 21) {
            if (position >= data.size) return null
            val next = data[position++].toInt() and 0xFF
            value = value or ((next and 0x7F) shl shift)
            if (next and 0x80 != 0) return value
            shift += 7
        }
        throw HiCarWireException("varint")
    }

    fun readBytes(count: Int): ByteArray? {
        if (count < 0 || count > remaining) return null
        val slice = data.copyOfRange(position, position + count)
        position += count
        return slice
    }
}

/** CRC-16/MODBUS (reflected, polynomial 0xA001, initial value 0), appended little endian. */
internal fun hiCarCrc16(data: ByteArray, end: Int = data.size): Int {
    var crc = 0
    for (index in 0 until end) {
        crc = crc xor (data[index].toInt() and 0xFF)
        repeat(8) {
            crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xA001 else 0)
        }
    }
    return crc
}

internal data class HiCarField(val tag: Int, val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is HiCarField && tag == other.tag && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * tag + value.contentHashCode()
}

/** Length-prefixed tag/value pairs used inside a frame payload. */
internal object HiCarTlv {
    fun encode(tag: Int, value: ByteArray): ByteArray = join(HiCarVarint.encode(tag), HiCarVarint.encode(value.size), value)

    fun fields(data: ByteArray): List<HiCarField> {
        val reader = HiCarReader(data)
        val out = ArrayList<HiCarField>()
        while (reader.remaining > 0) {
            val tag = reader.readVarint() ?: throw HiCarWireException("field truncated")
            val length = reader.readVarint() ?: throw HiCarWireException("field truncated")
            val value = reader.readBytes(length) ?: throw HiCarWireException("field truncated")
            out.add(HiCarField(tag, value))
        }
        return out
    }
}

internal fun join(vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(parts.sumOf { it.size })
    for (part in parts) out.write(part, 0, part.size)
    return out.toByteArray()
}

/** One nearby-wire frame: kind, flags, and an opaque payload. */
internal data class HiCarFrame(val kind: Int, val flags: Int, val payload: ByteArray) {
    val encrypted: Boolean get() = flags and 1 != 0

    override fun equals(other: Any?): Boolean =
        other is HiCarFrame && kind == other.kind && flags == other.flags && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (kind * 31 + flags) * 31 + payload.contentHashCode()
}

internal object HiCarWire {
    const val MAX_FRAME_PAYLOAD = 65_536
    private const val MAX_OPTIONS = 256

    fun encode(frame: HiCarFrame): ByteArray {
        if (frame.payload.size > MAX_FRAME_PAYLOAD) throw HiCarWireException("frame payload")
        val body = join(
            byteArrayOf(HiCarCodes.FRAME_MARKER.toByte()),
            HiCarVarint.encode(frame.kind),
            HiCarVarint.encode(frame.flags),
            HiCarVarint.encode(0),
            HiCarVarint.encode(frame.payload.size),
            frame.payload,
        )
        val crc = hiCarCrc16(body)
        return join(body, byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte()))
    }
}

/**
 * Incremental decoder for [HiCarWire.encode] output.
 *
 * The phone writes arbitrary RFCOMM chunks, so a frame may span reads. A protocol violation is
 * terminal: the buffer is dropped and every later call fails, which keeps a desynchronised stream
 * from being interpreted as valid frames.
 */
internal class HiCarFrameDecoder(private val maxBufferedBytes: Int = 65_536 + 512) {
    private var buffer = ByteArray(0)
    private var failed = false

    fun feed(chunk: ByteArray, count: Int = chunk.size): List<HiCarFrame> {
        if (failed) throw HiCarWireException("decoder failed")
        if (count < 0 || count > chunk.size) throw HiCarWireException("chunk size")
        buffer = join(buffer, chunk.copyOf(count))
        val frames = ArrayList<HiCarFrame>()
        try {
            while (buffer.isNotEmpty()) {
                val parsed = parseFrame() ?: break
                frames.add(parsed)
            }
            if (buffer.size > maxBufferedBytes) throw HiCarWireException("buffer limit")
            return frames
        } catch (failure: IOException) {
            failed = true
            buffer = ByteArray(0)
            throw failure
        }
    }

    private fun parseFrame(): HiCarFrame? {
        if (buffer[0].toInt() and 0xFF != HiCarCodes.FRAME_MARKER) throw HiCarWireException("frame marker")
        val reader = HiCarReader(buffer, 1)
        val kind = reader.readVarint() ?: return null
        val flags = reader.readVarint() ?: return null
        val optionsLength = reader.readVarint() ?: return null
        if (optionsLength > 256) throw HiCarWireException("frame options")
        reader.readBytes(optionsLength) ?: return null
        val length = reader.readVarint() ?: return null
        if (length > HiCarWire.MAX_FRAME_PAYLOAD) throw HiCarWireException("frame payload")
        val payload = reader.readBytes(length) ?: return null
        val checksum = reader.readBytes(2) ?: return null
        val expected = (checksum[0].toInt() and 0xFF) or ((checksum[1].toInt() and 0xFF) shl 8)
        if (hiCarCrc16(buffer, reader.position - 2) != expected) throw HiCarWireException("frame CRC")
        buffer = buffer.copyOfRange(reader.position, buffer.size)
        return HiCarFrame(kind, flags, payload)
    }
}
