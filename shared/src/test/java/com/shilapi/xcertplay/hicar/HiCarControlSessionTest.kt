package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiCarControlSessionTest {
    private val key = ByteArray(16) { it.toByte() }

    private class Recorder : HiCarControlSession.Events {
        val logs = ArrayList<String>()
        var negotiated = false
        var clock: Pair<Long, Long>? = null
        var application: Triple<Long, Long, ByteArray>? = null

        override fun onLog(message: String) {
            logs.add(message)
        }

        override fun onNegotiated() {
            negotiated = true
        }

        override fun onClock(high: Long, low: Long) {
            clock = high to low
        }

        override fun onApplicationService(module: Long, type: Long, body: ByteArray): List<ByteArray> {
            // The session clears the body once the callback returns, so a recorder must copy it.
            application = Triple(module, type, body.copyOf())
            return listOf("answer".toByteArray())
        }
    }

    @Test
    fun theSessionOpensWithAPlaintextOptionsGreeting() {
        val session = HiCarControlSession(HiCarRecordCipher(key))
        val initial = session.initial()
        assertEquals(HiCarCodes.RECORD_PLAINTEXT, initial[0].toInt() and 0xFF)
        val text = String(initial.copyOfRange(3, initial.size), Charsets.US_ASCII)
        assertTrue(text.startsWith("OPTIONS * DMSDP/1.0\r\n"))
        assertTrue(text.contains("CryptoAlg: aes128-ctr"))
        assertFalse(session.negotiated)
        assertNull(session.heartbeat())
    }

    @Test
    fun theCryptoSwitchEnablesEncryptionForBothDirections() {
        val recorder = Recorder()
        val session = HiCarControlSession(HiCarRecordCipher(key), recorder)
        val replies = session.feed(record(HiCarCodes.RECORD_PLAINTEXT, cryptoSwitchText()))
        assertEquals(1, replies.size)
        assertEquals(HiCarCodes.RECORD_PLAINTEXT, replies[0][0].toInt() and 0xFF)
        assertTrue(String(replies[0].copyOfRange(3, replies[0].size), Charsets.US_ASCII).startsWith("DMSDP/1.0 200 OK"))
        assertTrue(session.negotiated)
        assertTrue(recorder.negotiated)

        val heartbeat = session.heartbeat()
        assertNotNull(heartbeat)
        assertEquals(HiCarCodes.RECORD_ENCRYPTED, heartbeat!![0].toInt() and 0xFF)
    }

    @Test
    fun aPlaintextRecordAfterTheSwitchIsRejected() {
        val session = HiCarControlSession(HiCarRecordCipher(key))
        session.feed(record(HiCarCodes.RECORD_PLAINTEXT, cryptoSwitchText()))
        try {
            session.feed(record(HiCarCodes.RECORD_PLAINTEXT, "GET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n".toByteArray()))
            fail("downgrading the channel to plaintext must not be accepted")
        } catch (expected: HiCarWireException) {
            assertEquals("plaintext after encryption", expected.message)
        }
    }

    @Test
    fun anEncryptedRecordBeforeTheSwitchIsRejected() {
        val session = HiCarControlSession(HiCarRecordCipher(key))
        val encrypted = HiCarRecordCipher(key).encrypt("OPTIONS * DMSDP/1.0\r\n\r\n".toByteArray())
        try {
            session.feed(record(HiCarCodes.RECORD_ENCRYPTED, encrypted))
            fail("encryption cannot start before the switch")
        } catch (expected: HiCarWireException) {
            assertEquals("early encryption", expected.message)
        }
    }

    @Test
    fun theClockParametersAreAnsweredAfterDecryption() {
        val recorder = Recorder()
        val session = HiCarControlSession(HiCarRecordCipher(key), recorder)
        session.feed(record(HiCarCodes.RECORD_PLAINTEXT, cryptoSwitchText()))
        val phone = HiCarRecordCipher(key)
        val text = "SET_PARAMETER * DMSDP/1.0\r\nCSeq: 4\r\nTimeServerPort: 4007\r\n" +
            "TimeUsHigh: 12\r\nTimeUsLow: 34\r\nContent-Length: 0\r\n\r\n"
        val replies = session.feed(record(HiCarCodes.RECORD_ENCRYPTED, phone.encrypt(text.toByteArray())))
        assertEquals(1, replies.size)
        val answer = phone.decrypt(replies[0].copyOfRange(3, replies[0].size))
        assertTrue(String(answer, Charsets.US_ASCII).startsWith("DMSDP/1.0 200 OK"))
        assertEquals(12L to 34L, recorder.clock)
    }

    @Test
    fun anApplicationFrameIsAnsweredInsideTheEncryptedChannel() {
        val recorder = Recorder()
        val session = HiCarControlSession(HiCarRecordCipher(key), recorder)
        session.feed(record(HiCarCodes.RECORD_PLAINTEXT, cryptoSwitchText()))
        val phone = HiCarRecordCipher(key)
        val body = byteArrayOf(9, 8, 7)
        val frame = join(
            byteArrayOf('$'.code.toByte()),
            byteArrayOf(0, 0, 0, 1),
            byteArrayOf(0, 0, 0, 2),
            byteArrayOf(0, body.size.toByte()),
            body,
        )
        val replies = session.feed(record(HiCarCodes.RECORD_ENCRYPTED, phone.encrypt(frame)))
        assertEquals(1, replies.size)
        assertEquals(1L, recorder.application?.first)
        assertEquals(2L, recorder.application?.second)
        assertArrayEquals(body, recorder.application?.third)
        val answer = phone.decrypt(replies[0].copyOfRange(3, replies[0].size))
        assertEquals("answer", String(answer))
    }

    @Test
    fun anUnknownRecordMarkerIsRejected() {
        val session = HiCarControlSession(HiCarRecordCipher(key))
        try {
            session.feed(byteArrayOf(0x11, 0x00, 0x02, 0x41, 0x42))
            fail("only the two documented markers are valid")
        } catch (expected: HiCarWireException) {
            assertEquals("control header", expected.message)
        }
    }

    @Test
    fun theCipherRoundTripsAndRefusesShortRecords() {
        val cipher = HiCarRecordCipher(key)
        val plain = "hello HiCar".toByteArray()
        val record = cipher.encrypt(plain)
        assertArrayEquals(plain, cipher.decrypt(record))
        assertFalse(record.copyOfRange(16, record.size).contentEquals(plain))
        try {
            cipher.decrypt(ByteArray(8))
            fail("a record without an IV is not decryptable")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun aThirtyTwoByteKeyUsesItsFirstSixteenBytesForAes128() {
        val longKey = ByteArray(32) { (it + 1).toByte() }
        val cipher = HiCarRecordCipher(longKey)
        assertArrayEquals("abc".toByteArray(), HiCarRecordCipher(longKey).decrypt(cipher.encrypt("abc".toByteArray())))
    }

    private fun record(marker: Int, payload: ByteArray): ByteArray = join(
        byteArrayOf(marker.toByte(), ((payload.size ushr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte()),
        payload,
    )

    private fun cryptoSwitchText(): ByteArray =
        ("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 2\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\n" +
            "CryptoAlg: aes128-ctr\r\nContent-Length: 0\r\n\r\n").toByteArray()
}
