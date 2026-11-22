package com.shilapi.xcertplay.hicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import java.io.IOException

/**
 * Dials a phone that is already in the car.
 *
 * `ACTION_ACL_CONNECTED` is the moment the phone's classic Bluetooth link comes up — which on a head
 * unit means the phone is connected for calls and media, well before anyone thinks about HiCar.
 * That is exactly when the reverse direction of the protocol is worth using: the car dials the
 * phone's `0xFE35` service instead of waiting to be dialled. GGBondLab/OpenHC does this and it is
 * what makes a session start with nobody touching the screen.
 *
 * The dial only happens for a phone this receiver has completed a session with
 * ([HiCarPhoneRegistry]); the first connection must still come from the phone, because that is when
 * the driver types the PIN.
 *
 * `ACL_CONNECTED` is a protected broadcast: it cannot be declared in the manifest, so this only
 * works while the receiver is running — which is the only time it matters.
 */
internal class HiCarAutoReconnect(
    context: Context,
    private val onLog: (String) -> Unit,
    /** Whether the receiver is accepting connections at all. */
    private val running: () -> Boolean,
    /**
     * Hands a connected socket to the session that would have served an incoming one.
     *
     * False means the round ended in between; the socket is closed here so a half-call does not keep
     * a Bluetooth link busy.
     */
    private val onSocket: (BluetoothSocket) -> Boolean,
) : AutoCloseable {

    private val appContext = context.applicationContext

    /** Monotonic, so a wall-clock change cannot make the cooldown negative or infinite. */
    @Volatile private var lastAttemptMillis = 0L
    @Volatile private var closed = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                return
            }
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
            val address = runCatching { device.address }.getOrNull()?.uppercase() ?: return
            val verdict = HiCarReconnectTrigger.decide(
                connected = action == BluetoothDevice.ACTION_ACL_CONNECTED,
                address = address,
                known = HiCarPhoneRegistry.known(appContext),
                running = running(),
                enabled = HiCarReconnectSettings.enabled(appContext),
                lastAttemptMillis = lastAttemptMillis,
                nowMillis = SystemClock.elapsedRealtime(),
            )
            if (!verdict.dial) {
                // Only worth a line when the phone arrived: a disconnect explains itself.
                if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    onLog("redial: $address appeared but is not dialled (${verdict.reason})")
                }
                return
            }
            lastAttemptMillis = SystemClock.elapsedRealtime()
            onLog("redial: dialling $address on 0xFE35 (${verdict.reason})")
            Thread({ dial(device, address) }, "hicar-redial").apply { isDaemon = true }.start()
        }
    }

    fun start() {
        if (registered || closed) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Explicitly not exported: this is our own receiver, and on Android 13+ an implicit one
        // would be refused at registration.
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        registered = ok
        onLog(
            if (ok) "redial: watching for the phone's Bluetooth link"
            else "redial: this build cannot watch Bluetooth links; the phone has to dial",
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /**
     * Builds and connects the socket, then hands it over.
     *
     * `createInsecureRfcommSocketToServiceRecord` rather than the bonded variant: HiCar does not
     * bond, and the secure call would fail with "device not paired" before the phone is ever reached.
     * A refused connect is logged with the exception class — "connection refused" and "service not
     * found" look identical on the car otherwise, and they mean different things (the phone is not
     * in HiCar mode vs this side asked for the wrong service).
     */
    @SuppressLint("MissingPermission")
    private fun dial(device: BluetoothDevice, address: String) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(HiCarCodes.SERVICE_CONTROL)
            socket.connect()
        } catch (failure: IOException) {
            onLog("redial: $address did not answer (${failure.javaClass.simpleName}: ${failure.message})")
            runCatching { socket?.close() }
            return
        } catch (failure: SecurityException) {
            onLog("redial: needs the nearby-devices permission")
            runCatching { socket?.close() }
            return
        }
        val handed = socket?.let { onSocket(it) } == true
        if (!handed) {
            runCatching { socket?.close() }
            onLog("redial: $address answered after the round ended; closed it")
            return
        }
        onLog("redial: $address connected on the control channel; the phone should dial the business one next")
    }
}
