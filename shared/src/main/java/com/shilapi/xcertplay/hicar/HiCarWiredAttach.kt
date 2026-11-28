package com.shilapi.xcertplay.hicar

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The USB-attach alias that makes the system grant the cable's permission by itself.
 *
 * Android records a USB grant per device instance, and a phone that accepted the car-info block
 * re-enumerates as a different device — so a plain receiver always costs two permission prompts per
 * insertion. An **activity alias** carrying the same `USB_DEVICE_ATTACHED` device filter is what turns
 * that into zero: the system then offers 「默认使用」, and once it is ticked the grant is recorded and
 * delivered with the attach, before the receiver even runs.
 *
 * Measured by OpenHC: the system's auto-grant arrives a few tens to ~150 ms *after* the attach
 * broadcast, which is why [graceMillis] exists — asking at once would pop a dialog next to the grant
 * the system was about to give.
 *
 * The enabled state of the component *is* the setting, so nothing is duplicated in preferences.
 */
internal object HiCarWiredAttach {

    /** The alias declared in the manifest, targeting the host activity. */
    private const val ALIAS = "com.shilapi.xcertplay.HiCarUsbAttachAlias"

    /** How long to wait for the system's own grant before asking, when the alias is enabled. */
    private const val GRACE_MS = 800L

    fun enabled(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** Turns the alias on or off. Its state is the setting; the system keeps any 「默认使用」 across it. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
        }
    }

    /** How long the cable waits for the system's own grant before showing a prompt. */
    fun graceMillis(context: Context): Long = if (enabled(context)) GRACE_MS else 0L

    private fun component(context: Context) = ComponentName(context.packageName, ALIAS)
}
