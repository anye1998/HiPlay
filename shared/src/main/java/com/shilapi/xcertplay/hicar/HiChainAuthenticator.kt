package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicarauth.HiChainNative
import java.io.File
import java.nio.charset.StandardCharsets
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
    private var peerAuthId: ByteArray? = null

    /**
     * This car's auth id in the shape the engine is told to use.
     *
     * `setprop debug.hicar.idtext 1` hands the engine the identity text - which is exactly what the
     * car's own auth agent does, its native glue copying both ids as strings capped at 64 - while
     * the default decodes the hex first. The two differ by the eight bytes the text spells out, and
     * since the phone rebuilds the signed bytes from its own copy, only the phone can say which one
     * it has stored. Making it a property keeps both shapes one `setprop` apart instead of a build.
     */
    private fun carAuthId(): ByteArray {
        val asText = HiChainNative.available &&
            runCatching { HiChainNative.debugIdShape() == 1 }.getOrDefault(false)
        if (asText) {
            return localIdentity.toByteArray(StandardCharsets.UTF_8)
        }
        return authIdBytes(localIdentity)
            ?: throw HiCarWireException("this car's identity '$localIdentity' is not an even-length hex id")
    }

    /**
     * Prints the long-term keys this pairing has in the store at one moment.
     *
     * The phone verifies our signature with the public key a bind gave it; an authenticate signs
     * with whatever private key the store returns for the same alias. Calling this before a session,
     * as soon as key agreement ends, and again at close puts both fingerprints in one log, so the
     * next run answers "is it the same key?" with bytes instead of reasoning.
     */
    private fun probeKeyset(stage: String) {
        val peer = peerAuthId ?: return
        val local = runCatching { carAuthId() }.getOrNull() ?: return
        if (!HiChainNative.available || handle == 0L) return
        val report = runCatching { HiChainNative.probeKeyset(local, peer) }.getOrNull() ?: return
        onLog("HiChain key store [$stage]: $report")
    }

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
        // The engine signs raw bytes; the hex the wire carries is only a transport encoding.
        val localAuthId = carAuthId()
        // The phone's id is not encoded twice. It reports a 64-character deviceId and signs over
        // those 64 characters verbatim: its authenticate request carries peerAuthId as 128 hex
        // characters, which decode to exactly this string and not to the 32 bytes it spells out.
        // Decoding it here made the car sign 104 bytes where the phone rebuilds 136, and the phone
        // reported that as HC_VERIFY_PROOF_FAILED - the same code a wrong key produces.
        val remoteAuthId = peerIdentity.toByteArray(StandardCharsets.UTF_8)
        if (remoteAuthId.isEmpty() || remoteAuthId.size > MAX_AUTH_ID_BYTES) {
            throw HiCarWireException("the phone's identity '${peerIdentity.take(8)}…' does not fit an auth id")
        }
        handle = HiChainNative.create(localAuthId, remoteAuthId, pin, ROLE_ACCESSORY)
        if (handle == 0L) {
            throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        }
        this.peerAuthId = remoteAuthId
        started = true
        stepIndex = 0
        operationDone = false
        failed = false
        // Whatever the previous run left behind: absent means a first bind, present means the phone
        // is expected to authenticate against this key.
        probeKeyset("session start")
        // The code the engine will authenticate with, masked. A run where this does not match the
        // code on the screen has one obvious explanation, and the log should be able to show it
        // without anyone having to reproduce the screen.
        onLog(
            "HiChain begin: role=accessory peer=$peerIdentity " +
                "(ids: this car ${localAuthId.size} bytes, the phone ${remoteAuthId.size} bytes) " +
                "pin=${pin.take(2)}…${pin.takeLast(2)} " +
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
            // For a bind this is the moment the public half has already gone to the phone, so this
            // fingerprint is exactly what the phone should remember as ours.
            probeKeyset("key agreement end")
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
        val name = code?.let { HiChainCodes.errorName(it) ?: "code $it" }
        return if (name == null) trimmed else "$trimmed  [$name]"
    }

    override fun close() {
        // Last look before the store is released: a key that changed during an authenticate was
        // regenerated instead of reloaded, which is precisely what the phone's verify failure means.
        probeKeyset("session close")
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

        /** The engine's own buffer size for an auth id (HC_AUTH_ID_BUFF_LEN on the native side). */
        const val MAX_AUTH_ID_BYTES = 64
        val PIN_PATTERN = Regex("[0-9]{6}")

        /** The inform message carries its error as a bare int32 payload. */
        val PAYLOAD_INT = Regex("\"payload\"\\s*:\\s*(-?\\d+)")
    }
}
