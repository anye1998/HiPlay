package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wired data path, without a cable.
 *
 * Nothing here has run against a phone: the head unit has no cable session yet and the emulator has
 * no USB host. What can be pinned down is the part that has to be exactly right — the NTB16 framing
 * the phone parses, the ARP reply it needs before it will send anything, and the IPv4 checks that
 * decide whether a packet is for this car. A mistake in any of these looks, from the driver's seat,
 * like "the cable does nothing".
 */
class HiCarNcmTest {
    private val carIp = byteArrayOf(192.toByte(), 168.toByte(), 66.toByte(), 137.toByte())
    private val carMac = HiCarEthernetEndpoint.DEFAULT_CAR_MAC
    private val phoneMac = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F)
    private val phoneIp = byteArrayOf(192.toByte(), 168.toByte(), 66.toByte(), 100.toByte())

    // ---------------------------------------------------------------- NTB16

    @Test
    fun `one datagram is framed with both headers and a terminator`() {
        val layout = NtbOutLayout.DEFAULT
        assertEquals(12, layout.ndpIndex)
        assertEquals(28, layout.datagramOffset)
        val frame = ByteArray(60) { 0x11 }
        val block = HiCarNtb16.frame(frame, 0, 60, layout, sequence = 7, zlpPacketSize = 512)
        assertEquals(0x484D434E.toLong(), block.u32le(0))
        assertEquals(12, block.u16le(4))
        assertEquals(7, block.u16le(6))
        // 28 + 60 = 88, already a multiple of four.
        assertEquals(88, block.u16le(8))
        assertEquals(12, block.u16le(10))
        assertEquals(0x304D434E.toLong(), block.u32le(12))
        assertEquals(16, block.u16le(16))
        assertEquals(0, block.u16le(18))
        assertEquals(28, block.u16le(20))
        assertEquals(60, block.u16le(22))
        assertEquals(0, block.u16le(24))
        assertEquals(0, block.u16le(26))
        assertEquals(88, block.size)
    }

    @Test
    fun `a short datagram is padded to the ethernet minimum`() {
        val layout = NtbOutLayout.DEFAULT
        val arp = ByteArray(42) { 0x22 }
        val block = HiCarNtb16.frame(arp, 0, 42, layout, sequence = 1, zlpPacketSize = 0)
        // The declared datagram length includes the padding: a peer that drops 42-byte frames would
        // otherwise drop every ARP reply.
        assertEquals(60, block.u16le(22))
        assertEquals(88, block.size)
        for (index in 28 + 42 until 88) assertEquals(0, block[index].toInt())
    }

    @Test
    fun `a block that would end on a full packet gets one extra byte`() {
        val layout = NtbOutLayout.DEFAULT
        // 28 + 60 = 88; with a 44-byte endpoint packet 88 is exactly two packets.
        assertTrue(HiCarNtb16.blockLength(layout, 60, 44) == 89)
        assertEquals(0, HiCarNtb16.blockLength(layout, 60, 0) % 4 - 0)
    }

    @Test
    fun `a block round trips through the parser`() {
        val layout = NtbOutLayout.DEFAULT
        val first = ByteArray(60) { 0x33 }
        val block = HiCarNtb16.frame(first, 0, 60, layout, sequence = 3, zlpPacketSize = 0)
        val seen = ArrayList<ByteArray>()
        val count = HiCarNtb16.parse(block, 0, block.size) { buffer, offset, length ->
            seen.add(buffer.copyOfRange(offset, offset + length))
        }
        assertEquals(1, count)
        assertArrayEquals(first, seen.single())
    }

    @Test
    fun `a malformed block is refused before any datagram is handed out`() {
        val layout = NtbOutLayout.DEFAULT
        val block = HiCarNtb16.frame(ByteArray(60), 0, 60, layout, sequence = 1, zlpPacketSize = 0)
        block[0] = 0
        var delivered = 0
        val failure = runCatching {
            HiCarNtb16.parse(block, 0, block.size) { _, _, _ -> delivered++ }
        }.exceptionOrNull()
        assertTrue(failure is HiCarWireException)
        assertEquals("a bad signature must not deliver half a frame", 0, delivered)
    }

    @Test
    fun `a truncated block is refused`() {
        val layout = NtbOutLayout.DEFAULT
        val block = HiCarNtb16.frame(ByteArray(60), 0, 60, layout, sequence = 1, zlpPacketSize = 0)
        val failure = runCatching { HiCarNtb16.parse(block, 0, 40) { _, _, _ -> } }.exceptionOrNull()
        assertTrue(failure is HiCarWireException)
    }

    @Test
    fun `the layout follows the alignment the phone reports`() {
        val layout = NtbOutLayout.of(4, 0, 4)
        assertEquals(12, layout.ndpIndex)
        assertEquals(28, layout.datagramOffset)
        // An alignment of 8 moves the NDP16 to 16 and the datagram past it.
        val wide = NtbOutLayout.of(8, 0, 8)
        assertEquals(16, wide.ndpIndex)
        assertEquals(32, wide.datagramOffset)
        // Nonsense falls back to the defaults instead of producing an unmeetable offset.
        assertEquals(layout.ndpIndex, NtbOutLayout.of(0, 0, 3).ndpIndex)
    }

    @Test
    fun `the phone's NTB parameters are read from the documented offsets`() {
        val raw = ByteArray(28)
        raw.putU16le(0, 28)
        raw.putU16le(2, 3)
        raw.putU32le(4, 16384)
        raw.putU16le(8, 4)
        raw.putU32le(16, 3568)
        raw.putU16le(20, 4)
        raw.putU16le(24, 4)
        raw.putU16le(26, 16)
        val parameters = NtbParameters.parse(raw)
        assertEquals(3, parameters.formatsSupported)
        assertTrue(parameters.supportsNtb16)
        assertEquals(16384L, parameters.ntbInMaxSize)
        assertEquals(3568L, parameters.ntbOutMaxSize)
        assertEquals(4, parameters.outDivisor)
        assertEquals(4, parameters.outAlignment)
        assertEquals(NtbOutLayout.of(4, 0, 4).datagramOffset, NtbOutLayout.of(
            parameters.outDivisor,
            parameters.outPayloadRemainder,
            parameters.outAlignment,
        ).datagramOffset)
    }

    @Test
    fun `the receive plan stays under the system transfer cap`() {
        // Android 8 caps one bulk transfer at 16 KiB; a larger phone block would be truncated.
        assertEquals(16384, HiCarCdcNcm.receivePlan(16384, 28).receiveSize)
        // No answer from the phone means the system cap, which is the whole NTB16 range on modern Android.
        assertEquals(HiCarCdcNcm.MAX_NTB16_BLOCK, HiCarCdcNcm.receivePlan(null, 28).receiveSize)
        assertEquals(3552, HiCarCdcNcm.receivePlan(3552, 28).receiveSize)
        // An absurd value is clamped to the NTB16 ceiling, which the system can still transfer whole.
        val clamped = HiCarCdcNcm.receivePlan(1_000_000, 28)
        assertEquals(HiCarCdcNcm.MAX_NTB16_BLOCK, clamped.receiveSize)
        assertNull(clamped.setInputSize)
        // On Android 8 or older the transfer cap is 16 KiB, so a bigger phone block has to be cut down
        // before the phone ever sends one.
        val compressed = HiCarCdcNcm.receivePlan(65536, 27)
        assertEquals(HiCarCdcNcm.LEGACY_MAX_BULK_TRANSFER, compressed.receiveSize)
        assertEquals(HiCarCdcNcm.LEGACY_MAX_BULK_TRANSFER, compressed.setInputSize)
        // The size request is a 4-byte little-endian value.
        assertArrayEquals(byteArrayOf(0x00, 0x40, 0, 0), HiCarCdcNcm.encodeNtbInputSize(16384))
    }

    // ---------------------------------------------------------------- Ethernet, ARP, IPv4

    private class Collected : HiCarEthernetEndpoint.Output {
        val toPhone = ArrayList<ByteArray>()
        val toTunnel = ArrayList<ByteArray>()
        override fun sendToPhone(frame: ByteArray, offset: Int, length: Int) {
            toPhone.add(frame.copyOfRange(offset, offset + length))
        }

        override fun deliverToTunnel(packet: ByteArray, offset: Int, length: Int) {
            toTunnel.add(packet.copyOfRange(offset, offset + length))
        }
    }

    private fun arpRequest(target: ByteArray): ByteArray {
        val frame = ByteArray(60)
        HiCarEthernet.writeHeader(frame, 0, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), phoneMac, HiCarEthernet.TYPE_ARP)
        val arp = HiCarEthernet.HEADER_LENGTH
        frame.putU16be(arp, HiCarArp.HARDWARE_ETHERNET)
        frame.putU16be(arp + 2, HiCarEthernet.TYPE_IPV4)
        frame[arp + 4] = 6.toByte()
        frame[arp + 5] = 4.toByte()
        frame.putU16be(arp + 6, HiCarArp.OP_REQUEST)
        System.arraycopy(phoneMac, 0, frame, arp + 8, 6)
        System.arraycopy(phoneIp, 0, frame, arp + 14, 4)
        System.arraycopy(target, 0, frame, arp + 24, 4)
        return frame
    }

    private fun ipv4Packet(source: ByteArray, destination: ByteArray, payload: Int = 4): ByteArray {
        val total = 20 + payload
        val packet = ByteArray(total)
        packet[0] = 0x45.toByte()
        packet.putU16be(2, total)
        packet[8] = 64.toByte()
        packet[9] = HiCarIpv4.PROTOCOL_UDP.toByte()
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        for (index in 0 until payload) packet[20 + index] = 0x5A.toByte()
        packet.putU16be(10, HiCarChecksum.compute(packet, 0, 20))
        return packet
    }

    @Test
    fun `an ARP request for this car is answered with this car's address`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val collected = Collected()
        val result = endpoint.handleFromPhone(arpRequest(carIp), 0, 60, collected)
        assertEquals(HiCarEthernetEndpoint.Inbound.ARP_REPLIED, result)
        val reply = collected.toPhone.single()
        assertEquals(HiCarEthernet.HEADER_LENGTH + HiCarArp.LENGTH, reply.size)
        assertEquals(HiCarEthernet.TYPE_ARP, HiCarEthernet.etherType(reply, 0))
        // Destination is the phone, source is this car.
        assertArrayEquals(phoneMac, reply.copyOfRange(0, 6))
        assertArrayEquals(carMac, reply.copyOfRange(6, 12))
        val arp = HiCarEthernet.HEADER_LENGTH
        assertEquals(HiCarArp.OP_REPLY, reply.u16be(arp + 6))
        assertArrayEquals(carMac, reply.copyOfRange(arp + 8, arp + 14))
        assertArrayEquals(carIp, reply.copyOfRange(arp + 14, arp + 18))
        assertArrayEquals(phoneMac, reply.copyOfRange(arp + 18, arp + 24))
        assertArrayEquals(phoneIp, reply.copyOfRange(arp + 24, arp + 28))
        assertTrue(endpoint.knowsPhone)
        assertArrayEquals(phoneIp, endpoint.phoneAddress)
    }

    @Test
    fun `an ARP for another address is counted but not answered`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val collected = Collected()
        val other = byteArrayOf(192.toByte(), 168.toByte(), 66.toByte(), 1)
        val result = endpoint.handleFromPhone(arpRequest(other), 0, 60, collected)
        assertEquals(HiCarEthernetEndpoint.Inbound.ARP_FOR_OTHER_ADDRESS, result)
        assertTrue(collected.toPhone.isEmpty())
    }

    @Test
    fun `an ARP probe is ignored`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val collected = Collected()
        val frame = arpRequest(carIp)
        // A probe announces 0.0.0.0 as its own address; answering would fight address detection.
        java.util.Arrays.fill(frame, HiCarEthernet.HEADER_LENGTH + 14, HiCarEthernet.HEADER_LENGTH + 18, 0)
        val result = endpoint.handleFromPhone(frame, 0, 60, collected)
        assertEquals(HiCarEthernetEndpoint.Inbound.ARP_IGNORED, result)
        assertTrue(collected.toPhone.isEmpty())
    }

    @Test
    fun `an IPv4 packet for this car goes into the tunnel without its ethernet header`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val collected = Collected()
        val packet = ipv4Packet(phoneIp, carIp)
        val frame = ByteArray(HiCarEthernet.HEADER_LENGTH + packet.size)
        HiCarEthernet.writeHeader(frame, 0, carMac, phoneMac, HiCarEthernet.TYPE_IPV4)
        System.arraycopy(packet, 0, frame, HiCarEthernet.HEADER_LENGTH, packet.size)
        val result = endpoint.handleFromPhone(frame, 0, frame.size, collected)
        assertEquals(HiCarEthernetEndpoint.Inbound.DELIVERED, result)
        assertArrayEquals(packet, collected.toTunnel.single())
        assertArrayEquals(phoneIp, endpoint.phoneAddress)
    }

    @Test
    fun `an IPv4 packet for somebody else is not delivered`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val collected = Collected()
        val packet = ipv4Packet(phoneIp, byteArrayOf(8, 8, 8, 8))
        val frame = ByteArray(HiCarEthernet.HEADER_LENGTH + packet.size)
        HiCarEthernet.writeHeader(frame, 0, carMac, phoneMac, HiCarEthernet.TYPE_IPV4)
        System.arraycopy(packet, 0, frame, HiCarEthernet.HEADER_LENGTH, packet.size)
        assertEquals(HiCarEthernetEndpoint.Inbound.NOT_FOR_US, endpoint.handleFromPhone(frame, 0, frame.size, collected))
        assertTrue(collected.toTunnel.isEmpty())
    }

    @Test
    fun `a packet from the tunnel gets the phone's address in front of it`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        // The phone's MAC is learned from its own frames; without one, nothing can be sent.
        assertNull(endpoint.phoneAddress)
        val early = ipv4Packet(carIp, phoneIp)
        val earlyBuffer = ByteArray(28 + early.size)
        System.arraycopy(early, 0, earlyBuffer, 28, early.size)
        assertEquals(
            HiCarEthernetEndpoint.Outbound.NO_PHONE_YET,
            endpoint.wrapForPhone(earlyBuffer, 28, early.size),
        )
        endpoint.handleFromPhone(arpRequest(carIp), 0, 60, Collected())
        val packet = ipv4Packet(carIp, phoneIp)
        val buffer = ByteArray(28 + packet.size)
        System.arraycopy(packet, 0, buffer, 28, packet.size)
        assertEquals(HiCarEthernetEndpoint.Outbound.WRAPPED, endpoint.wrapForPhone(buffer, 28, packet.size))
        assertArrayEquals(phoneMac, buffer.copyOfRange(14, 20))
        assertArrayEquals(carMac, buffer.copyOfRange(20, 26))
        assertEquals(HiCarEthernet.TYPE_IPV4, buffer.u16be(26))
        assertEquals(HiCarEthernet.HEADER_LENGTH + packet.size, endpoint.frameLength(buffer, 28))
    }

    @Test
    fun `a malformed tunnel packet is dropped rather than framed`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        endpoint.handleFromPhone(arpRequest(carIp), 0, 60, Collected())
        val garbage = ByteArray(20)
        val buffer = ByteArray(28 + garbage.size)
        System.arraycopy(garbage, 0, buffer, 28, garbage.size)
        assertEquals(HiCarEthernetEndpoint.Outbound.DROPPED, endpoint.wrapForPhone(buffer, 28, garbage.size))
    }

    @Test
    fun `the checksum accepts a packet it produced and rejects a changed one`() {
        val packet = ipv4Packet(carIp, phoneIp)
        assertTrue(HiCarChecksum.isValid(packet, 0, 20))
        packet[12] = (packet[12] + 1).toByte()
        assertFalse(HiCarChecksum.isValid(packet, 0, 20))
    }

    @Test
    fun `an IPv6 frame is reported as unsupported, not as malformed`() {
        val endpoint = HiCarEthernetEndpoint(carIp, carMac)
        val frame = ByteArray(60)
        HiCarEthernet.writeHeader(frame, 0, byteArrayOf(0x33, 0x33, 0, 0, 0, 1), phoneMac, HiCarEthernet.TYPE_IPV6)
        assertEquals(
            HiCarEthernetEndpoint.Inbound.UNSUPPORTED_TYPE,
            endpoint.handleFromPhone(frame, 0, 60, Collected()),
        )
    }
}
