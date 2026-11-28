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
 * **Partly verified on a car.** On 2026-11-27 the block was accepted (91 bytes) and the phone did
 * re-enumerate, but the poll could not see it and a second insertion never got a permission answer.
 * Both are fixed here (see [awaitNcm] and [handOver]); the link itself is still unproven. The
 * framing, the ARP rules and the tunnel plumbing each have unit tests. See `docs/HICAR_WIRED.md`.
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
    /**
     * The identity the Bluetooth side already answers with, 64 upper-case hex digits.
     *
     * The block carries the same value as a 32-byte field. It has to be the *same* value: the
     * derivation is `SHA-256` over the upper-case hex text of model‖sub-model‖address, not over the
     * raw bytes, and a block whose device id disagrees with the handshake is a car the phone cannot
     * place — see [carInfo].
     */
    private val localIdentity: String = "",
    /** The port the phone should dial: the same control port the wireless path serves. */
    private val controlPort: Int,
    /** The address the phone will reach this side on, e.g. `192.168.66.137`. */
    val hostAddress: String = randomHostAddress(),
    /**
     * Called once the tunnel is carrying packets, with [hostAddress].
     *
     * That is the moment the cable becomes a usable HiCar transport, and the moment the wireless
     * path has to give way — see [HiCarTransportArbiter]. It is called from a transport thread.
     */
    private val onLinkUp: (String) -> Unit = {},
    /** Called when the cable or the tunnel goes away, with the reason. From a transport thread. */
    private val onLinkDown: (String) -> Unit = {},
) : AutoCloseable {

    /**
     * What the phone's USB port is, from the wired path's point of view.
     *
     * The switch block only works in [NORMAL]; with USB debugging on the phone ignores it, and after
     * the switch the same product id comes back with the two CDC interfaces.
     */
    private enum class PhoneMode { NOT_A_PHONE, NORMAL, USB_DEBUGGING, NCM, UNSUPPORTED }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)

    @Volatile private var closed = false
    @Volatile private var busy = false
    @Volatile private var up = false

    /** What to do as soon as the system allows opening [phone], whichever answer arrives first. */
    @Volatile private var afterPermission: ((UsbDevice) -> Unit)? = null

    /**
     * Whether this insertion has already been spent on a car-info block.
     *
     * The phone answers one block per insertion and then resets about 1.5 s later; a second block in
     * the same insertion is ignored, and an immediate unplug-replug often is too (measured by OpenHC).
     */
    @Volatile private var usedThisPlug = false

    /** When the cable was last pulled, so a re-plug inside the hold is not mistaken for a fresh one. */
    @Volatile private var unpluggedAt = 0L
    private var path: HiCarNcmDataPath? = null
    private var port: HiCarNcmUsb? = null

    /** The phone's own address on the cable, once it has announced it. */
    val phoneAddress: String? get() = path?.phoneAddress

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PERMISSION -> {
                    val device = deviceOf(intent)
                    val claimed = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    // The extra alone is not evidence: before Android 13 this action is only ours by
                    // name, so the system's own record decides.
                    val allowed = device != null && manager?.hasPermission(device) == true
                    onLog(
                        "USB: permission ${if (allowed) "granted" else "denied"}" +
                            (device?.let { " for ${it.deviceName}" } ?: ""),
                    )
                    when {
                        allowed && device != null -> runAfterPermission(device)
                        !claimed && device != null -> onLog(
                            "wired: the USB permission was refused; the phone stays in its normal mode",
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = deviceOf(intent) ?: return
                    val mode = phoneMode(device)
                    onLog("USB: device attached${describe(device)} — ${label(mode)}")
                    when (mode) {
                        PhoneMode.NCM -> bring(device)
                        PhoneMode.NORMAL -> bring(device)
                        // Said once per attach on purpose: this is the one state a driver can fix
                        // without touching the car, and the symptom is otherwise a silent no-op.
                        PhoneMode.USB_DEBUGGING -> onLog(
                            "wired: the phone has USB debugging on (pid 0x5000) and ignores the switch " +
                                "request in that state; turn USB debugging off on the phone, then plug in again",
                        )
                        PhoneMode.UNSUPPORTED -> onLog(
                            "wired: this device is in a USB mode this build cannot switch " +
                                "(pid 0x${device.productId.toString(16)}); nothing sent",
                        )
                        PhoneMode.NOT_A_PHONE -> Unit
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = deviceOf(intent) ?: return
                    onLog("USB: device detached${describe(device)}")
                    teardown("the cable was unplugged")
                    afterPermission = null
                    if (usedThisPlug) unpluggedAt = System.currentTimeMillis()
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
        // with the cable attached — and the case a manifest receiver leaves behind when the launch of
        // the activity is what started this run.
        val present = runCatching { manager?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        present.forEach { onLog("wired: already on the port${describe(it)} — ${label(phoneMode(it))}") }
        val candidate = present.firstOrNull { phoneMode(it) == PhoneMode.NCM }
            ?: present.firstOrNull { phoneMode(it) == PhoneMode.NORMAL }
        if (candidate != null) {
            onLog("wired: starting with${describe(candidate)}")
            bring(candidate)
        } else if (present.any { phoneMode(it) == PhoneMode.USB_DEBUGGING }) {
            onLog(
                "wired: the phone has USB debugging on; it will not accept the switch until that is off",
            )
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
     * Both are done on one background thread: the switch blocks for up to fifteen seconds and the
     * enumeration poll with it, and none of that belongs on a UI or Bluetooth thread.
     */
    private fun bring(device: UsbDevice) {
        when (phoneMode(device)) {
            PhoneMode.NCM -> handOver(device) { allowed -> startOnWorker { openAndRun(allowed) } }
            PhoneMode.NORMAL -> handOver(device) { allowed -> startOnWorker { switchToNcm(allowed) } }
            PhoneMode.USB_DEBUGGING -> onLog(
                "wired: not switching while USB debugging is on; the phone would ignore the block",
            )
            PhoneMode.UNSUPPORTED, PhoneMode.NOT_A_PHONE -> Unit
        }
    }

    /** Runs the pending step once, whichever answer for the permission arrived first. */
    private fun runAfterPermission(device: UsbDevice) {
        val action = afterPermission ?: return
        afterPermission = null
        runCatching { action(device) }
    }

    /**
     * Asks the system for [device] and runs [then] once it may be opened.
     *
     * Two sources answer, deliberately. The broadcast is the normal one, but it is delivered to a
     * receiver this class registers itself and can be missed while the phone re-enumerates — the
     * 2026-11-28 log asked for permission and never heard back at all, so nothing was ever sent. The
     * poll also covers a device the system already trusts (the grant is per device instance, and the
     * NCM device is a new one).
     */
    private fun handOver(device: UsbDevice, then: (UsbDevice) -> Unit) {
        val usb = manager ?: return
        if (usb.hasPermission(device)) {
            then(device)
            return
        }
        afterPermission = then
        requestPermission(device)
        Thread({
            val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
            while (System.currentTimeMillis() < deadline && !closed) {
                if (usb.hasPermission(device)) {
                    runAfterPermission(device)
                    return@Thread
                }
                Thread.sleep(PERMISSION_POLL_MILLIS)
            }
            if (!closed && afterPermission != null) {
                afterPermission = null
                onLog(
                    "wired: no answer to the USB permission dialog for ${device.deviceName} within " +
                        "${PERMISSION_WAIT_MS / 1000}s, so nothing was sent. Allow it on the head unit's " +
                        "screen (or enable 「USB 授权自动确认」), then unplug and plug in again",
                )
            }
        }, "hicar-usb-permission").apply { isDaemon = true }.start()
    }

    /** One carrier thread at a time: the switch blocks, the enumeration poll blocks with it. */
    private fun startOnWorker(work: () -> Unit) {
        if (closed || busy) return
        busy = true
        Thread({
            try {
                work()
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
        if (!maySpendThisPlug()) return false
        val connection = runCatching { usb.openDevice(device) }.getOrNull() ?: run {
            onLog("wired: could not open ${device.deviceName}; another app may hold it")
            return false
        }
        val packet = carInfo()
        // Devices that were already in the NCM layout before this switch: the phone's new device must
        // not be confused with one of those, or the poll returns instantly with the wrong object.
        val stale = ncmDeviceNames(usb)
        try {
            var sent = -1
            for (attempt in 1..CAR_INFO_ATTEMPTS) {
                sent = runCatching {
                    connection.controlTransfer(
                        REQUEST_OUT,
                        REQUEST_CODE,
                        0,
                        REQUEST_INDEX,
                        packet,
                        packet.size,
                        CONTROL_TIMEOUT_MS,
                    )
                }.getOrDefault(-1)
                if (sent == packet.size) break
                onLog("wired: car info attempt $attempt/$CAR_INFO_ATTEMPTS returned $sent")
                if (attempt < CAR_INFO_ATTEMPTS) Thread.sleep(CAR_INFO_RETRY_MILLIS)
            }
            onLog("wired: car info ${packet.size} bytes -> $sent (address $hostAddress:$controlPort)")
            if (sent != packet.size) {
                onLog("wired: the phone did not take the switch request (expected ${packet.size} bytes)")
                return false
            }
            usedThisPlug = true
            onLog(
                "wired: waiting for the phone to appear as CDC-NCM " +
                    "(same product id, plus a class 2/13 control interface and a class 10 data one)",
            )
            val match = awaitNcm(usb, stale, SWITCH_WAIT_MS)
            if (match == null) {
                onLog(
                    "wired: the phone did not re-enumerate as NCM within ${SWITCH_WAIT_MS / 1000}s; " +
                        "the port now shows ${portLayouts(usb)}",
                )
                return false
            }
            onLog("wired: the phone switched to the NCM layout${describe(match)} — ${label(phoneMode(match))}")
            port?.close()
            port = null
            return openAndRun(match)
        } finally {
            runCatching { connection.close() }
        }
    }

    /**
     * Whether this insertion may still be used.
     *
     * One block per insertion: after it the phone resets itself, and a second block in the same
     * insertion — or an immediate unplug-replug — is ignored. Spending it would only waste the next
     * insertion, so the hold is reported instead of being silently consumed.
     */
    private fun maySpendThisPlug(): Boolean {
        if (!usedThisPlug) return true
        if (unpluggedAt > 0 && System.currentTimeMillis() - unpluggedAt >= REPLUG_HOLD_MS) {
            usedThisPlug = false
            unpluggedAt = 0
            return true
        }
        onLog(
            "wired: this insertion already sent car info and the phone answers once per insertion; " +
                "unplug the cable, wait ${REPLUG_HOLD_MS / 1000}s and plug it back in",
        )
        return false
    }

    private fun ncmDeviceNames(usb: UsbManager): Set<String> = runCatching {
        usb.deviceList.values.filter { phoneMode(it) == PhoneMode.NCM }.map { it.deviceName }.toSet()
    }.getOrDefault(emptySet())

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
        up = true
        onLog("wired: carrying packets at $hostAddress/24; the phone should dial $hostAddress:$controlPort")
        onLinkUp(hostAddress)
        return true
    }

    private fun teardown(reason: String) {
        val data = path
        path = null
        data?.stop()
        port?.close()
        port = null
        HiCarWiredVpn.close(appContext)
        val wasUp = up
        up = false
        if (data != null) onLog("wired: stopped ($reason)")
        // Only a link that was actually carrying packets is worth announcing: a failed setup would
        // otherwise tell the receiver to resume wireless for a cable that never worked.
        if (wasUp && !closed) onLinkDown(reason)
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

    /**
     * Polls for the device that came back in the NCM layout.
     *
     * A device that was already in that layout before the switch is skipped ([stale]): the phone's
     * new object has a new name, so name is enough, and it avoids reading `serialNumber` — which
     * throws for a device that is not authorised yet, and that is exactly the device being waited for.
     */
    private fun awaitNcm(usb: UsbManager, stale: Set<String>, waitMillis: Long): UsbDevice? {
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline && !closed) {
            val devices = runCatching { usb.deviceList.values.toList() }.getOrNull().orEmpty()
            devices.firstOrNull { it.deviceName !in stale && phoneMode(it) == PhoneMode.NCM }?.let { return it }
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
        val deviceId = HiCarCarInfo.deviceId(modelBytes, subModel, bt, localIdentity)
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

    /**
     * The phone's layout after the switch: a CDC-NCM control interface *and* a CDC data interface.
     *
     * Both are required, not just the control one. A device that grows only a control interface is
     * something else, and [HiCarNcmUsb.open] would fail a step later with a much less useful message.
     */
    private fun inNcmLayout(device: UsbDevice): Boolean {
        var control = false
        var data = false
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == CLASS_COMM && iface.interfaceSubclass == SUBCLASS_NCM) control = true
            if (iface.interfaceClass == CLASS_CDC_DATA) data = true
        }
        return control && data
    }

    /** What the port looks like, in the one word the log needs. */
    private fun phoneMode(device: UsbDevice): PhoneMode {
        if (device.vendorId != HUAWEI_VID && device.vendorId != HONOR_VID) return PhoneMode.NOT_A_PHONE
        if (inNcmLayout(device)) return PhoneMode.NCM
        return when (device.productId) {
            PID_NORMAL_A, PID_NORMAL_B, PID_NORMAL_C -> PhoneMode.NORMAL
            PID_USB_DEBUGGING -> PhoneMode.USB_DEBUGGING
            else -> PhoneMode.UNSUPPORTED
        }
    }

    private fun label(mode: PhoneMode): String = when (mode) {
        PhoneMode.NOT_A_PHONE -> "not a Huawei/Honor phone"
        PhoneMode.NORMAL -> "normal mode, answers the switch block"
        PhoneMode.USB_DEBUGGING -> "USB debugging on, refuses the switch block"
        PhoneMode.NCM -> "already CDC-NCM"
        PhoneMode.UNSUPPORTED -> "a product id this build does not switch"
    }

    /**
     * What the device's interface layout actually is, in the terms a reader of the log needs.
     *
     * NCM is the protocol this class drives. RNDIS is the other thing a phone may expose over the
     * same cable, with different framing and no NCM descriptors — naming it stops the next reader
     * from reading "the switch failed" when the phone did switch, just to something else.
     */
    private fun layoutKind(device: UsbDevice): String {
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        return when {
            interfaces.any { it.interfaceClass == CLASS_COMM && it.interfaceSubclass == SUBCLASS_NCM } ->
                "CDC-NCM"
            interfaces.any { it.interfaceClass == CLASS_WIRELESS } ->
                "RNDIS (wireless-controller class), which this driver cannot carry"
            interfaces.any { it.interfaceClass == CLASS_COMM } ->
                "CDC but not NCM (no subclass 0x0d interface)"
            interfaces.any { it.interfaceClass == CLASS_CDC_DATA } ->
                "CDC data only, no control interface"
            else -> "vendor-specific"
        }
    }

    /**
     * Every Huawei device on the port, with its layout.
     *
     * Printed when the switch does not produce an NCM device: the count of interfaces alone cannot
     * tell a phone that never switched from one that switched to a protocol this build does not
     * speak, and those two need opposite fixes.
     */
    private fun portLayouts(usb: UsbManager): String {
        val devices = runCatching { usb.deviceList.values.toList() }.getOrNull().orEmpty()
            .filter { it.vendorId == HUAWEI_VID }
        if (devices.isEmpty()) return "no Huawei device on the port"
        return devices.joinToString("; ") { "${describe(it)} → ${layoutKind(it)}" }
    }

    private fun describe(device: UsbDevice): String {
        val interfaces = (0 until device.interfaceCount).joinToString(", ") { index ->
            val iface = device.getInterface(index)
            "${hex(iface.interfaceClass)}/${hex(iface.interfaceSubclass)}/${hex(iface.interfaceProtocol)}"
        }
        return " (${device.deviceName}, vid=0x${device.vendorId.toString(16)}, " +
            "pid=0x${device.productId.toString(16)}, " +
            "${device.interfaceCount} interface(s) [class/subclass/protocol]: $interfaces)"
    }

    private fun hex(value: Int): String = (value and 0xFF).toString(16).padStart(2, '0')

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
        private const val HONOR_VID = 0x339B

        /**
         * The product ids a phone answers the switch block in, and the one it refuses it in.
         *
         * Measured, not guessed (`HuaweiPhone` in OpenHC): the normal states are 0x107d/0x107e/0x107f,
         * 0x5000 means "USB debugging on" and the phone ignores the block there, and the same id is
         * kept after the switch — only the interfaces change.
         */
        private const val PID_NORMAL_A = 0x107D
        private const val PID_NORMAL_B = 0x107E
        private const val PID_NORMAL_C = 0x107F
        private const val PID_USB_DEBUGGING = 0x5000

        private const val CAR_INFO_ATTEMPTS = 3
        private const val CAR_INFO_RETRY_MILLIS = 300L
        private const val PERMISSION_WAIT_MS = 60_000L
        private const val PERMISSION_POLL_MILLIS = 250L
        private const val REPLUG_HOLD_MS = 5_000L

        private val IDENTITY = Regex("^[0-9A-Fa-f]{64}$")

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
        private const val SWITCH_WAIT_MS = 15_000L

        private const val CLASS_COMM = 2
        private const val CLASS_CDC_DATA = 10
        private const val CLASS_WIRELESS = 0xE0
        private const val SUBCLASS_NCM = 13

        private val MODEL = Regex("^[0-9A-Fa-f]{8}$")
    }
}

/**
 * Whether the cable is driven at all, and whether plugging one starts the receiver.
 *
 * [enabled] is on by default: the cable has priority over Wi-Fi, and a driver who plugs a cable
 * expects it to be used without hunting for a switch. [autoStart] is what lets a cable wake the app
 * from a cold start; turning it off keeps the cable working once the app is already up.
 */
object HiCarWiredSettings {
    private const val PREFERENCES = "hicar-wired"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTOSTART = "autostart"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Whether an inserted cable may start the app by itself. */
    fun autoStart(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, true)

    fun setAutoStart(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }
}
