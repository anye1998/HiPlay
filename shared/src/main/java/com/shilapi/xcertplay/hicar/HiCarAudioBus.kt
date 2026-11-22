package com.shilapi.xcertplay.hicar

/**
 * What a HiCar audio packet is *for*, read from the RTP SSRC.
 *
 * The reference implementation treats the SSRC as two fields rather than a random id:
 *
 * ```
 *   31            16 15              0
 *   +---------------+----------------+
 *   |  usage (16)   |  1000 + bus    |
 *   +---------------+----------------+
 * ```
 *
 * `bus` is the audio channel the phone separated the sound into (8 is music, 0 is navigation when
 * it is split out); `usage` is an Android `AudioAttributes.USAGE_*` value, which is how a guidance
 * prompt is told apart from music even when both arrive on one UDP port.
 *
 * This file is pure logic so the mapping can be tested without a phone.
 */
internal object HiCarAudioBus {
    const val UNKNOWN = -1

    /** The bus music always uses when the phone splits the audio. */
    const val MUSIC = 8

    /** The bus navigation guidance uses when it is not mixed into music. */
    const val NAVIGATION = 0

    private const val BASE = 1000L
    private const val MAX_BUS = 63

    /** The bus number, or [UNKNOWN] when the low half is outside the documented range. */
    fun busOf(ssrc: Long): Int {
        val low = ssrc and 0xFFFF
        return if (low in BASE..BASE + MAX_BUS) (low - BASE).toInt() else UNKNOWN
    }

    /** The Android usage in the high half; 0 means "the phone did not say". */
    fun usageOf(ssrc: Long): Int = ((ssrc ushr 16) and 0xFFFF).toInt()
}

/** The playback route a packet belongs to. Each route owns one player and one audio focus. */
internal enum class HiCarAudioRoute {
    MEDIA,
    NAVIGATION,
    VOICE,
    CALL,
    RINGTONE,
    ;

    companion object {
        /**
         * Maps an Android usage to a route.
         *
         * The numbers are `AudioAttributes.USAGE_*`: 1 media, 14 game, 12 navigation, 2/3 call,
         * 6 ringtone, and the rest (alarm, notification, assistant, …) are short prompts.
         * A usage of 0 means the phone did not label the stream, so the bus decides:
         * bus 0 is the split navigation bus and everything else is treated as music.
         */
        fun of(usage: Int, bus: Int): HiCarAudioRoute = when (usage) {
            1, 14 -> MEDIA
            12 -> NAVIGATION
            2, 3 -> CALL
            6 -> RINGTONE
            4, 5, 7, 8, 9, 10, 11, 13, 16, 17, 18 -> VOICE
            0 -> if (bus == HiCarAudioBus.NAVIGATION) NAVIGATION else MEDIA
            else -> if (bus == HiCarAudioBus.NAVIGATION) NAVIGATION else MEDIA
        }
    }
}
