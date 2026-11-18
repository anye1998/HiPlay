package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicar.reference.HiSightWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Runs the reference receiver's HiSight implementation next to this port on the same bytes.
 *
 * The point is to stop guessing. `docs/HICAR_REFERENCE_MAP.md` claims this is a port of
 * `HiSightWire` from huducat586/hicar-android-receiver; a claim is not evidence. The three reference
 * files are copied verbatim into `src/test/.../reference` (MIT, package and visibility only) and this
 * test feeds both implementations one scripted handshake, then compares every answer.
 *
 * The script follows the order the phone actually used on 2026-11-18 11:11, which is also the order
 * the reference expects: plaintext `200` for our ANNOUNCE, the phone's own ANNOUNCE selecting
 * `aes128ctr`, `OPTIONS`, `GET_PARAMETER`, `SET_PARAMETER` with the presentation URL and a `SETUP`
 * trigger, and finally the `200` that names the media and input ports.
 *
 * Because both sides encrypt with their own random IV, the comparison is on decrypted text, and the
 * phone's records are encrypted here with this port's cipher: the reference accepting them is itself
 * a check that the two ciphers agree.
 */
class HiCarHiSightReferenceParityTest {

    private val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
    private val phone = HiCarRecordCipher(key, 65_000)

    private class Step(val name: String, val bytes: ByteArray, val encrypted: Boolean)

    @Test
    fun `the opening announce is byte for byte what the reference sends`() {
        val reference = HiSightWire(key, {})
        val mine = HiCarHiSightWire(key, {}, withInput = false)
        assertArrayEquals(reference.initial(), mine.initial())
        assertArrayEquals(reference.initial(), HiSightWire(key, {}, true).initial())
    }

    @Test
    fun `every answer in the handshake matches the reference`() {
        val reference = HiSightWire(key, {}, true)
        val mine = HiCarHiSightWire(key, {}, withInput = true)
        val referenceAnswers = ArrayList<String>()
        val mineAnswers = ArrayList<String>()

        for (step in handshake()) {
            val fromReference = reference.feed(step.bytes, step.bytes.size).map { plainOf(it, step.encrypted) }
            val fromMine = mine.feed(step.bytes, step.bytes.size).map { plainOf(it, step.encrypted) }
            referenceAnswers += fromReference
            mineAnswers += fromMine
            assertEquals("answer count for ${step.name}", fromReference.size, fromMine.size)
            assertEquals("answers for ${step.name}", fromReference, fromMine)
        }

        assertTrue("the handshake must produce answers", referenceAnswers.isNotEmpty())
        assertEquals(reference.mediaPort(), mine.mediaPort)
        assertEquals(reference.inputPort(), mine.inputPort)
        assertEquals(41129, mine.mediaPort)
        assertEquals(41234, mine.inputPort)
    }

    @Test
    fun `play and teardown match the reference`() {
        val reference = HiSightWire(key, {}, true)
        val mine = HiCarHiSightWire(key, {}, withInput = true)
        for (step in handshake()) {
            reference.feed(step.bytes, step.bytes.size)
            mine.feed(step.bytes, step.bytes.size)
        }
        assertEquals(
            plainOf(reference.play(), true),
            plainOf(mine.play(), true),
        )
        assertEquals(
            plainOf(reference.stop()!!, true),
            plainOf(mine.stop()!!, true),
        )
    }

    /**
     * One unreadable record must not end the session.
     *
     * This is the one place where this port is deliberately more forgiving than the reference: at
     * 11:11 the OPTIONS parsed and the record *after* it did not, and the reference's behaviour there
     * (throw, `failed = true`) costs the whole video session. The length prefix still frames the next
     * record, so skipping one and saying so keeps a recoverable surprise recoverable.
     */
    @Test
    fun `a record without a CSeq is skipped and the negotiation still completes`() {
        val logs = ArrayList<String>()
        val mine = HiCarHiSightWire(key, { logs += it }, withInput = true)
        val script = handshake()
        for (step in script.take(3)) mine.feed(step.bytes, step.bytes.size)

        // Encrypted, otherwise well formed, but no CSeq: unreadable as RTSP, still framed.
        val broken = record("SET_PARAMETER * RTSP/1.0\r\nHis-Trigger-Method: SETUP\r\n\r\n", encrypted = true)
        val answers = mine.feed(broken, broken.size)
        assertEquals("a skipped record answers nothing", 0, answers.size)
        assertTrue(
            "the skip has to be visible in the log: $logs",
            logs.any { it.contains("session continues") },
        )

        for (step in script.drop(3)) mine.feed(step.bytes, step.bytes.size)
        assertEquals("the session must still negotiate the ports", 41129, mine.mediaPort)
        assertEquals(41234, mine.inputPort)
    }

    /** The phone's side of the 11:11 handshake, in the order the log shows it. */
    private fun handshake(): List<Step> {
        val announceBody = "encrypt_description: encrypt_list=aes128ctr;version=1\r\n"
        val capabilities = "his_version\r\nhis_video_formats\r\nhis_device_type\r\nhis_uibc_capability\r\n"
        val setupBody =
            "his_presentation_url: rtsp://192.168.49.2:41129/hisight/target\r\n" +
                "his_trigger_method: SETUP\r\n"
        return listOf(
            Step("acknowledge our ANNOUNCE", record("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n", false), false),
            Step("phone ANNOUNCE", record(message("ANNOUNCE * RTSP/1.0", "2", announceBody), false), false),
            Step("OPTIONS", record(message("OPTIONS * RTSP/1.0", "3", heartbeatBody()), true), true),
            Step("GET_PARAMETER", record(message("GET_PARAMETER * RTSP/1.0", "4", capabilities), true), true),
            Step("SET_PARAMETER triggering SETUP", record(message("SET_PARAMETER * RTSP/1.0", "5", setupBody), true), true),
            Step(
                "200 for SETUP",
                record(
                    "RTSP/1.0 200 OK\r\nCSeq: 3\r\nTransport: RTP/AVP/UDP;unicast;client_port=0;" +
                        "server_port=41129;remotectl_port=41234\r\n\r\n",
                    true,
                ),
                true,
            ),
        )
    }

    /** 34 bytes, the size the phone used; the content is a keepalive it is entitled to send. */
    private fun heartbeatBody(): String = "his_heartbeat: 600\r\nhis_alive: 1\r\n"

    private fun message(start: String, cseq: String, body: String): String {
        val bytes = body.toByteArray(StandardCharsets.US_ASCII).size
        return "$start\r\nCSeq: $cseq\r\nContent-Type: text/parameters\r\nContent-Length: $bytes\r\n\r\n$body"
    }

    private fun record(text: String, encrypted: Boolean): ByteArray {
        val plain = text.toByteArray(StandardCharsets.US_ASCII)
        val payload = if (encrypted) phone.encrypt(plain) else plain
        return byteArrayOf(
            ((payload.size ushr 24) and 0xFF).toByte(),
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload
    }

    private fun plainOf(record: ByteArray, encrypted: Boolean): String {
        val payload = record.copyOfRange(4, record.size)
        val plain = if (encrypted) phone.decrypt(payload) else payload
        return String(plain, StandardCharsets.US_ASCII)
    }
}
