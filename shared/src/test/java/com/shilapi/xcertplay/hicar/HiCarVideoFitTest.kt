package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window geometry and the stored display mode are pure logic, so they are pinned here instead of
 * being eyeballed on a head unit that is not in reach.
 */
class HiCarVideoFitTest {
    private val tolerance = 0.01f

    @Test
    fun `a wide container letterboxes a 16 by 9 picture on the sides`() {
        // 2000×1000 is wider than 16:9, so the picture is limited by the height.
        val fit = HiCarVideoFit.fit(16f / 9f, 2000f, 1000f)
        assertEquals(1000f, fit.height, tolerance)
        assertEquals(1777.77f, fit.width, tolerance)
        // The shape is preserved, which is the whole point: no squashing on a wide panel.
        assertEquals(16f / 9f, fit.width / fit.height, tolerance)
    }

    @Test
    fun `a tall container letterboxes above and below`() {
        val fit = HiCarVideoFit.fit(16f / 9f, 1000f, 1000f)
        assertEquals(1000f, fit.width, tolerance)
        assertEquals(562.5f, fit.height, tolerance)
        assertEquals(16f / 9f, fit.width / fit.height, tolerance)
    }

    @Test
    fun `a container with the right shape is filled exactly`() {
        val fit = HiCarVideoFit.fit(4f / 3f, 800f, 600f)
        assertEquals(800f, fit.width, tolerance)
        assertEquals(600f, fit.height, tolerance)
    }

    @Test
    fun `degenerate input yields an empty fit, not a negative size`() {
        assertTrue(HiCarVideoFit.fit(16f / 9f, 0f, 600f).isEmpty)
        assertTrue(HiCarVideoFit.fit(16f / 9f, 800f, 0f).isEmpty)
        assertTrue(HiCarVideoFit.fit(16f / 9f, -800f, 600f).isEmpty)
        assertTrue(HiCarVideoFit.fit(16f / 9f, Float.NaN, 600f).isEmpty)
    }

    @Test
    fun `a nonsense aspect ratio falls back to 16 by 9`() {
        val fallback = HiCarVideoFit.fit(0f, 1600f, 900f)
        assertEquals(1600f, fallback.width, tolerance)
        assertEquals(900f, fallback.height, tolerance)

        // A decoder reporting a 100:1 frame must not produce a 16000-pixel-wide window.
        val clamped = HiCarVideoFit.fit(100f, 1600f, 900f)
        assertEquals(HiCarVideoFit.DEFAULT_ASPECT, clamped.width / clamped.height, tolerance)
    }

    @Test
    fun `the decoder's own geometry wins over the offered one`() {
        assertEquals(16f / 9f, HiCarVideoFit.aspectOf(1920, 1080), tolerance)
        assertEquals(4f / 3f, HiCarVideoFit.aspectOf(800, 600), tolerance)
        // A zero height would be a division by zero on every frame; the default is safer.
        assertEquals(HiCarVideoFit.DEFAULT_ASPECT, HiCarVideoFit.aspectOf(1920, 0), tolerance)
        assertEquals(HiCarVideoFit.DEFAULT_ASPECT, HiCarVideoFit.aspectOf(0, 0), tolerance)
    }

    @Test
    fun `the display mode round-trips through its stored value`() {
        for (mode in HiCarWindowMode.values()) {
            assertEquals(mode, HiCarWindowMode.of(mode.stored))
        }
        // An unknown or absent value must not leave the window in an undefined state.
        assertEquals(HiCarWindowMode.FULLSCREEN, HiCarWindowMode.of(null))
        assertEquals(HiCarWindowMode.FULLSCREEN, HiCarWindowMode.of("nonsense"))
    }

    @Test
    fun `each mode reports the bars it keeps`() {
        assertTrue(!HiCarWindowMode.FULLSCREEN.showsStatusBar)
        assertTrue(!HiCarWindowMode.FULLSCREEN.showsNavigationBar)

        assertTrue(HiCarWindowMode.STATUS_BAR.showsStatusBar)
        assertTrue(!HiCarWindowMode.STATUS_BAR.showsNavigationBar)

        assertTrue(HiCarWindowMode.STATUS_BAR_AND_NAVIGATION.showsStatusBar)
        assertTrue(HiCarWindowMode.STATUS_BAR_AND_NAVIGATION.showsNavigationBar)
    }
}
