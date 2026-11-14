package com.shilapi.xcertplay.hicar

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The probe reports; it must never take the receiver down with it.
 *
 * Robolectric has no Bluetooth radio, which is exactly the state a head unit without BLE presents:
 * the point of these cases is that [HiCarAdvertisementProbe.start] explains itself and leaves
 * [HiCarAdvertisementProbe.running] false instead of throwing into the caller's setup thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class HiCarAdvertisementProbeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun withoutARadioItSaysSoAndStaysStopped() {
        val messages = mutableListOf<String>()
        val probe = HiCarAdvertisementProbe(context) { messages += it }
        probe.start(byteArrayOf(1, 1, 1, 3), durationMillis = 50)
        assertFalse("a scan that never started must not claim to be running", probe.running)
        assertTrue(
            "the reason has to be in the log: $messages",
            messages.any { it.contains("self-scan") },
        )
        probe.close()
    }

    @Test
    fun closingTwiceIsHarmless() {
        val probe = HiCarAdvertisementProbe(context)
        probe.start(byteArrayOf(1), durationMillis = 50)
        probe.close()
        probe.close()
        assertFalse(probe.running)
    }
}
