package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The HiSight handshake, exercised without a phone.
 *
 * Every failure this covers has the same shape on the car: the phone connects, says nothing useful,
 * and closes — which reads identically whether the record framing is wrong, the cipher switch was
 * missed, or the `server_port` was parsed from the wrong header. The test names the step instead.
 */
class HiCarHiSightWireTest {
    private val key = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `announce opens the session in the clear with a four byte length`() {
        val wire = HiCarHiSightWire(key, {}, withInput = true)
        val record = wire.initial()
        val size = readLength(record)
        assertEquals(record.size - 4, size)
        val text = String(record, 4, size, StandardCharsets.US_ASCII)
        assertTrue(text.startsWith("ANNOUNCE * RTSP/1.0\r\n"))
        assertTrue(text.contains("encrypt_list=aes128ctr"))
        // The body must be announced with its length, or the phone reads the parameters as headers.
        assertTrue(text.contains("Content-Length: "))
    }

    @Test
    fun `phone acknowledge then announce turns encryption on`() {
        val wire = HiCarHiSightWire(key, {}, withInput = true)
        wire.initial()
        // The phone's reply to our ANNOUNCE predates the cipher switch, so it is still plaintext.
        assertTrue(wire.feed(record("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n", encrypted = false)).isEmpty())
        val replies = wire.feed(
            record("ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\n", false),
        )
        assertEquals(1, replies.size)
        // This answer is still in the clear: it is sent before the cipher is on.
        val answer = String(replies.single(), 4, readLength(replies.single()), StandardCharsets.US_ASCII)
        assertTrue(answer.startsWith("RTSP/1.0 200 OK"))
    }

    @Test
    fun `options is answered and pulls the phone onto hisight`() {
        val wire = opened()
        val replies = wire.feed(record("OPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n", encrypted = true))
        assertEquals(2, replies.size)
        val answer = decrypt(replies[0])
        assertTrue(answer.startsWith("RTSP/1.0 200 OK"))
        assertTrue(answer.contains("hisight1.0"))
        val solicit = decrypt(replies[1])
        assertTrue(solicit.startsWith("OPTIONS * RTSP/1.0"))
        assertTrue(solicit.contains("Require: hisight1.0"))
    }

    @Test
    fun `get parameter reports the advertised display and input capability`() {
        val wire = opened()
        val body = "his_version\r\nhis_video_formats\r\nhis_uibc_capability\r\n"
        val replies = wire.feed(
            record("GET_PARAMETER * RTSP/1.0\r\nCSeq: 3\r\nContent-Length: ${body.length}\r\n\r\n$body", encrypted = true),
        )
        val answer = decrypt(replies.single())
        assertTrue(answer.contains("his_version: 1.0"))
        assertTrue(answer.contains("width 800;height 480"))
        assertTrue(answer.contains("his_uibc_capability"))
        // A capability the phone did not ask for must not be volunteered.
        assertTrue(!answer.contains("his_device_type"))
    }

    @Test
    fun `setup trigger sends setup and the answer names both ports`() {
        val wire = opened()
        val body = "his_presentation_url: rtsp://192.168.1.2/stream\r\nhis_trigger_method: SETUP\r\n"
        val replies = wire.feed(
            record("SET_PARAMETER * RTSP/1.0\r\nCSeq: 4\r\nContent-Length: ${body.length}\r\n\r\n$body", encrypted = true),
        )
        assertEquals(2, replies.size)
        val setup = decrypt(replies[1])
        assertTrue(setup.startsWith("SETUP rtsp://192.168.1.2/stream RTSP/1.0"))
        assertTrue(setup.contains("client_port=0"))

        val transport = "RTP/AVP/UDP;unicast;client_port=0;server_port=5100;remotectl_port=5101"
        // The SETUP's own CSeq is what the phone answers; taking it from the message keeps this test
        // from depending on the wire's internal counter.
        val cseq = Regex("CSeq: ([0-9]+)").find(setup)!!.groupValues[1]
        val answers = wire.feed(
            record("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nTransport: $transport\r\n\r\n", encrypted = true),
        )
        assertTrue(answers.isEmpty())
        assertEquals(5100, wire.mediaPort)
        assertEquals(5101, wire.inputPort)
    }

