package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SSRC fields, the gain curve and the tunnel hold are pure logic, so they are pinned here. */
class HiCarAudioBusTest {
    @Test
    fun `the ssrc splits into bus and usage`() {
        // Music: bus 8, usage 1 (USAGE_MEDIA).
        val music = (1L shl 16) or (1008L)
        assertEquals(8, HiCarAudioBus.busOf(music))
        assertEquals(1, HiCarAudioBus.usageOf(music))

        // Navigation: bus 0, usage 12 (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).
        val navigation = (12L shl 16) or 1000L
        assertEquals(0, HiCarAudioBus.busOf(navigation))
        assertEquals(12, HiCarAudioBus.usageOf(navigation))
    }

    @Test
    fun `an ssrc outside the documented range is unknown, not guessed`() {
        assertEquals(-1, HiCarAudioBus.busOf(0x1234L))
        assertEquals(-1, HiCarAudioBus.busOf(999L))
        assertEquals(-1, HiCarAudioBus.busOf(1064L))
    }

    @Test
    fun `usage picks the route and the bus breaks ties`() {
        assertEquals(HiCarAudioRoute.MEDIA, HiCarAudioRoute.of(1, 8))
        assertEquals(HiCarAudioRoute.NAVIGATION, HiCarAudioRoute.of(12, 8))
        assertEquals(HiCarAudioRoute.CALL, HiCarAudioRoute.of(2, 8))
        assertEquals(HiCarAudioRoute.RINGTONE, HiCarAudioRoute.of(6, 8))
        assertEquals(HiCarAudioRoute.VOICE, HiCarAudioRoute.of(4, 8))
        // No usage: the split navigation bus still means navigation, anything else is music.
        assertEquals(HiCarAudioRoute.NAVIGATION, HiCarAudioRoute.of(0, HiCarAudioBus.NAVIGATION))
        assertEquals(HiCarAudioRoute.MEDIA, HiCarAudioRoute.of(0, HiCarAudioBus.MUSIC))
    }
}

class HiCarSpeedGainTest {
    private val curve = HiCarSpeedGainCurve(startKmh = 30.0, fullKmh = 100.0, maxGainDb = 10.0)

    @Test
    fun `below the start speed nothing is added`() {
        assertEquals(0.0, curve.gainDb(0.0), 1e-9)
        assertEquals(0.0, curve.gainDb(29.9), 1e-9)
        assertEquals(0.0, curve.gainDb(30.0), 1e-9)
    }

    @Test
    fun `the middle is linear and the top is capped`() {
        assertEquals(5.0, curve.gainDb(65.0), 1e-9)
        assertEquals(10.0, curve.gainDb(100.0), 1e-9)
        assertEquals(10.0, curve.gainDb(180.0), 1e-9)
    }

    @Test
    fun `invalid speeds are treated as standing still`() {
        assertEquals(0.0, curve.gainDb(Double.NaN), 1e-9)
        assertEquals(0.0, curve.gainDb(-5.0), 1e-9)
        assertEquals(0.0, curve.gainDb(Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test
    fun `the smoother snaps once then moves at a bounded rate`() {
        val smoother = HiCarGainSmoother(maxDbPerSecond = 1.5)
        assertEquals(10.0, smoother.next(10.0, 1_000), 1e-9)
        // Already at the target: nothing to move.
        assertEquals(10.0, smoother.next(10.0, 1_000), 1e-9)
        // A drop of 10 dB at 1.5 dB/s for one second moves 1.5 dB.
        assertEquals(8.5, smoother.next(0.0, 1_000), 1e-9)
        smoother.reset()
        assertEquals(0.0, smoother.next(0.0, 1_000), 1e-9)
    }
}

class HiCarPcmGainTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(buffer: ByteArray, index: Int): Int {
        val raw = ((buffer[index * 2 + 1].toInt() and 0xFF) shl 8) or (buffer[index * 2].toInt() and 0xFF)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    @Test
    fun `zero gain leaves the block untouched`() {
        val buffer = pcm(1000, -1000, 32767, -32768)
        val before = buffer.copyOf()
        HiCarPcmGain.applyPcm16(buffer, 0, buffer.size, 0.0)
        assertTrue(before.contentEquals(buffer))
    }

    @Test
    fun `six decibels doubles quiet samples`() {
        val buffer = pcm(1000, -1000, 5000, -5000)
        HiCarPcmGain.applyPcm16(buffer, 0, buffer.size, 6.0206)
        assertEquals(2000, sampleAt(buffer, 0))
        assertEquals(-2000, sampleAt(buffer, 1))
        assertEquals(10000, sampleAt(buffer, 2))
        assertEquals(-10000, sampleAt(buffer, 3))
    }

    @Test
    fun `loud samples are limited instead of wrapped`() {
        val buffer = pcm(30000, -30000, 32767, -32768)
        val stats = HiCarPcmGain.applyPcm16(buffer, 0, buffer.size, 10.0)
        for (index in 0..3) {
            val value = sampleAt(buffer, index)
            assertTrue("sample $index must stay in range: $value", value in -32768..32767)
        }
        // The limiter has to be monotonic: a louder input still comes out louder.
        assertTrue(sampleAt(buffer, 0) > 0)
        assertTrue(sampleAt(buffer, 1) < 0)
        assertTrue("the limiter must have engaged", stats.limited >= 2)
    }

    @Test
    fun `an odd tail byte is left alone and an offset is honoured`() {
        val buffer = byteArrayOf(0x11, 0x22, 0x33)
        HiCarPcmGain.applyPcm16(buffer, 1, 2, 0.0)
        assertEquals(0x11.toByte(), buffer[0])
        assertEquals(0x22.toByte(), buffer[1])
        assertEquals(0x33.toByte(), buffer[2])

        val padded = pcm(1000, 1000) + byteArrayOf(0x7F)
        HiCarPcmGain.applyPcm16(padded, 0, padded.size, 6.0206)
        assertEquals(0x7F.toByte(), padded.last())
    }
}

class HiCarSpeedEstimatorTest {
    private val estimator = HiCarSpeedEstimator(
        staleAfterMillis = 8_000,
        holdAfterStaleMillis = 45_000,
        decayKmhPerSecond = 4.0,
    )

    @Test
    fun `without a fix the speed is zero`() {
        assertEquals(0.0, estimator.speedKmh(100_000), 1e-9)
    }

    @Test
    fun `a fresh fix is used as is`() {
        estimator.onFix(80.0, 0)
        assertEquals(80.0, estimator.speedKmh(5_000), 1e-9)
        assertTrue(estimator.isFresh(5_000))
    }

    @Test
    fun `a tunnel holds the last speed, then fades`() {
        estimator.onFix(80.0, 0)
        // 53 s is the end of the hold window (8 + 45).
        assertEquals(80.0, estimator.speedKmh(53_000), 1e-9)
        assertTrue(estimator.isHeld(20_000))
        // Ten seconds past the hold at 4 km/h per second: 80 - 40 = 40.
        assertEquals(40.0, estimator.speedKmh(63_000), 1e-9)
        // Far past the hold the estimate reaches zero and stays there.
        assertEquals(0.0, estimator.speedKmh(200_000), 1e-9)
    }

    @Test
    fun `a fix without speed does not reset the estimate`() {
        estimator.onFix(90.0, 0)
        estimator.onFix(null, 1_000)
        assertEquals(90.0, estimator.speedKmh(2_000), 1e-9)
    }

    @Test
    fun `a new session forgets the previous drive`() {
        estimator.onFix(120.0, 0)
        estimator.reset()
        assertEquals(0.0, estimator.speedKmh(1_000), 1e-9)
    }
}
