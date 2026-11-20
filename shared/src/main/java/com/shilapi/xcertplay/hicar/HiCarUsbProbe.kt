package com.shilapi.xcertplay.hicar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Asks a plugged-in phone to switch to the NCM configuration HiCar uses on a cable.
 *
 * Huawei's wired flow is documented up to one step: the car sends "一条 USB 控制信息，包含当前的车辆
 * 信息、是否支持 NCM、IP 和端口", and the phone answers by re-enumerating with a vendor-specific NCM
 * interface. That message is specified only in the NDA copy of the SDK (`driver_usb.c`), so its bytes
 * are not available here.
 *
 * What *is* available is a clean oracle. This head unit's kernel carries `huawei_cdc_ncm`, which
 * binds nothing but VID 0x12D1 interfaces of class 0xff/0x02/0x16 (and 0x46, 0x76, 0x03/0x16) — the
 * exact shape HiCar's wired mode presents. So "did the message land" is not a guess: the phone either
 * re-appears with that interface, or it does not.
 *
 * The search is therefore run as evidence rather than belief. First every vendor request code is
 * offered with a one-byte body and the return value recorded: Android's composite gadget stalls
 * whatever it does not implement, so the codes that answer are the ones worth talking to. Then each
 * accepted code is given the vehicle information in the several shapes a car could plausibly send.
 * Anything that makes the phone re-enumerate ends the search and is logged as the answer.
 *
 * Everything here is behind `setprop debug.hicar.usbprobe 1`: firing vendor requests at the phone's
 * gadget is not something to do on every plug-in, and a request the phone half-handles is best
 * followed by a deliberate replug rather than a surprise.
 */
