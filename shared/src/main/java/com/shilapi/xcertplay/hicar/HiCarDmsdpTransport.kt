package com.shilapi.xcertplay.hicar

/**
 * DMSDP module framing, used on the TCP entry port only (`controlPort + 1`).
 *
 * This is *not* the nearby-wire frame and not the control record: it is `0x7F | module | length |
 * payload | CRC16`, with no kind, no flags and no options. A build that confused the three would
 * fail at the first byte in each direction, which is exactly what happened while the media stage was
 * unimplemented — see docs/HICAR_MIGRATION.md 7.35.
 *
 * Ported from `DmsdpWire` in huducat586/hicar-android-receiver (MIT), itself an adaptation of
 * Aki894/CarLink `dmsdp_wire.py` (MIT).
 */
internal object HiCarDmsdpFrames {
    const val MAX_PAYLOAD = 65_536

    fun encode(module: Int, payload: ByteArray): ByteArray {
        if (payload.size > MAX_PAYLOAD) throw HiCarWireException("DMSDP size")
        val body = join(
            byteArrayOf(HiCarCodes.FRAME_MARKER.toByte()),
            HiCarVarint.encode(module),
            HiCarVarint.encode(payload.size),
            payload,
        )
        val crc = hiCarCrc16(body)
        return join(body, byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte()))
    }
}

internal data class HiCarDmsdpPacket(val module: Int, val payload: ByteArray)

/** Incremental decoder for [HiCarDmsdpFrames]; a violation is terminal, as everywhere else here. */
internal class HiCarDmsdpDecoder {
    private var buffer = ByteArray(0)
    private var failed = false

    fun feed(chunk: ByteArray, count: Int = chunk.size): List<HiCarDmsdpPacket> {
        if (failed) throw HiCarWireException("DMSDP decoder state")
        if (count < 0 || count > chunk.size) throw HiCarWireException("DMSDP decoder state")
        buffer = join(buffer, chunk.copyOf(count))
        val packets = ArrayList<HiCarDmsdpPacket>()
        try {
            while (buffer.isNotEmpty()) {
                val parsed = parse() ?: break
                packets.add(parsed)
            }
            if (buffer.size > HiCarDmsdpFrames.MAX_PAYLOAD + 11) throw HiCarWireException("DMSDP buffer")
            return packets
        } catch (failure: HiCarWireException) {
            failed = true
            buffer = ByteArray(0)
            throw failure
        }
    }

    private fun parse(): HiCarDmsdpPacket? {
        if (buffer[0].toInt() and 0xFF != HiCarCodes.FRAME_MARKER) throw HiCarWireException("DMSDP marker")
        val reader = HiCarReader(buffer, 1)
        val module = reader.readVarint() ?: return null
        val length = reader.readVarint() ?: return null
        if (length > HiCarDmsdpFrames.MAX_PAYLOAD) throw HiCarWireException("DMSDP size")
        val payload = reader.readBytes(length) ?: return null
        val checksum = reader.readBytes(2) ?: return null
        val expected = (checksum[0].toInt() and 0xFF) or ((checksum[1].toInt() and 0xFF) shl 8)
        if (hiCarCrc16(buffer, reader.position - 2) != expected) throw HiCarWireException("DMSDP CRC")
        buffer = buffer.copyOfRange(reader.position, buffer.size)
        return HiCarDmsdpPacket(module, payload)
    }
}

/**
 * Answers the entry-port handshake: module 1, a sixteen-byte all-zero request, and the reply carries
 * the control port in bytes 1 and 2.
 *
 * The phone asks "which port speaks DMSDP control" and will only ever dial what this reply names, so
 * the port is the one the listener actually bound rather than a constant.
 */
internal fun hiCarDmsdpEntryReply(packet: HiCarDmsdpPacket, controlPort: Int): ByteArray {
    if (packet.module != HiCarCodes.DMSDP_MODULE_ENTRY) throw HiCarWireException("DMSDP request")
    if (packet.payload.size != 16 || packet.payload.any { it.toInt() != 0 }) throw HiCarWireException("DMSDP request")
    if (controlPort !in 1024..65535) throw HiCarWireException("DMSDP request")
    val reply = ByteArray(16)
    reply[1] = ((controlPort ushr 8) and 0xFF).toByte()
    reply[2] = (controlPort and 0xFF).toByte()
    return HiCarDmsdpFrames.encode(HiCarCodes.DMSDP_MODULE_ENTRY, reply)
}
