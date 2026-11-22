package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The audio decisions that can be checked without a speaker: which channel a lane is pinned to, and
 * what the preview tone actually contains.
 *
 * The rest of the sink is device work — an `AudioTrack` on a head unit either makes a sound or does
 * not. What is testable is the part that silently goes wrong: a channel number stored from a build
 * that wrote something else, a lane that would be pinned to the wrong stream, a preview that is
 * silent because its amplitude came out as zero.
 */
class HiCarAudioLaneTest {
    @Test
    fun `only a number a head unit could use is kept`() {
        assertEquals(HiCarAudioSettings.AUTO, HiCarAudioSettings.normalize(0))
        assertEquals(3, HiCarAudioSettings.normalize(3))
        assertEquals(14, HiCarAudioSettings.normalize(14))
        assertEquals(HiCarAudioSettings.MAX_STREAM, HiCarAudioSettings.normalize(20))
        // A negative, a typo and a value from some future build all fall back to automatic.
        assertEquals(HiCarAudioSettings.AUTO, HiCarAudioSettings.normalize(-1))
        assertEquals(HiCarAudioSettings.AUTO, HiCarAudioSettings.normalize(21))
        assertEquals(HiCarAudioSettings.AUTO, HiCarAudioSettings.normalize(999))
    }

    @Test
    fun `each lane takes its own channel and the rest stay automatic`() {
        val settings = HiCarAudioSettings(mediaStream = 3, navigationStream = 14)
        assertEquals(3, settings.streamFor(HiCarAudioRoute.MEDIA))
        assertEquals(14, settings.streamFor(HiCarAudioRoute.NAVIGATION))
        // Prompts, calls and ringtones are too short to be worth pinning.
        assertEquals(0, settings.streamFor(HiCarAudioRoute.VOICE))
        assertEquals(0, settings.streamFor(HiCarAudioRoute.CALL))
        assertEquals(0, settings.streamFor(HiCarAudioRoute.RINGTONE))
    }

    @Test
    fun `usage decides the route before the bus does`() {
        assertEquals(HiCarAudioRoute.NAVIGATION, HiCarAudioRoute.of(12, HiCarAudioBus.MUSIC))
        assertEquals(HiCarAudioRoute.MEDIA, HiCarAudioRoute.of(1, HiCarAudioBus.NAVIGATION))
        assertEquals(HiCarAudioRoute.CALL, HiCarAudioRoute.of(2, 0))
        assertEquals(HiCarAudioRoute.RINGTONE, HiCarAudioRoute.of(6, 0))
        // An unlabelled stream is decided by the bus: 0 is the split navigation bus.
        assertEquals(HiCarAudioRoute.NAVIGATION, HiCarAudioRoute.of(0, HiCarAudioBus.NAVIGATION))
        assertEquals(HiCarAudioRoute.MEDIA, HiCarAudioRoute.of(0, HiCarAudioBus.MUSIC))
    }

    @Test
    fun `the preview tone is stereo, audible and the length it says`() {
        val pcm = HiCarPreviewTone.pcm16(millis = 200, hz = 880.0, sampleRate = 48_000)
        // 200 ms of 16-bit stereo: 9600 frames, four bytes each.
        assertEquals(38_400, pcm.size)
        var peak = 0
        var first = -1
        var last = -1
        var at = 0
        while (at + 3 < pcm.size) {
            val left = ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
            val right = ((pcm[at + 2].toInt() and 0xFF) or (pcm[at + 3].toInt() shl 8)).toShort().toInt()
            assertEquals("both channels carry the same sample", left, right)
            if (first < 0) first = left
            last = left
            peak = maxOf(peak, abs(left))
            at += 4
        }
        // About -14 dBFS: loud enough to hear over an idling engine, quiet enough not to startle.
        assertTrue("peak $peak", peak in 5_000..7_500)
        // The fade means the first and last sample are near silence, so no click reaches the amp.
        assertTrue(abs(first) < peak / 4)
        assertTrue(abs(last) < peak / 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a preview longer than the setting may hold is refused`() {
        HiCarPreviewTone.pcm16(millis = 10_000)
    }

    @Test
    fun `a channel choice survives a round trip through the store format`() {
        // What the settings page writes and reads back: only the two numbers, normalised on load.
        assertEquals(
            HiCarAudioSettings(mediaStream = 3, navigationStream = 14),
            HiCarAudioSettings(
                mediaStream = HiCarAudioSettings.normalize(3),
                navigationStream = HiCarAudioSettings.normalize(14),
            ),
        )
        assertEquals("自动", HiCarAudioSettings.label(0))
        assertEquals("声道 14", HiCarAudioSettings.label(14))
    }
}
