package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit exists to be *read by a person comparing two numbers*, so the wording of a masked or
 * random address is part of the behaviour, not cosmetics. The cases below are the addresses this
 * project actually saw on a phone's scanner, so a change here should be a deliberate one.
 */
class HiCarDeviceAuditTest {
    @Test
    fun aMaskedAddressIsLabelledRatherThanPrintedAsAMac() {
        val described = HiCarDeviceAudit.describeBluetoothAddress("02:00:00:00:00:00")
        assertTrue(described.contains("masked by the platform"))
        // The placeholder must never be presented as a type anyone could compare.
        assertFalse(described.contains("public"))
    }

    @Test
    fun aMissingAddressIsNoneAndNotAnEmptyString() {
        assertEquals("none", HiCarDeviceAudit.describeBluetoothAddress(null))
        assertEquals("none", HiCarDeviceAudit.describeBluetoothAddress("  "))
    }

    @Test
    fun anOddFirstOctetCannotBeAPublicAddress() {
        // 65:4C:79:F7:29:33 is what the tablet actually advertised with.
        val described = HiCarDeviceAudit.describeBluetoothAddress("65:4C:79:F7:29:33")
        assertTrue(described.contains("I/G set"))
        assertTrue(described.contains("cannot be a public BR address"))
    }

    @Test
    fun aSecondObservedAddressIsJudgedTheSameWay() {
        // 51:FA:3E:3D:72:72 replaced it twenty minutes later: the address rotates.
        assertTrue(HiCarDeviceAudit.addressType("51:FA:3E:3D:72:72").contains("I/G set"))
    }

    @Test
    fun aTopByteOf0x8To0xBfCanOnlyBePublic() {
        // 10 is not a legal random-address form, so this is decidable without the scanner.
        assertEquals("public", HiCarDeviceAudit.addressType("A0:10:81:11:22:33"))
    }

    @Test
    fun anAddressThatCouldBeEitherSaysSoInsteadOfGuessing() {
        // 4C:5E:0C is a real OUI, and 01 in the top two bits is also a valid private form.
        assertTrue(HiCarDeviceAudit.addressType("4C:5E:0C:AA:BB:CC").contains("ambiguous"))
    }

    @Test
    fun anAddressThatIsNotHexSaysUnknownInsteadOfGuessing() {
        assertEquals("unknown type", HiCarDeviceAudit.addressType("zz:00:00:00:00:00"))
    }
}
