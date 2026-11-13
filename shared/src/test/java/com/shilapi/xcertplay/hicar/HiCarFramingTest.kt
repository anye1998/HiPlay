package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiCarFramingTest {
    @Test
    fun varintTerminatorIsTheHighBitOfTheLastByte() {
        assertArrayEquals(byteArrayOf(0x80.toByte()), HiCarVarint.encode(0))
        assertArrayEquals(byteArrayOf(0xFF.toByte()), HiCarVarint.encode(127))
        assertArrayEquals(byteArrayOf(0x00, 0x81.toByte()), HiCarVarint.encode(128))
        assertArrayEquals(byteArrayOf(0x7F, 0x81.toByte()), HiCarVarint.encode(255))
    }

    @Test
    fun varintRoundTripsThroughTheReader() {
        for (value in listOf(0, 1, 127, 128, 300, 16_383, 16_384, HiCarVarint.MAX_VALUE)) {
            val reader = HiCarReader(HiCarVarint.encode(value))
            assertEquals(value, reader.readVarint())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun readerReportsTruncationInsteadOfGuessing() {
        assertNull(HiCarReader(byteArrayOf(0x00)).readVarint())
        assertNull(HiCarReader(byteArrayOf(0x01)).readBytes(4))
    }

    @Test
    fun frameRoundTripsAndDetectsCorruption() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = HiCarWire.encode(HiCarFrame(4, 0, payload))
        assertEquals(HiCarCodes.FRAME_MARKER.toByte(), encoded[0])

        val decoded = HiCarFrameDecoder().feed(encoded)
        assertEquals(1, decoded.size)
        assertEquals(4, decoded[0].kind)
        assertEquals(0, decoded[0].flags)
        assertArrayEquals(payload, decoded[0].payload)
        assertFalse(decoded[0].encrypted)

        val damaged = encoded.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        try {
            HiCarFrameDecoder().feed(damaged)
            fail("A frame with a bad checksum must be rejected")
        } catch (expected: HiCarWireException) {
            assertEquals("frame CRC", expected.message)
        }
    }

    @Test
    fun decoderKeepsPartialFramesUntilTheRestArrives() {
        val first = HiCarWire.encode(HiCarFrame(0, 0, HiCarTlv.encode(1, byteArrayOf(2))))
        val second = HiCarWire.encode(HiCarFrame(0, 0, HiCarTlv.encode(3, "abc".toByteArray())))
        val stream = first + second
        val decoder = HiCarFrameDecoder()
        val head = decoder.feed(stream, 4)
        assertEquals(0, head.size)
        val tail = decoder.feed(stream.copyOfRange(4, stream.size))
        assertEquals(2, tail.size)
        assertEquals(0, tail[0].kind)
        assertEquals(0, tail[1].kind)
    }

    @Test
    fun decoderRejectsAForeignMarker() {
        try {
            HiCarFrameDecoder().feed(byteArrayOf(0x7E, 0x00, 0x80.toByte()))
            fail("A stream without the 0x7F marker is not a frame")
        } catch (expected: HiCarWireException) {
            assertEquals("frame marker", expected.message)
        }
    }

    @Test
    fun tlvRoundTripsAndRejectsTruncatedFields() {
        val encoded = HiCarTlv.encode(3, byteArrayOf(9, 9, 9))
        val fields = HiCarTlv.fields(encoded)
        assertEquals(1, fields.size)
        assertEquals(3, fields[0].tag)
        assertArrayEquals(byteArrayOf(9, 9, 9), fields[0].value)
        try {
            HiCarTlv.fields(encoded.copyOf(encoded.size - 1))
            fail("A truncated TLV must be rejected")
        } catch (expected: HiCarWireException) {
            assertEquals("field truncated", expected.message)
        }
    }

    @Test
    fun beaconCarriesTheModelBytesAndSequence() {
        val data = HiCarBeacon.serviceData("A1B2C3D4", 7)
        assertEquals(HiCarBeacon.SERVICE_DATA_BYTES, data.size)
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 3, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 4, 0xD4.toByte(), 16, 7),
            data,
        )
    }

    @Test
    fun beaconRefusesAModelThatIsNotEightHexDigits() {
        for (model in listOf("HiCar", "A1B2C3", "GGGGGGGG", "")) {
            try {
                HiCarBeacon.serviceData(model, 0)
                fail("model '$model' must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertNotNull(expected.message)
            }
        }
    }

    @Test
    fun beaconSequenceRotatesWithinOneByte() {
        assertEquals(1, HiCarBeacon.nextSequence(0))
        assertEquals(0, HiCarBeacon.nextSequence(255))
    }

    @Test
    fun rfcommChannelComesFromTheSocketDescriptionOnly() {
        assertEquals(7, HiCarRfcommServices.channelFromDescription("BluetoothSocket TYPE_RFCOMM Channel: 7"))
        assertEquals(-1, HiCarRfcommServices.channelFromDescription("BluetoothSocket TYPE_RFCOMM"))
        assertEquals(-1, HiCarRfcommServices.channelFromDescription("Channel: 7 without a type"))
        assertEquals(-1, HiCarRfcommServices.channelFromDescription("TYPE_RFCOMM Channel: 31"))
        assertEquals(-1, HiCarRfcommServices.channelFromDescription(null))
    }
}
