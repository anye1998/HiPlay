package com.shilapi.xcertplay.hicar

import kotlin.math.PI
import kotlin.math.sin

/**
 * The tone the settings page plays when the driver asks "which channel is that?".
 *
 * A channel number is meaningless until it is heard: two numbers that both "work" can be the same
 * speaker on a head unit whose amplifier maps several streams onto one output. A short, quiet sine
 * answers that in a second, and it is generated here rather than shipped as a file so it costs the
 * APK nothing and can be asserted in a unit test.
 */
internal object HiCarPreviewTone {
    const val SAMPLE_RATE = 48_000
    private const val DEFAULT_MILLIS = 700
    private const val DEFAULT_HZ = 880.0

    /**
     * 16-bit little-endian stereo PCM.
     *
     * Two identical channels on purpose: a mono track on a head unit that expects stereo can end up
     * on the left speaker only, which would look like "channel 11 is broken" when it is the preview
     * that is.
     *
     * The amplitude is about -14 dBFS, with a 5 ms fade at each end so switching to the preview does
     * not click through the amplifier.
     */
    fun pcm16(
        millis: Int = DEFAULT_MILLIS,
        hz: Double = DEFAULT_HZ,
        amplitude: Double = 0.2,
        sampleRate: Int = SAMPLE_RATE,
    ): ByteArray {
        require(millis in 1..5_000) { "preview length" }
        require(sampleRate in 8_000..192_000) { "preview rate" }
        require(hz > 0 && hz < sampleRate / 2) { "preview tone" }
        require(amplitude > 0 && amplitude <= 1) { "preview amplitude" }
        val frames = sampleRate.toLong() * millis / 1000
        val fade = sampleRate / 200 // 5 ms
        val out = ByteArray(frames.toInt() * 4)
        var at = 0
        for (frame in 0 until frames) {
            val ramp = when {
                frame < fade -> frame.toDouble() / fade
                frame >= frames - fade -> (frames - frame).toDouble() / fade
                else -> 1.0
            }
            val value = (sin(2 * PI * hz * frame / sampleRate) * amplitude * ramp * Short.MAX_VALUE).toInt()
            val low = (value and 0xFF).toByte()
            val high = ((value shr 8) and 0xFF).toByte()
            // Left then right, identical.
            out[at++] = low
            out[at++] = high
            out[at++] = low
            out[at++] = high
        }
        return out
    }
}
