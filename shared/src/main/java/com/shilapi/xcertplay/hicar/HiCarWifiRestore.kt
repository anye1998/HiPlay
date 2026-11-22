package com.shilapi.xcertplay.hicar

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Puts the head unit's own Wi-Fi back after the receiver stops.
 *
 * A `LocalOnlyHotspot` on Android 10 takes the only radio: bringing the access point up drops the
 * station connection the car was using for its own traffic, and nothing brings it back when the group
 * is released. The driver is left on a unit whose Wi-Fi is simply off until they open settings.
 *
 * Whether this side may turn it back on is not in our hands. `setWifiEnabled` has been closed to
 * non-system apps since Android 10, so on this head unit the answer is usually "no" — which is why
 * the result says so instead of pretending. When it is refused, the screen offers the one action
 * that does work: the system's Wi-Fi panel.
 */
object HiCarWifiRestore {

    /** What the radio was doing before the receiver took it. */
    fun snapshot(context: Context): Boolean = wifi(context)?.isWifiEnabled == true

    /**
     * Turns Wi-Fi back on if it was on before and is off now.
     *
     * Called once, when the receiver actually stops — not between rounds. A phone that drops and
     * reconnects would otherwise have its access point torn down under it.
     */
    fun restore(context: Context, wasEnabled: Boolean, onLog: (String) -> Unit): Result {
        if (!wasEnabled) return Result.NOT_NEEDED
        val manager = wifi(context) ?: return Result.NOT_NEEDED
        if (manager.isWifiEnabled) return Result.NOT_NEEDED
        val accepted = runCatching { manager.setWifiEnabled(true) }.getOrDefault(false)
        return if (accepted) {
            onLog("wifi: the head unit's own Wi-Fi is back on")
            Result.RESTORED
        } else {
            onLog(
                "wifi: the hotspot left the head unit's Wi-Fi off and this build may not switch it " +
                    "back (setWifiEnabled is closed to apps since Android 10); open Wi-Fi settings",
            )
            Result.REFUSED
        }
    }

    /** Whether the platform is even likely to honour [restore]; used by the settings page copy. */
    val maySwitchItself: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    enum class Result { NOT_NEEDED, RESTORED, REFUSED }

    private fun wifi(context: Context): WifiManager? =
        runCatching { context.applicationContext.getSystemService(WifiManager::class.java) }.getOrNull()
}

/** Whether the receiver should try to give the radio back when it stops. */
object HiCarWifiSettings {
    private const val PREFERENCES = "hicar-wifi"
    private const val KEY_RESTORE = "restore_after_session"

    /** On by default: the failure it prevents is silent and the cost of trying is one API call. */
    fun restoreEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE, true)

    fun setRestoreEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESTORE, enabled).apply()
    }
}
