package com.shilapi.xcertplay.hicar

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * The USB side of a wired session: the NCM interfaces, their bulk endpoints, and the class requests.
 *
 * A phone that accepted the car-info block re-enumerates with Huawei's own NCM layout:
 *
 * ```
 * interface 0 alt 0   CDC NCM control (class 2 / subclass 13), one interrupt IN endpoint
 * interface 1 alt 0   CDC data (class 10), no endpoints
 * interface 1 alt 1   CDC data (class 10), one bulk IN and one bulk OUT
 * ```
 *
 * Nothing in this head unit's kernel claims that layout (`huawei_cdc_ncm` binds 0xff/0x02/0x16, and
 * the phone does not present that), so the packets have to be moved by hand: claim the interfaces,
 * switch the data interface to its active alternate setting, read the NTB parameters, and run the
 * [HiCarNcmDataPath] framing over the two bulk endpoints.
 *
 * Ported from `CdcNcm` / `NcmInterfaces` / the USB half of `NcmUsbLink` in GGBondLab/OpenHC
 * (GPL-3.0-only).
 */
internal class HiCarNcmUsb private constructor(
    private val connection: UsbDeviceConnection,
    private val control: UsbInterface,
    private val dataActive: UsbInterface,
    private val dataIdle: UsbInterface?,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val parameters: NtbParameters?,
    private val plan: HiCarCdcNcm.ReceivePlan,
) : NtbPort, AutoCloseable {

    override val receiveSize: Int get() = plan.receiveSize
    override val outLayout: NtbOutLayout get() = layout
    override val maxOutBlockLength: Int get() = parameters?.ntbOutMaxSize?.toInt()?.coerceAtLeast(1) ?: 2048
    override val zlpPacketSize: Int get() = bulkOut.maxPacketSize

    private val layout = if (parameters == null) {
        NtbOutLayout.DEFAULT
    } else {
        NtbOutLayout.of(parameters.outDivisor, parameters.outPayloadRemainder, parameters.outAlignment)
    }

    @Volatile private var closed = false

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) return -1
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val read = runCatching {
            connection.bulkTransfer(bulkIn, buffer, minOf(receiveSize, buffer.size), timeoutMs)
        }.getOrDefault(-1)
        if (read >= 0) return read
        // bulkTransfer reports a timeout and a dead device the same way. Only the elapsed time tells
        // them apart: a timeout waits, an unplugged device returns at once. Treating an error as a
        // timeout spins until the next stage gives up instead of closing the link.
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        return if (elapsed >= timeoutMs / 2) 0 else -1
    }

    override fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean {
        if (closed) return false
        val written = runCatching { connection.bulkTransfer(bulkOut, buffer, length, timeoutMs) }.getOrDefault(-1)
        return written == length
    }

    override fun close() {
        if (closed) return
        closed = true
        // Release before closing: a claimed interface left behind makes the next open fail until the
        // device is unplugged.
        runCatching { connection.releaseInterface(dataActive) }
        dataIdle?.let { runCatching { connection.releaseInterface(it) } }
        runCatching { connection.releaseInterface(control) }
        runCatching { connection.close() }
    }

    /** What the phone reported, for the log. */
    fun describe(): String {
        val p = parameters
        return if (p == null) {
            "no NTB parameters; assuming ${HiCarCdcNcm.MIN_NTB_SIZE}-byte blocks and the default layout"
        } else {
            "NTB formats 0x${p.formatsSupported.toString(16)}, in max ${p.ntbInMaxSize}, " +
                "out max ${p.ntbOutMaxSize}, out alignment ${p.outDivisor}/${p.outPayloadRemainder}/" +
                "${p.outAlignment}, $layout, receive ${plan.receiveSize}"
        }
    }

    companion object {
        /** The phone's NCM control interface: CDC class, NCM subclass. */
        private const val CLASS_COMM = 2
        private const val SUBCLASS_NCM = 13
        private const val CLASS_CDC_DATA = 10

        private const val CONTROL_TIMEOUT_MS = 1000
        private const val NOTIFICATION_BYTES = 16
        private const val NOTIFICATION_TIMEOUT_MS = 300

        /**
         * Opens the device and prepares the NCM interfaces, or returns null with a reason logged.
         *
         * The data interface is switched to its idle setting and back to the active one: that cycle
         * is what resets the NCM function, and a device opened for the first time otherwise reads
         * nothing at all on the bulk endpoint.
         */
        fun open(manager: UsbManager, device: UsbDevice, onLog: (String) -> Unit): HiCarNcmUsb? {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val control = interfaces.firstOrNull {
                it.interfaceClass == CLASS_COMM && it.interfaceSubclass == SUBCLASS_NCM
            }
            if (control == null) {
                onLog("USB NCM: no control interface (class 2 / subclass 13); the phone is not in NCM mode")
                return null
            }
            val dataActive = interfaces.firstOrNull { iface ->
                iface.interfaceClass == CLASS_CDC_DATA &&
                    bulkEndpoints(iface).let { it.first != null && it.second != null }
            }
            if (dataActive == null) {
                onLog("USB NCM: no data interface with both bulk endpoints")
                return null
            }
            val dataIdle = interfaces.firstOrNull {
                it.interfaceClass == CLASS_CDC_DATA && it.id == dataActive.id && it.endpointCount == 0
            }
            val (inEndpoint, outEndpoint) = bulkEndpoints(dataActive)
            if (inEndpoint == null || outEndpoint == null) {
                onLog("USB NCM: the data interface has no usable bulk pair")
                return null
            }
            // The control interface's interrupt IN endpoint: the phone posts its link state there.
            val notification = (0 until control.endpointCount)
                .map { control.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
            val connection = runCatching { manager.openDevice(device) }.getOrNull()
            if (connection == null) {
                onLog("USB NCM: could not open ${device.deviceName}; another app may hold it")
                return null
            }
            try {
                // 1. Claim only the two interfaces the packets use, and force the claim: the head
                //    unit's kernel driver binds the NCM layout the moment the phone switches, and a
                //    forced claim kicks it out (measured by OpenHC; a normal app may do it).
                if (!connection.claimInterface(control, true) ||
                    !connection.claimInterface(dataActive, true)
                ) {
                    throw IllegalStateException("the NCM interfaces could not be claimed")
                }
                // 2. The block sizes come from the phone.
                val parameters = readParameters(connection, control, onLog)
                // 3. Select the idle alternate setting: that cycle is what resets the NCM function,
                //    and a data interface opened for the first time otherwise reads nothing.
                dataIdle?.let {
                    if (!connection.setInterface(it)) {
                        onLog("USB NCM: could not select the idle setting; continuing")
                    }
                }
                // 4. Cap the phone's input size *while the data interface is still idle* — the spec
                //    only allows this request in that state, and OpenHC sends it here for that reason.
                val plan = HiCarCdcNcm.receivePlan(parameters?.ntbInMaxSize, Build.VERSION.SDK_INT)
                plan.setInputSize?.let { size ->
                    val payload = HiCarCdcNcm.encodeNtbInputSize(size)
                    val sent = connection.controlTransfer(
                        HiCarCdcNcm.REQUEST_TYPE_OUT,
                        HiCarCdcNcm.SET_NTB_INPUT_SIZE,
                        0,
                        control.id,
                        payload,
                        payload.size,
                        CONTROL_TIMEOUT_MS,
                    )
                    onLog(
                        if (sent == payload.size) {
                            "USB NCM: the phone keeps blocks at $size bytes"
                        } else {
                            "USB NCM: could not cap blocks at $size bytes (result $sent); larger " +
                                "blocks will be dropped"
                        },
                    )
                }
                // 5. Only now the setting that carries the endpoints.
                if (!connection.setInterface(dataActive)) {
                    throw IllegalStateException("the data alternate setting was refused")
                }
                // 6. Read one connection notification off the control interface: the phone posts its
                //    link state there once the function is up, and draining it is what the
                //    measured-good flow does. A failure here is not fatal.
                notification?.let {
                    runCatching {
                        connection.bulkTransfer(it, ByteArray(NOTIFICATION_BYTES), NOTIFICATION_BYTES, NOTIFICATION_TIMEOUT_MS)
                    }
                }
                if (parameters != null && !parameters.supportsNtb16) {
                    onLog("USB NCM: the phone does not list NTB16 support; the framing will probably be refused")
                }
                return HiCarNcmUsb(
                    connection = connection,
                    control = control,
                    dataActive = dataActive,
                    dataIdle = dataIdle,
                    bulkIn = inEndpoint,
                    bulkOut = outEndpoint,
                    parameters = parameters,
                    plan = plan,
                )
            } catch (failure: Exception) {
                onLog("USB NCM: ${failure.message ?: failure.javaClass.simpleName}")
                runCatching { connection.releaseInterface(dataActive) }
                runCatching { connection.releaseInterface(control) }
                runCatching { connection.close() }
                return null
            }
        }

        /** The bulk IN and OUT endpoints of one interface, matched by direction. */
        private fun bulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = input ?: endpoint
                else output = output ?: endpoint
            }
            return input to output
        }

        /**
         * `GET_NTB_PARAMETERS`. Null when the phone stalls it: the session can still run on the
         * defaults, and a device that answers this badly is worth a log line rather than a failure.
         */
        private fun readParameters(
            connection: UsbDeviceConnection,
            control: UsbInterface,
            onLog: (String) -> Unit,
        ): NtbParameters? {
            val buffer = ByteArray(NtbParameters.LENGTH)
            val read = connection.controlTransfer(
                HiCarCdcNcm.REQUEST_TYPE_IN,
                HiCarCdcNcm.GET_NTB_PARAMETERS,
                0,
                control.id,
                buffer,
                buffer.size,
                CONTROL_TIMEOUT_MS,
            )
            if (read < NtbParameters.LENGTH) {
                onLog("USB NCM: GET_NTB_PARAMETERS answered $read bytes; using the defaults")
                return null
            }
            return runCatching { NtbParameters.parse(buffer, read) }
                .onFailure { onLog("USB NCM: NTB parameters unreadable (${it.message})") }
                .getOrNull()
        }
    }
}
