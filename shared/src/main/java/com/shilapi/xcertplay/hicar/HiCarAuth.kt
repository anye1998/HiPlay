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

    /** True once a key exists. Note this happens *before* the operation itself is over. */
    val complete: Boolean

    /**
     * True once the engine reports the whole operation finished.
     *
     * A first-time bind derives the session key at the end of key agreement and then sends the
     * exchange phase — 661 bytes of peer auth info in the run at 11:35 — which the engine must still
     * parse and answer. Treating [complete] as "stop feeding" discarded that phase, left the phone
     * waiting and cost the driver a second PIN prompt.
     */
    val operationComplete: Boolean
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

    /**
     * True while part of an envelope is buffered.
     *
     * A single envelope can span reads, and the continuation of one does not start with `{`. Anyone
     * deciding what protocol a chunk belongs to has to ask this first: the run at 11:53 was killed by
     * a chunk that began mid-envelope and was therefore mistaken for a different protocol.
     */
    val awaitingRest: Boolean get() = partial.isNotEmpty()

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
        // Every envelope the phone sends, in its own words. A run that ends in an engine inform says
        // only *that* something was rejected; the phone's message number and payload are the only
        // place its reason exists, and without this line a log can show a failed step and no cause.
        onLog(
            "HiCar in: oper_type=$operType message=${data.optInt("message", -1)}" +
                "${messageName(data.optInt("message", -1))} keys=${keysOf(data)} " +
                "${dataText.length}B${errorSuffix(dataText)} ${brief(dataText)}",
        )
        return when (operType) {
            OPER_IDENTITY -> {
                if (data.optBoolean("usb_connection", false)) throw HiCarWireException("usb connection")
                val peer = data.optString("deviceId", "")
                if (peer.toByteArray(StandardCharsets.UTF_8).size !in 1..64) throw HiCarWireException("identity length")
                // A repeat of the same identity is not an error: the phone re-sends it when its own
                // attempt timed out or its dialog was reopened, and refusing it closed a channel the
                // phone was still willing to use (twice at 04:05, logged as "identity state").
                // Answering again with the same identity is idempotent and costs nothing. A *different*
                // identity mid-session is a real violation and still ends the channel.
                if (identityExchanged) {
                    if (peer != peerIdentity) throw HiCarWireException("identity changed mid-session")
                    onLog("HiCar identity repeated by the phone; answering with the same identity")
                    return listOf(identityReply())
                }
                peerIdentity = peer
                identityExchanged = true
                authenticator?.begin(peer)
                onLog("HiCar identity exchanged")
                listOf(identityReply())
            }

            OPER_AUTH -> {
                if (!identityExchanged) throw HiCarWireException("authentication order")
                val engine = authenticator
                    ?: throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
                // The gate is the engine's own "operation over", not "a key exists": a bind derives
                // the key at the end of key agreement and then still expects the exchange phase.
                // The run at 11:35 sent exactly that (message 3, 661 bytes) and got nothing back.
                if (engine.operationComplete) {
                    onLog(
                        "HiCar envelope after authentication: oper_type=$operType, " +
                            "message=${data.optInt("message", -1)}, keys=${keysOf(data)}, " +
                            "${dataText.length} bytes; no reply is defined for it yet",
                    )
                    return emptyList()
                }
                // A peer inform is the phone saying "I am giving up, here is why". The engine has no
                // parser for it (`build_struct_by_receive_data` in hichain.c lists requests and
                // responses only), so feeding it in answers HC_UNKNOW_MESSAGE — our own error, sent
                // back as a second inform, which buries the phone's reason and invites it to close.
                // The run at 04:05 did exactly that twice. Reading it here keeps the reason.
                val messageCode = data.optInt("message", -1)
                if (messageCode == MESSAGE_INFORM) {
                    val reason = data.optJSONObject("payload")?.optString("errorCode", "") ?: ""
                    val named = reason.toIntOrNull()?.let { HiChainCodes.errorName(it) ?: "code $it" }
                    onLog(
                        "HiCar: the phone ended the operation itself (inform, errorCode=$reason" +
                            (named?.let { " $it" } ?: "") + "); that code is the phone's own reason, " +
                            "and the step lines above say how far it had got",
                    )
                    throw HiCarWireException("peer ended the operation (inform $reason)")
                }
                // Message 17 announces the key length the phone will accept; every other message
                // keeps the current value, which starts at the engine's 32-byte default.
                if (messageCode == MESSAGE_STS_REQUEST) {
                    val requested = data.optJSONObject("payload")?.optInt("keyLength", 0) ?: 0
                    if (requested != 0) {
                        if (requested != 16 && requested != 32) throw HiCarWireException("key length")
                        keyLength = requested
                    }
                }
                val packets = engine.step(dataText.toByteArray(StandardCharsets.UTF_8), keyLength)
                // The phone's own message number names the phase (17 announces the key length, 3 is
                // the bind exchange); without it a step count says nothing about where we are.
                onLog("HiChain step: ${packets.size} packet(s) for message ${data.optInt("message", -1)}")
                // What we answered with matters as much as what arrived: a peer that gives up right
                // after our reply has judged that reply, so its message number and fields belong in
                // the log next to the step that produced it.
                packets.forEach { packet -> onLog("HiChain out: ${brief(String(packet, StandardCharsets.UTF_8))}") }
                packets.map { packet ->
                    envelope(OPER_AUTH, JSONObject(String(packet, StandardCharsets.UTF_8)))
                }
            }

            else -> {
                // An unknown operation is recorded rather than fatal: closing here is what made the
                // phone restart its whole flow (and ask for the PIN again) with nothing to show for it.
                onLog(
                    "HiCar envelope with unknown oper_type=$operType " +
                        "(message=${data.optInt("message", -1)}, keys=${keysOf(data)}, ${dataText.length} bytes); ignored",
                )
                emptyList()
            }
        }
    }

    private fun keysOf(data: JSONObject): String =
        runCatching { data.keys().asSequence().sorted().joinToString(",") }.getOrDefault("unreadable")

    /**
     * The identity reply, built in one place.
     *
     * It is sent once per connection and again if the phone repeats its own identity, and the second
     * copy has to be byte-identical: a phone that compares the two would see a difference it cannot
     * explain, and `has_pin` in particular decides whether it asks for the code at all.
     */
    private fun identityReply(): ByteArray = envelope(
        OPER_IDENTITY,
        JSONObject()
            .put("deviceId", localIdentity)
            .put("devicename", deviceName)
            .put("has_pin", pinConfigured)
            .put("usb_connection", false)
            .put("CONNECT_ABILITY_KEY", "1")
            .put("CAR_MODE_ID", model),
    )

    /**
     * One JSON object squeezed onto one log line.
     *
     * The line has to survive being read on a head unit and pasted into a report, so it is cut at a
     * fixed length: a PAKE or exchange payload can be kilobytes of hex, and the part that explains a
     * refusal (`message`, `errorCode`, `authId`, `keyLength`) is always near the front.
     */
    private fun brief(text: String, limit: Int = BRIEF_CHARS): String {
        val compact = text.replace(Regex("\\s+"), " ")
        return if (compact.length <= limit) compact else compact.take(limit) + "…"
    }

    /** Message numbers from `base.h`, so a log line does not need the header open beside it. */
    private fun messageName(code: Int): String = HiChainCodes.messageName(code)

    /**
     * A field the phone only fills in when it is complaining.
     *
     * `INFORM_MESSAGE` carries `payload.errorCode`, and that number is the whole reason a run died:
     * printed as a bare 5 it looks like a step count, so it is translated here into the `hc_error`
     * name the engine itself uses.
     */
    private fun errorSuffix(payload: String): String {
        val code = ERROR_CODE.find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return ""
        return " errorCode=$code${HiChainCodes.errorName(code)?.let { " $it" } ?: ""}"
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

        /** `INFORM_MESSAGE`: a peer's own "I am ending this, with this error code". */
        const val MESSAGE_INFORM = 0x8080
        const val MAX_MESSAGE_BYTES = 65_536
        const val MAX_DEPTH = 32

        /** Enough of a payload to name the phase; the hex bodies are far longer and say nothing here. */
        const val BRIEF_CHARS = 240

        /** An `errorCode` inside an inform payload; the only field that explains a refusal. */
        val ERROR_CODE = Regex("\"errorCode\"\\s*:\\s*(-?\\d+)")
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
    override val operationComplete: Boolean get() = false

    override fun begin(peerIdentity: String) = Unit

    override fun step(chunk: ByteArray, keyLength: Int): List<ByteArray> = throw HiCarAuthException(blocker)

    override fun close() = Unit
}
