package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiCarNegotiationTest {
    private val summary = "0123456789ABCDEF".toByteArray()

    @Test
    fun parameterNegotiationAnswersAllThreeQuestions() {
        val negotiator = HiCarChannelNegotiator(summary) { 9 }
        val request = HiCarFrame(
            kind = 0,
            flags = 0,
            payload = join(
                HiCarTlv.encode(1, byteArrayOf(2)),
                HiCarTlv.encode(3, "peer".toByteArray()),
                HiCarTlv.encode(5, byteArrayOf(0)),
            ),
        )
        val reply = negotiator.respond(request)
        assertNotNull(reply)
        val fields = HiCarTlv.fields(HiCarFrameDecoder().feed(reply!!).single().payload)
        assertEquals(listOf(2, 4, 6), fields.map { it.tag })
        assertArrayEquals(byteArrayOf(2), fields[0].value)
        assertArrayEquals(summary, fields[1].value)
        assertArrayEquals(byteArrayOf(0), fields[2].value)
        assertTrue(negotiator.complete)
    }

    @Test
    fun businessChannelIsRefusedBeforeAllParametersArrived() {
        val negotiator = HiCarChannelNegotiator(summary) { 9 }
        negotiator.respond(HiCarFrame(0, 0, HiCarTlv.encode(1, byteArrayOf(2))))
        try {
            negotiator.respond(HiCarFrame(2, 0, HiCarTlv.encode(3, businessRequest())))
            fail("the business request needs the complete negotiation")
        } catch (expected: HiCarWireException) {
            assertEquals("business channel unavailable", expected.message)
        }
    }

    @Test
    fun businessChannelReplyCarriesTheAssignedChannelAndServiceUuid() {
        val negotiator = HiCarChannelNegotiator(summary) { 24 }
        negotiator.respond(
            HiCarFrame(
                0,
                0,
                join(
                    HiCarTlv.encode(1, byteArrayOf(2)),
                    HiCarTlv.encode(3, "peer".toByteArray()),
                    HiCarTlv.encode(5, byteArrayOf(0)),
                ),
            ),
        )
        val request = businessRequest()
        val reply = negotiator.respond(HiCarFrame(2, 0, HiCarTlv.encode(3, request)))
        assertNotNull(reply)
        val field = HiCarTlv.fields(HiCarFrameDecoder().feed(reply!!).single().payload).single()
        assertEquals(4, field.tag)
        val reader = HiCarReader(field.value)
        assertEquals(0, reader.readVarint())
        assertEquals(24, reader.readVarint())
        assertArrayEquals(request, reader.readBytes(request.size))
        assertEquals(16, reader.readVarint())
        assertArrayEquals(uuidBytes(HiCarCodes.SERVICE_BUSINESS), reader.readBytes(16))
        assertEquals(0, reader.remaining)
        assertTrue(negotiator.businessAnnounced)
    }

    @Test
    fun aMissingChannelIsAHardFailureNotAGuess() {
        val negotiator = HiCarChannelNegotiator(summary) { 0 }
        negotiator.respond(
            HiCarFrame(
                0,
                0,
                join(
                    HiCarTlv.encode(1, byteArrayOf(2)),
                    HiCarTlv.encode(3, "peer".toByteArray()),
                    HiCarTlv.encode(5, byteArrayOf(0)),
                ),
            ),
        )
        try {
            negotiator.respond(HiCarFrame(2, 0, HiCarTlv.encode(3, businessRequest())))
            fail("an unreadable RFCOMM channel must not be advertised")
        } catch (expected: HiCarWireException) {
            assertEquals("business channel unavailable", expected.message)
        }
    }

    @Test
    fun encryptedFramesAreRefusedBeforeAuthentication() {
        val negotiator = HiCarChannelNegotiator(summary) { 9 }
        try {
            negotiator.respond(HiCarFrame(0, 1, HiCarTlv.encode(1, byteArrayOf(2))))
            fail("the control channel is plaintext before authentication")
        } catch (expected: HiCarWireException) {
            assertEquals("encrypted before authentication", expected.message)
        }
    }

    @Test
    fun otherBusinessKindsAreRefused() {
        val negotiator = HiCarChannelNegotiator(summary) { 9 }
        negotiator.respond(
            HiCarFrame(
                0,
                0,
                join(
                    HiCarTlv.encode(1, byteArrayOf(2)),
                    HiCarTlv.encode(3, "peer".toByteArray()),
                    HiCarTlv.encode(5, byteArrayOf(0)),
                ),
            ),
        )
        // business = 20 is the data socket, not the control channel requested here.
        val wrongBusiness = join(
            HiCarVarint.encode(HiCarCodes.BUSINESS_TRANSPORT_RFCOMM),
            HiCarVarint.encode(20),
            HiCarVarint.encode(0),
            HiCarVarint.encode(0),
        )
        try {
            negotiator.respond(HiCarFrame(2, 0, HiCarTlv.encode(3, wrongBusiness)))
            fail("only the control business may be announced on this channel")
        } catch (expected: HiCarWireException) {
            assertEquals("unsupported business", expected.message)
        }
    }

    private fun businessRequest(): ByteArray = join(
        HiCarVarint.encode(HiCarCodes.BUSINESS_TRANSPORT_RFCOMM),
        HiCarVarint.encode(HiCarCodes.BUSINESS_KIND_CONTROL),
        HiCarVarint.encode(0),
        HiCarVarint.encode(0),
    )
}
