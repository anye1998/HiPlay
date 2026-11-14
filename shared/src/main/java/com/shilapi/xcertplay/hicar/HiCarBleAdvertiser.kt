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
                onLog(
                    "HiCar advertisement started (${serviceData.size} bytes of 0xFDEE service data, " +
                        "device name in the scan response)",
                )
                started.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                outcome.set(false)
                onLog("HiCar advertisement refused: ${reason(errorCode)}")
                started.countDown()
            }
        }
        val settingsBuilder = baseSettings()
        val askedForPublicAddress = requestPublicAddress(settingsBuilder)
        val settings = settingsBuilder.build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(HiCarCodes.SERVICE_DISCOVERY), serviceData)
            .setIncludeDeviceName(false)
            .build()
        // The 0xFDEE service data plus the flags fill 18 of the 31 bytes a legacy advertisement
        // allows, so the name cannot ride along in the same packet — it goes into the scan response.
        // That is not cosmetic: without a name a phone-side scanner lists this device as "N/A", and
        // an "N/A" row cannot be told apart from the other nameless advertisers in the room, which
        // is exactly the check that decides whether the advertisement ever left the tablet.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        return try {
            try {
                advertise(leAdvertiser, settings, data, scanResponse, callback)
            } catch (denied: SecurityException) {
                // The hidden setter accepted the value but the stack refuses to act on it without
                // BLUETOOTH_PRIVILEGED. Drop the request rather than the beacon: an advertisement
                // with a private address still proves everything else in the chain.
                if (!askedForPublicAddress) throw denied
                onLog(
                    "HiCar advertisement: the public address needs BLUETOOTH_PRIVILEGED " +
                        "(${denied.message}); retrying with the platform's own address",
                )
                advertise(leAdvertiser, baseSettings().build(), data, scanResponse, callback)
            }
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

    /**
     * The advertising parameters HiCar needs regardless of address type.
     *
     * Connectable + legacy is exactly the ADV_IND event Huawei requires; a non-connectable event
     * (ADV_NONCONN_IND) makes the phone connect and then never show the HiCar prompt.
     */
    private fun baseSettings(): AdvertiseSettings.Builder = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .setTimeout(0)

    /** Starts advertising, dropping the device name if this stack will not carry a scan response. */
    private fun advertise(
        leAdvertiser: BluetoothLeAdvertiser,
        settings: AdvertiseSettings,
        data: AdvertiseData,
        scanResponse: AdvertiseData,
        callback: AdvertiseCallback,
    ) {
        try {
            leAdvertiser.startAdvertising(settings, data, scanResponse, callback)
        } catch (unsupported: IllegalArgumentException) {
            // A stack that refuses a scan response still takes the advertisement; losing the name
            // is better than losing the beacon.
            onLog("HiCar advertisement: scan response refused (${unsupported.message}); advertising without the name")
            leAdvertiser.startAdvertising(settings, data, callback)
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

    /**
     * Asks the platform to advertise with the adapter's public address instead of a private one.
     *
     * This is the one HiCar requirement an app cannot satisfy through the SDK. A HiCar phone takes
     * the head unit's BR/EDR address from the advertisement's *own address*: Huawei's HiCar
     * Bluetooth adaptation guide tells vendors to patch AOSP (`own_addr_type = BLE_ADDR_PUBLIC`,
     * and the random address replaced with the controller address), and its fallback for stacks
     * that keep a random MAC is a whole extra GATT server that hands the fixed MAC to the phone.
     * Neither is an app-level API: `AdvertiseSettings.Builder.setOwnAddressType` is `@hide
     * @SystemApi` and needs `BLUETOOTH_PRIVILEGED` for any value but the default.
     *
     * So this tries the hidden setter and reports what actually happened. A `NoSuchMethodException`
     * here is the answer, not a failure to work around: it means the advertisement will carry a
     * resolvable private address, and a phone that derives the BR address from it has nothing to
     * dial — which looks exactly like "the phone ignores a beacon it clearly received".
     */
    private fun requestPublicAddress(builder: AdvertiseSettings.Builder): Boolean {
        val parameterType = Int::class.javaPrimitiveType ?: return false
        return try {
            builder.javaClass.getMethod("setOwnAddressType", parameterType)
                .invoke(builder, ADDRESS_TYPE_PUBLIC)
            onLog(
                "HiCar advertisement: public advertising address accepted by the platform; check the " +
                    "phone's scanner — the address must now be the adapter's own, not a private one",
            )
            true
        } catch (failure: Throwable) {
            onLog(
                "HiCar advertisement: the platform keeps a private advertising address " +
                    "(${failure.javaClass.simpleName}); a HiCar phone reads the BR address from that " +
                    "field, so it has nothing to dial and never shows its prompt",
            )
            false
        }
    }

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

    private companion object {
        /** `AdvertisingSetParameters.ADDRESS_TYPE_PUBLIC`, @hide @SystemApi, value 0. */
        const val ADDRESS_TYPE_PUBLIC = 0
    }
}
