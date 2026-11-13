package com.shilapi.xcertplay.hicar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.IOException

/**
 * The two insecure RFCOMM listeners a HiCar car side must publish.
 *
 * HiCar does not bond: the phone connects to a service record without pairing, so both sockets are
 * `listenUsingInsecureRfcommWithServiceRecord`. The business record is registered first, because the
 * phone is told its port during control-channel negotiation; the control record has a fixed UUID
 * the phone already knows.
 */
class HiCarRfcommServices private constructor(
    private val business: BluetoothServerSocket,
    private val control: BluetoothServerSocket,
    /** The channel the system assigned to the business record; 0 when it could not be read. */
    val businessChannel: Int,
) : Closeable {
    /** Blocks until the phone opens the business (authentication) channel. */
    fun acceptBusiness(): BluetoothSocket = business.accept()

    /** Blocks until the phone opens the nearby control channel. */
    fun acceptControl(): BluetoothSocket = control.accept()

    override fun close() {
        closeQuietly(business::close)
        closeQuietly(control::close)
    }

    companion object {
        /**
         * Registers both records. Returns null when the platform refuses to expose the business
         * channel: advertising a port the phone cannot reach would fail later and less clearly.
         */
        @SuppressLint("MissingPermission")
        fun open(adapter: BluetoothAdapter, onLog: (String) -> Unit = {}): HiCarRfcommServices? {
            val business = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    HiCarCodes.BUSINESS_SERVICE_NAME,
                    HiCarCodes.SERVICE_BUSINESS,
                )
            } catch (failure: IOException) {
                onLog("HiCar business record refused: ${failure.javaClass.simpleName}")
                return null
            } catch (failure: SecurityException) {
                onLog("HiCar business record needs the nearby-devices permission")
                return null
            }
            val channel = resolveChannel(business, onLog)
            if (channel < 1) {
                closeQuietly(business::close)
                onLog("HiCar business channel unavailable; not advertising a guessed port")
                return null
            }
            val control = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    HiCarCodes.CONTROL_SERVICE_NAME,
                    HiCarCodes.SERVICE_CONTROL,
                )
            } catch (failure: IOException) {
                closeQuietly(business::close)
                onLog("HiCar control record refused: ${failure.javaClass.simpleName}")
                return null
            } catch (failure: SecurityException) {
                closeQuietly(business::close)
                onLog("HiCar control record needs the nearby-devices permission")
                return null
            }
            return HiCarRfcommServices(business, control, channel)
        }

        /**
         * Reads the RFCOMM channel the system bound to a server socket.
         *
         * There is no public API for this. `getChannel` exists on some vendor builds; the platform
         * field and the socket description are the fallbacks. Every path is best effort and a
         * failure is reported instead of replaced by a constant.
         */
        @SuppressLint("DiscouragedPrivateApi")
        private fun resolveChannel(server: BluetoothServerSocket, onLog: (String) -> Unit): Int {
            runCatching {
                val method = server.javaClass.getMethod("getChannel")
                (method.invoke(server) as? Int)?.let { channel ->
                    if (channel in 1..30) {
                        onLog("HiCar business channel read from getChannel")
                        return channel
                    }
                }
            }
            runCatching {
                val field = server.javaClass.getDeclaredField("mSocket").apply { isAccessible = true }
                val socket = field.get(server) ?: return@runCatching
                runCatching {
                    val port = socket.javaClass.getDeclaredMethod("getPort").apply { isAccessible = true }
                    (port.invoke(socket) as? Int)?.let { channel ->
                        if (channel in 1..30) return channel
                    }
                }
                runCatching {
                    val port = socket.javaClass.getDeclaredField("mPort").apply { isAccessible = true }
                    val channel = port.getInt(socket)
                    if (channel in 1..30) return channel
                }
            }
            return channelFromDescription(server.toString())
        }

        /** Exposed for tests: the channel number inside a socket description. */
        internal fun channelFromDescription(description: String?): Int {
            if (description == null || description.length > 1024 || !description.contains("TYPE_RFCOMM")) return -1
            val match = CHANNEL.find(description) ?: return -1
            val channel = match.groupValues[1].toIntOrNull() ?: return -1
            return if (channel in 1..30) channel else -1
        }

        private val CHANNEL = Regex("\\bChannel:\\s*([0-9]+)\\b")

        private fun closeQuietly(close: () -> Unit) {
            try {
                close()
            } catch (_: IOException) {
                // A listener that is already gone needs no second error.
            }
        }
    }
}
