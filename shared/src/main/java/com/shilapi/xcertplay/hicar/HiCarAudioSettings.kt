package com.shilapi.xcertplay.hicar

import android.content.Context

/**
 * Which of the head unit's output channels each of the two important sounds goes to.
 *
 * Android has two ways to name an output: `AudioAttributes` (usage) and the legacy stream type
 * number. A car unit's own satnav usually lives on a stream type the platform does not know — 11 and
 * up are vendor additions on most Chinese head units — and only the legacy constructor can name it.
 * So the value is kept as a bare number and `0` means "let the system choose by usage", which is
 * what this receiver did before and what works on a unit with no special channels.
 *
 * Media and navigation are the only two that may be chosen: prompts, calls and ringtones are too
 * short to be worth pinning, and pinning a call to the wrong channel is worse than leaving it.
 */
data class HiCarAudioSettings(
    val mediaStream: Int = AUTO,
    val navigationStream: Int = AUTO,
) {
    init {
        require(normalize(mediaStream) == mediaStream) { "media stream" }
        require(normalize(navigationStream) == navigationStream) { "navigation stream" }
    }

    internal fun streamFor(route: HiCarAudioRoute): Int = when (route) {
        HiCarAudioRoute.MEDIA -> mediaStream
        HiCarAudioRoute.NAVIGATION -> navigationStream
        else -> AUTO
    }

    companion object {
        const val AUTO = 0
        const val MIN_STREAM = 1

        /**
         * The highest number a head unit might use for its own channels.
         *
         * The legacy API takes any int, but the platform's own stream types stop at 10 and a vendor
         * channel is a small addition on top; accepting 999 would just mean a typo silently builds
         * an `AudioTrack` that never makes a sound.
         */
        const val MAX_STREAM = 20

        /** Anything outside the range (a stored value from another build, a typo) is automatic. */
        fun normalize(value: Int): Int = if (value in MIN_STREAM..MAX_STREAM) value else AUTO

        fun label(value: Int): String = if (value == AUTO) "自动" else "声道 $value"
    }
}

/** Persists [HiCarAudioSettings]; same shape as the display and video settings. */
object HiCarAudioSettingsStore {
    const val PREFERENCES = "hicar-audio"
    private const val KEY_MEDIA_STREAM = "media_stream"
    private const val KEY_NAVIGATION_STREAM = "navigation_stream"

    fun load(context: Context): HiCarAudioSettings {
        val preferences = preferences(context)
        return HiCarAudioSettings(
            mediaStream = HiCarAudioSettings.normalize(preferences.getInt(KEY_MEDIA_STREAM, 0)),
            navigationStream = HiCarAudioSettings.normalize(preferences.getInt(KEY_NAVIGATION_STREAM, 0)),
        )
    }

    fun save(context: Context, settings: HiCarAudioSettings) {
        preferences(context).edit()
            .putInt(KEY_MEDIA_STREAM, settings.mediaStream)
            .putInt(KEY_NAVIGATION_STREAM, settings.navigationStream)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
