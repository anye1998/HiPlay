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

    override var sessionKey: ByteArray? = null
        private set

    override val complete: Boolean get() = sessionKey != null

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
        return step.packets
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
    }
}
