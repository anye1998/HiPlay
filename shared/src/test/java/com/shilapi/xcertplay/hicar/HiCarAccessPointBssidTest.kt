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
    private fun info(
        bssid: String?,
        interfaceName: String? = "p2p0",
        ownerDeviceAddress: String? = null,
    ) = WirelessHotspotInfo(
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
        groupOwnerDeviceAddress = ownerDeviceAddress,
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
    fun whenTheTwoSourcesDisagreeTheKernelValueWinsAndSaysSo() {
        // sysfs is the kernel's own value for that exact interface; the framework can hold a cached
        // view of a recreated interface. Either way the disagreement stays visible in the label.
        val choice = HiCarAccessPointBssid.choose("0a:1b:2c:3d:4e:5f", "0a:1b:2c:3d:4e:60")
        assertEquals("0a:1b:2c:3d:4e:60", choice?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.SYSFS_OVER_INTERFACE, choice?.source)
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

    @Test
    fun theSecondTabletRunFallsBackToTheOwnerDeviceAddress() {
        // hicar-log-20261114-115903.txt: framework 02:00:00:00:00:00, interface p2p-p2p0-2, sysfs
        // unreadable, no link-local. The framework's Wi-Fi Direct owner address was never tried
        // because the placeholder is not null; it is the last source that can answer here.
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", interfaceName = "p2p-p2p0-2", ownerDeviceAddress = "0a:1b:2c:3d:4e:5f"),
            readSysfs = { throw java.io.FileNotFoundException("/sys/class/net/p2p-p2p0-2/address") },
            deriveFromLinkLocal = { null },
        )
        assertEquals("0a:1b:2c:3d:4e:5f", attempt.resolution?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.OWNER, attempt.resolution?.source)
        assertTrue(attempt.reasons.toString(), attempt.reasons.any { it.startsWith("sysfs=FileNotFound") })
        assertTrue(attempt.reasons.toString(), attempt.reasons.any { it.startsWith("link-local=") })
    }

    @Test
    fun aMaskedInterfaceWithALinkLocalAddressNeedsNoFileAccess() {
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", interfaceName = "p2p0", ownerDeviceAddress = "a2:b3:c4:d5:e6:f7"),
            readSysfs = { null },
            deriveFromLinkLocal = { "0a:1b:2c:3d:4e:5f" },
        )
        // The interface's own address describes the access point; the owner identity is persistent
        // and may be a different, non-broadcast address while it is randomised.
        assertEquals("0a:1b:2c:3d:4e:5f", attempt.resolution?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.LINK_LOCAL, attempt.resolution?.source)
    }

    @Test
    fun theKernelTableAnswersWhenBothJavaAndSysfsRefuse() {
        // The framework masks the address and Huawei's sysfs entry is unreadable; getifaddrs() is the
        // same data `ip link` prints and is not filtered by the framework.
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", interfaceName = "p2p-p2p0-2"),
            readSysfs = { throw java.io.FileNotFoundException("/sys/class/net/p2p-p2p0-2/address") },
            readKernel = { "0a:1b:2c:3d:4e:5f" },
            deriveFromLinkLocal = { null },
        )
        assertEquals("0a:1b:2c:3d:4e:5f", attempt.resolution?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.KERNEL, attempt.resolution?.source)
    }

    @Test
    fun anOwnerAddressThatIsItselfAPlaceholderIsNotUsed() {
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", ownerDeviceAddress = "00:00:00:00:00:00"),
            readSysfs = { null },
            deriveFromLinkLocal = { null },
        )
        assertNull(attempt.resolution)
        assertTrue(attempt.reasons.toString(), attempt.reasons.any { it.startsWith("owner=00:00") })
    }

    @Test
    fun theFrameworkGroupNameIsShortenedToTheKernelBaseName() {
        assertEquals("p2p0", HiCarAccessPointBssid.baseInterfaceName("p2p-p2p0-5"))
        assertNull("a plain interface name is not rewritten", HiCarAccessPointBssid.baseInterfaceName("p2p0"))
        assertNull(HiCarAccessPointBssid.baseInterfaceName("wlan0"))
    }

    @Test
    fun theKernelBaseNameIsTriedWhenTheGroupNameHasNoAddress() {
        // hicar-log-20261114-130043.txt: framework p2p-p2p0-5, kernel knows no AF_PACKET entry for it.
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", interfaceName = "p2p-p2p0-5"),
            readSysfs = { throw java.io.FileNotFoundException("/sys/class/net/p2p-p2p0-5/address") },
            readKernel = { name -> if (name == "p2p0") "02:dc:03:2b:48:d0" else null },
            deriveFromLinkLocal = { "02:DC:03:2B:48:D0" },
            describeKernel = { "no entry" },
        )
        assertEquals("02:dc:03:2b:48:d0", attempt.resolution?.bssid)
        assertEquals(HiCarAccessPointBssid.Source.KERNEL_AND_LINK_LOCAL, attempt.resolution?.source)
    }

    @Test
    fun aKernelValueThatDisagreesWithTheLinkLocalIsStillReportedAsKernelOnly() {
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = "02:00:00:00:00:00", interfaceName = "p2p0"),
            readSysfs = { null },
            readKernel = { "0a:1b:2c:3d:4e:5f" },
            deriveFromLinkLocal = { "0a:1b:2c:3d:4e:60" },
        )
        assertEquals(HiCarAccessPointBssid.Source.KERNEL, attempt.resolution?.source)
    }

    @Test
    fun aTotalFailureNamesEverySourceItTried() {
        val attempt = HiCarAccessPointBssid.inspect(
            info(bssid = null, interfaceName = null),
            readSysfs = { null },
            deriveFromLinkLocal = { null },
        )
        assertNull(attempt.resolution)
        val report = attempt.reasons.joinToString("; ")
        assertTrue(report, report.contains("framework=none"))
        assertTrue(report, report.contains("sysfs=no valid interface name"))
        assertTrue(report, report.contains("owner=absent"))
        assertEquals(3, attempt.reasons.size)
    }
}
