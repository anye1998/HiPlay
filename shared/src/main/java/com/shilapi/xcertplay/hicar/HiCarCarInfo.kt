package com.shilapi.xcertplay.hicar

import java.security.MessageDigest

/**
 * The 32-byte device id the wired car-info block carries.
 *
 * It must be the same value the Bluetooth handshake presents, and that value is **not** the hash of
 * the raw fields: it is `SHA-256` over the upper-case hex *text* of `model(3) ‖ sub-model(1) ‖
 * address(6)` — twenty ASCII characters, e.g. `00040A0498BB1E6787ED`. Hashing the ten raw bytes
 * instead produces a different 32-byte value, which describes a car the phone never authenticated
 * with; the block is then read and ignored rather than refused, so the symptom is a silent nothing.
 *
 * Kept separate from the USB code so it can be tested without a device.
 */
internal object HiCarCarInfo {

    const val DEVICE_ID_BYTES = 32

    private val IDENTITY = Regex("^[0-9A-Fa-f]{64}$")

    /**
     * The bytes to put in the block.
     *
     * @param localIdentity the identity the handshake uses, 64 hex digits; when it is not exactly
     *   that, the value is derived again from the fields, so the two never disagree.
     */
    fun deviceId(
        modelBytes: ByteArray,
        subModel: Byte,
        bluetoothAddress: ByteArray,
        localIdentity: String,
    ): ByteArray {
        val text = localIdentity.trim()
        if (IDENTITY.matches(text)) {
            return ByteArray(DEVICE_ID_BYTES) { index ->
                text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        return derive(modelBytes, subModel, bluetoothAddress)
    }

    /** The derivation itself, for the case where no identity text is available. */
    fun derive(modelBytes: ByteArray, subModel: Byte, bluetoothAddress: ByteArray): ByteArray {
        val seed = buildString {
            modelBytes.forEach { append(hex(it)) }
            append(hex(subModel))
            bluetoothAddress.forEach { append(hex(it)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
    }

    private fun hex(value: Byte): String = (value.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
}
