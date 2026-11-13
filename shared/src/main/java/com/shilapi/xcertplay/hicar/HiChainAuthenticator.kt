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
) : HiCarAuthenticator {
    private var handle: Long = 0
    private var started = false

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
    }

    override fun step(chunk: ByteArray, keyLength: Int): List<ByteArray> {
        if (!started || handle == 0L) throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        val step = HiChainNative.stepDecoded(handle, chunk, keyLength)
            ?: throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
        if (step.error != 0) throw HiCarAuthException(HiCarAuthBlocker.VENDOR_SIGNATURE_REQUIRED)
        if (step.finished) {
            val key = HiChainNative.sessionKey(handle)
                ?: throw HiCarAuthException(HiCarAuthBlocker.NATIVE_HICHAIN_LIBRARY_MISSING)
            sessionKey = key
        }
        return step.packets
    }

    override fun close() {
        if (handle != 0L) {
            HiChainNative.close(handle)
            handle = 0
        }
        started = false
        sessionKey?.let { Arrays.fill(it, 0) }
        sessionKey = null
    }

    private companion object {
        const val ROLE_ACCESSORY = 2
        val PIN_PATTERN = Regex("[0-9]{6}")
    }
}