internal class HiCarUsbProbe(
    context: Context,
    private val model: String,
    private val port: Int,
    private val onLog: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)
    private var registered = false

    @Volatile private var busy = false

    private val permission = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            onLog("USB probe: permission for ${device.deviceName} ${if (granted) "granted" else "refused"}")
            if (granted) launch(device)
        }
    }

    /** Entry point: called for every attached device, decides whether this one is worth probing. */
    fun offer(device: UsbDevice) {
        if (!HiCarDebug.enabled(PROP)) return
        if (device.vendorId != HUAWEI_VID) return
        if (inNcmConfiguration(device)) {
            onLog("USB probe: the phone is already in the HiCar NCM configuration; nothing to ask for")
            return
        }
        if (busy) {
            onLog("USB probe: a probe is already running")
            return
        }
        val usb = manager ?: return
        register()
        if (usb.hasPermission(device)) {
            launch(device)
            return
        }
        onLog("USB probe: asking for permission to talk to ${device.deviceName}")
        usb.requestPermission(device, permissionIntent())
    }

    private fun launch(device: UsbDevice) {
        if (busy) return
        busy = true
        Thread({ run(device) }, "hicar-usb-probe").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun run(device: UsbDevice) {
        val usb = manager ?: return stop()
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        val connection = runCatching { usb.openDevice(device) }.getOrNull()
        if (connection == null) {
            onLog("USB probe: could not open ${device.deviceName}; the phone may be in use by the system")
            return stop()
        }
        try {
            // Step one: which vendor request codes does this phone answer at all?
            val accepted = ArrayList<Int>()
            for (code in 1..MAX_REQUEST) {
                val result = connection.controlTransfer(REQUEST_OUT, code, 0, 0, ByteArray(1), 1, TIMEOUT_MS)
                if (result >= 0) accepted.add(code)
                if (switched(usb, serial)) {
                    onLog("USB probe: the phone switched during the roll-call (request 0x%02X)".format(code))
                    return
                }
            }
            onLog(
                "USB probe: ${accepted.size} of $MAX_REQUEST vendor request codes answered" +
                    if (accepted.isEmpty()) "" else ": ${accepted.joinToString(",") { "0x%02X".format(it) }}",
            )
            if (accepted.isEmpty()) {
                onLog("USB probe: the phone stalls every vendor request, so the switch is not driven from here")
                return
            }

            // Step two: give each answered code the vehicle information in the shapes a car could send.
            val payloads = payloads()
            for (code in accepted) {
                for ((index, payload) in payloads.withIndex()) {
                    val result = connection.controlTransfer(
                        REQUEST_OUT, code, 0, 0, payload, payload.size, TIMEOUT_MS,
                    )
                    onLog(
                        "USB probe: 0x%02X payload %d/%d (%d bytes) -> %d".format(
                            code, index + 1, payloads.size, payload.size, result,
                        ),
                    )
                    if (switched(usb, serial)) {
                        onLog(
                            "USB probe: the phone switched to the HiCar NCM configuration with " +
                                "request 0x%02X and payload %d".format(code, index + 1),
                        )
                        return
                    }
                    Thread.sleep(PAUSE_MS)
                }
            }
            onLog("USB probe: no combination made the phone switch; the format is not among these guesses")
        } catch (failure: Exception) {
            onLog("USB probe: stopped (${failure.javaClass.simpleName})")
        } finally {
            runCatching { connection.close() }
            stop()
        }
    }

    private fun stop() {
        busy = false
    }

    /**
     * The phone re-appears with a vendor-specific interface when it accepts the switch.
     *
     * The serial is kept because the device object itself is replaced by re-enumeration; matching on
     * vendor alone would also match a second Huawei device on a hub.
     */
    private fun switched(usb: UsbManager, serial: String): Boolean {
        val devices = runCatching { usb.deviceList.values.toList() }.getOrNull() ?: return false
        return devices.any { device ->
            device.vendorId == HUAWEI_VID &&
                (serial.isEmpty() || runCatching { device.serialNumber }.getOrNull() == serial) &&
                inNcmConfiguration(device)
        }
    }

    private fun inNcmConfiguration(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val contract = device.getInterface(index)
            contract.interfaceClass == USB_CLASS_VENDOR &&
                contract.interfaceSubclass == NCM_SUBCLASS &&
                contract.interfaceProtocol in NCM_PROTOCOLS
        }

    /**
     * The vehicle information, in the shapes a car-side implementation could plausibly serialise.
     *
     * The documented content is fixed - model, NCM support, IP, port - so only the encoding varies
     * between them. JSON in two key spellings, a flat variant, and a delimited one, which is what a
     * C implementation writing straight to a buffer tends to produce.
     */
    private fun payloads(): List<ByteArray> {
        val address = NCM_ADDRESS
        val bodies = listOf(
            """{"modelId":"$model","supportNcm":1,"ip":"$address","port":$port}""",
            """{"modelId":"$model","supportNcm":true,"ip":"$address","port":$port}""",
            """{"deviceModel":"$model","supportNcm":true,"ip":"$address","port":$port}""",
            """{"modelId":"$model","ncm":true,"ipv4":"$address","port":$port}""",
            "$model;1;$address;$port",
        )
        return bodies.map { it.toByteArray(Charsets.US_ASCII) }
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter(ACTION_PERMISSION)
        runCatching { appContext.registerReceiver(permission, filter) }
        registered = true
    }

    private fun permissionIntent(): PendingIntent {
        val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    fun close() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(permission) }
        registered = false
    }

    private companion object {
        const val PROP = "debug.hicar.usbprobe"
        const val HUAWEI_VID = 0x12D1
        const val USB_CLASS_VENDOR = 0xFF
        const val NCM_SUBCLASS = 0x02
        val NCM_PROTOCOLS = setOf(0x16, 0x46, 0x76)

        /** Vendor, host to device: the only direction that can carry a command. */
        const val REQUEST_OUT = 0x40
        const val MAX_REQUEST = 0xFF
        const val TIMEOUT_MS = 300
        const val PAUSE_MS = 150L

        /** Where the car takes its address from, per the wired-connection guide's 192.168.66.0/24. */
        const val NCM_ADDRESS = "192.168.66.100"

        const val ACTION_PERMISSION = "com.shilapi.xcertplay.hicar.USB_PERMISSION"
    }
}
