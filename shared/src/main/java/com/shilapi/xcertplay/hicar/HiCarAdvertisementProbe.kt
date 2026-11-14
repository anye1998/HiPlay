package com.shilapi.xcertplay.hicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans for 0xFDEE advertisements on the car side, mostly to learn what is actually on the air.
 *
 * The scan is deliberately **unfiltered**. An earlier version filtered on the service UUID and, when
 * it heard nothing, reported "discovery is not reaching the air" — an over-claim: a radio cannot
 * receive while it transmits, and many controllers never report the device's own advertisements to
 * the host, so silence is not proof of absence. Scanning everything separates the cases that matter:
 *
 * - nothing at all was heard → the scanner itself is not working (permission, radio, filter), and no
 *   conclusion about our advertisement is possible;
 * - other devices were heard but none carries 0xFDEE → either our advertisement is not on the air or
 *   this device cannot hear itself; only a second device can tell those apart;
 * - an 0xFDEE payload equal to ours was heard → proof it reached the air;
 * - 0xFDEE payloads that differ → someone else's HiCar device is nearby, and the samples show how a
 *   real payload looks.
 */
class HiCarAdvertisementProbe(
    context: Context,
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val adapter =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val heard = AtomicInteger(0)
    private val fdee = AtomicInteger(0)
    private val matching = AtomicInteger(0)
    private val devices = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * One entry per advertised payload, so the report can be compared with a phone-side scanner
     * screen line by line. Grouping by address alone would hide a device that advertises more than
     * one payload, and grouping by payload alone would hide how many radios are involved.
     */
    private class Sighting(
        val address: String,
        @Volatile var payload: ByteArray,
        @Volatile var name: String?,
        @Volatile var rssi: Int,
        @Volatile var ours: Boolean,
    ) {
        val count = AtomicInteger(1)
    }

    private val sightings = java.util.Collections.synchronizedMap(LinkedHashMap<String, Sighting>())

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var expected: ByteArray = ByteArray(0)

    /** True once [start] has been called and not yet stopped; the log only needs a summary. */
    val running: Boolean get() = callback != null

    @SuppressLint("MissingPermission")
    fun start(serviceData: ByteArray, durationMillis: Long = DEFAULT_DURATION_MILLIS) {
        if (running) return
        expected = serviceData
        val local = adapter
        if (local == null || !local.isEnabled) {
            onLog("HiCar self-scan: no usable Bluetooth adapter")
            return
        }
        val leScanner = runCatching { local.bluetoothLeScanner }.getOrNull()
        if (leScanner == null) {
            onLog("HiCar self-scan: BLE scanning is unavailable")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        val listener = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                record(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::record)
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("HiCar self-scan refused: code $errorCode (SCAN_FAILED_*); the scanner itself is not running")
                stop()
            }
        }
        try {
            // No filter: a filtered scan that hears nothing cannot distinguish "not transmitted"
            // from "filter never matched".
            leScanner.startScan(null, settings, listener)
        } catch (denied: SecurityException) {
            onLog("HiCar self-scan needs the nearby-devices permission (BLUETOOTH_SCAN)")
            return
        }
        scanner = leScanner
        callback = listener
        onLog("HiCar self-scan started for ${durationMillis / 1000}s; listening for any 0xFDEE payload")
        handler.postDelayed({ finish() }, durationMillis)
    }

    private fun record(result: ScanResult) {
        heard.incrementAndGet()
        val address = runCatching { result.device.address }.getOrNull() ?: "unknown"
        devices.add(address)
        val record = result.scanRecord ?: return
        val data = record.getServiceData(ParcelUuid(HiCarCodes.SERVICE_DISCOVERY)) ?: return
        fdee.incrementAndGet()
        val isOurs = data.contentEquals(expected)
        if (isOurs) matching.incrementAndGet()
        // The name travels in the scan response, so a scanner that only reads the advertisement
        // packet sees none — that is a limit of the report, not evidence about our own payload.
        val name = runCatching { record.deviceName ?: result.device.name }.getOrNull()
        val key = "$address ${hex(data)}"
        synchronized(sightings) {
            val known = sightings[key]
            if (known != null) {
                known.count.incrementAndGet()
                known.rssi = result.rssi
                if (name != null) known.name = name
            } else {
                sightings[key] = Sighting(address, data, name, result.rssi, isOurs)
            }
        }
    }

    /**
     * Reports what the scan saw, in terms of what can and cannot be concluded.
     *
     * The wording matters more than the numbers: "not heard" is not the same claim as "not sent".
     */
    private fun finish() {
        val total = heard.get()
        val fdeeTotal = fdee.get()
        val ours = matching.get()
        val seen = synchronized(sightings) { sightings.values.toList() }
        when {
            total == 0 ->
                onLog(
                    "HiCar self-scan: heard no BLE advertisement at all, so the scanner is not " +
                        "working (permission or radio); nothing can be concluded about our payload",
                )
            ours > 0 -> {
                val who = seen.firstOrNull { it.ours }
                onLog(
                    "HiCar self-scan: our payload is on the air ($ours time(s)" +
                        (who?.let { ", from ${it.address}${it.name?.let { name -> " '$name'" } ?: ""}" } ?: "") +
                        "); a phone that ignores it is filtering on content, not missing it",
                )
            }
            fdeeTotal > 0 -> {
                val others = seen.filterNot { it.ours }.distinctBy { it.address }
                onLog(
                    "HiCar self-scan: ${others.size} other 0xFDEE advertiser(s) heard, none carrying " +
                        "our payload. Compare these lines with the phone's scanner before concluding " +
                        "anything: the tablet may simply not hear itself",
                )
                seen.sortedByDescending { it.count.get() }.take(MAX_SAMPLES).forEach { sighting ->
                    onLog(
                        "  ${sighting.address} x${sighting.count.get()} rssi=${sighting.rssi} " +
                            "name=${sighting.name?.let { "'$it'" } ?: "none"} " +
                            "data=${hex(sighting.payload)}",
                    )
                }
                onLog("  ours, never heard: ${hex(expected)}")
            }
            else ->
                onLog(
                    "HiCar self-scan: heard ${devices.size} other BLE device(s) but no 0xFDEE payload. " +
                        "This device may simply not hear its own advertisement, so this is NOT proof " +
                        "that nothing was transmitted; a second device must check",
                )
        }
        stop()
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.ROOT, "%02x", it) }

    override fun close() = stop()

    private fun stop() {
        val runningScanner = scanner
        val runningCallback = callback
        scanner = null
        callback = null
        handler.removeCallbacksAndMessages(null)
        if (runningScanner == null || runningCallback == null) return
        runCatching { runningScanner.stopScan(runningCallback) }
    }

    companion object {
        /** Long enough to catch a few advertising intervals, short enough not to hold the radio. */
        const val DEFAULT_DURATION_MILLIS = 20_000L
        private const val MAX_SAMPLES = 5
    }
}
