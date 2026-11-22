package com.shilapi.xcertplay.hicar

/**
 * Holds the speed used for the volume curve together when the source goes quiet.
 *
 * Two sources feed it, and both can stop:
 *
 * - **car bus** (wheel speed over the manufacturer's interface) never stops while the car is on, but
 *   it can be unavailable on a unit whose interface this app does not know;
 * - **location** stops in a tunnel, an underground car park, or when the phone disables GPS.
 *
 * A naive "no fix means 0 km/h" would cut the gain to zero at the exact moment the driver is on a
 * motorway in a tunnel — the opposite of what the feature is for. So a stale fix is held for a
 * while, then decays, so a parked car that lost GPS does not keep a motorway boost forever.
 *
 * The class is pure logic: the caller supplies monotonic milliseconds and decides where the fix
 * came from.
 */
internal class HiCarSpeedEstimator(
    /** How long a fix stays "current" without updates before it is considered stale. */
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    /** How long a stale fix may still be trusted at full value (a tunnel, typically). */
    private val holdAfterStaleMillis: Long = DEFAULT_HOLD_AFTER_STALE_MILLIS,
    /** After the hold, how fast the estimate falls back to zero, in km/h per second. */
    private val decayKmhPerSecond: Double = DEFAULT_DECAY_KMH_PER_SECOND,
    private val maxKmh: Double = DEFAULT_MAX_KMH,
) {
    private var lastKmh = 0.0
    private var lastFixAtMillis = 0L
    private var haveFix = false

    /** True while the estimate is derived from a fix fresher than the hold window. */
    fun isFresh(nowMillis: Long): Boolean = haveFix && nowMillis - lastFixAtMillis <= staleAfterMillis

    /** True while the estimate is held through a gap rather than measured. */
    fun isHeld(nowMillis: Long): Boolean =
        haveFix && nowMillis - lastFixAtMillis in (staleAfterMillis + 1)..(staleAfterMillis + holdAfterStaleMillis)

    /**
     * Records a fix. [kmh] is null when the location had no usable speed; that is *not* a zero, and
     * the previous value is kept rather than replaced.
     */
    fun onFix(kmh: Double?, atMillis: Long) {
        if (kmh == null || !kmh.isFinite()) return
        lastKmh = kmh.coerceIn(0.0, maxKmh)
        lastFixAtMillis = atMillis
        haveFix = true
    }

    /** The speed to use at [nowMillis], after holding and decaying a stale fix. */
    fun speedKmh(nowMillis: Long): Double {
        if (!haveFix) return 0.0
        val age = (nowMillis - lastFixAtMillis).coerceAtLeast(0L)
        val trustedUntil = staleAfterMillis + holdAfterStaleMillis
        if (age <= trustedUntil) return lastKmh
        val decayed = lastKmh - decayKmhPerSecond * ((age - trustedUntil) / 1000.0)
        return decayed.coerceIn(0.0, maxKmh)
    }

    /** A new session starts with no history, so a previous drive cannot colour this one. */
    fun reset() {
        lastKmh = 0.0
        lastFixAtMillis = 0L
        haveFix = false
    }

    companion object {
        /** Normal updates arrive about once a second; three misses mean the source has a problem. */
        const val DEFAULT_STALE_AFTER_MILLIS = 8_000L

        /**
         * A tunnel on a motorway can be 10–20 km; at 100 km/h that is up to twelve minutes. The hold
         * is deliberately shorter than that: holding a guessed speed for too long is worse than
         * letting the boost fade, because the fade is audible but a wrong boost is not noticed until
         * the driver leaves the tunnel and the music is suddenly too loud.
         */
        const val DEFAULT_HOLD_AFTER_STALE_MILLIS = 45_000L

        /** 4 km/h per second: from 100 km/h to zero in about 25 seconds, a gentle fade. */
        const val DEFAULT_DECAY_KMH_PER_SECOND = 4.0

        const val DEFAULT_MAX_KMH = 300.0
    }
}
