package com.shilapi.xcertplay.hicar

/**
 * USB CDC-NCM 1.0 framing: the 16-bit transfer block (NTB16) this side sends and receives.
 *
 * A cable is the only way into a head unit whose kernel has no `rndis_host` or `cdc_ncm` binding for
 * the phone, which is the case here: the phone re-enumerates as a NCM device and nothing in the
 * system claims it. So the network has to be driven from user space — and the first thing that means
 * is this framing, because a NCM device does not speak "Ethernet frames", it speaks NTBs containing
 * them.
 *
 * Layout (all little endian):
 *
 * ```
 * NTH16   12 bytes  "NCMH" | wHeaderLength=12 | wSequence | wBlockLength | wNdpIndex
 * NDP16   8+4n      "NCM0" | wLength | wNextNdpIndex | n × {wDatagramIndex, wDatagramLength} | {0,0}
 * datagram          one Ethernet frame, no FCS
 * ```
 *
 * Ported from `Ntb16` in GGBondLab/OpenHC (GPL-3.0-only).
 */
internal object HiCarNtb16 {
    const val NTH16_LENGTH = 12
    const val NDP16_HEADER_LENGTH = 8
    const val NDP16_ENTRY_LENGTH = 4

    /** An NDP16 with one entry and its terminator; also the smallest legal NDP16. */
    const val SINGLE_NDP16_LENGTH = NDP16_HEADER_LENGTH + 2 * NDP16_ENTRY_LENGTH

    /** "NCMH" little endian. */
    const val NTH16_SIGNATURE = 0x484D434E

    /** "NCM0": datagrams without CRC. The host never opens CRC mode, so this is the only form read. */
    const val NDP16_SIGNATURE = 0x304D434E

    /** `wBlockLength` is 16 bits. */
    const val MAX_BLOCK_LENGTH = 0xFFFF

    /**
     * The shortest Ethernet frame, without FCS.
     *
     * An ARP reply is 42 bytes; some peers drop anything shorter than 60, so the sender pads. The
     * padding is inside the datagram, not after it: `wDatagramLength` includes it.
     */
    const val MIN_DATAGRAM_LENGTH = 60

    /** An Ethernet header; anything shorter is not a frame. */
    const val MIN_ETHERNET_FRAME = 14

    /** Bound on the NDP16 chain; only one is ever seen, and this stops a `wNextNdpIndex` loop. */
    const val MAX_NDP_COUNT = 16

    /** Hands out each datagram of a received block; `offset`/`length` point into the caller's buffer. */
    fun interface DatagramSink {
        fun onDatagram(buffer: ByteArray, offset: Int, length: Int)
    }

    /**
     * The block length for one datagram, including padding.
     *
     * @param zlpPacketSize the send endpoint's maximum packet size. When the block is an exact
     *   multiple of it, one zero byte is appended: otherwise the last packet is a full one and the
     *   receiver waits for a zero-length packet to consider the transfer finished. 0 disables this.
     */
    fun blockLength(layout: NtbOutLayout, datagramLength: Int, zlpPacketSize: Int): Int {
        require(datagramLength >= 0) { "datagram length" }
        require(zlpPacketSize >= 0) { "endpoint packet size" }
        val payload = maxOf(datagramLength, MIN_DATAGRAM_LENGTH)
        var block = layout.datagramOffset + payload
        block += (4 - (block and 3)) and 3
        if (zlpPacketSize > 0 && block % zlpPacketSize == 0) block += 1
        return block
    }

