package com.shilapi.xcertplay.hicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Publishes the 0xFDEE advertisement so a nearby Huawei phone offers HiCar.
 *
 * The advertisement is connectable but never bonded: the phone may connect to the RFCOMM records
 * without pairing, which is the whole point of the ephemeral session model. Advertising is
 * therefore started only after both RFCOMM records exist, so a phone that reacts immediately still
 * finds a listener.
 */
class HiCarBleAdvertiser(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val adapter: BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callbacks: AdvertiseCallback? = null

    val available: Boolean
        get() = runCatching {
            val local = adapter ?: return false
            local.isEnabled && local.isMultipleAdvertisementSupported && local.bluetoothLeAdvertiser != null
        }.getOrDefault(false)

    /** Starts advertising [serviceData]; true means the platform accepted the request. */
    @SuppressLint("MissingPermission")
    fun start(serviceData: ByteArray): Boolean {
        val local = adapter
        if (local == null) {
            onLog("HiCar advertisement: no Bluetooth adapter")
            return false
        }
        if (!available) {
            onLog("HiCar advertisement: BLE advertising is unavailable on this head unit")
            return false
        }
        stop()
        if (!hasAdvertisePermission()) {
            // startAdvertising would throw SecurityException here and lose the real reason; say it
            // first, because the rest of the chain (access point, RFCOMM) is already up by now.
            onLog("HiCar advertisement needs the nearby-devices permission (BLUETOOTH_ADVERTISE)")
            return false
        }
        // Logging the adapter state makes "nobody reacted" explainable, but these getters are
        // permission-checked: a diagnostic line must never be what stops the advertisement.
        runCatching { onLog(describeAdapter(local, serviceData)) }
        val leAdvertiser = local.bluetoothLeAdvertiser ?: return false
        val started = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                outcome.set(true)
                onLog("HiCar advertisement started (${serviceData.size} bytes of 0xFDEE service data)")
                started.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                outcome.set(false)
                onLog("HiCar advertisement refused: ${reason(errorCode)}")
                started.countDown()
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(HiCarCodes.SERVICE_DISCOVERY), serviceData)
            .setIncludeDeviceName(false)
            .build()
        return try {
            leAdvertiser.startAdvertising(settings, data, callback)
            advertiser = leAdvertiser
            callbacks = callback
            // startAdvertising is asynchronous on most builds; a refusal arrives via the callback.
            started.await(2, TimeUnit.SECONDS)
            // A request still pending after the wait is treated as started: its failure callback
            // still arrives and is reported then.
            outcome.get() ?: true
        } catch (failure: RuntimeException) {
            onLog("HiCar advertisement failed: ${failure.javaClass.simpleName}")
            false
        }
    }

    override fun close() {
        stop()
    }

    private fun stop() {
        val running = advertiser
        val callback = callbacks
        advertiser = null
        callbacks = null
        if (running == null || callback == null) return
        try {
            running.stopAdvertising(callback)
        } catch (_: RuntimeException) {
            // Stopping an advertisement the platform already dropped is not an error.
        }
    }

    /**
     * Adapter state plus the payload, with each getter guarded separately.
     *
     * `isMultipleAdvertisementSupported` and the advertiser handle are readable here (they got us this
     * far) while `getAddress`/`getScanMode`/`getName` are refused on some vendor builds. One refused
     * getter used to swallow the whole line, which is why an earlier log had no address at all.
     */
    @SuppressLint("MissingPermission")
    private fun describeAdapter(local: BluetoothAdapter, serviceData: ByteArray): String {
        fun <T> read(block: () -> T): String = try {
            block()?.toString() ?: "none"
        } catch (failure: Exception) {
            "${failure.javaClass.simpleName}"
        }
        val mode = try {
            describeScanMode(local.scanMode)
        } catch (failure: Exception) {
            failure.javaClass.simpleName
        }
        // The local class of device would be worth logging — a tablet advertises itself as a computer,
        // and a receiver a phone only accepts as car audio would be ignored on that alone — but
        // BluetoothAdapter.getBluetoothClass() is not in the public SDK, so an app cannot even read
        // it, let alone set it. See the capability table in docs/HICAR_MIGRATION.md.
        return "HiCar advertisement: address=${read { local.address }}, mode=$mode, " +
            "name='${read { local.name }}', " +
            "payload=${serviceData.joinToString(" ") { String.format(Locale.ROOT, "%02x", it) }}"
    }

    private fun hasAdvertisePermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun describeScanMode(mode: Int): String = when (mode) {
        BluetoothAdapter.SCAN_MODE_NONE -> "not connectable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "connectable, not discoverable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "discoverable"
        else -> "mode $mode"
    }

    private fun reason(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "code $errorCode"
    }
}
