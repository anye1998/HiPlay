package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.vehicle.VehicleGear
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Speed and gear through the adb shell (autoservice binder), where apps would need a BYD signature.
 * The ids are BYD SDK 1.0.5's speed (km/h) and gearbox devices; on DiLink 5.0 they read 0.0 and 1
 * while parked.
 */
internal object BydWheelSpeed {
    const val SPEED = "service call autoservice 7 i32 1013 i32 -1807745016" // float km/h
    const val GEAR = "service call autoservice 5 i32 1011 i32 555745336" // 1 P, 2 R, 3 N, 4 D

    fun speedCommand(context: Context): String = if (BydVehicleOptions.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.SPEED)?.address?.command() ?: SPEED
    } else SPEED

    fun gearCommand(context: Context): String = if (BydVehicleOptions.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.GEAR)?.address?.command() ?: GEAR
    } else GEAR

    fun metersPerSecond(output: String?): Double? {
        val kmh = BydParcel.value(output)?.let { java.lang.Float.intBitsToFloat(it).toDouble() } ?: return null
        return if (kmh in 0.0..300.0) kmh / 3.6 else null
    }

    fun gear(output: String?): VehicleGear? = when (BydParcel.value(output)) {
        1 -> VehicleGear.PARK
        2 -> VehicleGear.REVERSE
        3 -> VehicleGear.NEUTRAL
        4, 5, 6 -> VehicleGear.DRIVE // M and S are forward selector positions on older SDKs.
        else -> null
    }
}