    /**
     * Frames in place: the datagram is already at `buffer[layout.datagramOffset, …)`, and this writes
     * the NTH16, the NDP16 and the padding, returning the block length.
     */
    fun frameInPlace(
        buffer: ByteArray,
        layout: NtbOutLayout,
        datagramLength: Int,
        sequence: Int,
        zlpPacketSize: Int,
    ): Int {
        require(datagramLength >= MIN_ETHERNET_FRAME) { "datagram shorter than an Ethernet header" }
        val block = blockLength(layout, datagramLength, zlpPacketSize)
        require(block <= MAX_BLOCK_LENGTH) { "block does not fit an NTB16: $block bytes" }
        require(block <= buffer.size) { "buffer too small: $block bytes needed, ${buffer.size} available" }
        val payload = maxOf(datagramLength, MIN_DATAGRAM_LENGTH)

        // Clear everything that is not the datagram: the gap between the headers, and the padding.
        buffer.fill(0, NTH16_LENGTH, layout.datagramOffset)
        buffer.fill(0, layout.datagramOffset + datagramLength, block)

        buffer.putU32le(0, NTH16_SIGNATURE)
        buffer.putU16le(4, NTH16_LENGTH)
        buffer.putU16le(6, sequence and 0xFFFF)
        buffer.putU16le(8, block)
        buffer.putU16le(10, layout.ndpIndex)

        val ndp = layout.ndpIndex
        buffer.putU32le(ndp, NDP16_SIGNATURE)
        buffer.putU16le(ndp + 4, SINGLE_NDP16_LENGTH)
        buffer.putU16le(ndp + 6, 0)
        buffer.putU16le(ndp + 8, layout.datagramOffset)
        buffer.putU16le(ndp + 10, payload)
        // The {0,0} terminator is already zero from the fill above.
        return block
    }

    /** A new block holding `datagram[offset, offset + length)`. */
    fun frame(
        datagram: ByteArray,
        offset: Int,
        length: Int,
        layout: NtbOutLayout,
        sequence: Int,
        zlpPacketSize: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= datagram.size) { "range" }
        val out = ByteArray(blockLength(layout, length, zlpPacketSize))
        System.arraycopy(datagram, offset, out, layout.datagramOffset, length)
        frameInPlace(out, layout, length, sequence, zlpPacketSize)
        return out
    }

    /**
     * Splits one received block, handing every datagram to [sink] in order and returning the count.
     *
     * The whole block is validated before the first datagram is handed out, so a malformed block
     * never delivers half a frame. Only the fields that affect reading are checked (signatures,
     * lengths, offsets); outbound alignment is the sender's business.
     */
    fun parse(block: ByteArray, offset: Int, length: Int, sink: DatagramSink): Int {
        require(offset >= 0 && length >= 0 && offset + length <= block.size) { "range" }
        val limit = checkedBlockLimit(block, offset, length)
        val count = walk(block, offset, limit, null)
        walk(block, offset, limit, sink)
        return count
    }

    /** Validates the NTH16 and returns the block's effective length relative to [offset]. */
    private fun checkedBlockLimit(block: ByteArray, offset: Int, length: Int): Int {
        if (length < NTH16_LENGTH) throw HiCarWireException("NTB shorter than its header: $length bytes")
        if (block.u32le(offset) != NTH16_SIGNATURE.toLong()) throw HiCarWireException("NTH16 signature")
        val headerLength = block.u16le(offset + 4)
        if (headerLength != NTH16_LENGTH) throw HiCarWireException("NTH16 header length is not 12: $headerLength")
        val blockLength = block.u16le(offset + 8)
        // Zero means "the block is ended by a short packet"; the transfer length is the block length.
        if (blockLength == 0) return length
        if (blockLength < NTH16_LENGTH) throw HiCarWireException("NTB block length too small: $blockLength")
        if (blockLength > length) {
            throw HiCarWireException("NTB incomplete: $blockLength declared, $length received")
        }
        return blockLength
    }

    /**
     * Walks the NDP16 chain. With a null sink this only validates and counts; offsets are relative to
     * [base] and bounded by [limit].
     */
    private fun walk(block: ByteArray, base: Int, limit: Int, sink: DatagramSink?): Int {
        var ndp = block.u16le(base + 10)
        var ndpCount = 0
        var datagrams = 0
        while (true) {
            if (ndp < NTH16_LENGTH || ndp + NDP16_HEADER_LENGTH > limit) {
                throw HiCarWireException("NDP16 offset out of bounds: $ndp (block $limit)")
            }
            if (++ndpCount > MAX_NDP_COUNT) throw HiCarWireException("more than $MAX_NDP_COUNT NDP16 blocks")
            if (block.u32le(base + ndp) != NDP16_SIGNATURE.toLong()) {
                throw HiCarWireException("NDP16 signature at $ndp")
            }
            val ndpLength = block.u16le(base + ndp + 4)
            if (ndpLength < SINGLE_NDP16_LENGTH || ndp + ndpLength > limit) {
                throw HiCarWireException("NDP16 length $ndpLength at $ndp (block $limit)")
            }
            val next = block.u16le(base + ndp + 6)
            var entry = ndp + NDP16_HEADER_LENGTH
            val entriesEnd = ndp + ndpLength
            while (entry + NDP16_ENTRY_LENGTH <= entriesEnd) {
                val index = block.u16le(base + entry)
                val entryLength = block.u16le(base + entry + 2)
                // The terminator is {0,0}; either half being zero ends the list (offset 0 is inside
                // the NTH16, and length 0 has nothing in it).
                if (index == 0 || entryLength == 0) break
                if (entryLength < MIN_ETHERNET_FRAME) {
                    throw HiCarWireException("datagram shorter than an Ethernet header: $entryLength at $index")
                }
                if (index < NTH16_LENGTH || index + entryLength > limit) {
                    throw HiCarWireException("datagram out of bounds: $index + $entryLength (block $limit)")
                }
                sink?.onDatagram(block, base + index, entryLength)
                datagrams++
                entry += NDP16_ENTRY_LENGTH
            }
            if (next == 0) return datagrams
            if (next == ndp) throw HiCarWireException("NDP16 chain points at itself: $ndp")
            ndp = next
        }
    }
}