    @Test
    fun `play is refused before a media port exists`() {
        val wire = opened()
        val failure = runCatching { wire.play() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("rtsp media state", failure?.message)
    }

    @Test
    fun `a header whose name uses an underscore is still read`() {
        // 11:11 ended an entire session here: `his_heartbeat` did not match the strict header
        // grammar and the parser threw instead of carrying on.
        val wire = opened()
        val replies = wire.feed(
            record(
                "OPTIONS * RTSP/1.0\r\nCSeq: 2\r\nhis_heartbeat: 60\r\nContent-Length: 0\r\n\r\n",
                encrypted = true,
            ),
        )
        assertEquals(2, replies.size)
        assertTrue(decrypt(replies[0]).startsWith("RTSP/1.0 200 OK"))
    }

    @Test
    fun `a blank header row is skipped rather than rejected`() {
        val wire = opened()
        val replies = wire.feed(
            record("OPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\nContent-Length: 0\r\n\r\n", encrypted = true),
        )
        // The first block ends at the blank row, so this is a valid OPTIONS with a stray body.
        assertTrue(replies.isNotEmpty())
    }

    @Test
    fun `a record with the wrong cipher offer is rejected`() {
        val wire = opened()
        val failure = runCatching {
            wire.feed(record("ANNOUNCE * RTSP/1.0\r\nCSeq: 9\r\n\r\nencrypt_description: encrypt_list=none\r\n", true))
        }.exceptionOrNull()
        assertEquals("rtsp cipher selection", failure?.message)
    }

    private fun opened(): HiCarHiSightWire {
        val wire = HiCarHiSightWire(key, {}, withInput = true)
        wire.initial()
        wire.feed(record("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n", false))
        wire.feed(record("ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\n", false))
        return wire
    }

    private fun record(payload: String, encrypted: Boolean): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.US_ASCII)
        val body = if (encrypted) HiCarRecordCipher(key, 65_000).encrypt(bytes) else bytes
        return join(
            byteArrayOf(
                ((body.size ushr 24) and 0xFF).toByte(),
                ((body.size ushr 16) and 0xFF).toByte(),
                ((body.size ushr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            ),
            body,
        )
    }

    private fun decrypt(record: ByteArray): String {
        val body = record.copyOfRange(4, record.size)
        return String(HiCarRecordCipher(key, 65_000).decrypt(body), StandardCharsets.US_ASCII)
    }

    private fun readLength(record: ByteArray): Int {
        var size = 0
        for (index in 0 until 4) size = (size shl 8) or (record[index].toInt() and 0xFF)
        return size
    }
}

/** The media path: RTP header, PES header, encrypted payload, one access unit out. */
class HiCarHiSightMediaTest {
    private val key = ByteArray(16) { (it + 3).toByte() }

    @Test
    fun `one rtp packet with the marker bit yields one access unit`() {
        val accessUnit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4, 5, 6, 7)
        val decoder = HiCarHiSightMedia(key)
        var seen: ByteArray? = null
        var kind = -1
        decoder.packet(rtp(pes(accessUnit), sequence = 1, timestamp = 90_000, marker = true)) { payloadType, _, data ->
            kind = payloadType
            seen = data.copyOf()
        }
        assertEquals(96, kind)
        assertArrayEquals(accessUnit, seen)
    }

    @Test
    fun `a packet without the marker bit is held until the access unit ends`() {
        val accessUnit = byteArrayOf(1, 1, 1, 1, 2, 2, 2)
        val stream = pes(accessUnit)
        val cut = stream.size / 2
        val decoder = HiCarHiSightMedia(key)
        var received: ByteArray? = null
        decoder.packet(rtp(stream.copyOfRange(0, cut), sequence = 1, timestamp = 1, marker = false)) { _, _, _ ->
            throw AssertionError("a partial access unit must not be decoded")
        }
        decoder.packet(rtp(stream.copyOfRange(cut, stream.size), sequence = 2, timestamp = 1, marker = true)) { _, _, data ->
            received = data.copyOf()
        }
        assertArrayEquals(accessUnit, received)
    }

    @Test
    fun `a sequence gap discards the partial access unit`() {
        val decoder = HiCarHiSightMedia(key)
        val stream = pes(byteArrayOf(1, 1, 1, 1))
        decoder.packet(rtp(stream.copyOfRange(0, 16), sequence = 1, timestamp = 1, marker = false)) { _, _, _ -> }
        val second = pes(byteArrayOf(9, 9))
        var received: ByteArray? = null
        // Sequence 3 after 1: packet 2 is missing, so the buffered prefix is not a whole unit.
        decoder.packet(rtp(second, sequence = 3, timestamp = 1, marker = true)) { _, _, data -> received = data.copyOf() }
        assertArrayEquals(byteArrayOf(9, 9), received)
        assertEquals(1L, decoder.discontinuities)
    }

    @Test
    fun `a payload type outside the video range is refused`() {
        val packet = rtp(pes(byteArrayOf(1, 2, 3)), sequence = 1, timestamp = 1, marker = true)
        packet[1] = (0x80 or 99).toByte()
        val failure = runCatching { HiCarHiSightMedia(key).packet(packet) { _, _, _ -> } }.exceptionOrNull()
        assertEquals("media RTP kind", failure?.message)
    }

