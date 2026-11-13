package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiCarAccessPointsTest {
    private fun info(frequency: Int?, backend: WirelessHotspotBackend = WirelessHotspotBackend.WIFI_P2P) =
        WirelessHotspotInfo(
            ssid = "HiCar-0412",
            passphrase = "0f1e2d3c4b5a",
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = 36,
            frequencyMHz = frequency,
            bssid = "02:00:00:00:00:01",
            interfaceName = "p2p0",
            hostAddress = null,
            bandLabel = "5 GHz",
            backend = backend,
        )

    private class FakeManager(
        private val outcome: () -> WirelessHotspotInfo,
        val name: String,
    ) : WirelessHotspotManager {
        var closed = false
        override fun start(timeoutMillis: Long): WirelessHotspotInfo = outcome()
        override fun close() {
            closed = true
        }
    }

    private fun accepted() = { frequency: Int? -> frequency != null && HiCarNetworkOffer.frequencyMask(frequency) != 0 }

    @Test
    fun theFirstBackendThatStartsOnAnAcceptedChannelWins() {
        val first = FakeManager({ info(5180) }, "first")
        val second = FakeManager({ info(5745) }, "second")
        val outcome = HiCarAccessPoints.open(
            listOf(
                HiCarAccessPoints.Backend("first") { first },
                HiCarAccessPoints.Backend("second") { second },
            ),
            timeoutMillis = 1_000,
            accepted = accepted(),
        )
        assertEquals("first", outcome.choice?.backend?.name)
        assertSame(first, outcome.choice?.manager)
        assertFalse("the chosen backend must stay open", first.closed)
        assertFalse("the untried backend must not be closed", second.closed)
    }

    @Test
    fun aBackendThatSettlesOnTwoPointFourGhzIsClosedAndTheNextOneIsTried() {
        // The HUAWEI MRX-AL09 case: Android 12 reserved a 2.4 GHz group, which no HiCar phone joins.
        val local = FakeManager({ info(2412, WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT) }, "local-only")
        val p2p = FakeManager({ info(5745) }, "wi-fi direct")
        val outcome = HiCarAccessPoints.open(
            listOf(
                HiCarAccessPoints.Backend("local-only hotspot") { local },
                HiCarAccessPoints.Backend("Wi-Fi Direct") { p2p },
            ),
            timeoutMillis = 1_000,
            accepted = accepted(),
        )
        assertEquals("Wi-Fi Direct", outcome.choice?.backend?.name)
        assertTrue("the rejected 2.4 GHz group must be released", local.closed)
        assertTrue(outcome.bandRefused)
        assertTrue(outcome.reasons.single(), outcome.reasons.single().contains("2412 MHz"))
    }

    @Test
    fun everyRejectionIsReportedWithItsOwnReason() {
        val outcome = HiCarAccessPoints.open(
            listOf(
                HiCarAccessPoints.Backend("missing hardware") { throw IllegalStateException("no WifiP2pManager") },
                HiCarAccessPoints.Backend("refusing") { FakeManager({ throw java.io.IOException("radio busy") }, "refusing") },
                HiCarAccessPoints.Backend("wrong band") { FakeManager({ info(null) }, "wrong band") },
            ),
            timeoutMillis = 1_000,
            accepted = accepted(),
        )
        assertNull(outcome.choice)
        assertEquals(3, outcome.reasons.size)
        assertTrue(outcome.reasons[0], outcome.reasons[0].contains("unavailable"))
        assertTrue(outcome.reasons[1], outcome.reasons[1].contains("radio busy"))
        assertTrue(outcome.reasons[2], outcome.reasons[2].contains("unknown MHz"))
        // Something did start, so the caller can say "wrong band" instead of "no access point at all".
        assertTrue(outcome.bandRefused)
    }

    @Test
    fun aBackendThatNeverStartsDoesNotClaimAWrongBand() {
        val outcome = HiCarAccessPoints.open(
            listOf(HiCarAccessPoints.Backend("broken") { throw IllegalStateException("unavailable") }),
            timeoutMillis = 1_000,
            accepted = accepted(),
        )
        assertNull(outcome.choice)
        assertFalse(outcome.bandRefused)
    }

    @Test
    fun aFailedStartClosesTheBackendItTried() {
        val failing = FakeManager({ throw java.io.IOException("timeout") }, "failing")
        HiCarAccessPoints.open(
            listOf(HiCarAccessPoints.Backend("failing") { failing }),
            timeoutMillis = 1_000,
            accepted = accepted(),
        )
        assertTrue(failing.closed)
    }

    @Test
    fun theCapabilityMaskStillRejectsTwoPointFourGhz() {
        // Guards the premise of every case above: 2.4 GHz is not in HiCar's channel list.
        assertEquals(0, HiCarNetworkOffer.frequencyMask(2412))
        assertEquals(0, HiCarNetworkOffer.frequencyMask(2484))
        assertTrue(HiCarNetworkOffer.frequencyMask(5180) != 0)
        assertTrue(HiCarNetworkOffer.frequencyMask(5745) != 0)
    }
}
