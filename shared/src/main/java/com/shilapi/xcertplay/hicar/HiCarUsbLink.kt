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
    /** The model id the phone is told about; it is part of the wired switch message. */
    model: String = "",
    /** The port the switch message advertises, and the one the endpoints listen on. */
    port: Int = HiCarCodes.DEFAULT_CONTROL_PORT,
    /** Called once per USB attach with the interface and address the phone brought up. */
    private val onLink: (String, String) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)
    private val probe = HiCarUsbProbe(context, model, port, onLog)

    @Volatile private var closed = false
    private var watcher: Thread? = null

    /** Incremented per attach; a poller from an older attach sees a stale token and exits. */
    @Volatile private var generation = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    onLog("USB: device attached${device?.let { describe(it) } ?: ""}")
                    device?.takeIf { inNcmConfiguration(it) }?.let {
                        onLog("USB: the phone arrived already in the HiCar NCM configuration")
                    }
                    device?.let { probe.offer(it) }
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
            present.forEach { probe.offer(it) }
            if (present.any { it.vendorId == HUAWEI_VID }) watchForNetwork()
        }
    }

    /**
     * Follows the interface table for three minutes after an attach.
     *
     * The NCM interface does not exist at attach time - the phone enumerates, the host driver binds,
     * then the address appears - and a phone that was told to switch re-enumerates again, which is a
     * second attach a few seconds later. So each attach restarts the window instead of the first one
     * owning it: the switch that matters happens after the second enumeration, not the first.
     */
    private fun watchForNetwork() {
        if (closed) return
        val token = ++generation
        watcher = Thread({
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + WATCH_MILLIS
            var reported = false
            while (!closed && generation == token && System.currentTimeMillis() < deadline) {
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

    /**
     * The shape `huawei_cdc_ncm` binds, which is the only evidence that the switch actually landed.
     *
     * The phone's other USB modes (MTP, RNDIS tethering) never present this triple, and this head
     * unit has no `rndis_host`, so a network interface can only appear from a phone that took the
     * HiCar NCM configuration.
     */
    private fun inNcmConfiguration(device: android.hardware.usb.UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val contract = device.getInterface(index)
            contract.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                contract.interfaceSubclass == NCM_SUBCLASS &&
                contract.interfaceProtocol in NCM_PROTOCOLS
        }

    private fun prefixOf(nic: NetworkInterface): Int = runCatching {
        nic.interfaceAddresses.firstOrNull { it.address is Inet4Address }?.networkPrefixLength?.toInt() ?: 24
    }.getOrDefault(24)

    private fun describe(device: android.hardware.usb.UsbDevice): String {
        val vendor = if (device.vendorId == HUAWEI_VID) " (Huawei)" else ""
        val classes = (0 until device.interfaceCount).joinToString(",") { index ->
            val contract = device.getInterface(index)
            when (contract.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> "comm"
                UsbConstants.USB_CLASS_CDC_DATA -> "cdc-data"
                UsbConstants.USB_CLASS_MASS_STORAGE -> "storage"
                UsbConstants.USB_CLASS_HID -> "hid"
                UsbConstants.USB_CLASS_AUDIO -> "audio"
                UsbConstants.USB_CLASS_MISC -> "misc"
                // A vendor interface is the interesting one: print the triple so the NCM shape is
                // readable straight from the log instead of guessed at.
                UsbConstants.USB_CLASS_VENDOR_SPEC ->
                    "vendor/%02X/%02X".format(contract.interfaceSubclass, contract.interfaceProtocol)
                else -> "class${contract.interfaceClass}"
            }
        }
        return ": ${device.deviceName} vid=0x%04X pid=0x%04X%s interfaces=[%s]"
            .format(device.vendorId, device.productId, vendor, classes)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { appContext.unregisterReceiver(receiver) }
        runCatching { probe.close() }
        runCatching { watcher?.join(200) }
        watcher = null
    }

    private companion object {
        const val HUAWEI_VID = 0x12D1

        /**
         * Long enough to cover enumerate -> switch (a second enumeration) -> bind -> address.
         * Restarted by every attach, so the second enumeration gets its own full window.
         */
        const val WATCH_MILLIS = 180_000L
        const val POLL_MILLIS = 1_500L

        /** The triple `huawei_cdc_ncm` binds; see [inNcmConfiguration]. */
        const val NCM_SUBCLASS = 0x02
        val NCM_PROTOCOLS = setOf(0x16, 0x46, 0x76)

        val PHONE_LINK_PREFIXES = listOf("usb", "wwan", "ncm", "rndis", "eth")
    }
}
