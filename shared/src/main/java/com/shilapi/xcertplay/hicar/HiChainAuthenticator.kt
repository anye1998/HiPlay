package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicarauth.HiChainNative
import java.io.File
import java.util.Arrays

/**
 * HiChain authentication backed by the native engine in `:hicar-auth`.
 *
 * The engine is OpenHarmony's `deviceauth_lite` with `huks_lite` as a software key store; the key
 * material never leaves the app's private directory, and a session key is only returned once the
 * engine itself reports success. The six-digit [pin] is the code the car shows and the driver types
 * on the phone: the first connection binds trust, later ones authenticate against it.
 *
 * Roles: the car is HiChain's accessory (2), the phone is the centre (1).
 */
internal class HiChainAuthenticator(
    private val storeDirectory: File,
    private val seed: String,
    private val localIdentity: String,
    private val pin: String,
    private val onLog: (String) -> Unit = {},
) : HiCarAuthenticator {
    private var handle: Long = 0
    private var started = false
    private var stepIndex = 0
    private var operationDone = false
    private var failed = false

    override var sessionKey: ByteArray? = null
        private set

    override val complete: Boolean get() = sessionKey != null

    /** The engine's own end marker; a bind only reaches it after the exchange phase. */
    override val operationComplete: Boolean get() = operationDone

    override fun begin(peerIdentity: String) {
        if (!HiChainNative.available) {
            throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        }
        if (!PIN_PATTERN.matches(pin)) {
            // A wrong-length PIN cannot match what the phone asks the driver for, so the exchange
            // would fail much later with no useful message.
            throw HiCarAuthException(HiCarAuthBlocker.PIN_NOT_TRUSTED)
        }
        val prepared = HiChainNative.storeDirectory(storeDirectory)
        if (!prepared.isDirectory) throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        val initialized = HiChainNative.init(prepared.absolutePath, seed)
        if (initialized != 0) {
            throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        }
        handle = HiChainNative.create(localIdentity, peerIdentity, pin, ROLE_ACCESSORY)
        if (handle == 0L) {
            throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        }
        started = true
        stepIndex = 0
        operationDone = false
        failed = false
        // The code the engine will authenticate with, masked. A run where this does not match the
        // code on the screen has one obvious explanation, and the log should be able to show it
        // without anyone having to reproduce the screen.
        onLog(
            "HiChain begin: role=accessory peer=$peerIdentity pin=${pin.take(2)}…${pin.takeLast(2)} " +
                "(${pin.length} digits, to be compared with the code on this screen)",
        )
    }

    override fun step(chunk: ByteArray, keyLength: Int): List<ByteArray> {
        if (!started || handle == 0L) throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        if (failed) {
            // Past END_FAILED the engine has nothing left to say; feeding it further messages only
            // produces more informs and buries the one that carries the reason.
            onLog("HiChain step: session already failed, ${chunk.size} bytes ignored")
            return emptyList()
        }
        val step = HiChainNative.stepDecoded(handle, chunk, keyLength)
            ?: throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        stepIndex++
        // One line per step, with the engine's own numbers: the phase it reached and whether a key
        // exists yet. Without this, a run that derived a key and then discarded it looked identical
        // to one where the phone rejected the code.
        onLog(
            "HiChain step $stepIndex: engine=${step.code}, phase=${step.describe()}, " +
                "key=${step.keyLength}B, error=${step.error}, out=${step.packets.size}",
        )
        if (step.error != 0) {
            // Every non-zero error used to be reported as "needs a vendor-signed app", which sent the
            // investigation to the wrong place: a wrong PIN, an unsupported key length and a broken
            // packet all landed there. The numeric code is what separates them, so it goes in the log.
            onLog(
                "HiChain step $stepIndex failed: engine error=${step.error}, keyLength=$keyLength; " +
                    "a wrong code on the phone is the most likely cause, but the number is what decides",
            )
            throw HiCarAuthException(HiCarAuthBlocker.PIN_NOT_TRUSTED)
        }
        if (step.failedResult) {
            // The engine answers a failed request with an inform message that carries its own error
            // code. That packet is the only place the reason exists, so it is printed rather than the
            // bare word END_FAILED. The session is over at this point; further packets are ignored.
            failed = true
            onLog("HiChain step $stepIndex: engine reported END_FAILED; its own report follows")
            step.packets.forEachIndexed { index, packet ->
                onLog("  engine reply ${index + 1}: ${describePacket(packet)}")
            }
            return emptyList()
        }
        if (step.finished) {
            val key = HiChainNative.sessionKey(handle)
            if (key == null) {
                // The engine says the exchange produced a key but will not hand it over; say which
                // gate refused instead of throwing a library-missing blocker that means nothing here.
                onLog(
                    "HiChain: ${step.keyLength}-byte key reported but not exported " +
                        "(phase=${step.describe()}, error=${step.error}); the export gate refused it",
                )
                throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
            }
            sessionKey = key
            onLog("HiChain step $stepIndex finished: ${key.size}-byte session key")
        }
        if (step.completed) {
            // The bind exchange has been parsed and answered at this point. This, not the key, is
            // what says the engine is done with the session.
            operationDone = true
            onLog(
                "HiChain step $stepIndex completed the operation (phase=${step.describe()}), " +
                    "out=${step.packets.size}",
            )
        }
        return step.packets
    }

    /**
     * The engine's own packets are JSON text; this trims one to a log line.
     *
     * Printing them is what turns "END_FAILED" into a numeric reason, which is the difference
     * between guessing at the failure and reading it. The inform message carries a bare int32
     * payload, so the number is translated here rather than looked up by hand afterwards.
     */
    private fun describePacket(packet: ByteArray): String {
        val text = packet.map { byte -> if (byte.toInt() in 32..126) byte.toInt().toChar() else ' ' }
            .joinToString("")
        val trimmed = if (text.length <= 300) text else text.take(300) + "…"
        val code = PAYLOAD_INT.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = code?.let { ERROR_NAMES[it] ?: "code $it" }
        return if (name == null) trimmed else "$trimmed  [$name]"
    }

    override fun close() {
        // An exchange that ran steps and ended without a key can mean two very different things: the
        // phone rejected the code, or the engine finished a phase we did not recognise as the end.
        // The earlier wording asserted the first, and the run at 21:43 showed the second, so this
        // states only what is known and leaves the per-step lines above to say which one happened.
        if (started && stepIndex > 0 && sessionKey == null) {
            onLog(
                "HiChain ended after $stepIndex step(s) with no session key: either the phone rejected " +
                    "the confirm, or it closed while the exchange was still mid-phase — the step lines " +
                    "above carry the phase it reached",
            )
        }
        // A key without a finished operation is the bind exchange abandoned half-way: the phone has
        // its side done, we have a key, and the channel still dies — the case at 11:35.
        if (started && stepIndex > 0 && sessionKey != null && !operationDone) {
            onLog(
                "HiChain ended after $stepIndex step(s) with a key but before the operation finished " +
                    "(phase never reached END_SUCCESS); the exchange phase was incomplete",
            )
        }
        if (handle != 0L) {
            HiChainNative.close(handle)
            handle = 0
        }
        started = false
        stepIndex = 0
        sessionKey?.let { Arrays.fill(it, 0) }
        sessionKey = null
    }

    private companion object {
        const val ROLE_ACCESSORY = 2
        val PIN_PATTERN = Regex("[0-9]{6}")

        /** The inform message carries its error as a bare int32 payload. */
        val PAYLOAD_INT = Regex("\"payload\"\\s*:\\s*(-?\\d+)")

        /**
         * `hc_result`/`hc_error` names from `hichain.h`, so the log says which failure it was.
         *
         * Only the codes the exchange phase can plausibly return are listed; an unlisted number is
         * still printed, just without a name.
         */
        val ERROR_NAMES = mapOf(
            2 to "HC_INNER_ERROR",
            3 to "HC_STATE_ERROR",
            9 to "HC_BUILD_OBJECT_FAILED",
            12 to "HC_VERSION_UNSUPPORT",
            16 to "HC_IMPORT_AUTH_DATA_FAILED",
            26 to "HC_DECRYPT_FAILED",
            29 to "HC_VERIFY_EXCHANGE_FAILED",
            30 to "HC_SIGN_EXCHANGE_FAILED",
            31 to "HC_SAVE_LTPK_FAILED",
            33 to "HC_GET_LTPK_FAILED",
            34 to "HC_GEN_ALIAS_FAILED",
            37 to "HC_UNSUPPORT",
            38 to "HC_MESSAGE_INCONSISTENCY",
            39 to "HC_OPERATION_CODE_ERROR",
        )
    }
}