/**
 * Where the NDP16 and the datagram sit in an outbound block.
 *
 * Derived from the alignment the phone reports in `GET_NTB_PARAMETERS`: the NDP16 offset is a
 * multiple of `wNdpOutAlignment`, and the datagram offset satisfies
 * `offset % wNdpOutDivisor == wNdpOutPayloadRemainder`. The phone in this car reports 4/0/4, which
 * puts the NDP16 at 12 and the datagram at 28.
 */
internal class NtbOutLayout private constructor(val ndpIndex: Int, val datagramOffset: Int) {

    override fun toString(): String = "NtbOutLayout(ndp=$ndpIndex, datagram=$datagramOffset)"

    companion object {
        const val DEFAULT_DIVISOR = 4
        const val DEFAULT_REMAINDER = 0
        const val DEFAULT_ALIGNMENT = 4

        /** Upper bound on the alignment: real devices report small values, and a huge one cannot be met. */
        const val MAX_ALIGNMENT = 256

        val DEFAULT: NtbOutLayout = of(DEFAULT_DIVISOR, DEFAULT_REMAINDER, DEFAULT_ALIGNMENT)

        /**
         * The layout for the phone's reported alignment. [alignment] has to be a power of two ≥ 4 and
         * [divisor] ≥ 1; values outside that are replaced by the defaults ([isValid] tells which).
         */
        fun of(divisor: Int, remainder: Int, alignment: Int): NtbOutLayout {
            val d = if (divisor in 1..MAX_ALIGNMENT) divisor else DEFAULT_DIVISOR
            val a = if (isValidAlignment(alignment)) alignment else DEFAULT_ALIGNMENT
            // Kotlin's mod has the divisor's sign; java.lang.Math.floorMod is not on old Android.
            val r = remainder.mod(d)
            val ndp = alignUp(HiCarNtb16.NTH16_LENGTH, a)
            val minimum = ndp + HiCarNtb16.SINGLE_NDP16_LENGTH
            val datagram = minimum + (r - minimum).mod(d)
            return NtbOutLayout(ndp, datagram)
        }

        fun isValid(divisor: Int, alignment: Int): Boolean =
            divisor in 1..MAX_ALIGNMENT && isValidAlignment(alignment)

        private fun isValidAlignment(alignment: Int): Boolean =
            alignment in 4..MAX_ALIGNMENT && (alignment and (alignment - 1)) == 0

        private fun alignUp(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment
    }
}

/** `GET_NTB_PARAMETERS` (28 bytes, little endian); In is phone → car, Out is car → phone. */
internal class NtbParameters(
    val formatsSupported: Int,
    val ntbInMaxSize: Long,
    val ntbOutMaxSize: Long,
    val outDivisor: Int,
    val outPayloadRemainder: Int,
    val outAlignment: Int,
    val outMaxDatagrams: Int,
) {
    val supportsNtb16: Boolean get() = (formatsSupported and 0x01) != 0

    companion object {
        const val LENGTH = 28

        fun parse(data: ByteArray, length: Int = data.size): NtbParameters {
            if (length < LENGTH) throw HiCarWireException("NTB parameters shorter than $LENGTH: $length")
            // Skip wLength (2), then the formats; the In-direction alignment is not needed to receive.
            return NtbParameters(
                formatsSupported = data.u16le(2),
                ntbInMaxSize = data.u32le(4),
                ntbOutMaxSize = data.u32le(16),
                outDivisor = data.u16le(20),
                outPayloadRemainder = data.u16le(22),
                outAlignment = data.u16le(24),
                outMaxDatagrams = data.u16le(26),
            )
        }
    }
}

/** The class requests a NCM device answers, and how big a receive buffer to use. */
internal object HiCarCdcNcm {
    /** Device → host, class request, interface recipient. */
    const val REQUEST_TYPE_IN = 0xA1

