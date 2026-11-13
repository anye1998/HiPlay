package com.shilapi.xcertplay.hicar

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR record cipher used by both the DMSDP control channel and the HiSight media channel.
 *
 * The authenticated HiChain key may be 16 or 32 bytes; the first 16 bytes are the AES-128 key and a
 * fresh random 16-byte IV prefixes every record. There is no MAC on the record itself: integrity
 * comes from the HiChain handshake, which is why this class is only ever constructed with a key
 * that a completed authentication produced.
 */
internal class HiCarRecordCipher(sessionKey: ByteArray, private val maxRecordBytes: Int = 1400) {
    private var key: ByteArray? = Arrays.copyOf(sessionKey, AES_KEY_BYTES)
    private val random = SecureRandom()

    init {
        require(sessionKey.size == 16 || sessionKey.size == 32) { "authenticated key size" }
        require(maxRecordBytes in 17..10 * 1024 * 1024) { "record size" }
    }

    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        require(plain.isNotEmpty() && plain.size + IV_BYTES < maxRecordBytes) { "cipher record size" }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        return join(iv, transform(plain, iv))
    }

    @Synchronized
    fun decrypt(record: ByteArray): ByteArray {
        require(record.size > IV_BYTES && record.size < maxRecordBytes) { "cipher record size" }
        return transform(record.copyOfRange(IV_BYTES, record.size), record.copyOf(IV_BYTES))
    }

    @Synchronized
    fun clear() {
        key?.let { Arrays.fill(it, 0) }
        key = null
    }

    private fun transform(data: ByteArray, iv: ByteArray): ByteArray {
        val material = key ?: throw IOException("cipher closed")
        if (iv.size != IV_BYTES) throw IOException("cipher IV")
        return try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (failure: GeneralSecurityException) {
            throw IOException("cipher unavailable", failure)
        }
    }

    private companion object {
        const val AES_KEY_BYTES = 16
        const val IV_BYTES = 16
    }
}
