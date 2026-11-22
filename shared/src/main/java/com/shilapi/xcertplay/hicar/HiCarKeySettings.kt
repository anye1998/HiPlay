package com.shilapi.xcertplay.hicar

import android.content.Context

/**
 * Whether the car's media keys are handed to the phone.
 *
 * On by default: with a connection up, a steering-wheel "next" that changes the car's own radio
 * instead of the phone's music is the surprising behaviour. It is still a switch, because a head unit
 * whose radio shares those keys may want them back.
 */
object HiCarKeySettings {
    const val PREFERENCES = "hicar-keys"
    private const val KEY_MEDIA_KEYS = "media_keys"

    fun mediaKeysEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MEDIA_KEYS, true)

    fun setMediaKeysEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_MEDIA_KEYS, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
