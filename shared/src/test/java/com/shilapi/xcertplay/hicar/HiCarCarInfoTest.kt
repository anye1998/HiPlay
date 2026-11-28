package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The block's device id against the identity the handshake presents.
 *
 * The regression this pins down is a wrong hash input: hashing the ten raw bytes instead of the
 * twenty-character hex text produced a 32-byte value that matched nothing the phone had seen, and the
 * block was then ignored without an error.
 */
class HiCarCarInfoTest {

    // 00040A04 + 98:BB:1E:67:87:ED, the pair used throughout docs/HICAR_MIGRATION.md.
    private val model = byteArrayOf(0x00, 0x04, 0x0A)
    private val subModel = 0x04.toByte()
    private val address = byteArrayOf(0x98.toByte(), 0xBB.toByte(), 0x1E, 0x67, 0x87.toByte(), 0xED.toByte())

    private val identity = "4751B763A806980D15845EA846548537BDA94E5D22B27597FEAD804C13A92F69"

    @Test
    fun `the derivation is the hex text hash, not the raw byte hash`() {
        val derived = HiCarCarInfo.derive(model, subModel, address)
        assertEquals(identity, derived.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `a supplied identity is used byte for byte`() {
        val bytes = HiCarCarInfo.deviceId(model, subModel, address, identity)
        assertEquals(HiCarCarInfo.DEVICE_ID_BYTES, bytes.size)
        assertEquals(identity, bytes.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `lower case identity is accepted and decoded the same way`() {
        val bytes = HiCarCarInfo.deviceId(model, subModel, address, identity.lowercase())
        assertEquals(identity, bytes.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `an unusable identity falls back to the same derivation`() {
        // 16 digits was the old ANDROID_ID-derived shape; it must not be sent as 8 bytes.
        val fromShort = HiCarCarInfo.deviceId(model, subModel, address, "C404CDDDEE2E9660")
        val derived = HiCarCarInfo.derive(model, subModel, address)
        assertArrayEquals(derived, fromShort)
        assertEquals(identity, fromShort.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `an empty identity still produces the handshake value`() {
        val bytes = HiCarCarInfo.deviceId(model, subModel, address, "")
        assertEquals(identity, bytes.joinToString("") { "%02X".format(it) })
    }
}
