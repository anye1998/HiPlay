package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two parts of the picture path that a phone cannot be asked about without a phone: what is
 * offered in `his_video_formats`, and what a touch packet looks like once it carries several
 * fingers.
 *
 * Both were a single hard-coded value before (`fps 30;gop 30;bitrate 2000000`, and a 14-byte
 * one-contact body), so the failures this covers are new rather than hypothetical: a 120 Hz panel
 * that gets 30 frames, a pinch that the phone reads as two taps.
 */
class HiCarVideoAndTouchTest {
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val fullHd = HiCarDisplaySize.FULL_HD
    private val hd = HiCarDisplaySize(1280, 720)

    // ---------------------------------------------------------------- video offer

    @Test
    fun `standard quality offers the largest step the screen fits at the panel rate`() {
        val offer = HiCarVideoSettings().offer(fullHd, 60f)
        assertEquals(1920, offer.width)
        assertEquals(1080, offer.height)
        assertEquals(60, offer.fps)
        assertEquals(60, offer.gop)
        // 30 fps is the reference; 60 needs half again as much, not twice: the extra frames repeat.
        assertEquals(3_000_000, offer.bitrate)
        assertEquals(1_500_000, offer.minBitrate)
        assertEquals(4_500_000, offer.maxBitrate)
    }

    @Test
    fun `smooth caps the picture at 720p and lowers the bitrate`() {
        val offer = HiCarVideoSettings(quality = HiCarVideoQuality.SMOOTH).offer(fullHd, 60f)
        assertEquals(1280, offer.width)
        assertEquals(720, offer.height)
        assertEquals(1_800_000, offer.bitrate)
    }

    @Test
    fun `sharp keeps the resolution and spends the difference on bitrate`() {
        val offer = HiCarVideoSettings(quality = HiCarVideoQuality.SHARP).offer(fullHd, 60f)
        assertEquals(1920, offer.width)
        assertEquals(1080, offer.height)
        assertEquals(4_800_000, offer.bitrate)
    }

    @Test
    fun `a small screen is never offered more than it can show`() {
        val offer = HiCarVideoSettings(quality = HiCarVideoQuality.SHARP).offer(hd, 60f)
        assertEquals(1280, offer.width)
        assertEquals(720, offer.height)
    }

    @Test
    fun `frame rate follows the panel but is quantised to what phones encode`() {
        val settings = HiCarVideoSettings()
        // 59.94 Hz is a 60 Hz panel with an HDMI clock behind it; 0 Hz is what a dead bridge reports.
        assertEquals(60, settings.fpsFor(59.94f))
        assertEquals(60, settings.fpsFor(60f))
        assertEquals(90, settings.fpsFor(89.9f))
        assertEquals(120, settings.fpsFor(120f))
        assertEquals(30, settings.fpsFor(24f))
        assertEquals(60, settings.fpsFor(0f))
        assertEquals(60, settings.fpsFor(Float.NaN))
    }

    @Test
    fun `a fixed frame rate ignores the panel`() {
        val fixed = HiCarVideoSettings(frameRate = HiCarFrameRateMode.FPS_30)
        assertEquals(30, fixed.fpsFor(120f))
        assertEquals(30, fixed.offer(fullHd, 120f).gop)
        val sixty = HiCarVideoSettings(frameRate = HiCarFrameRateMode.FPS_60)
        assertEquals(60, sixty.fpsFor(30f))
    }

    @Test
    fun `the formats line carries every field the phone reads`() {
        val offer = HiCarVideoSettings().offer(fullHd, 60f)
        val line = offer.formats(density = 320, screenWidth = 1920, screenHeight = 1080)
        assertTrue(line.startsWith("codecs 1;"))
        assertTrue(line.contains("fps 60;gop 60;"))
        assertTrue(line.contains("bitrate ${offer.bitrate};"))
        assertTrue(line.contains("vbr-min ${offer.minBitrate};"))
        assertTrue(line.contains("vbr-max ${offer.maxBitrate};"))
        assertTrue(line.contains("dpi 320;scr-w 1920;scr-h 1080;"))
        assertTrue(line.contains("width 1920;height 1080"))
    }

