package com.shilapi.xcertplay.hicar

import android.content.Context

/**
 * The speed-volume settings, shared by the receiver and the settings screen.
 *
 * Kept in `SharedPreferences` rather than a settings framework so a car update cannot silently
 * change how loud the music gets: every value has an explicit default and an explicit range.
 */
data class HiCarSpeedVolumeSettings(
    val enabled: Boolean = false,
    /** Below this speed nothing is added; a car park is not a motorway. */
    val startKmh: Double = HiCarSpeedGainCurve.DEFAULT_START_KMH,
    /** The gain reaches its ceiling here. */
    val fullKmh: Double = HiCarSpeedGainCurve.DEFAULT_FULL_KMH,
    /** Ceiling of the curve, in dB. */
    val maxGainDb: Double = HiCarSpeedGainCurve.DEFAULT_MAX_GAIN_DB,
    /** `auto` uses the car bus first and falls back to location; the others pin one source. */
    val source: Source = Source.AUTO,
) {
    enum class Source { AUTO, VEHICLE, LOCATION }

    internal fun curve(): HiCarSpeedGainCurve = HiCarSpeedGainCurve(
        startKmh = startKmh.coerceIn(0.0, 200.0),
        fullKmh = fullKmh.coerceIn(startKmh + 1.0, 300.0),
        maxGainDb = maxGainDb.coerceIn(0.0, HiCarSpeedGainCurve.MAX_ALLOWED_DB),
    )

    companion object {
        const val PREFERENCES = "hicar-speed-volume"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START = "start_kmh"
        private const val KEY_FULL = "full_kmh"
        private const val KEY_MAX_GAIN = "max_gain_db"
        private const val KEY_SOURCE = "source"

        /** The range the settings page may offer; outside it the curve is clamped anyway. */
        const val MIN_START_KMH = 10.0
        const val MAX_START_KMH = 90.0
        const val MIN_FULL_KMH = 50.0
        const val MAX_FULL_KMH = 160.0
        const val MAX_GAIN_DB = 18.0

        fun load(context: Context): HiCarSpeedVolumeSettings {
            val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return HiCarSpeedVolumeSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                startKmh = prefs.getFloat(KEY_START, HiCarSpeedGainCurve.DEFAULT_START_KMH.toFloat()).toDouble(),
                fullKmh = prefs.getFloat(KEY_FULL, HiCarSpeedGainCurve.DEFAULT_FULL_KMH.toFloat()).toDouble(),
                maxGainDb = prefs.getFloat(KEY_MAX_GAIN, HiCarSpeedGainCurve.DEFAULT_MAX_GAIN_DB.toFloat()).toDouble(),
                source = runCatching { Source.valueOf(prefs.getString(KEY_SOURCE, Source.AUTO.name)!!) }
                    .getOrDefault(Source.AUTO),
            )
        }

        fun save(context: Context, settings: HiCarSpeedVolumeSettings) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putFloat(KEY_START, settings.startKmh.toFloat())
                .putFloat(KEY_FULL, settings.fullKmh.toFloat())
                .putFloat(KEY_MAX_GAIN, settings.maxGainDb.toFloat())
                .putString(KEY_SOURCE, settings.source.name)
                .apply()
        }
    }
}
