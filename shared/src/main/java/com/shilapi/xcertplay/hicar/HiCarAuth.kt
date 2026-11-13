package com.shilapi.xcertplay.hicar

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

/** Why the authentication step cannot complete on a side-loaded build. */
enum class HiCarAuthBlocker(val message: String) {
    /**
     * HiChain is Huawei's own pairing protocol. It needs the native `deviceauth_lite` engine plus a
     * HUKS key store and a device credential provisioned for the product; neither is part of AOSP
     * and neither can be obtained by installing an APK.
     */
    NATIVE_HICHAIN_LIBRARY_MISSING("HiChain native engine is not bundled"),

    /** A HiCar accessory identity is issued to a product, not generated on the car. */
    DEVICE_CREDENTIAL_MISSING("No HiCar accessory credential is provisioned"),

    /** Signature/SELinux conditions some HiCar capabilities require from a pre-installed app. */
    VENDOR_SIGNATURE_REQUIRED("Capability requires a vendor-signed system app"),
}

internal class HiCarAuthException(val blocker: HiCarAuthBlocker, cause: Throwable? = null) :
    IOException(blocker.message, cause)

/**
 * Session authentication seam.
 *
 * The receiver only advances to the network and media stages with a key this interface produced.
 * Implementations must return null until the handshake actually finished, so a missing engine
 * shows up as a blocked connection rather than as an unencrypted session.
 */
internal interface HiCarAuthenticator : Closeable {
    /** The peer identity the phone announced in `oper_type` 1. */
    fun begin(peerIdentity: String)

    /** Feeds one transport chunk and returns the bytes to write back, in order. */
    fun step(chunk: ByteArray): List<ByteArray>

    /** Non-null only after a successful authentication; 16 or 32 bytes. */
    val sessionKey: ByteArray?

    val complete: Boolean
}

/**
 * The identity half of HiCar authentication: the JSON envelope exchange the phone starts.
 *
 * This part is implemented and unit-tested because it is plain JSON on the business channel. It
 * deliberately stops at the first `oper_type` 3 message, which is where HiChain begins and where a
 * side-loaded app has no engine to answer with.
 */
internal class HiCarIdentityExchange(
    private val localIdentity: String,
    private val deviceName: String,
    private val model: String,
    private val pinConfigured: Boolean,
    private val onLog: (String) -> Unit = {},
) {
    var peerIdentity: String? = null
        private set

    var identityExchanged: Boolean = false
        private set

    private var partial = StringBuilder()
    private var quoted = false
    private var escaped = false
    private var depth = 0
    private var failed = false

    /** Feeds business-channel bytes; returns the envelopes to send back. */
    fun feed(chunk: ByteArray, count: Int = chunk.size): List<ByteArray> {
        if (failed) throw HiCarWireException("authentication session finished")
        if (count < 0 || count > chunk.size) throw HiCarWireException("authentication chunk")
        val replies = ArrayList<ByteArray>()
        try {
            for (index in 0 until count) {
                val byte = chunk[index].toInt() and 0xFF
                if (partial.isEmpty()) {
                    if (byte == ' '.code || byte == '\n'.code || byte == '\r'.code || byte == '\t'.code) continue
                    if (byte != '{'.code) throw HiCarWireException("JSON marker")
                }
                partial.append(byte.toChar())
                if (partial.length > MAX_MESSAGE_BYTES) throw HiCarWireException("JSON limit")
                when {
                    quoted -> when {
                        escaped -> escaped = false
                        byte == '\\'.code -> escaped = true
                        byte == '"'.code -> quoted = false
                    }

                    byte == '"'.code -> quoted = true
                    byte == '{'.code || byte == '['.code -> {
                        depth++
                        if (depth > MAX_DEPTH) throw HiCarWireException("JSON nesting")
                    }

                    byte == '}'.code || byte == ']'.code -> {
                        depth--
                        if (depth < 0) throw HiCarWireException("JSON nesting")
                        if (depth == 0) {
                            val text = partial.toString()
                            partial = StringBuilder()
                            handleMessage(text)?.let(replies::add)
                        }
                    }
                }
            }
            return replies
        } catch (failure: IOException) {
            failed = true
            throw failure
        } catch (failure: RuntimeException) {
            failed = true
            throw HiCarWireException("authentication failure", failure)
        }
    }

    private fun handleMessage(text: String): ByteArray? {
        val envelope = try {
            JSONObject(text)
        } catch (failure: JSONException) {
            throw HiCarWireException("envelope JSON", failure)
        }
        val operType = envelope.optInt("oper_type", -1)
        val dataText = envelope.optString("data", "")
        if (dataText.isEmpty()) throw HiCarWireException("data type")
        val data = try {
            JSONObject(dataText)
        } catch (failure: JSONException) {
            throw HiCarWireException("data JSON", failure)
        }
        return when (operType) {
            OPER_IDENTITY -> {
                if (identityExchanged || data.optBoolean("usb_connection", false)) {
                    throw HiCarWireException("identity state")
                }
                val peer = data.optString("deviceId", "")
                if (peer.toByteArray(StandardCharsets.UTF_8).size !in 1..64) throw HiCarWireException("identity length")
                peerIdentity = peer
                identityExchanged = true
                onLog("HiCar identity exchanged")
                envelope(
                    OPER_IDENTITY,
                    JSONObject()
                        .put("deviceId", localIdentity)
                        .put("devicename", deviceName)
                        .put("has_pin", pinConfigured)
                        .put("usb_connection", false)
                        .put("CONNECT_ABILITY_KEY", "1")
                        .put("CAR_MODE_ID", model),
                )
            }

            OPER_AUTH -> {
                if (!identityExchanged) throw HiCarWireException("authentication order")
                // Reaching here means the phone wants HiChain. Nothing on a side-loaded APK can
                // answer it, so the session ends with an explicit blocker instead of a fake key.
                throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
            }

            else -> throw HiCarWireException("operation type")
        }
    }

    private fun envelope(operType: Int, data: JSONObject): ByteArray =
        JSONObject()
            .put("version", "A_0")
            .put("discover_type", 1)
            .put("oper_type", operType)
            .put("data", data.toString())
            .put("usb_connection", false)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val OPER_IDENTITY = 1
        const val OPER_AUTH = 3
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_DEPTH = 32
    }
}

/**
 * The authenticator a build without the HiChain engine uses.
 *
 * [begin] succeeds so the identity exchange still runs and the log shows exactly how far the phone
 * got; [step] then fails closed on the first HiChain message. It never synthesises a key: an
 * invented key would encrypt the session with material the phone does not share, which looks like a
 * connection attempt while guaranteeing failure later.
 */
internal class HiCarUnavailableAuthenticator(
    private val blocker: HiCarAuthBlocker = HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING,
) : HiCarAuthenticator {
    override val sessionKey: ByteArray? get() = null
    override val complete: Boolean get() = false

    override fun begin(peerIdentity: String) = Unit

    override fun step(chunk: ByteArray): List<ByteArray> = throw HiCarAuthException(blocker)

    override fun close() = Unit
}