    @Test
    fun `stored values survive a rename of the enum`() {
        assertEquals(HiCarVideoQuality.SMOOTH, HiCarVideoQuality.of("smooth"))
        assertEquals(HiCarVideoQuality.BALANCED, HiCarVideoQuality.of("nonsense"))
        assertEquals(HiCarFrameRateMode.FPS_60, HiCarFrameRateMode.of("60"))
        assertEquals(HiCarFrameRateMode.FOLLOW_DISPLAY, HiCarFrameRateMode.of(null))
    }

    // ---------------------------------------------------------------- touch body

    @Test
    fun `one contact is a ten byte body with a seven byte declared length`() {
        val body = body(HiCarInputEvent.touch(HiCarInputEvent.ACTION_DOWN, 100, 200))
        assertEquals(10, body.size)
        assertEquals(0, body[0].toInt())
        assertEquals(7, be16(body, 1))
        assertEquals(1, body[3].toInt())
        assertEquals(0, body[4].toInt())
        assertEquals(100, be16(body, 5))
        assertEquals(200, be16(body, 7))
        // `4 + 5N` is odd for one contact, so a pad byte closes the body.
        assertEquals(0, body[9].toInt())
    }

    @Test
    fun `two contacts share one packet, sorted by pointer id`() {
        val body = body(
            HiCarInputEvent.touch(
                HiCarInputEvent.ACTION_MOVE,
                listOf(HiCarTouchPoint(5, 300, 400), HiCarTouchPoint(2, 10, 20)),
            ),
        )
        assertEquals(14, body.size)
        assertEquals(2, body[0].toInt())
        assertEquals(11, be16(body, 1))
        assertEquals(2, body[3].toInt())
        // Sorted: id 2 first even though it was passed second.
        assertEquals(2, body[4].toInt())
        assertEquals(10, be16(body, 5))
        assertEquals(20, be16(body, 7))
        assertEquals(5, body[9].toInt())
        assertEquals(300, be16(body, 10))
        assertEquals(400, be16(body, 12))
    }