    /** A PES stream: fourteen plaintext header bytes, then the encrypted access unit. */
    private fun pes(accessUnit: ByteArray): ByteArray {
        val encrypted = HiCarRecordCipher(key, 2 * 1024 * 1024).encrypt(accessUnit)
        val size = 8 + encrypted.size
        val out = ByteArray(14 + encrypted.size)
        out[2] = 1
        out[4] = ((size ushr 8) and 0xFF).toByte()
        out[5] = (size and 0xFF).toByte()
        out[6] = 0x84.toByte()
        // 0x80 in byte seven means "no PTS", which keeps the payload at a fixed 14-byte offset.
        out[7] = 0x80.toByte()
        encrypted.copyInto(out, 14)
        return out
    }

    private fun rtp(payload: ByteArray, sequence: Int, timestamp: Long, marker: Boolean): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        header[2] = (sequence ushr 8).toByte()
        header[3] = sequence.toByte()
        for (index in 0..3) header[4 + index] = (timestamp ushr (24 - index * 8)).toByte()
        return header + payload
    }
}

/** Audio and input: two more places where one wrong byte produces silence rather than an error. */
class HiCarAudioAndInputTest {
    private val key = ByteArray(16) { (it + 7).toByte() }

    @Test
    fun `an adts frame is unpacked into one aac access unit`() {
        val aac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frame = adts(aac, rateIndex = 3, channels = 2)
        val media = HiCarAudioMedia()
        val parsed = media.receive(rtp(frame, sequence = 1, timestamp = 0, ssrc = 1100))
        assertEquals(1, parsed.size)
        assertArrayEquals(aac, parsed.single().data)
        assertEquals(48_000, parsed.single().sampleRate)
        assertEquals(2, parsed.single().channels)
    }

    @Test
    fun `a duplicate rtp sequence is dropped, not played twice`() {
        val frame = adts(byteArrayOf(9, 9, 9), rateIndex = 3, channels = 1)
        val media = HiCarAudioMedia()
        assertEquals(1, media.receive(rtp(frame, sequence = 1, timestamp = 0, ssrc = 1100)).size)
        assertEquals(0, media.receive(rtp(frame, sequence = 1, timestamp = 0, ssrc = 1100)).size)
        assertEquals(1L, media.duplicates)
    }

    @Test
    fun `the udp record wraps the ciphertext with the marker the phone uses`() {
        val rtp = rtp(adts(byteArrayOf(4, 5), 3, 2), sequence = 1, timestamp = 0, ssrc = 1)
        val cipher = HiCarRecordCipher(key, 65_536)
        val record = HiCarAudioMedia.record(rtp, cipher)
        assertEquals(0xAB, record[0].toInt() and 0xFF)
        val size = ((record[1].toInt() and 0xFF) shl 8) or (record[2].toInt() and 0xFF)
        assertEquals(record.size - 3, size)
        assertArrayEquals(rtp, HiCarAudioMedia.decrypt(record, HiCarRecordCipher(key, 65_536)))
    }

    @Test
    fun `a touch encodes the marker, the length and the 800 by 480 coordinates`() {
        val encoder = HiCarUiInputEncoder(key)
        val record = encoder.encode(HiCarInputEvent.touch(HiCarInputEvent.ACTION_DOWN, 400, 240), 1_234)
        // The length field counts itself plus the ciphertext, so it equals the whole record.
        val length = ((record[2].toInt() and 0xFF) shl 8) or (record[3].toInt() and 0xFF)
        assertEquals(record.size, length)
        val plain = HiCarRecordCipher(key, 1_500).decrypt(record.copyOfRange(4, record.size))
        // Six marker bytes, then action, length, flags, x, y, elapsed, terminator.
        for (index in 0 until 6) assertEquals(0xFF, plain[index].toInt() and 0xFF)
        assertEquals(0, plain[6].toInt())
        assertEquals(11, ((plain[7].toInt() and 0xFF) shl 8) or (plain[8].toInt() and 0xFF))
        // Body starts after the six marker bytes: x occupies 5-6 and y 7-8 of the body.
        assertEquals(400, ((plain[11].toInt() and 0xFF) shl 8) or (plain[12].toInt() and 0xFF))
        assertEquals(240, ((plain[13].toInt() and 0xFF) shl 8) or (plain[14].toInt() and 0xFF))
    }

