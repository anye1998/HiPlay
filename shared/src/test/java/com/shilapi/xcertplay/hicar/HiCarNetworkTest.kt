package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiCarNetworkTest {
    private val offer = HiCarNetworkOffer(
        ssid = "HiCarLab-12345678",
        bssid = "AA:BB:CC:DD:EE:FF",
        passphrase = "12345678",
        ownerAddress = "192.168.43.1",
        clientAddress = "192.168.43.2",
        frequencyMhz = 5180,
        controlPort = 4005,
    )

    @Test
    fun credentialsUseTheDocumentedColumnLayout() {
        assertEquals(
            "192.168.43.2##HiCarLab-12345678\nAA:BB:CC:DD:EE:FF\n12345678\n5180\n1##version:3##4005##2##hicarApIp:192.168.43.1",
            String(offer.credentials(), Charsets.US_ASCII),
        )
        assertEquals(4006, offer.dataPort)
    }

    @Test
    fun aTwoPointFourGigahertzOfferIsRefused() {
        for (frequency in listOf(2412, 2437, 2462, 0)) {
            try {
                HiCarNetworkOffer(
                    ssid = "HiCarLab-12345678",
                    bssid = "AA:BB:CC:DD:EE:FF",
                    passphrase = "12345678",
                    ownerAddress = "192.168.43.1",
                    clientAddress = "192.168.43.2",
                    frequencyMhz = frequency,
                    controlPort = 4005,
                )
                fail("frequency $frequency is not in the phone's capability bitmap")
            } catch (expected: IllegalArgumentException) {
                assertEquals("HiCar AP frequency", expected.message)
            }
        }
    }

    @Test
    fun anAddressOutsideThePrivateRangesIsRefused() {
        try {
            HiCarNetworkOffer(
                ssid = "HiCarLab-12345678",
                bssid = "AA:BB:CC:DD:EE:FF",
                passphrase = "12345678",
                ownerAddress = "8.8.8.1",
                clientAddress = "8.8.8.2",
                frequencyMhz = 5180,
                controlPort = 4005,
            )
            fail("a public range would send the passphrase over a non-private network")
        } catch (expected: IllegalArgumentException) {
            assertEquals("HiCar AP must be a private network", expected.message)
        }
    }

    @Test
    fun aHashInTheSsidIsRefusedBecauseItWouldShiftTheColumns() {
        try {
            HiCarNetworkOffer(
                ssid = "HiCar#Lab",
                bssid = "AA:BB:CC:DD:EE:FF",
                passphrase = "12345678",
                ownerAddress = "192.168.43.1",
                clientAddress = "192.168.43.2",
                frequencyMhz = 5180,
                controlPort = 4005,
            )
            fail("'#' is the column separator in the credential string")
        } catch (expected: IllegalArgumentException) {
            assertEquals("HiCar AP SSID", expected.message)
        }
    }

    @Test
    fun capabilityNegotiationAcceptsTheOfferedChannel() {
        val negotiator = HiCarNetworkNegotiator(offer).also { it.authenticated = true }
        val reply = negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(1, networkRequest())))
        assertNotNull(reply)
        val field = HiCarTlv.fields(HiCarFrameDecoder().feed(reply!!).single().payload).single()
        assertEquals(2, field.tag)
        val reader = HiCarReader(field.value)
        assertEquals(3, reader.readVarint())
        assertEquals(1, reader.readVarint())
        val accepted = HiCarTlv.fields(reader.readBytes(reader.remaining)!!)
        assertEquals(3, accepted.single().tag)
        assertEquals(1, accepted.single().value[0].toInt())
        assertTrue(negotiator.negotiated)
    }

    @Test
    fun aChannelThePhoneDoesNotSupportIsNotAccepted() {
        val negotiator = HiCarNetworkNegotiator(offer).also { it.authenticated = true }
        // Capability 5 without this frequency's bit: 5200 MHz is a different bit than 5180.
        val request = join(
            HiCarVarint.encode(HiCarCodes.NETWORK_CHANNEL),
            HiCarTlv.encode(
                3,
                join(
                    HiCarVarint.encode(2), HiCarVarint.encode(1),
                    HiCarVarint.encode(4), HiCarVarint.encode(1),
                    HiCarVarint.encode(5), HiCarVarint.encode(HiCarNetworkOffer.frequencyMask(5200)),
                ),
            ),
        )
        val reply = negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(1, request)))
        assertNotNull(reply)
        val reader = HiCarReader(HiCarTlv.fields(HiCarFrameDecoder().feed(reply!!).single().payload).single().value)
        assertEquals(3, reader.readVarint())
        assertEquals(255, reader.readVarint())
        assertFalse(negotiator.negotiated)
    }

    @Test
    fun theDataPortIsRefusedUntilTheNetworkIsNegotiated() {
        val negotiator = HiCarNetworkNegotiator(offer).also { it.authenticated = true }
        val request = join(
            HiCarVarint.encode(HiCarCodes.NETWORK_CHANNEL),
            HiCarVarint.encode(HiCarCodes.DATA_BUSINESS),
            HiCarVarint.encode(0),
            HiCarVarint.encode(HiCarCodes.DATA_PROTOCOL_TCP),
        )
        try {
            negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(3, request)))
            fail("the data endpoint must follow a successful negotiation")
        } catch (expected: HiCarWireException) {
            assertEquals("data endpoint not ready", expected.message)
        }
    }

    @Test
    fun credentialsAreReleasedOnlyAfterTheSelectionAndTheStationAddress() {
        val negotiator = HiCarNetworkNegotiator(offer).also { it.authenticated = true }
        negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(1, networkRequest())))
        val selection = join(
            HiCarVarint.encode(HiCarCodes.NETWORK_CHANNEL),
            HiCarVarint.encode(1),
            HiCarTlv.encode(3, join(byteArrayOf(2), HiCarVarint.encode(offer.frequencyMhz))),
        )
        assertNull(negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(7, selection))))
        val command = negotiator.takePending()
        assertEquals(1, command.size)
        val commandFrame = HiCarFrameDecoder().feed(command.single()).single()
        assertEquals(33, commandFrame.kind)
        assertEquals(1, HiCarTlv.fields(commandFrame.payload).single().tag)

        // Tag 1 is "no address yet": answer with a marker, not with the passphrase.
        val waiting = negotiator.respond(HiCarFrame(33, 0, HiCarTlv.encode(1, ByteArray(0))))
        assertNotNull(waiting)
        assertEquals("busy", String(HiCarTlv.fields(HiCarFrameDecoder().feed(waiting!!).single().payload).single().value))
        assertFalse(negotiator.credentialsSent)

        val station = "11:22:33:44:55:66".toByteArray(Charsets.US_ASCII)
        val credentials = negotiator.respond(HiCarFrame(33, 0, HiCarTlv.encode(2, station)))
        assertNotNull(credentials)
        val value = HiCarTlv.fields(HiCarFrameDecoder().feed(credentials!!).single().payload).single().value
        assertArrayEquals(offer.credentials(), value)
        assertTrue(negotiator.credentialsSent)
    }

    @Test
    fun anUnauthenticatedSessionGetsNoAnswer() {
        val negotiator = HiCarNetworkNegotiator(offer)
        assertNull(negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(1, networkRequest()))))
        assertFalse(negotiator.negotiated)
    }

    @Test
    fun aStationAddressThatIsNotAMacIsRejected() {
        val negotiator = HiCarNetworkNegotiator(offer).also { it.authenticated = true }
        negotiator.respond(HiCarFrame(4, 0, HiCarTlv.encode(1, networkRequest())))
        try {
            negotiator.respond(HiCarFrame(33, 0, HiCarTlv.encode(2, "not-a-mac".toByteArray())))
            fail("the credential reply is tied to a station address")
        } catch (expected: HiCarWireException) {
            assertEquals("client address", expected.message)
        }
        assertFalse(negotiator.credentialsSent)
    }

    private fun networkRequest(): ByteArray = join(
        HiCarVarint.encode(HiCarCodes.NETWORK_CHANNEL),
        HiCarTlv.encode(
            3,
            join(
                HiCarVarint.encode(2), HiCarVarint.encode(1),
                HiCarVarint.encode(4), HiCarVarint.encode(1),
                HiCarVarint.encode(5), HiCarVarint.encode(HiCarNetworkOffer.frequencyMask(offer.frequencyMhz)),
            ),
        ),
    )
}
