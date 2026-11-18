package com.shilapi.xcertplay.hicar

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three wire formats the media stage adds, checked without a phone.
 *
 * Each of these was a guess until the reference implementation was read: the entry handshake, the
 * `$` service packet and the eleven-byte header inside it are exactly the kind of detail that costs a
 * car trip per mistake. JSON is involved, so this runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HiCarDmsdpTransportTest {
    @Test
    fun entryReplyNamesTheControlPortInBytesOneAndTwo() {
        val request = HiCarDmsdpFrames.encode(HiCarCodes.DMSDP_MODULE_ENTRY, ByteArray(16))
        val packet = HiCarDmsdpDecoder().feed(request).single()
        val reply = HiCarDmsdpDecoder().feed(hiCarDmsdpEntryReply(packet, 4007)).single()
        assertEquals(HiCarCodes.DMSDP_MODULE_ENTRY, reply.module)
        assertEquals(16, reply.payload.size)
        assertEquals(0x0F, reply.payload[1].toInt() and 0xFF)
        assertEquals(0xA7, reply.payload[2].toInt() and 0xFF)
    }

    @Test
    fun aCorruptedModuleFrameIsRefused() {
        val encoded = HiCarDmsdpFrames.encode(HiCarCodes.DMSDP_MODULE_ENTRY, ByteArray(16))
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()
        try {
            HiCarDmsdpDecoder().feed(encoded)
            throw AssertionError("a broken CRC must not decode")
        } catch (expected: HiCarWireException) {
            assertEquals("DMSDP CRC", expected.message)
        }
    }

    @Test
    fun deviceInfoIsAnsweredWithOurIdentityAndTheSessionAddresses() {
        val business = business(displayReady = false)
        val request = packet(HiCarCodes.DMSDP_COMMAND_DEVICE_INFO, """{"deviceId":"PHONE","businessId":7}""")
        val reply = business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, request).single()
        val body = serviceBody(reply, HiCarCodes.DMSDP_COMMAND_DEVICE_INFO)
        assertEquals("0011223344556677", body.getString("deviceId"))
        assertEquals("10.42.0.1", body.getString("localIp"))
        assertEquals("10.42.0.2", body.getString("remoteIp"))
        assertEquals(7, body.getInt("businessId"))
        assertEquals("A1B2C3D4", body.getJSONObject("properties").getString("2003"))
    }

    @Test
    fun theDisplayServiceStaysUnannouncedUntilADecoderExists() {
        val business = business(displayReady = false)
        val request = packet(HiCarCodes.DMSDP_COMMAND_DEVICE_INFO, """{"deviceId":"PHONE"}""")
        business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, request)
        val answers = business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, maskPacket(HiCarCodes.DMSDP_COMMAND_SERVICE_REQUEST, 8))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun aReadyDecoderAnnouncesTheDisplayServiceOnce() {
        val business = business(displayReady = true)
        business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, packet(HiCarCodes.DMSDP_COMMAND_DEVICE_INFO, """{"deviceId":"PHONE"}"""))
        val request = maskPacket(HiCarCodes.DMSDP_COMMAND_SERVICE_REQUEST, 8)
        val first = business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, request)
        val service = serviceBody(first.single(), HiCarCodes.DMSDP_COMMAND_SERVICE_ANNOUNCE)
        assertEquals("display1", service.getString("serviceId"))
        assertEquals(8, service.getInt("serviceType"))
        assertEquals(800, service.getJSONObject("properties").getInt("1009"))
        assertEquals(480, service.getJSONObject("properties").getInt("1010"))
        // A repeat must not announce the same service twice; the phone treats that as a new session.
        assertTrue(business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, request).isEmpty())
    }

    @Test
    fun serviceRequestsAreRefusedBeforeTheIdentityExchange() {
        val business = business(displayReady = true)
        try {
            business.handle(3L, HiCarCodes.DMSDP_TYPE_WILDCARD, maskPacket(HiCarCodes.DMSDP_COMMAND_SERVICE_REQUEST, 8))
            throw AssertionError("order must be enforced")
        } catch (expected: HiCarWireException) {
            assertEquals("service request order", expected.message)
        }
    }

    private fun business(displayReady: Boolean) = HiCarDeviceBusiness(
        deviceId = "0011223344556677",
        model = "A1B2C3D4",
        ownerAddress = "10.42.0.1",
        clientAddress = "10.42.0.2",
        onLog = {},
        displayReady = { displayReady },
    )

    /** `command` as a varint followed by `json`, which is how the device module addresses its body. */
    private fun packet(command: Int, json: String): ByteArray =
        join(HiCarVarint.encode(command), json.toByteArray(Charsets.UTF_8))

    /** The service request carries a big-endian capability mask instead of JSON. */
    private fun maskPacket(command: Int, mask: Int): ByteArray = join(
        HiCarVarint.encode(command),
        byteArrayOf(((mask ushr 24) and 0xFF).toByte(), ((mask ushr 16) and 0xFF).toByte(),
            ((mask ushr 8) and 0xFF).toByte(), (mask and 0xFF).toByte()),
    )

    /** The service packet header, then the command and the JSON that follows it. */
    private fun serviceBody(reply: ByteArray, command: Int): JSONObject {
        assertEquals('$'.code.toByte(), reply[0])
        assertEquals(3, reply[4].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), reply.copyOfRange(5, 9))
        val length = ((reply[9].toInt() and 0xFF) shl 8) or (reply[10].toInt() and 0xFF)
        assertEquals(reply.size - 11, length)
        val reader = HiCarReader(reply, 11)
        assertEquals(command, reader.readVarint())
        return JSONObject(String(reply, reader.position, reply.size - reader.position, Charsets.UTF_8))
    }
}
