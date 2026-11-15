package com.shilapi.xcertplay.hicarauth

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw binding to the HiChain engine (`libhicarauth.so`).
 *
 * The library is built from OpenHarmony's `deviceauth_lite` and `huks_lite` (Apache-2.0) by
 * `scripts/fetch-hichain-sources.py` plus the CMake project next to this module. When those sources
 * have not been fetched, [engineBuilt] is false and every call returns a failure instead of
 * throwing [UnsatisfiedLinkError] at an unpredictable moment.
 *
 * All methods are synchronized on the class: the engine keeps one HUKS store and one root key, so
 * two concurrent sessions would race over the same key material. Callers must still serialise a
 * single session's steps.
 */
class HiChainNative private constructor() {
    companion object {
        /** True when this APK was built with the native engine, not merely when the phone could run it. */
        val engineBuilt: Boolean = BuildConfig.ENGINE_BUILT

        private val loaded: Boolean by lazy {
            if (!engineBuilt) {
                false
            } else {
                runCatching { System.loadLibrary("hicarauth") }.isSuccess
            }
        }

        val available: Boolean get() = loaded

        /**
         * Opens the HUKS store in [directory] and derives the store's UDID from [seed].
         *
         * Returns 0 on success; the directory must already exist and be reachable only by this app
         * (the native side refuses a directory other users can read).
         */
        @JvmStatic
        @Synchronized
        external fun init(directory: String, seed: String): Int

        /** Creates a session. [role] is 1 for the centre and 2 for the accessory (the car). */
        @JvmStatic
        @Synchronized
        external fun create(selfIdentity: String, peerIdentity: String, pin: String, role: Int): Long

        @JvmStatic
        @Synchronized
        external fun step(handle: Long, packet: ByteArray?, keyLength: Int): Array<ByteArray>?

        /** The session key, or null unless the exchange finished successfully. */
        @JvmStatic
        @Synchronized
        external fun sessionKey(handle: Long): ByteArray?

        @JvmStatic
        @Synchronized
        external fun close(handle: Long)

        /**
         * Runs the key-store calls a bind's exchange phase makes, in order, on a throwaway alias.
         *
         * Returns one line per call plus a verdict, or null when the engine is not in this build.
         * The point is that this runs anywhere the APK installs: finding out on the car that
         * Ed25519 signing is broken costs a whole test cycle.
         */
        @JvmStatic
        @Synchronized
        external fun selfTest(): String?

        /** One engine step: what the engine wants sent, plus what it reported. */
        class Step(
            val code: Int,
            val result: Int,
            val keyLength: Int,
            val error: Int,
            val packets: List<ByteArray>,
        ) {
            /**
             * True once the engine has handed over a session key.
             *
             * `result` cannot be the criterion. It is the engine's `hc_result`, which for a bind
             * reports the protocol phase (`KEY_AGREEMENT_END` = 3) rather than success, and only an
             * authenticate operation maps the phase onto `END_SUCCESS` = 0. Requiring 0 therefore
             * rejected every completed first-time pair — the key was derived and then thrown away,
             * which the phone reported as a failed connection. A key of a usable length, with no
             * engine error and no `END_FAILED`, is the evidence that the exchange finished.
             */
            val finished: Boolean
                get() = error == 0 && result != RESULT_END_FAILED && keyLength in setOf(16, 32)

            /**
             * True once the engine reports the operation itself as over (`END_SUCCESS`).
             *
             * A bind reaches [finished] at the end of key agreement, then still needs the exchange
             * phase before the engine is done. [completed] is the gate for "stop feeding this
             * session"; [finished] only says a key exists.
             */
            val completed: Boolean get() = error == 0 && result == RESULT_END_SUCCESS

            /** True when the engine itself declares the exchange failed (`END_FAILED`). */
            val failedResult: Boolean get() = error == 0 && result == RESULT_END_FAILED

            /** The phase names above, so a log line says "KEY_AGREEMENT_END" and not "3". */
            fun describe(): String = when (result) {
                0 -> "END_SUCCESS"
                1 -> "END_FAILED"
                2 -> "KEY_AGREEMENT_PROCESSING"
                3 -> "KEY_AGREEMENT_END"
                4 -> "OPERATION_PROCESSING"
                5 -> "OPERATION_END"
                else -> "result=$result"
            }
        }

        /**
         * Runs one step and decodes the header the native side prepends.
         *
         * The header is four native-order ints: engine code, service result, key length, error flag.
         * A malformed header is treated as an engine error rather than as a short packet list.
         */
        fun stepDecoded(handle: Long, packet: ByteArray?, keyLength: Int): Step? {
            val raw = step(handle, packet, keyLength) ?: return null
            val header = raw.firstOrNull() ?: return null
            if (header.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder())
            val code = buffer.int
            val result = buffer.int
            val keyLengthReported = buffer.int
            val error = buffer.int
            return Step(code, result, keyLengthReported, error, raw.drop(1))
        }

        private const val HEADER_BYTES = 16

        /** `END_FAILED` from the engine's own `hc_result` enum; the only result that is a failure. */
        private const val RESULT_END_FAILED = 1

        /** `END_SUCCESS`: the whole operation is over, exchange phase included. */
        private const val RESULT_END_SUCCESS = 0

        /** The store directory, created 0700 so the native check accepts it. */
        fun storeDirectory(file: File): File = file.apply {
            if (!exists() && !mkdirs()) {
                // The native side reports the failure; this is not the place to throw.
            }
            runCatching { setReadable(false, false); setReadable(true, true) }
            runCatching { setWritable(false, false); setWritable(true, true) }
            runCatching { setExecutable(false, false); setExecutable(true, true) }
        }
    }
}
