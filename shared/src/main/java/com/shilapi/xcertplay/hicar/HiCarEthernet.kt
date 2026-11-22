package com.shilapi.xcertplay.hicar

// Index arithmetic for the data path. Packet headers are big endian (network order); the NCM
// structures are little endian. Callers check bounds first; these do not.

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

internal fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

internal fun ByteArray.putU16be(at: Int, value: Int) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

internal fun ByteArray.putU16le(at: Int, value: Int) {
    this[at] = value.toByte()
    this[at + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.putU32le(at: Int, value: Int) {
    putU16le(at, value and 0xFFFF)
    putU16le(at + 2, value ushr 16)
}

/** Bytes `[a+aOffset, a+aOffset+length)` equal `[b+bOffset, …)`. */
internal fun regionEquals(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, length: Int): Boolean {
    for (i in 0 until length) {
        if (a[aOffset + i] != b[bOffset + i]) return false
    }
    return true
}

internal fun isAllZero(data: ByteArray, offset: Int, length: Int): Boolean {
    for (i in 0 until length) {
        if (data[offset + i].toInt() != 0) return false
    }
    return true
}

/**
 * Internet checksum (RFC 1071): one's-complement sum of big-endian 16-bit words, inverted. An odd
 * length puts the last byte in the high half of the final word. IPv4, ICMP, TCP and UDP all use it;
 * TCP and UDP add the pseudo header.
 */
internal object HiCarChecksum {

    /** The one's-complement sum, not inverted. Accumulated in a Long so a long packet cannot overflow. */
    fun sum(data: ByteArray, offset: Int, length: Int, initial: Long = 0): Long {
        var sum = initial
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += data.u16be(index)
            index += 2
        }
        if (index < end) sum += data.u8(index) shl 8
        return sum
    }

    fun fold(sum: Long): Int {
        var value = sum
        while (value ushr 16 != 0L) value = (value and 0xFFFF) + (value ushr 16)
        return value.toInt()
    }

    fun compute(data: ByteArray, offset: Int, length: Int): Int =
        fold(sum(data, offset, length)).inv() and 0xFFFF

    /** Summing the checksum field along with the data gives 0xFFFF when it is correct. */
    fun isValid(data: ByteArray, offset: Int, length: Int): Boolean = fold(sum(data, offset, length)) == 0xFFFF
}

/**
 * The minimal IPv4 handling the forwarding path needs.
 *
 * This side never rewrites a packet: it validates the header, reads the addresses, and passes the
 * packet on. Every check here is one that would otherwise show up as "the phone's browser does not
 * work" with nothing in the log.
 */
internal object HiCarIpv4 {
    const val MIN_HEADER_LENGTH = 20
    const val ADDRESS_LENGTH = 4

    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    fun version(packet: ByteArray, offset: Int): Int = packet.u8(offset) ushr 4

    fun headerLength(packet: ByteArray, offset: Int): Int = (packet.u8(offset) and 0x0F) * 4

    fun totalLength(packet: ByteArray, offset: Int): Int = packet.u16be(offset + 2)

    fun protocol(packet: ByteArray, offset: Int): Int = packet.u8(offset + 9)

    fun sourceAt(offset: Int): Int = offset + 12

    fun destinationAt(offset: Int): Int = offset + 16

    /**
     * Checks that `packet[offset, offset + available)` starts with a complete IPv4 packet and returns
     * its total length. An Ethernet frame may carry padding, so the total length decides where the
     * packet ends and `available` is only the upper bound.
     */
    fun checkedTotalLength(packet: ByteArray, offset: Int, available: Int, verifyChecksum: Boolean = true): Int {
        if (available < MIN_HEADER_LENGTH) throw HiCarWireException("IPv4 packet shorter than 20 bytes: $available")
        if (version(packet, offset) != 4) {
            throw HiCarWireException("not IPv4: version ${version(packet, offset)}")
        }
        val header = headerLength(packet, offset)
        if (header < MIN_HEADER_LENGTH) throw HiCarWireException("IPv4 header length $header")
        val total = totalLength(packet, offset)
        if (total < header || total > available) {
            throw HiCarWireException("IPv4 total length $total (header $header, available $available)")
        }
        if (verifyChecksum && !HiCarChecksum.isValid(packet, offset, header)) {
            throw HiCarWireException("IPv4 header checksum")
        }
        return total
    }

    /** Four dotted decimal octets, for the log. */
    fun format(address: ByteArray, offset: Int = 0): String =
        "${address.u8(offset)}.${address.u8(offset + 1)}.${address.u8(offset + 2)}.${address.u8(offset + 3)}"
}

/** Ethernet II frame header (14 bytes, big endian): destination MAC | source MAC | ether type. */
internal object HiCarEthernet {
    const val HEADER_LENGTH = 14
    const val MAC_LENGTH = 6

    const val TYPE_IPV4 = 0x0800
    const val TYPE_ARP = 0x0806
    const val TYPE_IPV6 = 0x86DD

    fun etherType(frame: ByteArray, offset: Int): Int = frame.u16be(offset + 12)

    fun writeHeader(buffer: ByteArray, offset: Int, destination: ByteArray, source: ByteArray, etherType: Int) {
        System.arraycopy(destination, 0, buffer, offset, MAC_LENGTH)
        System.arraycopy(source, 0, buffer, offset + MAC_LENGTH, MAC_LENGTH)
        buffer.putU16be(offset + 12, etherType)
    }

    /** A unicast address has the low bit of its first byte clear; multicast and broadcast are not a device. */
    fun isUnicast(mac: ByteArray, offset: Int = 0): Boolean = (mac[offset].toInt() and 0x01) == 0

    /** Log form: the last two octets only, so no full device identifier ends up in a report. */
    fun maskedMac(mac: ByteArray, offset: Int = 0): String =
        "xx:xx:xx:xx:" + hexByte(mac.u8(offset + 4)) + ":" + hexByte(mac.u8(offset + 5))

    private fun hexByte(value: Int): String {
        val digits = "0123456789abcdef"
        return "" + digits[value ushr 4] + digits[value and 0x0F]
    }
}

/**
 * IPv4 ARP (RFC 826) inside an Ethernet frame: 28 bytes, big endian.
 *
 * TUN is a layer-3 device, so the kernel does not answer ARP for it. The phone asks "who has
 * 192.168.66.x" about once a second, and without an answer every packet it sends waits for a
 * resolution that never comes.
 */
internal object HiCarArp {
    const val LENGTH = 28
    const val HARDWARE_ETHERNET = 1
    const val OP_REQUEST = 1
    const val OP_REPLY = 2

    class Packet(
        val operation: Int,
        val senderMac: ByteArray,
        val senderIp: ByteArray,
        val targetMac: ByteArray,
        val targetIp: ByteArray,
    ) {
        /** Address-conflict probe (sender IP all zero): the peer is asking whether an address is free. */
        val isProbe: Boolean get() = isAllZero(senderIp, 0, 4)

        /** Address announcement (sender IP equals target IP). */
        val isAnnouncement: Boolean get() = senderIp.contentEquals(targetIp)
    }

    fun parse(frame: ByteArray, offset: Int, length: Int): Packet {
        if (length < HiCarEthernet.HEADER_LENGTH + LENGTH) {
            throw HiCarWireException("ARP frame shorter than ${HiCarEthernet.HEADER_LENGTH + LENGTH}: $length")
        }
        val arp = offset + HiCarEthernet.HEADER_LENGTH
        if (frame.u16be(arp) != HARDWARE_ETHERNET || frame.u16be(arp + 2) != HiCarEthernet.TYPE_IPV4 ||
            frame.u8(arp + 4) != HiCarEthernet.MAC_LENGTH || frame.u8(arp + 5) != 4
        ) {
            throw HiCarWireException("not IPv4 over Ethernet ARP")
        }
        return Packet(
            operation = frame.u16be(arp + 6),
            senderMac = frame.copyOfRange(arp + 8, arp + 14),
            senderIp = frame.copyOfRange(arp + 14, arp + 18),
            targetMac = frame.copyOfRange(arp + 18, arp + 24),
            targetIp = frame.copyOfRange(arp + 24, arp + 28),
        )
    }

    /** Writes a reply for [request] into `out[outOffset]` and returns the frame length (42, unpadded). */
    fun writeReply(request: Packet, ourMac: ByteArray, out: ByteArray, outOffset: Int): Int {
        HiCarEthernet.writeHeader(out, outOffset, request.senderMac, ourMac, HiCarEthernet.TYPE_ARP)
        val arp = outOffset + HiCarEthernet.HEADER_LENGTH
        out.putU16be(arp, HARDWARE_ETHERNET)
        out.putU16be(arp + 2, HiCarEthernet.TYPE_IPV4)
        out[arp + 4] = HiCarEthernet.MAC_LENGTH.toByte()
        out[arp + 5] = 4
        out.putU16be(arp + 6, OP_REPLY)
        System.arraycopy(ourMac, 0, out, arp + 8, HiCarEthernet.MAC_LENGTH)
        System.arraycopy(request.targetIp, 0, out, arp + 14, 4)
        System.arraycopy(request.senderMac, 0, out, arp + 18, HiCarEthernet.MAC_LENGTH)
        System.arraycopy(request.senderIp, 0, out, arp + 24, 4)
        return HiCarEthernet.HEADER_LENGTH + LENGTH
    }
}

/**
 * The car's minimal Ethernet host: one IPv4 address and one MAC, with the ARP that TUN cannot answer.
 *
 * The phone is the only peer on the link: its MAC is learned from the frames it sends, and every
 * outbound frame goes to it. ARP requests for the car's address are answered here; IPv4 packets for
 * it lose their Ethernet header and go into the tunnel; packets read from the tunnel get a header
 * back.
 *
 * Threading: [handleFromPhone] only on the receive thread, [wrapForPhone] only on the transmit
 * thread; the shared state is the phone's address, published through volatile fields.
 *
 * Ported from `EthernetEndpoint` in GGBondLab/OpenHC (GPL-3.0-only).
 */
internal class HiCarEthernetEndpoint(carAddress: ByteArray, carMac: ByteArray = DEFAULT_CAR_MAC) {

    enum class Inbound {
        /** The phone asked for this car's address; answered. */
        ARP_REPLIED,

        /** An ARP that needs no answer: a reply, a probe or an announcement. */
        ARP_IGNORED,

        /**
         * The phone asked for a different address. Usually it is using another car-info block — for
         * example the one the head unit's own projection app advertises — and looking for that one.
         */
        ARP_FOR_OTHER_ADDRESS,

        /** An IPv4 packet for this car, handed to the tunnel. */
        DELIVERED,

        /** An IPv4 packet for somebody else. */
        NOT_FOR_US,

        /** Neither IPv4 nor ARP, e.g. the phone's IPv6 multicast listener report. */
        UNSUPPORTED_TYPE,

        /** The frame or packet is malformed. */
        MALFORMED,
    }

    enum class Outbound {
        WRAPPED,
        /** The phone's MAC is not known yet: it has not sent an ARP or a packet. */
        NO_PHONE_YET,
        DROPPED,
    }

    /** Where the receive side puts its results. Both calls must finish with the buffer before returning. */
    interface Output {
        fun sendToPhone(frame: ByteArray, offset: Int, length: Int)

        fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int)
    }

    private val carAddress = carAddress.copyOf()
    private val carMac = carMac.copyOf()
    private val arpReply = ByteArray(HiCarEthernet.HEADER_LENGTH + HiCarArp.LENGTH)

    @Volatile private var phoneMac: ByteArray? = null
    @Volatile private var phoneIp: ByteArray? = null

    init {
        require(carAddress.size == HiCarIpv4.ADDRESS_LENGTH) { "car address must be a 4-byte IPv4 address" }
        require(carMac.size == HiCarEthernet.MAC_LENGTH && HiCarEthernet.isUnicast(carMac)) {
            "car MAC must be a 6-byte unicast address"
        }
    }

    /** The phone's IPv4 address, once learned. */
    val phoneAddress: ByteArray? get() = phoneIp?.copyOf()

    val knowsPhone: Boolean get() = phoneMac != null

    fun handleFromPhone(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        if (length < HiCarEthernet.HEADER_LENGTH) return Inbound.MALFORMED
        return when (HiCarEthernet.etherType(frame, offset)) {
            HiCarEthernet.TYPE_ARP -> handleArp(frame, offset, length, output)
            HiCarEthernet.TYPE_IPV4 -> handleIpv4(frame, offset, length, output)
            else -> Inbound.UNSUPPORTED_TYPE
        }
    }

    /**
     * An IPv4 packet read from the tunnel sits at `buffer[packetOffset, …)` with at least 14 bytes of
     * room before it; this writes the Ethernet header there.
     */
    fun wrapForPhone(buffer: ByteArray, packetOffset: Int, packetLength: Int): Outbound {
        require(packetOffset >= HiCarEthernet.HEADER_LENGTH) { "no room for the Ethernet header" }
        try {
            // The kernel's header checksum is always right; recomputing it would only cost time.
            HiCarIpv4.checkedTotalLength(buffer, packetOffset, packetLength, verifyChecksum = false)
        } catch (failure: HiCarWireException) {
            return Outbound.DROPPED
        }
        val mac = phoneMac ?: return Outbound.NO_PHONE_YET
        HiCarEthernet.writeHeader(
            buffer,
            packetOffset - HiCarEthernet.HEADER_LENGTH,
            mac,
            carMac,
            HiCarEthernet.TYPE_IPV4,
        )
        return Outbound.WRAPPED
    }

    /** The whole frame length after [wrapForPhone] succeeded. */
    fun frameLength(buffer: ByteArray, packetOffset: Int): Int =
        HiCarEthernet.HEADER_LENGTH + HiCarIpv4.totalLength(buffer, packetOffset)

    private fun handleArp(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        val arp = try {
            HiCarArp.parse(frame, offset, length)
        } catch (failure: HiCarWireException) {
            return Inbound.MALFORMED
        }
        if (arp.operation != HiCarArp.OP_REQUEST || arp.isProbe || arp.isAnnouncement) return Inbound.ARP_IGNORED
        if (!HiCarEthernet.isUnicast(arp.senderMac)) return Inbound.MALFORMED
        if (!arp.targetIp.contentEquals(carAddress)) return Inbound.ARP_FOR_OTHER_ADDRESS
        learnPhone(arp.senderMac, 0, arp.senderIp, 0)
        val written = HiCarArp.writeReply(arp, carMac, arpReply, 0)
        output.sendToPhone(arpReply, 0, written)
        return Inbound.ARP_REPLIED
    }

    private fun handleIpv4(frame: ByteArray, offset: Int, length: Int, output: Output): Inbound {
        val ip = offset + HiCarEthernet.HEADER_LENGTH
        val total = try {
            HiCarIpv4.checkedTotalLength(frame, ip, length - HiCarEthernet.HEADER_LENGTH)
        } catch (failure: HiCarWireException) {
            return Inbound.MALFORMED
        }
        if (!regionEquals(frame, HiCarIpv4.destinationAt(ip), carAddress, 0, HiCarIpv4.ADDRESS_LENGTH)) {
            return Inbound.NOT_FOR_US
        }
        if (HiCarEthernet.isUnicast(frame, offset + HiCarEthernet.MAC_LENGTH)) {
            learnPhone(frame, offset + HiCarEthernet.MAC_LENGTH, frame, HiCarIpv4.sourceAt(ip))
        }
        output.deliverToTunnel(frame, ip, total)
        return Inbound.DELIVERED
    }

    private fun learnPhone(mac: ByteArray, macOffset: Int, ip: ByteArray, ipOffset: Int) {
        val knownMac = phoneMac
        if (knownMac == null || !regionEquals(knownMac, 0, mac, macOffset, HiCarEthernet.MAC_LENGTH)) {
            phoneMac = mac.copyOfRange(macOffset, macOffset + HiCarEthernet.MAC_LENGTH)
        }
        val knownIp = phoneIp
        if (knownIp == null || !regionEquals(knownIp, 0, ip, ipOffset, HiCarIpv4.ADDRESS_LENGTH)) {
            phoneIp = ip.copyOfRange(ipOffset, ipOffset + HiCarIpv4.ADDRESS_LENGTH)
        }
    }

    companion object {
        /**
         * The car's MAC: locally administered (bit 1 of the first byte) and unicast, so it cannot
         * collide with a vendor-assigned address. The phone re-learns it on every connection with
         * ARP, so a fixed value is fine.
         */
        val DEFAULT_CAR_MAC: ByteArray get() = byteArrayOf(0x02, 0x4F, 0x48, 0x43, 0x00, 0x01)
    }
}
