package com.shilapi.xcertplay.hicar

import kotlin.math.abs
import kotlin.math.pow

/**
 * Applies a gain to 16-bit little-endian PCM, with a soft limiter instead of a hard clip.
 *
 * The requirement is to make music louder with speed **inside the car's own audio path**, never by
 * touching the head unit's or the phone's volume: the phone's stream is decoded here, so multiplying
 * its samples is the only place the change is both immediate and reversible.
 *
 * A plain multiply-and-clamp turns loud passages into square waves (audible distortion, and worse on
 * small speakers). The limiter keeps the sample monotonic and bounded instead:
 *
 * ```
 *   |v| <= knee            -> unchanged
 *   |v| >  knee            -> knee + (32767 - knee) * x / (1 + x),  x = (|v| - knee) / (32767 - knee)
 * ```
 *
 * `x / (1 + x)` rises quickly at first and approaches 1 asymptotically, so a +10 dB boost still
 * reaches full scale for a real transient without being chopped flat. It costs one division per
 * sample that crosses the knee; quiet samples take the branch-free multiply only.
 */
internal object HiCarPcmGain {
    /** Samples above this fraction of full scale go through the limiter. */
    const val DEFAULT_KNEE = 0.80

    private const val FULL_SCALE = 32767.0

    /** What one block cost, for the diagnostic log; never contains audio. */
    data class Stats(val samples: Int, val limited: Int, val peakBefore: Int, val peakAfter: Int)

    /**
     * Multiplies [length] bytes of 16-bit little-endian PCM starting at [offset] in place.
     *
     * [gainDb] is clamped to the range the curve allows, so a bad caller cannot blow up the audio.
     * An odd trailing byte (which should not happen in PCM) is left untouched.
     */
    fun applyPcm16(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size,
        gainDb: Double,
        knee: Double = DEFAULT_KNEE,
    ): Stats {
        if (length < 2 || offset < 0 || offset + length > buffer.size) {
            return Stats(0, 0, 0, 0)
        }
        val gain = linear(gainDb)
        if (gain == 1.0) return Stats(length / 2, 0, 0, 0)
        val kneeValue = (FULL_SCALE * knee.coerceIn(0.0, 0.99))
        val headroom = FULL_SCALE - kneeValue
        var limited = 0
        var peakBefore = 0
        var peakAfter = 0
        var index = offset
        val end = offset + (length and 1.inv())
        while (index + 1 < end) {
            // Little endian: the low byte comes first.
            val sample = ((buffer[index + 1].toInt() and 0xFF) shl 8) or (buffer[index].toInt() and 0xFF)
            val signed = if (sample >= 0x8000) sample - 0x10000 else sample
            val amplified = signed * gain
            val magnitude = abs(amplified)
            if (magnitude > peakBefore) peakBefore = magnitude.toInt()
            val out = if (magnitude <= kneeValue) {
                amplified
            } else {
                limited++
                val x = (magnitude - kneeValue) / headroom
                val limitedMagnitude = kneeValue + headroom * (x / (1.0 + x))
                if (amplified < 0) -limitedMagnitude else limitedMagnitude
            }
            val rounded = out.toInt().coerceIn(-32768, 32767)
            if (abs(rounded) > peakAfter) peakAfter = abs(rounded)
            buffer[index] = (rounded and 0xFF).toByte()
            buffer[index + 1] = ((rounded shr 8) and 0xFF).toByte()
            index += 2
        }
        return Stats((end - offset) / 2, limited, peakBefore, peakAfter)
    }

    /** Decibels to a linear multiplier; invalid or non-positive input is unity. */
    fun linear(gainDb: Double): Double {
        val db = gainDb.coerceIn(-60.0, HiCarSpeedGainCurve.MAX_ALLOWED_DB)
        return 10.0.pow(db / 20.0)
    }
}
