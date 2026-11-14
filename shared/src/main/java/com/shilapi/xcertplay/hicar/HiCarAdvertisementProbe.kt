package com.shilapi.xcertplay.hicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans for our own 0xFDEE advertisement, so "the phone ignored us" stops being a guess.
 *
 * Up to now every conclusion about discovery rested on the phone's silence, which is
 * indistinguishable from an advertisement that never went on air or went out with the wrong bytes.
 * This scanner runs on the car side for a short window after advertising starts and reports what is
 * actually visible on the air: how many 0xFDEE advertisements were seen, whether the payload matches
 * the one we sent, and a few samples.
 *
 * It needs `BLUETOOTH_SCAN`; without it the platform refuses the scan and that is reported instead of
 * being silently skipped.
 */
class HiCarAdvertisementProbe(
    context: Context,
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val adapter =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val seen = AtomicInteger(0)
    private val ours = AtomicInteger(0)
    private val samples = CopyOnWriteArrayList<String>()

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
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HiCarCodes.SERVICE_DISCOVERY))
            .build()
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
                onLog("HiCar self-scan refused: code $errorCode (SCAN_FAILED_*); the phone would see nothing")
                stop()
            }
        }
        try {
            leScanner.startScan(listOf(filter), settings, listener)
        } catch (denied: SecurityException) {
            onLog("HiCar self-scan needs the nearby-devices permission (BLUETOOTH_SCAN)")
            return
        }
        scanner = leScanner
        callback = listener
        onLog("HiCar self-scan started for ${durationMillis / 1000}s; looking for our own 0xFDEE data")
        handler.postDelayed({ finish() }, durationMillis)
    }

    private fun record(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(ParcelUuid(HiCarCodes.SERVICE_DISCOVERY)) ?: return
        seen.incrementAndGet()
        if (data.contentEquals(expected)) ours.incrementAndGet()
        if (samples.size < MAX_SAMPLES) {
            samples += "${result.device.address} rssi=${result.rssi} data=${hex(data)}"
        }
    }

    /**
     * Reports what the scan saw and stops.
     *
     * "no 0xFDEE advertisement was visible" and "ours was visible but the phone did nothing" lead to
     * opposite next steps, so they must not read the same in the log.
     */
    private fun finish() {
        val total = seen.get()
        val matching = ours.get()
        when {
            total == 0 ->
                onLog(
                    "HiCar self-scan: no 0xFDEE advertisement heard in this window " +
                        "(our own included), so discovery is not reaching the air",
                )
            matching > 0 ->
                onLog(
                    "HiCar self-scan: our advertisement is on the air ($matching of $total match " +
                        "the payload we sent); a phone that ignores it is filtering on content, not missing it",
                )
            else -> {
                onLog("HiCar self-scan: $total 0xFDEE advertisements heard, none matches our payload")
                samples.forEach { onLog("  sample: $it") }
            }
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