    @Test
    fun `ten contacts still fit one record and keep the declared length consistent`() {
        val points = (0 until 10).map { HiCarTouchPoint(it, it * 10, it * 20) }
        val body = body(HiCarInputEvent.touch(HiCarInputEvent.ACTION_MOVE, points))
        assertEquals(54, body.size)
        assertEquals(51, be16(body, 1))
        assertEquals(10, body[3].toInt())
        // Even body, no pad: the last byte is the y of the tenth contact.
        assertEquals(180, be16(body, 52))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a repeated pointer id is refused before it reaches the phone`() {
        HiCarInputEvent.touch(
            HiCarInputEvent.ACTION_MOVE,
            listOf(HiCarTouchPoint(1, 10, 10), HiCarTouchPoint(1, 20, 20)),
        )
    }

    @Test(expected = HiCarWireException::class)
    fun `a coordinate no packet can carry is refused`() {
        val encoder = HiCarUiInputEncoder(key)
        encoder.encode(HiCarInputEvent.touch(HiCarInputEvent.ACTION_DOWN, 70_000, 0), 0)
    }

    // ---------------------------------------------------------------- router rules

    private class Recorder : HiCarInputRouter.Sender {
        val events = ArrayList<HiCarInputEvent>()
        override fun send(event: HiCarInputEvent): Boolean {
            events.add(event)
            return true
        }
    }

    private fun router(): Pair<HiCarInputRouter, Recorder> {
        val router = HiCarInputRouter()
        val recorder = Recorder()
        router.setDisplaySize(1920, 1080)
        router.attach(recorder)
        return router to recorder
    }

    @Test
    fun `a second down is refused while a finger is still down`() {
        val (router, recorder) = router()
        assertTrue(router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 10, 10))))
        assertFalse(router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(1, 20, 20))))
        assertEquals(1, recorder.events.size)
    }

    @Test
    fun `a move before any down is refused`() {
        val (router, _) = router()
        assertFalse(router.touches(HiCarInputEvent.ACTION_MOVE, listOf(HiCarTouchPoint(0, 10, 10))))
    }

    @Test
    fun `a second finger goes out as a move carrying both contacts`() {
        val (router, recorder) = router()
        router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 100, 100)))
        assertTrue(
            router.touches(
                HiCarInputEvent.ACTION_MOVE,
                listOf(HiCarTouchPoint(0, 100, 100), HiCarTouchPoint(1, 200, 200)),
            ),
        )
        assertEquals(2, recorder.events.last().points.size)
        assertEquals(HiCarInputEvent.ACTION_MOVE, recorder.events.last().action)
    }

    @Test
    fun `an up may only name one contact that is down`() {
        val (router, recorder) = router()
        router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 10, 10)))
        router.touches(
            HiCarInputEvent.ACTION_MOVE,
            listOf(HiCarTouchPoint(0, 10, 10), HiCarTouchPoint(1, 20, 20)),
        )
        assertFalse(
            router.touches(
                HiCarInputEvent.ACTION_UP,
                listOf(HiCarTouchPoint(0, 10, 10), HiCarTouchPoint(1, 20, 20)),
            ),
        )
        assertFalse(router.touches(HiCarInputEvent.ACTION_UP, listOf(HiCarTouchPoint(7, 10, 10))))
        assertTrue(router.touches(HiCarInputEvent.ACTION_UP, listOf(HiCarTouchPoint(0, 10, 10))))
        assertEquals(HiCarInputEvent.ACTION_UP, recorder.events.last().action)
    }

    @Test
    fun `cancel lifts the gesture with one anchor and forgets the others`() {
        val (router, recorder) = router()
        router.touches(
            HiCarInputEvent.ACTION_DOWN,
            listOf(HiCarTouchPoint(3, 100, 100), HiCarTouchPoint(4, 200, 200)),
        )
        assertTrue(router.cancelTouch())
        val up = recorder.events.last()
        assertEquals(HiCarInputEvent.ACTION_UP, up.action)
        assertEquals(1, up.points.size)
        assertEquals(3, up.points.single().id)
        // Nothing is left down, so a second cancel has nothing to say.
        assertFalse(router.cancelTouch())
        // And a new gesture may start.
        assertTrue(router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 5, 5))))
    }

    @Test
    fun `cancel releases touches without releasing held media keys`() {
        val (router, recorder) = router()
        router.mediaKey(87, down = true)
        router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 10, 10)))
        router.cancelTouch()
        val keys = recorder.events.filter { it.action == HiCarInputEvent.ACTION_KEY_UP }
        assertTrue("a cancelled touch must not lift the wheel", keys.isEmpty())
        // The full cancel still does.
        router.cancel()
        assertTrue(recorder.events.any { it.action == HiCarInputEvent.ACTION_KEY_UP })
    }

    @Test
    fun `a contact outside the picture is refused whole`() {
        val (router, _) = router()
        assertFalse(router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(0, 1920, 10))))
        assertFalse(router.touches(HiCarInputEvent.ACTION_DOWN, listOf(HiCarTouchPoint(32, 10, 10))))
    }

    // ---------------------------------------------------------------- helpers

    /** The plain body of a packet: after the 4-byte header, the IV and the six marker bytes. */
    private fun body(event: HiCarInputEvent): ByteArray {
        val packet = HiCarUiInputEncoder(key).encode(event, 0)
        val plain = HiCarRecordCipher(key).decrypt(packet.copyOfRange(4, packet.size))
        assertEquals(0xFF.toByte(), plain[0])
        return plain.copyOfRange(6, plain.size)
    }

    private fun be16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
