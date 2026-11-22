package com.shilapi.xcertplay.hicar

import android.content.Context

/**
 * When this side may dial the phone instead of waiting to be dialled.
 *
 * HiCar normally has the phone dial the car: the advertisement says which car is present and the
 * phone opens the two RFCOMM channels. That leaves one failure with no local answer — the phone
 * decides the car is already "known", shows nothing, and never dials, while the car waits forever
 * with its listeners open.
 *
 * The way out is the reverse direction, which the protocol also allows: when the phone's classic
 * Bluetooth link comes up (the phone is in the car, connected for calls or media), the car dials the
 * phone's 0xFE35 service. GGBondLab/OpenHC does exactly this and it is what makes a session start
 * without anyone pressing anything.
 *
 * The rules below are deliberately conservative, because a wrong dial is noisy: it starts a
 * connection attempt the driver did not ask for, on a radio that may be busy with a call.
 */
internal object HiCarReconnectTrigger {

    /**
     * How long after a failed attempt this side stays quiet.
     *
     * Short enough that walking back to the car with the phone in a pocket still connects, long
     * enough that a phone bouncing its Bluetooth link cannot turn into a dial loop.
     */
    const val COOLDOWN_MILLIS = 20_000L

    data class Verdict(val dial: Boolean, val reason: String)

    /**
     * @param connected true for `ACL_CONNECTED`, false for `ACL_DISCONNECTED`
     * @param address the phone's Bluetooth address, already normalised to upper case
     * @param known addresses this receiver has completed a session with
     * @param running whether the receiver is accepting connections at all
     * @param enabled the driver's switch
     * @param lastAttemptMillis when this side last dialled, monotonic; 0 for never
     */
    fun decide(
        connected: Boolean,
        address: String,
        known: Set<String>,
        running: Boolean,
        enabled: Boolean,
        lastAttemptMillis: Long,
        nowMillis: Long,
    ): Verdict {
        if (!connected) return Verdict(false, "the phone's Bluetooth link went away")
        if (!running) return Verdict(false, "the receiver is not started")
        if (!enabled) return Verdict(false, "automatic redial is switched off")
        if (address.isBlank()) return Verdict(false, "the phone has no readable address")
        if (address.uppercase() !in known) {
            // Never dialled before: this side has nothing to authenticate against, and the first
            // connection has to come from the phone so the PIN can be typed.
            return Verdict(false, "first connection with this phone: it has to dial once")
        }
        if (lastAttemptMillis != 0L && nowMillis - lastAttemptMillis in 0 until COOLDOWN_MILLIS) {
            return Verdict(false, "redialled ${(nowMillis - lastAttemptMillis) / 1000}s ago")
        }
        return Verdict(true, "the phone's Bluetooth link is up and this car knows it")
    }
}

/** Which phones this receiver may dial without asking. */
object HiCarPhoneRegistry {
    private const val PREFERENCES = "hicar-phones"
    private const val KEY_ADDRESSES = "addresses"

    /** At most this many; a car that has been paired with a fleet does not need more. */
    private const val MAX_PHONES = 4

    fun known(context: Context): Set<String> {
        val stored = preferences(context).getStringSet(KEY_ADDRESSES, emptySet()).orEmpty()
        return stored.map { it.uppercase() }.toSet()
    }

    /**
     * Remembers a phone that completed a session.
     *
     * Only a completed authentication is worth remembering: an address seen on a failed attempt may
     * belong to a phone that never got through, and dialling it later would be a call into nothing.
     */
    fun remember(context: Context, address: String) {
        val normalised = address.trim().uppercase()
        if (normalised.count { it == ':' } != 5) return
        val current = known(context).toMutableList()
        if (normalised in current) {
            // Move it to the end so the oldest one is the one dropped.
            current.remove(normalised)
        }
        current.add(normalised)
        while (current.size > MAX_PHONES) current.removeAt(0)
        preferences(context).edit().putStringSet(KEY_ADDRESSES, current.toSet()).apply()
    }

    fun forget(context: Context) {
        preferences(context).edit().remove(KEY_ADDRESSES).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/** Whether the driver wants this side to dial a known phone when it appears. */
object HiCarReconnectSettings {
    private const val PREFERENCES = "hicar-reconnect"
    private const val KEY_ENABLED = "redial_on_bluetooth"

    /**
     * On by default.
     *
     * The failure it prevents is the one that looks like the app is broken: the phone is in the car,
     * Bluetooth is connected, and the receiver just sits there. The dial only happens for a phone
     * that has completed a session before.
     */
    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
