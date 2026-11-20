package com.shilapi.xcertplay.hicar

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Asks a plugged-in phone to switch to the NCM configuration HiCar uses on a cable.
 *
 * This request used to be the one part of the wired flow without a public description. It is not
 * missing any more: this head unit ships Huawei's own car-side stack (`com.huawei.nearby`, i.e.
 * `HwNearbyCar.apk`), and that Java is neither packed nor encrypted. `com.huawei.nearby.c.e` - log
 * tag `CarInfoHandler` - builds a 91-byte car-info block and hands it straight to
 * `UsbDeviceConnection.controlTransfer(0x40, 0x34, 0, 0xA0, data, 91, 2000)`.
 *
 * The block is little-endian and self-describing:
 *
 * | offset | content |
 * |---|---|
 * | 0 | `"hwnearby"` |
 * | 8 | total length, `0x005B` |
 * | 10 | `0x0001`, `0x0100`, `0x0001`, then `00 01 01` |
 * | 19 | tag 1, len 3, model id |
 * | 24 | tag 2, len 1, sub-model id |
 * | 27 | tag 3, len 32, device id |
 * | 61 | tag 4, len 6, the head unit's Bluetooth address |
 * | 69 | tag 6, len 14, the address the phone should dial |
 * | 85 | tag 7, len 4, the port it should dial |
 *
 * The device id is `SHA256(model id ‖ sub-model id ‖ Bluetooth address)`; the address is
 * `192.168.66.` plus a number in 100..254, and the port comes from a `ServerSocket(0)` the real
 * implementation opens just before sending - the car listens and the phone dials. This build
 * advertises the control port it already listens on instead, which is what the phone would reach if
 * the interface could be brought up.
 *
 * The oracle for "did it land" stays the same: `huawei_cdc_ncm` binds nothing but interfaces of
 * class 0xff / subclass 0x02 / protocol 0x16 (and 0x46, 0x76), so the phone either re-enumerates
 * with that interface or it does not.
 *
 * Everything here is behind `setprop debug.hicar.usbprobe 1`: firing vendor requests at a phone's
 * gadget is not something to do on every plug-in.
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
            val packet = carInfo()
            onLog("USB probe: car info ${packet.size} bytes: ${HiCarDebug.hex(packet, packet.size)}")
            val sent = connection.controlTransfer(REQUEST_OUT, REQUEST_CODE, 0, REQUEST_INDEX, packet, packet.size, TIMEOUT_MS)
            onLog("USB probe: control transfer 0x40/0x34 index 0xA0 -> $sent (expected ${packet.size})")
            if (sent < 0) {
                onLog("USB probe: the phone stalled the request; nothing else to try from here")
                return
            }
            if (switched(usb, serial, SWITCH_WAIT_MS)) {
                onLog("USB probe: the phone switched to the HiCar NCM configuration")
                return
            }
            onLog("USB probe: the request was accepted but the phone stayed in its current configuration")
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
     * The 91 bytes of car info, in the order and encoding the stock stack uses.
     *
     * The model id is the same eight hexadecimal digits the advertisement carries, split the same
     * way the beacon splits them: three bytes plus a one-byte sub-model. A model that is not eight
     * hex digits is a configuration error, and sending zeros is more honest than sending a guess -
     * the log says so either way.
     */
    private fun carInfo(): ByteArray {
        val modelBytes = ByteArray(3)
        var subModel = 0.toByte()
        if (MODEL.matches(model)) {
            for (index in 0 until 3) {
                modelBytes[index] = model.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            subModel = model.substring(6, 8).toInt(16).toByte()
        } else {
            onLog("USB probe: model '$model' is not eight hex digits; sending zeros")
        }
        val address = bluetoothAddress()
        val deviceId = MessageDigest.getInstance("SHA-256").run {
            update(modelBytes)
            update(subModel)
            update(address)
            digest()
        }
        val host = "192.168.66." + Random.nextInt(ADDRESS_MIN, ADDRESS_MAX + 1)
        val dialPort = HiCarDebug.number(PORT_PROP, port)

        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.putShort(PACKET_SIZE.toShort())
        buffer.putShort(1.toShort())
        buffer.putShort(VERSION)
        buffer.putShort(1.toShort())
        buffer.put(0.toByte())
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        buffer.put(MODEL_ID_TAG)
        buffer.put(3.toByte())
        buffer.put(modelBytes)
        buffer.put(SUB_MODEL_TAG)
        buffer.put(1.toByte())
        buffer.put(subModel)
        buffer.put(DEVICE_ID_TAG)
        buffer.put(DEVICE_ID_BYTES.toByte())
        buffer.put(deviceId)
        buffer.put(BLUETOOTH_TAG)
        buffer.put(6.toByte())
        buffer.put(address)
        buffer.put(HOST_TAG)
        buffer.put(HOST_BYTES.toByte())
        buffer.put(host.toByteArray(Charsets.US_ASCII))
        buffer.put(PORT_TAG)
        buffer.put(4.toByte())
        buffer.putInt(dialPort)
        return buffer.array()
    }

    /**
     * The head unit's Bluetooth address, which the phone folds into the device id it remembers.
     *
     * Apps cannot read the real address on modern Android - the framework hands out
     * `02:00:00:00:00:00` - so `debug.hicar.btmac` exists for the value the settings screen already
     * knows. A wrong address does not break the switch, but it makes the phone see a new car.
     */
    private fun bluetoothAddress(): ByteArray {
        val override = HiCarDebug.text(BTMAC_PROP)
        val text = override.ifEmpty {
            runCatching { BluetoothAdapter.getDefaultAdapter()?.address }.getOrNull().orEmpty()
        }
        val bytes = parseAddress(text)
        if (bytes == null) {
            onLog("USB probe: no usable Bluetooth address ('$text'); sending zeros. setprop $BTMAC_PROP aa:bb:cc:dd:ee:ff")
            return ByteArray(6)
        }
        return bytes
    }

    private fun parseAddress(text: String): ByteArray? {
        val parts = text.split(':', '-')
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        for (index in 0 until 6) {
            val value = parts[index].toIntOrNull(16) ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    /**
     * The phone re-appears with a vendor-specific interface when it accepts the switch.
     *
     * The serial is kept because the device object itself is replaced by re-enumeration; matching on
     * vendor alone would also match a second Huawei device on a hub.
     */
    private fun switched(usb: UsbManager, serial: String, waitMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline) {
            val devices = runCatching { usb.deviceList.values.toList() }.getOrNull().orEmpty()
            val match = devices.firstOrNull { device ->
                device.vendorId == HUAWEI_VID &&
                    (serial.isEmpty() || runCatching { device.serialNumber }.getOrNull() == serial) &&
                    inNcmConfiguration(device)
            }
            if (match != null) {
                onLog("USB probe: phone now reports ${describe(match)}")
                return true
            }
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun inNcmConfiguration(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val contract = device.getInterface(index)
            contract.interfaceClass == USB_CLASS_VENDOR &&
                contract.interfaceSubclass == NCM_SUBCLASS &&
                contract.interfaceProtocol in NCM_PROTOCOLS
        }

    private fun describe(device: UsbDevice): String {
        val interfaces = (0 until device.interfaceCount).joinToString(",") { index ->
            val contract = device.getInterface(index)
            "%02X/%02X/%02X".format(
                contract.interfaceClass, contract.interfaceSubclass, contract.interfaceProtocol,
            )
        }
        return "vid=0x%04X pid=0x%04X interfaces=[%s]".format(device.vendorId, device.productId, interfaces)
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
        const val BTMAC_PROP = "debug.hicar.btmac"
        const val PORT_PROP = "debug.hicar.usbport"
        const val HUAWEI_VID = 0x12D1

        const val MAGIC = "hwnearby"
        const val PACKET_SIZE = 91
        const val VERSION: Short = 0x0100

        val MODEL_ID_TAG: Byte = 1
        val SUB_MODEL_TAG: Byte = 2
        val DEVICE_ID_TAG: Byte = 3
        val BLUETOOTH_TAG: Byte = 4
        val HOST_TAG: Byte = 6
        val PORT_TAG: Byte = 7
        const val DEVICE_ID_BYTES = 32
        const val HOST_BYTES = 14

        const val ADDRESS_MIN = 100
        const val ADDRESS_MAX = 254

        val MODEL = Regex("[0-9a-fA-F]{8}")

        const val USB_CLASS_VENDOR = 0xFF
        const val NCM_SUBCLASS = 0x02
        val NCM_PROTOCOLS = setOf(0x16, 0x46, 0x76)

        /** Vendor, host to device: the only direction that can carry a command. */
        const val REQUEST_OUT = 0x40
        const val REQUEST_CODE = 0x34
        const val REQUEST_INDEX = 0xA0
        const val TIMEOUT_MS = 2000
        const val SWITCH_WAIT_MS = 8_000L
        const val POLL_MS = 250L

        const val ACTION_PERMISSION = "com.shilapi.xcertplay.hicar.USB_PERMISSION"
    }
}
