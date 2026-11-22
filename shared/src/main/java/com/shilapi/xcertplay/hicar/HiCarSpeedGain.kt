package com.shilapi.xcertplay.hicar

import kotlin.math.abs
import kotlin.math.pow

/**
 * How much louder music should get as the car goes faster.
 *
 * The shape is deliberately piecewise linear with a dead zone, because a car is not a laboratory:
 *
 * ```
 *  gain
 *   max |               ________________
 *       |              /
 *       |             /
 *       |____________/
 *       +------------+------------------+------> km/h
 *       0        startKmh          fullKmh
 * ```
 *
 * - below [startKmh] nothing changes: at a red light or in a car park, road noise does not grow and
 *   boosting music would only make the driver turn it down again;
 * - between the two points the gain rises linearly, so the change is felt as gradual rather than as
 *   a step;
 * - at and above [fullKmh] the gain stops: beyond that, engine and wind noise grow more slowly than
 *   the ear's tolerance for loud music, and every extra dB costs headroom.
 *
 * Values are decibels. The PCM application (and its clipping protection) lives in
 * [HiCarPcmGain]; this class only decides the number.
 */
internal class HiCarSpeedGainCurve(
    val startKmh: Double = DEFAULT_START_KMH,
    val fullKmh: Double = DEFAULT_FULL_KMH,
    val maxGainDb: Double = DEFAULT_MAX_GAIN_DB,
) {
    init {
        require(startKmh >= 0.0) { "speed gain start" }
        require(fullKmh > startKmh) { "speed gain range" }
        require(maxGainDb in 0.0..MAX_ALLOWED_DB) { "speed gain ceiling" }
    }

    /** Gain in dB for a speed in km/h. Invalid speeds are treated as standing still. */
    fun gainDb(kmh: Double): Double {
        if (!kmh.isFinite() || kmh <= startKmh) return 0.0
        if (kmh >= fullKmh) return maxGainDb
        return maxGainDb * (kmh - startKmh) / (fullKmh - startKmh)
    }

    /** Linear multiplier for [gainDb]; kept here so the audio path does not repeat the formula. */
    fun linear(kmh: Double): Double = 10.0.pow(gainDb(kmh) / 20.0)

    companion object {
        /** Below this the road noise is low enough that boosting would be unpleasant. */
        const val DEFAULT_START_KMH = 30.0

        /** The gain reaches its ceiling here. */
        const val DEFAULT_FULL_KMH = 100.0

        /**
         * Ceiling of the curve, not of the PCM stage. +10 dB is roughly "twice as loud" to the ear;
         * above that a normal music mix clips hard even after the limiter in [HiCarPcmGain].
         */
        const val DEFAULT_MAX_GAIN_DB = 10.0

        /** Refuse configurations that would make the limiter work permanently. */
        const val MAX_ALLOWED_DB = 18.0
    }
}

/**
 * Moves the applied gain toward the target at a bounded rate.
 *
 * A speed reading can jump (a GPS fix after a tunnel, a wheel-speed sample glitch). Applying the new
 * gain in one step is audible as a click or a surge, so the change is rate limited: the driver hears
 * the music settle over a second or two instead of jumping.
 */
internal class HiCarGainSmoother(
    /** How fast the applied gain may move, in dB per second. */
    private val maxDbPerSecond: Double = DEFAULT_DB_PER_SECOND,
) {
    private var currentDb = 0.0
    private var initialized = false

    /** The gain currently in effect. */
    val valueDb: Double get() = currentDb

    /**
     * Advances toward [targetDb] by at most `maxDbPerSecond * elapsed`.
     *
     * The first call snaps to the target: there is no music playing yet, and ramping up from zero
     * would make the start of a session fade in for no reason.
     */
    fun next(targetDb: Double, elapsedMillis: Long): Double {
        val target = if (targetDb.isFinite()) targetDb else 0.0
        if (!initialized) {
            initialized = true
            currentDb = target
            return currentDb
        }
        if (elapsedMillis <= 0) return currentDb
        val step = maxDbPerSecond * (elapsedMillis / 1000.0)
        val delta = target - currentDb
        currentDb = when {
            abs(delta) <= step -> target
            delta > 0 -> currentDb + step
            else -> currentDb - step
        }
        return currentDb
    }

    /** A new session starts from silence rather than from the previous session's gain. */
    fun reset() {
        currentDb = 0.0
        initialized = false
    }

    companion object {
        /**
         * 1.5 dB/s: from 0 to +10 dB takes about seven seconds, which is roughly how long a car
         * takes to reach motorway speed from a slip road.
         */
        const val DEFAULT_DB_PER_SECOND = 1.5
    }
}