    @Test
    fun `a key event carries the android key code`() {
        val encoder = HiCarUiInputEncoder(key)
        val record = encoder.encode(HiCarInputEvent.key(4, down = true), 0)
        val plain = HiCarRecordCipher(key, 1_500).decrypt(record.copyOfRange(4, record.size))
        assertEquals(HiCarInputEvent.ACTION_KEY_DOWN, plain[6].toInt())
        assertEquals(9, ((plain[7].toInt() and 0xFF) shl 8) or (plain[8].toInt() and 0xFF))
        assertEquals(4, ((plain[10].toInt() and 0xFF) shl 8) or (plain[11].toInt() and 0xFF))
    }

    @Test
    fun `the encoder refuses a negative coordinate`() {
        val encoder = HiCarUiInputEncoder(key)
        val failure = runCatching {
            encoder.encode(HiCarInputEvent.touch(HiCarInputEvent.ACTION_MOVE, -1, 10), 0)
        }.exceptionOrNull()
        assertEquals("touch coordinates", failure?.message)
    }

    @Test
    fun `the router refuses a coordinate outside the picture it was told`() {
        val router = HiCarInputRouter()
        val sent = ArrayList<Int>()
        router.attach(object : HiCarInputRouter.Sender {
            override fun send(event: HiCarInputEvent): Boolean {
                sent.add(event.action)
                return true
            }
        })
        // A 1080p picture: 800 would have been out of range at the old fixed size but is inside one.
        router.setDisplaySize(1920, 1080)
        assertTrue(router.touch(HiCarInputEvent.ACTION_DOWN, 800, 10))
        assertTrue(router.touch(HiCarInputEvent.ACTION_MOVE, 1919, 1079))
        assertTrue(!router.touch(HiCarInputEvent.ACTION_MOVE, 1920, 10))
        assertTrue(!router.touch(HiCarInputEvent.ACTION_MOVE, 10, 1080))
        assertTrue(router.touch(HiCarInputEvent.ACTION_UP, 10, 10))
        assertEquals(listOf(0, 2, 1), sent)
    }

    @Test
    fun `the router maps view pixels onto whatever size the picture is`() {
        val router = HiCarInputRouter()
        router.setDisplaySize(1920, 1080)
        assertArrayEquals(intArrayOf(960, 540), router.map(50f, 50f, 100, 100, clamp = false))
        assertEquals(null, router.map(120f, 50f, 100, 100, clamp = false))
        assertArrayEquals(intArrayOf(1919, 1079), router.map(120f, 120f, 100, 100, clamp = true))
    }

    @Test
    fun `the input router refuses a move before a down and after an up`() {
        val router = HiCarInputRouter()
        val sent = ArrayList<Int>()
        router.attach(object : HiCarInputRouter.Sender {
            override fun send(event: HiCarInputEvent): Boolean {
                sent.add(event.action)
                return true
            }
        })
        assertTrue(!router.touch(HiCarInputEvent.ACTION_MOVE, 10, 10))
        assertTrue(router.touch(HiCarInputEvent.ACTION_DOWN, 10, 10))
        assertTrue(!router.touch(HiCarInputEvent.ACTION_DOWN, 10, 10))
        assertTrue(router.touch(HiCarInputEvent.ACTION_UP, 10, 10))
        assertTrue(!router.touch(HiCarInputEvent.ACTION_MOVE, 10, 10))
        assertEquals(listOf(0, 1), sent)
    }

    @Test
    fun `view pixels map onto the 800 by 480 space and clamp only when asked`() {
        assertArrayEquals(intArrayOf(400, 240), HiCarInputRouter.map(50f, 50f, 100, 100, clamp = false))
        assertEquals(null, HiCarInputRouter.map(120f, 50f, 100, 100, clamp = false))
        assertArrayEquals(intArrayOf(799, 479), HiCarInputRouter.map(120f, 120f, 100, 100, clamp = true))
    }

    private fun adts(aac: ByteArray, rateIndex: Int, channels: Int): ByteArray {
        val length = aac.size + 7
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xF1.toByte()
        // Profile 1 (= AAC-LC after the +1), the rate index, then the high channel bit.
        frame[2] = (((1 shl 6) or (rateIndex shl 2) or ((channels shr 2) and 1))).toByte()
        frame[3] = (((channels and 3) shl 6) or ((length ushr 11) and 3)).toByte()
        frame[4] = ((length ushr 3) and 0xFF).toByte()
        frame[5] = (((length and 7) shl 5) or 0x1F).toByte()
        frame[6] = 0xFC.toByte()
        aac.copyInto(frame, 7)
        return frame
    }

    private fun rtp(payload: ByteArray, sequence: Int, timestamp: Long, ssrc: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 96
        header[2] = (sequence ushr 8).toByte()
        header[3] = sequence.toByte()
        for (index in 0..3) header[4 + index] = (timestamp ushr (24 - index * 8)).toByte()
        header[10] = ((ssrc ushr 8) and 0x0F).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header + payload
    }

    private fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
}
