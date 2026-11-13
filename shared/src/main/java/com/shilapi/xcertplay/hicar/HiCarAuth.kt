package com.shilapi.xcertplay.hicar

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

/**
 * Why the authentication step cannot complete in this build.
 *
 * HiChain is Huawei's pairing protocol, but it is not closed source only: OpenHarmony publishes an
 * Apache-2.0 implementation (`security_deviceauth` with `deviceauth_lite`, plus `security_huks`
 * with `huks_lite`) that third-party receivers compile into their APK, and both reference projects
 * for this migration do exactly that. Nothing here needs a Huawei-issued accessory certificate:
 * the first connection is authorised by the six-digit code shown on the car.
 */
enum class HiCarAuthBlocker(val message: String) {
    /**
     * The engine is missing from this APK, not unavailable in principle. Porting the OpenHarmony
     * sources behind our own JNI adapter is the remaining work; see `docs/HICAR_MIGRATION.md`.
     */
    NATIVE_HICHAIN_LIBRARY_MISSING("HiChain native engine (OpenHarmony deviceauth_lite) is not built into this APK"),

    /** The car's six-digit code has not been trusted by this phone yet; the driver enters it once. */
    PIN_NOT_TRUSTED("The phone has not yet trusted this car's six-digit code"),

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

    /**
     * Feeds one transport chunk and returns the bytes to write back, in order.
     *
     * [keyLength] is what the phone asked for (16 or 32); the engine must agree before it exports a key.
     */
    fun step(chunk: ByteArray, keyLength: Int = 32): List<ByteArray>

    /** Non-null only after a successful authentication; 16 or 32 bytes. */
    val sessionKey: ByteArray?

    val complete: Boolean
}

/**
 * The JSON envelope exchange that wraps HiChain messages on the business channel.
 *
 * `oper_type` 1 exchanges identities; `oper_type` 3 carries the HiChain packets themselves, which
 * are forwarded to [authenticator] untouched. The envelope framing lives here so it can be tested
 * without a phone, while the cryptography lives in the native engine.
 */
internal class HiCarIdentityExchange(
    private val localIdentity: String,
    private val deviceName: String,
    private val model: String,
    private val pinConfigured: Boolean,
    private val authenticator: HiCarAuthenticator? = null,
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
    private var keyLength = 32

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
                            replies.addAll(handleMessage(text))
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

    private fun handleMessage(text: String): List<ByteArray> {
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
                authenticator?.begin(peer)
                onLog("HiCar identity exchanged")
                listOf(
                    envelope(
                        OPER_IDENTITY,
                        JSONObject()
                            .put("deviceId", localIdentity)
                            .put("devicename", deviceName)
                            .put("has_pin", pinConfigured)
                            .put("usb_connection", false)
                            .put("CONNECT_ABILITY_KEY", "1")
                            .put("CAR_MODE_ID", model),
                    ),
                )
            }

            OPER_AUTH -> {
                if (!identityExchanged) throw HiCarWireException("authentication order")
                val engine = authenticator
                    ?: throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
                // Message 17 announces the key length the phone will accept; every other message
                // keeps the current value, which starts at the engine's 32-byte default.
                if (data.optInt("message", 0) == MESSAGE_STS_REQUEST) {
                    val requested = data.optJSONObject("payload")?.optInt("keyLength", 0) ?: 0
                    if (requested != 0) {
                        if (requested != 16 && requested != 32) throw HiCarWireException("key length")
                        keyLength = requested
                    }
                }
                val packets = engine.step(dataText.toByteArray(StandardCharsets.UTF_8), keyLength)
                onLog("HiChain step: ${packets.size} packet(s)")
                packets.map { packet ->
                    envelope(OPER_AUTH, JSONObject(String(packet, StandardCharsets.UTF_8)))
                }
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

        /** STS start: the only message that announces the key length. */
        const val MESSAGE_STS_REQUEST = 17
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

    override fun step(chunk: ByteArray, keyLength: Int): List<ByteArray> = throw HiCarAuthException(blocker)

    override fun close() = Unit
}
