package com.shilapi.xcertplay.hicar

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * A wired HiCar session, driven from user space.
 *
 * The wire path has three steps and this class is all three:
 *
 * 1. **Switch the phone into NCM.** A 91-byte car-info block goes out as a vendor control transfer
 *    (`0x40/0x34`, index `0xA0`); the phone re-enumerates with Huawei's NCM layout. This is the same
 *    block the diagnostic probe sends, and the address inside it is what the phone will dial — so the
 *    tunnel must be established at exactly that address.
 * 2. **Open the interfaces by hand.** Nothing in this head unit's kernel claims the layout, so
 *    [HiCarNcmUsb] claims the CDC control and data interfaces, selects the data alternate setting
 *    and reads the NTB parameters.
 * 3. **Move packets.** A `VpnService` TUN carries the IP packets; [HiCarNcmDataPath] wraps them in
 *    Ethernet and NTB16 for the bulk endpoints, and answers the phone's ARP for this car.
 *
 * Everything above the cable — the nearby handshake, authentication, HiSight — is unchanged: the
 * phone dials the same TCP ports on the address this class gave it.
 *
 * **Not verified on a car.** The framing, the ARP rules and the tunnel plumbing each have unit tests,
 * but no part of this has run against a real phone over a real cable. It is off by default for that
 * reason; see `docs/HICAR_WIRED.md`.
 *
 * The reverse-engineering this is built on is OpenHC's (`CarInfoSender`, `NcmUsbLink`,
 * `WiredVpnService`), GPL-3.0-only.
 */