    /** Host → device, class request, interface recipient. */
    const val REQUEST_TYPE_OUT = 0x21

    const val GET_NTB_PARAMETERS = 0x80
    const val SET_NTB_INPUT_SIZE = 0x86

    /** `bulkTransfer` was capped at 16 KiB before Android 9. */
    const val LEGACY_MAX_BULK_TRANSFER = 16384

    /** An NTB16 block length is 16 bits. */
    const val MAX_NTB16_BLOCK = 65536

    /** The smallest NTB the specification allows. */
    const val MIN_NTB_SIZE = 2048

    /** The 4-byte payload of SET_NTB_INPUT_SIZE: dwNtbInMaxSize, little endian. */
    fun encodeNtbInputSize(size: Int): ByteArray {
        require(size >= MIN_NTB_SIZE) { "NTB input size below $MIN_NTB_SIZE: $size" }
        return byteArrayOf(
            (size and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 24) and 0xFF).toByte(),
        )
    }

    /**
     * How large a receive buffer to use, and whether the phone has to be told to send smaller blocks.
     *
     * One bulk transfer is one NTB, so a phone whose block is larger than the system's transfer cap
     * would have its blocks truncated — which reads as corrupt frames, not as a size problem.
     *
     * @param deviceInMax the phone's dwNtbInMaxSize, or null when the request failed
     */
    fun receivePlan(deviceInMax: Long?, sdkInt: Int): ReceivePlan {
        val systemMax = if (sdkInt < 28) LEGACY_MAX_BULK_TRANSFER else MAX_NTB16_BLOCK
        if (deviceInMax == null) return ReceivePlan(systemMax, null)
        val device = deviceInMax.coerceIn(MIN_NTB_SIZE.toLong(), MAX_NTB16_BLOCK.toLong()).toInt()
        return if (device > systemMax) ReceivePlan(systemMax, systemMax) else ReceivePlan(device, null)
    }

    /** @property setInputSize the value for SET_NTB_INPUT_SIZE, or null when not needed. */
    data class ReceivePlan(val receiveSize: Int, val setInputSize: Int?)
}
