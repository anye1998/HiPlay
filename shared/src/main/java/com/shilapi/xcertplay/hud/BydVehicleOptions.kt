package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * The two vehicle options the speed path still consults.
 *
 * Both used to live in `BydOutputSettings`, a class that mostly described how CarPlay's cluster and
 * HUD outputs should look. That half is gone; the probe switch is what the speed reader needs, and it
 * keeps its old key so an installation that already chose a legacy probe keeps that choice.
 */
internal object BydVehicleOptions {
    private const val PREFS = "diplay_byd_outputs"
    private const val KEY_LEGACY_VEHICLE_PROBE = "legacy_vehicle_probe"

    /**
     * Whether the addresses saved by the head-unit probe are used instead of DiLink 5.0 constants.
     *
     * A saved probe means this device was already found to answer at other addresses; a fresh
     * installation stays on the default path, exactly as before.
     */
    fun legacyVehicleProbe(context: Context): Boolean {
        val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (settings.contains(KEY_LEGACY_VEHICLE_PROBE)) {
            settings.getBoolean(KEY_LEGACY_VEHICLE_PROBE, false)
        } else {
            BydVehicleFieldStore.load(context) != null
        }
    }
}
