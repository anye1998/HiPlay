package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiCarAccessPointBssidTest {
    private fun info(bssid: String?, interfaceName: String? = "p2p0") = WirelessHotspotInfo(
        ssid = "DIRECT-HL-0412",
        passphrase = "0f1e2d3c4b5a",
        security = Iap2WirelessSecurity.WPA_WPA2,
        channel = 161,
        frequencyMHz = 5805,
        bssid = bssid,
        interfaceName = interfaceName,
        hostAddress = null,
        bandLabel = "5 GHz",
        backend = WirelessHotspotBackend.WIFI_P2P,
    )

    @Test
    fun placeholdersAndNonUnicastAddressesAreNotUsable() {
        assertFalse(HiCarAccessPointBssid.usable(null))
        assertFalse(HiCarAccessPointBssid.usable(""))
        assertFalse(HiCarAccessPointBssid.usable("02:00:00:00:00:00"))
        assertFalse(HiCarAccessPointBssid.usable("00:00:00:00:00:00"))
        assertFalse("multicast cannot own a group", HiCarAccessPointBssid.usable("01:1b:2c:3d:4e:5f"))
        assertFalse(HiCarAccessPointBssid.usable("0a:1b:2c:3d:4e"))
        assertTrue(HiCarAccessPointBssid.usable("0a:1b:2c:3d:4e:5f"))
        assertTrue(HiCarAccessPointBssid.usable("AA:BB:CC:DD:EE:FE"))
    }

    @Test
    fun twoSourcesThatAgreeAreTrusted() {
        val choice = HiCarAccessPointBssid.choose("0a:1b:2c:3d:4e:5f", "0A:1B:2C:3D:4E:5F")
        assertEquals("0a:1b:2c:3d:4e:5f", choice?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.BOTH, choice?.source)
    }

    @Test
    fun twoSourcesThatDisagreeAreNotGuessedBetween() {
        // A stale or foreign value must not be sent: the phone would join a network that is not ours.
        assertNull(HiCarAccessPointBssid.choose("0a:1b:2c:3d:4e:5f", "0a:1b:2c:3d:4e:60"))
    }

    @Test
    fun sysfsRescuesTheTabletCaseWhereTheFrameworkReportsAPlaceholder() {
        // HUAWEI MRX-AL09: NetworkInterface returned 02:00:00:00:00:00 for p2p0.
        val choice = HiCarAccessPointBssid.choose("02:00:00:00:00:00", "0a:1b:2c:3d:4e:5f")
        assertEquals("0a:1b:2c:3d:4e:5f", choice?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.SYSFS, choice?.source)
    }

    @Test
    fun resolveReadsTheInterfaceFileByItsName() {
        var asked: String? = null
        val choice = HiCarAccessPointBssid.resolve(info(bssid = "02:00:00:00:00:00")) { name ->
            asked = name
            "0a:1b:2c:3d:4e:5f\n"
        }
        assertEquals("p2p0", asked)
        assertEquals("0a:1b:2c:3d:4e:5f", choice?.bssid)
    }

    @Test
    fun resolveFallsBackToTheFrameworkValueWhenTheFileIsUnreadable() {
        val choice = HiCarAccessPointBssid.resolve(info(bssid = "0a:1b:2c:3d:4e:5f")) { null }
        assertEquals("0a:1b:2c:3d:4e:5f", choice?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.INTERFACE, choice?.source)
    }

    @Test
    fun resolveReportsNothingWhenBothSourcesAreUnusable() {
        assertNull(HiCarAccessPointBssid.resolve(info(bssid = "02:00:00:00:00:00")) { null })
        assertNull(HiCarAccessPointBssid.resolve(info(bssid = null)) { "00:00:00:00:00:00" })
    }

    @Test
    fun anInterfaceNameWithPathCharactersIsNeverUsedAsAPath() {
        var asked = false
        val choice = HiCarAccessPointBssid.resolve(info(bssid = "0a:1b:2c:3d:4e:5f", interfaceName = "../../etc")) {
            asked = true
            "0a:1b:2c:3d:4e:5f"
        }
        assertFalse("the file must not be read for an invalid interface name", asked)
        assertEquals(HiCarAccessPointBssid.Source.INTERFACE, choice?.source)
    }
}