internal class HiCarWiredLink(
    context: Context,
    private val onLog: (String) -> Unit,
    /** The eight hexadecimal digits the advertisement carries; split 6 + 2 in the block. */
    private val model: String,
    /** The head unit's Bluetooth address as text; zeroes when unusable, with a line in the log. */
    private val bluetoothAddress: String,
    /** The port the phone should dial: the same control port the wireless path serves. */
    private val controlPort: Int,
    /** The address the phone will reach this side on, e.g. `192.168.66.137`. */
    val hostAddress: String = randomHostAddress(),
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)

    @Volatile private var closed = false
    @Volatile private var busy = false
    private var path: HiCarNcmDataPath? = null
    private var port: HiCarNcmUsb? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PERMISSION -> {
                    val device = deviceOf(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    onLog("USB: permission ${if (granted) "granted" else "denied"}${device?.let { " for ${it.deviceName}" } ?: ""}")
                    if (granted && device != null) bring(device)
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = deviceOf(intent) ?: return
                    onLog("USB: device attached${describe(device)}")
                    // The phone may already be the NCM device from a previous run.
                    if (inNcmLayout(device)) bring(device) else requestPermission(device)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = deviceOf(intent) ?: return
                    onLog("USB: device detached${describe(device)}")
                    teardown("the cable was unplugged")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        onLog(
            if (registered) "wired: watching the USB port; the car is ${hostAddress}:$controlPort"
            else "wired: this build cannot watch the USB port",
        )
        // A phone already plugged in when the receiver started is the normal case on a car that boots
        // with the cable attached.
        val present = runCatching { manager?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        present.firstOrNull { inNcmLayout(it) }?.let {
            onLog("wired: a phone is already in the NCM layout${describe(it)}")
            bring(it)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { appContext.unregisterReceiver(receiver) }
        teardown("the receiver stopped")
    }

    // ------------------------------------------------------------------ step 1: switch the phone

    /**
     * Asks the phone to switch, then drives the link once it re-appears.
     *
     * Both are done on one background thread: the switch blocks for up to eight seconds and the
     * enumeration poll with it, and none of that belongs on a UI or Bluetooth thread.
     */
    private fun bring(device: UsbDevice) {
        if (closed || busy) return
        busy = true
        Thread({
            try {
                // A phone that is already in the layout is one that switched in a previous run and
                // was never opened; switching it again would only reset a working device.
                if (inNcmLayout(device)) openAndRun(device) else switchToNcm(device)
            } catch (failure: Exception) {
                onLog("wired: ${failure.javaClass.simpleName}: ${failure.message}")
                teardown("setup failed")
            } finally {
                busy = false
            }
        }, "hicar-wired").apply { isDaemon = true }.start()
    }

    /**
     * Sends the car-info block and waits for the phone to come back as an NCM device.
     *
     * The block is 91 bytes and carries the address the phone should dial, so it is built from the
     * same [hostAddress] the tunnel is created on. Getting them out of step produces a phone that
     * connects to nothing, with no error on either side.
     */
    private fun switchToNcm(device: UsbDevice): Boolean {
        val usb = manager ?: return false
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            return false
        }
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        val connection = runCatching { usb.openDevice(device) }.getOrNull()
        if (connection == null) {
            onLog("wired: could not open ${device.deviceName}; another app may hold it")
            return false
        }
        try {
            val packet = carInfo()
            val sent = connection.controlTransfer(
                REQUEST_OUT,
                REQUEST_CODE,
                0,
                REQUEST_INDEX,
                packet,
                packet.size,
                CONTROL_TIMEOUT_MS,
            )
            onLog("wired: car info ${packet.size} bytes -> $sent (address $hostAddress:$controlPort)")
            if (sent < 0) {
                onLog("wired: the phone stalled the switch request")
                return false
            }
            val match = awaitNcm(usb, serial, SWITCH_WAIT_MS)
            if (match == null) {
                onLog("wired: the phone accepted the request but did not re-enumerate as NCM")
                return false
            }
            onLog("wired: the phone switched to the NCM layout${describe(match)}")
            port?.close()
            port = null
            return openAndRun(match)
        } finally {
            runCatching { connection.close() }
        }
    }

    /** After the switch the device object is new, so this opens it straight away. */
    private fun openAndRun(device: UsbDevice): Boolean {
        val usb = manager ?: return false
        val ncm = HiCarNcmUsb.open(usb, device, onLog) ?: return false
        port = ncm
        onLog("wired: ${ncm.describe()}")
        val tunnel = HiCarWiredVpn.open(appContext, hostAddress) ?: run {
            onLog("wired: no tunnel, so the cable carries nothing")
            ncm.close()
            port = null
            return false
        }
        val address = ipv4(hostAddress) ?: return false
        val data = HiCarNcmDataPath(
            port = ncm,
            tunnel = tunnel,
            endpoint = HiCarEthernetEndpoint(address),
            listener = object : HiCarNcmDataPath.Listener {
                override fun onLinkDown(reason: HiCarNcmDataPath.LinkDown) {
                    onLog("wired: link down (${reason.name.lowercase()})")
                    teardown("the wired link went down")
                }

                override fun onMilestone(milestone: HiCarNcmDataPath.Milestone, detail: String) {
                    onLog("wired: ${milestone.name.lowercase().replace('_', ' ')} $detail")
                }
            },
        )
        path = data
        data.start()
        onLog("wired: carrying packets at $hostAddress/24; the phone should dial $hostAddress:$controlPort")
        return true
    }

    private fun teardown(reason: String) {
        val data = path
        path = null
        data?.stop()
        port?.close()
        port = null
        HiCarWiredVpn.close(appContext)
        if (data != null) onLog("wired: stopped ($reason)")
    }

    // ------------------------------------------------------------------ helpers

    private fun requestPermission(device: UsbDevice) {
        val usb = manager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        onLog("wired: asking the system for permission to open ${device.deviceName}")
        runCatching { usb.requestPermission(device, pending) }
    }

    /** Polls for the device that came back in the NCM layout; the serial keeps a hub honest. */
    private fun awaitNcm(usb: UsbManager, serial: String, waitMillis: Long): UsbDevice? {
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline && !closed) {
            val devices = runCatching { usb.deviceList.values.toList() }.getOrNull().orEmpty()
            val match = devices.firstOrNull { device ->
                device.vendorId == HUAWEI_VID &&
                    (serial.isEmpty() || runCatching { device.serialNumber }.getOrNull() == serial) &&
                    inNcmLayout(device)
            }
            if (match != null) return match
            Thread.sleep(250)
        }
        return null
    }

    /**
     * The 91-byte car-info block.
     *
     * Field offsets are from the stock stack (`com.huawei.nearby.c.e`): magic, length, three fixed
     * shorts, then tagged fields — model id, sub-model, 32-byte device id, Bluetooth address, the
     * address to dial, and the port.
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
            onLog("wired: model '$model' is not eight hex digits; sending zeros")
        }
        val bt = parseAddress(bluetoothAddress) ?: run {
            onLog("wired: no usable Bluetooth address ('$bluetoothAddress'); sending zeros")
            ByteArray(6)
        }
        val deviceId = MessageDigest.getInstance("SHA-256").run {
            update(modelBytes)
            update(subModel)
            update(bt)
            digest()
        }
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.putShort(PACKET_SIZE.toShort())
        buffer.putShort(1)
        buffer.putShort(VERSION)
        buffer.putShort(1)
        buffer.put(0)
        buffer.put(1)
        buffer.put(1)
        buffer.put(MODEL_ID_TAG)
        buffer.put(3)
        buffer.put(modelBytes)
        buffer.put(SUB_MODEL_TAG)
        buffer.put(1)
        buffer.put(subModel)
        buffer.put(DEVICE_ID_TAG)
        buffer.put(DEVICE_ID_BYTES.toByte())
        buffer.put(deviceId)
        buffer.put(BLUETOOTH_TAG)
        buffer.put(6)
        buffer.put(bt)
        buffer.put(HOST_TAG)
        buffer.put(HOST_BYTES.toByte())
        buffer.put(hostAddress.toByteArray(Charsets.US_ASCII))
        buffer.put(PORT_TAG)
        buffer.put(4)
        buffer.putInt(controlPort)
        return buffer.array()
    }

    private fun inNcmLayout(device: UsbDevice): Boolean {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == CLASS_COMM && iface.interfaceSubclass == SUBCLASS_NCM) return true
        }
        return false
    }

    private fun describe(device: UsbDevice): String =
        " (${device.deviceName}, vid=0x${device.vendorId.toString(16)}, ${device.interfaceCount} interface(s))"

    private fun deviceOf(intent: Intent): UsbDevice? {
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

    private fun parseAddress(text: String): ByteArray? {
        val parts = text.trim().split(':', '-')
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        for (index in 0 until 6) {
            val value = parts[index].toIntOrNull(16) ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    companion object {
        /** Where the phone is told to find this side; the tunnel is built on the same address. */
        private const val SUBNET_PREFIX = "192.168.66."

        fun randomHostAddress(): String = SUBNET_PREFIX + Random.nextInt(100, 255)

        /** The Bluetooth address an unconfigured build would send; kept for the log line only. */
        fun defaultBluetoothAddress(): String =
            runCatching { BluetoothAdapter.getDefaultAdapter()?.address }.getOrNull().orEmpty()

        private const val ACTION_PERMISSION = "com.shilapi.xcertplay.hicar.USB_PERMISSION"
        private const val HUAWEI_VID = 0x12D1

        private const val MAGIC = "hwnearby"
        private const val PACKET_SIZE = 91
        private const val VERSION: Short = 0x0100

        private const val MODEL_ID_TAG: Byte = 1
        private const val SUB_MODEL_TAG: Byte = 2
        private const val DEVICE_ID_TAG: Byte = 3
        private const val BLUETOOTH_TAG: Byte = 4
        private const val HOST_TAG: Byte = 6
        private const val PORT_TAG: Byte = 7

        private const val DEVICE_ID_BYTES = 32
        private const val HOST_BYTES = 14

        private const val REQUEST_OUT = 0x40
        private const val REQUEST_CODE = 0x34
        private const val REQUEST_INDEX = 0xA0
        private const val CONTROL_TIMEOUT_MS = 2000
        private const val SWITCH_WAIT_MS = 8_000L

        private const val CLASS_COMM = 2
        private const val SUBCLASS_NCM = 13

        private val MODEL = Regex("^[0-9A-Fa-f]{8}$")
    }
}

/** Whether the driver turned the wired path on. Off by default: no part of it has run on a car. */
object HiCarWiredSettings {
    private const val PREFERENCES = "hicar-wired"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
