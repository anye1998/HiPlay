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
 * re-enumerate, but the poll could not see it. On 2026-11-28 nothing was ever sent: the permission
 * was asked twice from `onCreate` and never answered, which on this car means the system dropped the
 * request because no window was visible yet (see [handOver] and [setHostVisible]). The link itself
 * is still unproven; the framing, the ARP rules and the tunnel plumbing have unit tests. See
 * `docs/HICAR_WIRED.md`.
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
    /**
     * Raises the system's VPN consent dialog.
     *
     * The tunnel is the only way the phone's IP packets reach this receiver's TCP stack, and Android
     * refuses to create one without the driver's answer. The host activity owns the launcher, so it
     * passes it in here; [resumeAfterVpnConsent] takes the answer.
     */
    private val requestVpnConsent: (Intent) -> Unit = {},
) : AutoCloseable {

    /**
     * What the phone's USB port looks like, in the terms the log needs.
     *
     * This is a **description, never a gate**. OpenHC measured that a phone answers the block in
     * 0x107d/0x107e/0x107f and ignores it in 0x5000, but a product id we have not measured is not
     * evidence that the phone refuses: on 2026-11-28 the port carried a Huawei device whose layout
     * was the normal file-transfer one, and the build that produced the log did not even record the
     * id. Refusing to send on an unrecognised id therefore dead-ends a phone that would have
     * switched — sending to one that will not costs one ignored control transfer and leaves a line
     * in the log saying so.
     */
    private enum class PhoneMode { NOT_A_PHONE, KNOWN_NORMAL, DEBUGGING_PID, NCM, OTHER_PID }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(UsbManager::class.java)

    @Volatile private var closed = false
    @Volatile private var busy = false
    @Volatile private var up = false

    /**
     * Whether the receiver's window is actually on screen.
     *
     * The USB permission prompt is a system activity. On this car (Android 10) a request made while
     * the caller has no visible window is dropped without a dialog and without a callback — the
     * 2026-11-28 log asked twice, from `onCreate`, and heard nothing either time. So the request
     * waits for the screen: [setHostVisible] is what turns "asked" into "actually asked".
     */
    @Volatile private var hostVisible = false

    /** The device whose permission is being waited for, so a later ask can be made for it. */
    @Volatile private var pendingDevice: UsbDevice? = null

    @Volatile private var askedAt = 0L
    @Volatile private var askCount = 0

    /** Whether the VPN dialog has already been raised this session, so it is not raised in a loop. */
    @Volatile private var vpnAsked = false

    /** When the wait for the system's own USB grant ends; the poll asks after that, not before. */
    @Volatile private var graceUntil = 0L

    /** Serialises [startOnWorker]: one carrier thread, plus at most one queued step. */
    private val workerLock = Any()
    private var queuedWork: (() -> Unit)? = null

    /** Devices this receiver already started on, so the port poll does not re-ask them every second. */
    private val tried: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

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
                    onLog("USB: device attached${describe(device)} — ${label(phoneMode(device))}")
                    // Anything that is a Huawei/Honor phone gets the block, whatever its product id
                    // says: the id is a description, not a verdict (see [PhoneMode]).
                    if (phoneMode(device) != PhoneMode.NOT_A_PHONE) bring(device)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = deviceOf(intent) ?: return
                    onLog("USB: device detached${describe(device)}")
                    teardown("the cable was unplugged")
                    afterPermission = null
                    pendingDevice = null
                    askedAt = 0L
                    askCount = 0
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
        // A device already in the NCM layout is the one to drive; otherwise take any Huawei phone on
        // the port and try, rather than deciding from its product id that it is not worth asking.
        val phones = present.filter { phoneMode(it) != PhoneMode.NOT_A_PHONE }
        val candidate = phones.firstOrNull { phoneMode(it) == PhoneMode.NCM } ?: phones.firstOrNull()
        if (candidate != null) {
            tried.add(candidate.deviceName)
            onLog("wired: starting with${describe(candidate)}")
            bring(candidate)
        }
        // Keep the alias in step with the wired switch: it is what lets the system grant the cable's
        // permission without a dialog, and what stops it being offered when the cable is off.
        HiCarWiredAttach.setEnabled(appContext, HiCarWiredSettings.enabled(appContext))
        Thread({ pollPort() }, "hicar-usb-poll").apply { isDaemon = true }.start()
    }

    /**
     * Looks at the port once a second.
     *
     * Not every head unit delivers the attach broadcast — and one delivered while this process was
     * still starting is simply gone. The scan is what makes the cable work anyway. Devices already
     * handled are remembered until they disappear, so a phone that refused permission is not
     * re-asked every second.
     */
    private fun pollPort() {
        while (!closed) {
            Thread.sleep(PORT_POLL_MILLIS)
            if (closed) return
            val devices = runCatching { manager?.deviceList?.values?.toList() }.getOrNull().orEmpty()
            tried.retainAll(devices.map { it.deviceName }.toSet())
            if (afterPermission != null || path != null || port != null) continue
            val next = devices.firstOrNull { phoneMode(it) != PhoneMode.NOT_A_PHONE && it.deviceName !in tried }
                ?: continue
            tried.add(next.deviceName)
            onLog("wired: the port has a phone the attach broadcast did not report; starting with${describe(next)}")
            bring(next)
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
        // Consent first, before anything is sent: a phone answers one car-info block per insertion,
        // and spending it on a session that cannot build a tunnel leaves the cable dead until the
        // driver unplugs (OpenHC checks this at the same point, for the same reason).
        if (!HiCarWiredVpn.consented(appContext)) {
            pendingDevice = device
            onLog(
                "wired: no VPN consent for the cable yet, so this insertion is not spent; asking the " +
                    "driver first",
            )
            if (!vpnAsked) {
                vpnAsked = true
                HiCarWiredVpn.consentIntent(appContext)?.let { requestVpnConsent(it) }
            }
            return
        }
        if (phoneMode(device) == PhoneMode.NCM) {
            handOver(device) { allowed -> startOnWorker { openAndRun(allowed) } }
            return
        }
        // Said as a fact, not as a diagnosis: the id is one OpenHC measured while USB debugging was
        // on, and the block is still sent because the phone may simply ignore it.
        if (phoneMode(device) == PhoneMode.DEBUGGING_PID) {
            onLog(
                "wired: pid 0x5000 — the id OpenHC sees while USB debugging is on; sending the block " +
                    "anyway (a block that is ignored is harmless, one that is never sent cannot be diagnosed)",
            )
        }
        handOver(device) { allowed -> startOnWorker { switchToNcm(allowed) } }
    }

    /**
     * Tells the cable whether the receiver's window is on screen.
     *
     * Called from the host activity's `onResume`/`onPause`. A permission request made while nothing
     * is visible produces no dialog on this car and no callback, so the request is held until this
     * says yes — and repeated here in case the first one was dropped while the screen was coming up.
     */
    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        if (!visible) return
        // Ask for the VPN consent before a phone switches, not in the middle of it. The dialog is
        // shown once for this app, and doing it here means the tunnel is ready the moment the NCM
        // device appears — the phone otherwise reverts while the driver is reading a dialog.
        if (!vpnAsked && port == null && path == null) {
            val consent = runCatching { HiCarWiredVpn.consentIntent(appContext) }.getOrNull()
            if (consent != null) {
                vpnAsked = true
                onLog(
                    "wired: Android needs a one-time VPN consent before the cable can carry packets; " +
                        "raising that dialog now, ahead of the phone",
                )
                requestVpnConsent(consent)
            }
        }
        val device = pendingDevice ?: return
        val usb = manager ?: return
        if (usb.hasPermission(device)) {
            runAfterPermission(device)
            return
        }
        // Still inside the window where the system may grant by itself: the poll will ask after it.
        if (System.currentTimeMillis() < graceUntil) return
        askOnce(device, force = true)
    }

    /** Runs the pending step once, whichever answer for the permission arrived first. */
    private fun runAfterPermission(device: UsbDevice) {
        val action = afterPermission ?: return
        afterPermission = null
        pendingDevice = null
        runCatching { action(device) }
    }

    /**
     * Asks the system for [device] and runs [then] once it may be opened.
     *
     * Three things answer, deliberately:
     * - the broadcast, which is the normal one but can be missed while the phone re-enumerates;
     * - a poll of [UsbManager.hasPermission], because on 2026-11-28 the broadcast never came at all;
     * - the host activity's `onResume`, because a request made before the window exists is dropped
     *   on this car without a dialog and without a callback — that is what the log shows, twice.
     *
     * [askedAt] and [askCount] keep the re-asks bounded: three prompts at most, one every
     * [PERMISSION_REASK_MILLIS], so a driver who is not looking is not buried in dialogs.
     */
    private fun handOver(device: UsbDevice, then: (UsbDevice) -> Unit) {
        val usb = manager ?: return
        if (usb.hasPermission(device)) {
            then(device)
            return
        }
        afterPermission = then
        pendingDevice = device
        askedAt = 0L
        askCount = 0
        // With the USB-attach alias enabled the system grants by itself a few tens of milliseconds
        // after the attach; asking inside that window pops a dialog the driver does not need.
        val graceUntil = System.currentTimeMillis() + HiCarWiredAttach.graceMillis(appContext)
        this.graceUntil = graceUntil
        val waitFirst = graceUntil - System.currentTimeMillis()
        if (waitFirst > 0 && hostVisible) {
            onLog(
                "wired: waiting ${waitFirst}ms for the system's own grant before asking " +
                    "(the USB-attach alias is enabled)",
            )
        } else if (hostVisible) {
            askOnce(device)
        } else {
            onLog(
                "wired: the window is not on screen yet, so the USB permission dialog is held back " +
                    "until it is — asking now would be dropped silently on this car",
            )
        }
        Thread({
            val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
            while (System.currentTimeMillis() < deadline && !closed) {
                if (usb.hasPermission(device)) {
                    runAfterPermission(device)
                    return@Thread
                }
                if (hostVisible && afterPermission != null && System.currentTimeMillis() >= graceUntil) {
                    askOnce(device)
                }
                Thread.sleep(PERMISSION_POLL_MILLIS)
            }
            if (!closed && afterPermission != null) {
                afterPermission = null
                pendingDevice = null
                onLog(
                    "wired: no answer to the USB permission dialog for ${device.deviceName} within " +
                        "${PERMISSION_WAIT_MS / 1000}s after $askCount ask(s), so nothing was sent. " +
                        "Allow it on the head unit's screen (or enable 「USB 授权自动确认」), then plug in again",
                )
            }
        }, "hicar-usb-permission").apply { isDaemon = true }.start()
    }

    /** One prompt at a time, spaced out; the caller may force the first one after the screen appears. */
    @Synchronized
    private fun askOnce(device: UsbDevice, force: Boolean = false) {
        if (closed) return
        val now = System.currentTimeMillis()
        if (!force && now - askedAt < PERMISSION_REASK_MILLIS) return
        if (askCount >= PERMISSION_ASK_LIMIT) return
        askedAt = now
        askCount++
        requestPermission(device, askCount)
    }

    /**
     * One carrier thread at a time: the switch blocks, the enumeration poll blocks with it.
     *
     * A step that arrives while the thread is busy is **queued, not dropped**. Dropping it was how a
     * permission that arrived one moment after the switch was lost: the grant callback asked for the
     * open, found the carrier busy with the switch that was already failing, and returned.
     */
    private fun startOnWorker(work: () -> Unit) {
        if (closed) return
        synchronized(workerLock) {
            if (busy) {
                queuedWork = work
                onLog("wired: the carrier is busy; this step is queued until it is free")
                return
            }
            busy = true
        }
        Thread({ runWorker(work) }, "hicar-wired").apply { isDaemon = true }.start()
    }

    /** Runs one step, then the queued one, and only then frees the carrier. */
    private fun runWorker(work: () -> Unit) {
        try {
            work()
        } catch (failure: Exception) {
            onLog("wired: ${failure.javaClass.simpleName}: ${failure.message}")
            teardown("setup failed")
        } finally {
            val next = synchronized(workerLock) {
                val pending = queuedWork
                queuedWork = null
                busy = pending != null
                pending
            }
            if (next != null && !closed) {
                Thread({ runWorker(next) }, "hicar-wired").apply { isDaemon = true }.start()
            }
        }
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

    /**
     * After the switch the device object is new, so this opens it straight away.
     *
     * The USB grant is per device instance, and the NCM device is a different instance from the one
     * the block was sent to: opening it before its own grant arrives fails with "another app may
     * hold it", which is what the 2026-11-28 19:19 log reported for `/001/017`. So the grant is
     * checked first and asked for through the same [handOver] path.
     */
    private fun openAndRun(device: UsbDevice): Boolean {
        val usb = manager ?: return false
        // Already carrying, or opened and waiting for the tunnel's consent: do not open the same
        // device twice — the second claim of the control interface fails and reads like a cable fault.
        if (path != null) return true
        port?.let { open -> return beginCarrying(open) }
        if (!usb.hasPermission(device)) {
            onLog(
                "wired: ${device.deviceName} is a new device instance, so its own USB grant is needed " +
                    "before it can be opened",
            )
            handOver(device) { allowed -> startOnWorker { openAndRun(allowed) } }
            return false
        }
        val ncm = HiCarNcmUsb.open(usb, device, onLog) ?: return false
        port = ncm
        onLog("wired: ${ncm.describe()}")
        return beginCarrying(ncm)
    }

    /**
     * Puts the TUN behind the open port and starts moving packets.
     *
     * A `VpnService` may only be created with the driver's consent. When it is missing the port is
     * kept open and the system dialog is raised ([requestVpnConsent]); [resumeAfterVpnConsent] comes
     * back here instead of making the driver unplug and start over.
     */
    private fun beginCarrying(ncm: HiCarNcmUsb): Boolean {
        val tunnel = HiCarWiredVpn.open(
            context = appContext,
            address = hostAddress,
            onLog = onLog,
            onRevoked = {
                onLog("wired: the system revoked the tunnel")
                Thread({ teardown("the tunnel was revoked") }, "hicar-wired").apply { isDaemon = true }.start()
            },
        ) ?: run {
            // Consent is checked before an insertion is spent, so reaching this means something else
            // refused the tunnel. The log line from the VPN layer says which; the port is kept when a
            // dialog can still fix it.
            val consent = HiCarWiredVpn.consentIntent(appContext)
            if (consent != null && !vpnAsked) {
                vpnAsked = true
                onLog("wired: consent was missing after all; raising the dialog and keeping the open port")
                requestVpnConsent(consent)
                return false
            }
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

    /**
     * The driver's answer to the system's VPN prompt.
     *
     * Called from the host activity. A refusal is final for this session and says why; a grant
     * carries on with the port that is already open.
     */
    fun resumeAfterVpnConsent(granted: Boolean) {
        if (closed) return
        if (!granted) {
            onLog("wired: the VPN prompt was declined, so the cable cannot carry packets")
            teardown("the VPN prompt was declined")
            return
        }
        // Granted: carry on with what was waiting — an open port if the phone had already switched,
        // otherwise the device whose insertion was deliberately not spent.
        port?.let { open ->
            onLog("wired: VPN allowed; continuing with the open port")
            startOnWorker { beginCarrying(open) }
            return
        }
        val device = pendingDevice
        if (device != null) {
            onLog("wired: VPN allowed; continuing with the phone that was already waiting")
            startOnWorker { bring(device) }
            return
        }
        onLog("wired: VPN allowed; the cable can be driven now")
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
        vpnAsked = false
        if (data != null) onLog("wired: stopped ($reason)")
        // Only a link that was actually carrying packets is worth announcing: a failed setup would
        // otherwise tell the receiver to resume wireless for a cable that never worked.
        if (wasUp && !closed) onLinkDown(reason)
    }

    // ------------------------------------------------------------------ helpers

    private fun requestPermission(device: UsbDevice, attempt: Int) {
        val usb = manager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        onLog(
            "wired: asking the system for permission to open ${device.deviceName} " +
                "(ask $attempt/$PERMISSION_ASK_LIMIT, window ${if (hostVisible) "visible" else "hidden"})",
        )
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

    /** What the port looks like, in the one word the log needs. A description, never a verdict. */
    private fun phoneMode(device: UsbDevice): PhoneMode {
        if (device.vendorId != HUAWEI_VID && device.vendorId != HONOR_VID) return PhoneMode.NOT_A_PHONE
        if (inNcmLayout(device)) return PhoneMode.NCM
        return when (device.productId) {
            PID_NORMAL_A, PID_NORMAL_B, PID_NORMAL_C -> PhoneMode.KNOWN_NORMAL
            PID_USB_DEBUGGING -> PhoneMode.DEBUGGING_PID
            else -> PhoneMode.OTHER_PID
        }
    }

    private fun label(mode: PhoneMode): String = when (mode) {
        PhoneMode.NOT_A_PHONE -> "not a Huawei/Honor phone, left alone"
        PhoneMode.KNOWN_NORMAL -> "a product id OpenHC measured as answering the block"
        PhoneMode.DEBUGGING_PID -> "pid 0x5000, the id OpenHC measured with USB debugging on; block still sent"
        PhoneMode.NCM -> "already CDC-NCM"
        PhoneMode.OTHER_PID -> "an id this build has not measured; block still sent"
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
         * Product ids, kept for the log line only (`HuaweiPhone` in OpenHC measured them).
         *
         * They are **not** used to decide whether to send: the normal states are 0x107d/0x107e/0x107f,
         * 0x5000 was measured while USB debugging was on, and an id not in this list is simply
         * unmeasured — refusing on it would have blocked the 2026-11-28 phone, whose layout was the
         * ordinary file-transfer one.
         */
        private const val PID_NORMAL_A = 0x107D
        private const val PID_NORMAL_B = 0x107E
        private const val PID_NORMAL_C = 0x107F
        private const val PID_USB_DEBUGGING = 0x5000

        private const val CAR_INFO_ATTEMPTS = 3
        private const val CAR_INFO_RETRY_MILLIS = 300L
        private const val PERMISSION_WAIT_MS = 60_000L
        private const val PERMISSION_POLL_MILLIS = 250L
        private const val PERMISSION_REASK_MILLIS = 4_000L
        private const val PERMISSION_ASK_LIMIT = 3
        private const val PORT_POLL_MILLIS = 1_000L
        private const val REPLUG_HOLD_MS = 5_000L
        private const val VPN_PREFIX = 24

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
        // The USB-attach alias belongs to the same switch: with the cable off there is no reason for
        // the system to offer this app when a phone is plugged in.
        HiCarWiredAttach.setEnabled(context, enabled)
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
