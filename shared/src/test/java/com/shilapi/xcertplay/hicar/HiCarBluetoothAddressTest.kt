package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address parser, on the shapes the two shells actually print.
 *
 * The masked placeholder matters most: it is the value a build without `LOCAL_MAC_ADDRESS` sees, and
 * writing it into the address field would derive an identity no phone can match.
 */
class HiCarBluetoothAddressTest {

    @Test
    fun `a stored setting is accepted as it stands`() {
        assertEquals("98:BB:1E:67:87:ED", HiCarBluetoothAddress.fromSettings("98:BB:1E:67:87:ED\n"))
    }

    @Test
    fun `the setting is normalised to upper case`() {
        assertEquals("98:BB:1E:67:87:ED", HiCarBluetoothAddress.fromSettings("98:bb:1e:67:87:ed"))
    }

    @Test
    fun `an unset setting reads as nothing`() {
        assertNull(HiCarBluetoothAddress.fromSettings("null"))
        assertNull(HiCarBluetoothAddress.fromSettings(""))
        assertNull(HiCarBluetoothAddress.fromSettings(null))
    }

    @Test
    fun `the masked placeholder is refused, not filled in`() {
        assertNull(HiCarBluetoothAddress.fromSettings("02:00:00:00:00:00"))
        assertNull(HiCarBluetoothAddress.fromSettings("00:00:00:00:00:00"))
        assertFalse(HiCarBluetoothAddress.usable("02:00:00:00:00:00"))
    }

    @Test
    fun `a truncated or malformed address is refused`() {
        assertFalse(HiCarBluetoothAddress.usable("98:BB:1E:67:87"))
        assertFalse(HiCarBluetoothAddress.usable("98-BB-1E-67-87-ED"))
        assertFalse(HiCarBluetoothAddress.usable("98:BB:1E:67:87:ED:00"))
        assertTrue(HiCarBluetoothAddress.usable("98:BB:1E:67:87:ED"))
    }

    @Test
    fun `the service block is read, not the bonded list`() {
        val dump = """
            BluetoothManagerService: ready
            Bluetooth Status
              enabled: true
              state: ON
              address: 98:BB:1E:67:87:ED
              name: BYD DiLink

            Bonded devices:
              AA:BB:CC:DD:EE:FF  Mate 80 Pro Max
        """.trimIndent()
        assertEquals("98:BB:1E:67:87:ED", HiCarBluetoothAddress.fromDumpsys(dump))
    }

    @Test
    fun `a null address in the dump is skipped`() {
        val dump = """
            Bluetooth Status
              enabled: false
              address: null
              name: unknown
        """.trimIndent()
        assertNull(HiCarBluetoothAddress.fromDumpsys(dump))
    }

    @Test
    fun `a masked address in the dump is skipped too`() {
        val dump = """
            Bluetooth Status
              address: 02:00:00:00:00:00
        """.trimIndent()
        assertNull(HiCarBluetoothAddress.fromDumpsys(dump))
    }

    @Test
    fun `a later address line cannot stand in for the car's own`() {
        // Everything inside the searched head must be the service's own block; nothing after it
        // should be able to supply a value, so a dump whose only address is far down reads as absent.
        val dump = buildString {
            appendLine("Bluetooth Status")
            appendLine("  enabled: true")
            appendLine("  address: null")
            repeat(70) { appendLine("  filler $it") }
            appendLine("  address: AA:BB:CC:DD:EE:FF")
        }
        assertNull(HiCarBluetoothAddress.fromDumpsys(dump))
    }

    @Test
    fun `the settings command is the documented one`() {
        // A silent rename here would leave the reader querying a key nothing writes.
        assertEquals("settings get secure bluetooth_address", HiCarBluetoothAddress.SETTINGS_COMMAND)
        assertEquals("dumpsys bluetooth_manager", HiCarBluetoothAddress.DUMPSYS_COMMAND)
    }
}
