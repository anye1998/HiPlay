package com.shilapi.xcertplay.hicar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Which Bluetooth permission each HiCar step actually needs on the running Android version.
 *
 * This exists because the two regimes are different in kind, not just in name. From Android 12
 * (API 31) the nearby-devices permissions are runtime grants; on Android 11 and below they **do not
 * exist at all**, and the same abilities come from install-time `BLUETOOTH`/`BLUETOOTH_ADMIN` plus
 * `ACCESS_FINE_LOCATION` for any LE scan.
 *
 * Checking the API-31 names unconditionally is not a harmless extra: `checkSelfPermission` answers
 * DENIED for a permission the platform does not define, so an Android 10 head unit that holds every
 * permission it can hold gets told it is missing `BLUETOOTH_ADVERTISE` and never advertises at all.
 * That is exactly what happened on the BYD unit (see docs/HICAR_MIGRATION.md 7.15).
 */
object HiCarPermissions {

    /** The permissions that can still produce a system prompt on [sdkInt]. */
    fun runtimeRequests(sdkInt: Int): List<String> = if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            // The self-scan that proves our own advertisement reached the air.
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else {
        // Everything else below API 31 is install-time and cannot be refused at run time; an LE scan
        // is the one Bluetooth step that needs a runtime grant (location).
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(context: Context): List<String> = runtimeRequests(Build.VERSION.SDK_INT)
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun canConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(context, Manifest.permission.BLUETOOTH)
        }

    fun canAdvertise(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            granted(context, Manifest.permission.BLUETOOTH_ADMIN)
        }

    fun canScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** One line for the log, naming the permissions that exist on this build. */
    fun explain(sdkInt: Int): String = if (sdkInt >= Build.VERSION_CODES.S) {
        "SDK $sdkInt: BLUETOOTH_CONNECT / BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE are runtime grants"
    } else {
        "SDK $sdkInt: BLUETOOTH_CONNECT / BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE do not exist below API 31; " +
            "advertising needs BLUETOOTH_ADMIN (install-time) and scanning needs ACCESS_FINE_LOCATION"
    }

    /**
     * Every Bluetooth permission that matters here, granted or not, with the reason when it does not
     * apply. Used by the device audit: a missing line reads as "checked and fine", so each name is
     * printed with its state instead of being left out.
     */
    fun describe(context: Context): List<String> {
        val sdk = Build.VERSION.SDK_INT
        val names = if (sdk >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        val states = names.joinToString(", ") { name ->
            val state = when {
                granted(context, name) -> "granted"
                sdk < Build.VERSION_CODES.S && name != Manifest.permission.ACCESS_FINE_LOCATION ->
                    "not granted (install-time, cannot be requested)"
                else -> "DENIED"
            }
            "${name.substringAfterLast('.')}=$state"
        }
        val notApplicable = if (sdk >= Build.VERSION_CODES.S) {
            "not applicable on SDK $sdk: BLUETOOTH, BLUETOOTH_ADMIN (superseded)"
        } else {
            "not applicable on SDK $sdk: BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE (API 31+)"
        }
        return listOf(
            "bluetooth permissions: $states",
            "bluetooth permissions note: $notApplicable",
            "bluetooth state: canConnect=${canConnect(context)} canScan=${canScan(context)} " +
                "canAdvertise=${canAdvertise(context)}",
        )
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Kept for callers that only have an adapter handy and want to say why advertising is refused. */
    fun adapterUsable(adapter: BluetoothAdapter?): Boolean =
        adapter != null && adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
}
