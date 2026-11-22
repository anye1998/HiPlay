package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing deep link.
 *
 * A QR code is worse than a typed PIN when it is wrong: the phone silently dials an address that
 * never answers, and nothing on the car says why. So every field is checked before a code is drawn,
 * and these tests are the reasons a code is refused.
 */
class HiCarPairingCodeTest {
    private val model = "00040A04"
    private val address = "98:BB:1E:67:87:ED"

    private fun link(
        model: String = this.model,
        address: String = this.address,
        pin: String = "013579",
        name: String = "HiCar-0A04",
        usb: Boolean = true,
    ) = HiCarPairingCode.buildOrNull(model, address, pin, name, usb)

    @Test
    fun `the link carries every field the phone needs, in a fixed order`() {
        val link = link()!!
        assertEquals(
            "hicar://car/connect?devicename=HiCar-0A04&mac=$address" +
                "&moduleid=00040A&submoduleid=04&pin=013579&ability=6",
            link,
        )
    }

    @Test
    fun `the model id is split six plus two, matching the identity the handshake uses`() {
        val link = link(model = "00040A04")!!
        assertTrue(link.contains("moduleid=00040A"))
        assertTrue(link.contains("submoduleid=04"))
    }

    @Test
    fun `a leading zero in the pin is kept`() {
        // The phone fills the code in; dropping the zero makes every pairing fail with "wrong code".
        assertTrue(link(pin = "000123")!!.contains("pin=000123"))
    }

    @Test
    fun `wireless only is ability 4 and wireless plus usb is 6`() {
        assertTrue(link(usb = false)!!.endsWith("ability=4"))
        assertTrue(link(usb = true)!!.endsWith("ability=6"))
    }

    @Test
    fun `the address is normalised but a placeholder is refused`() {
        assertEquals(
            link(address = "98-bb-1e-67-87-ed")!!.substringAfter("mac=").substringBefore("&"),
            address,
        )
        for (placeholder in listOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            assertNull(placeholder, link(address = placeholder))
            assertTrue(HiCarPairingCode.problem(model, placeholder, "013579", "HiCar")!!.isNotEmpty())
        }
    }

    @Test
    fun `a malformed model, address or pin is refused with a reason`() {
        assertNull(link(model = "00040A0"))
        assertNull(link(model = "00040A0Z"))
        assertNull(link(address = "98:BB:1E:67:87"))
        assertNull(link(address = "98:BB:1E:67:87:EG"))
        assertNull(link(pin = "13579"))
        assertNull(link(pin = "13579A"))
        assertNull(link(name = "   "))
        assertTrue(HiCarPairingCode.problem("00040A04", address, "1357", "HiCar")!!.contains("6 位数字"))
    }

    @Test
    fun `a name that would break the query string is sanitised, not escaped`() {
        // `&`, `=`, `#`, `%`, `+`, `?` and whitespace all end or split a query parameter.
        assertEquals("HiCar_A_B_C_D_E_F", HiCarPairingCode.displayName("HiCar&A=B#C%D+E?F"))
        assertEquals("HiCar_0A04", HiCarPairingCode.displayName("  HiCar 0A04  "))
        val link = link(name = "car&pin=000000")!!
        // The injected `pin` must not appear as its own parameter.
        assertEquals(1, Regex("pin=").findAll(link).count())
    }

    @Test
    fun `a usable set of values has no problem`() {
        assertNull(HiCarPairingCode.problem(model, address, "013579", "HiCar-0A04"))
    }
}
