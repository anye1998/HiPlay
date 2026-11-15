package com.shilapi.xcertplay.hicar

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The envelope layer only; the cryptography behind [HiCarAuthenticator] is the native engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HiCarAuthExchangeTest {
    private fun exchange(authenticator: HiCarAuthenticator?) = HiCarIdentityExchange(
        localIdentity = "0011223344556677",
        deviceName = "HiCar receiver",
        model = "A1B2C3D4",
        pinConfigured = true,
        authenticator = authenticator,
    )

    @Test
    fun theIdentityEnvelopeCarriesThisCarAndStartsTheEngine() {
        val engine = FakeAuthenticator()
        val exchange = exchange(engine)
        val replies = exchange.feed(identityEnvelope("8899AABBCCDDEEFF"))
        assertEquals(1, replies.size)
        val envelope = JSONObject(String(replies.single()))
        assertEquals(1, envelope.getInt("oper_type"))
        val data = JSONObject(envelope.getString("data"))
        assertEquals("0011223344556677", data.getString("deviceId"))
        assertEquals("A1B2C3D4", data.getString("CAR_MODE_ID"))
        assertTrue(data.getBoolean("has_pin"))
        assertFalse(data.getBoolean("usb_connection"))
        assertEquals("8899AABBCCDDEEFF", engine.begun)
        assertEquals("8899AABBCCDDEEFF", exchange.peerIdentity)
        assertTrue(exchange.identityExchanged)
    }

    @Test
    fun aHiChainPacketIsForwardedWithTheAnnouncedKeyLength() {
        val engine = FakeAuthenticator()
        val exchange = exchange(engine)
        exchange.feed(identityEnvelope("8899AABBCCDDEEFF"))
        val replies = exchange.feed(authEnvelope(message = 17, keyLength = 16))
        assertEquals(1, replies.size)
        assertEquals(listOf(16), engine.lengths)
        val envelope = JSONObject(String(replies.single()))
        assertEquals(3, envelope.getInt("oper_type"))
        // The engine's own reply is carried verbatim as the envelope's data string.
        val data = JSONObject(envelope.getString("data"))
        assertEquals(32769, data.getInt("message"))
    }

    @Test
    fun anEnvelopeArrivingAfterTheOperationIsOverIsNotFedToTheEngineAgain() {
        // A bind derives its key before the exchange phase, so "a key exists" cannot be the gate for
        // stepping the engine: that is exactly what the run at 11:35 lost.
        val engine = FakeAuthenticator()
        engine.operationComplete = true
        val exchange = exchange(engine)
        exchange.feed(identityEnvelope("8899AABBCCDDEEFF"))

        val replies = exchange.feed(authEnvelope(message = 3, keyLength = 32))

        assertTrue("a finished operation must not be stepped again", engine.lengths.isEmpty())
        assertTrue(replies.isEmpty())
    }

    @Test
    fun aKeyLengthOtherThanSixteenOrThirtyTwoIsRefused() {        val exchange = exchange(FakeAuthenticator())
        exchange.feed(identityEnvelope("8899AABBCCDDEEFF"))
        try {
            exchange.feed(authEnvelope(message = 17, keyLength = 20))
            fail("the engine only accepts 16 or 32 byte keys")
        } catch (expected: HiCarWireException) {
            assertEquals("key length", expected.message)
        }
    }

    @Test
    fun withoutAnEngineTheSessionStopsAtTheFirstHiChainPacket() {
        val exchange = exchange(null)
        exchange.feed(identityEnvelope("8899AABBCCDDEEFF"))
        try {
            exchange.feed(authEnvelope(message = 17, keyLength = 32))
            fail("a build without the engine must not pretend to authenticate")
        } catch (expected: HiCarAuthException) {
            assertEquals(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING, expected.blocker)
        }
    }

    @Test
    fun anAuthPacketBeforeTheIdentityExchangeIsRefused() {
        val exchange = exchange(FakeAuthenticator())
        try {
            exchange.feed(authEnvelope(message = 17, keyLength = 32))
            fail("HiChain packets only make sense after the identity exchange")
        } catch (expected: HiCarWireException) {
            assertEquals("authentication order", expected.message)
        }
    }

    @Test
    fun aFinishedEngineExposesItsKeyOnlyWhenItReportsSuccess() {
        val key = ByteArray(32) { it.toByte() }
        val finished = FakeAuthenticator(key)
        finished.step(ByteArray(0), 32)
        assertTrue(finished.complete)
        assertArrayEquals(key, finished.sessionKey)

        val blocked = FakeAuthenticator(null)
        assertFalse(blocked.complete)
        assertNull(blocked.sessionKey)
    }

    private fun identityEnvelope(peer: String): ByteArray = JSONObject()
        .put("version", "A_0")
        .put("discover_type", 1)
        .put("oper_type", 1)
        .put("data", JSONObject().put("deviceId", peer).put("usb_connection", false).toString())
        .put("usb_connection", false)
        .toString()
        .toByteArray()

    private fun authEnvelope(message: Int, keyLength: Int): ByteArray = JSONObject()
        .put("version", "A_0")
        .put("discover_type", 1)
        .put("oper_type", 3)
        .put(
            "data",
            JSONObject()
                .put("message", message)
                .put("payload", JSONObject().put("keyLength", keyLength))
                .toString(),
        )
        .put("usb_connection", false)
        .toString()
        .toByteArray()

    private class FakeAuthenticator(private val key: ByteArray? = null) : HiCarAuthenticator {
        var begun: String? = null
        val lengths = mutableListOf<Int>()

        override var sessionKey: ByteArray? = key
            private set

        override val complete: Boolean get() = sessionKey != null

        override var operationComplete: Boolean = false

        override fun begin(peerIdentity: String) {
            begun = peerIdentity
        }

        override fun step(chunk: ByteArray, keyLength: Int): List<ByteArray> {
            lengths.add(keyLength)
            return listOf("""{"message":32769,"payload":{}}""".toByteArray())
        }

        override fun close() {
            sessionKey = null
        }
    }
}
