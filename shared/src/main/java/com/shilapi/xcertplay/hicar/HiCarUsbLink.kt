package com.shilapi.xcertplay.hicar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Watches the USB port for a phone and reports the network it brings with it.
 *
 * Wired HiCar is not a second framing of the nearby protocol: Huawei's guide "建立有线连接" has the
 * SDK handle the USB insert event, hand the PIN to the phone over that channel, and then run the
 * same connection. The 2022 troubleshooting note spells out the transport - the phone comes up as
 * an **NCM** network device and the car gets an interface (`wwan0` on most units). This head unit
 * does ship the driver: `/sys/module/cdc_ncm` and `/sys/module/rndis` are both loaded.
 *
 * So the work that starts here is discovery, not protocol: which interface appears, what address it
 * takes, and what the phone does with it. Everything above that - the nearby handshake over TCP in
 * place of RFCOMM - depends on evidence this class is built to produce, because guessing it would
 * cost the same weeks that the authentication did.
 *
 * Enumeration and the attach broadcast need no permission; only opening the device would.
 */
internal class HiCarUsbLink(
    context: Context,
    private val onLog: (String) -> Unit,
    /** Called once per USB attach with the interface and address the phone brought up. */
    private val onLink: (String, String) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)

    @Volatile private var closed = false
    private var watcher: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    onLog("USB: device attached${device?.let { describe(it) } ?: ""}")
                    watchForNetwork()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    onLog("USB: device detached${device?.let { describe(it) } ?: ""}")
                }
                else -> return
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching { appContext.registerReceiver(receiver, filter) }
        // What is already plugged in: a phone that was connected before this app started is the
        // normal case on a head unit that boots with the cable attached.
        val present = manager?.deviceList?.values.orEmpty()
        if (present.isEmpty()) {
            onLog("USB: nothing on the host port; wired HiCar would start when a phone is plugged in")
        } else {
            present.forEach { onLog("USB: already attached${describe(it)}") }
            if (present.any { it.vendorId == HUAWEI_VID }) watchForNetwork()
        }
    }

    /**
     * Follows the interface table for a minute after an attach.
     *
     * The NCM interface does not exist at attach time - the phone enumerates, then the host driver
     * binds, then DHCP or a static assignment appears - so a single read would report nothing and
     * look like "USB does not work here". A minute is long enough for any of those paths and short
     * enough not to leave a thread polling forever.
     */
    private fun watchForNetwork() {
        if (closed) return
        if (watcher?.isAlive == true) return
        watcher = Thread({
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + WATCH_MILLIS
            var reported = false
            while (!closed && System.currentTimeMillis() < deadline) {
                for ((name, address) in interfaces()) {
                    if (!seen.add(name)) continue
                    val kind = if (isPhoneLink(name)) "wired link" else "interface"
                    onLog("USB: $kind $name came up at $address")
                    if (isPhoneLink(name)) {
                        reported = true
                        runCatching { onLink(name, address) }
                    }
                }
                if (reported) return@Thread
                Thread.sleep(POLL_MILLIS)
            }
            if (!reported) {
                onLog(
                    "USB: no phone link appeared within ${WATCH_MILLIS / 1000}s " +
                        "(nothing like usb0/wwan0/ncm0 with an IPv4 address)",
                )
            }
        }, "hicar-usb").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun interfaces(): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()
        val all = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull() ?: return found
        for (nic in all) {
            if (!runCatching { nic.isUp }.getOrDefault(false)) continue
            val address = runCatching { nic.inetAddresses.toList() }
                .getOrNull()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?: continue
            found.add(nic.name to "${address.hostAddress}/${prefixOf(nic)}")
        }
        return found
    }

    /** Interface names that mean "a device is sharing a network over the cable" across vendors. */
    private fun isPhoneLink(name: String): Boolean =
        PHONE_LINK_PREFIXES.any { name.startsWith(it) }

    private fun prefixOf(nic: NetworkInterface): Int = runCatching {
        nic.interfaceAddresses.firstOrNull { it.address is Inet4Address }?.networkPrefixLength?.toInt() ?: 24
    }.getOrDefault(24)

    private fun describe(device: android.hardware.usb.UsbDevice): String {
        val vendor = if (device.vendorId == HUAWEI_VID) " (Huawei)" else ""
        val classes = (0 until device.interfaceCount).joinToString(",") { index ->
            when (device.getInterface(index).interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> "comm"
                UsbConstants.USB_CLASS_CDC_DATA -> "cdc-data"
                UsbConstants.USB_CLASS_MASS_STORAGE -> "storage"
                UsbConstants.USB_CLASS_HID -> "hid"
                UsbConstants.USB_CLASS_AUDIO -> "audio"
                UsbConstants.USB_CLASS_MISC -> "misc"
                UsbConstants.USB_CLASS_VENDOR_SPEC -> "vendor"
                else -> "class${device.getInterface(index).interfaceClass}"
            }
        }
        return ": ${device.deviceName} vid=0x%04X pid=0x%04X%s interfaces=[%s]"
            .format(device.vendorId, device.productId, vendor, classes)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { appContext.unregisterReceiver(receiver) }
        runCatching { watcher?.join(200) }
        watcher = null
    }

    private companion object {
        const val HUAWEI_VID = 0x12D1
        /** Long enough for enumerate → bind → address assignment, short enough to give up visibly. */
        const val WATCH_MILLIS = 60_000L
        const val POLL_MILLIS = 1_500L
        val PHONE_LINK_PREFIXES = listOf("usb", "wwan", "ncm", "rndis", "eth")
    }
}
