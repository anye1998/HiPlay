package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wired-priority rule, without a radio, a cable or a phone.
 *
 * The rule is the part that can be wrong in a way nobody notices until a driver is on the motorway
 * with no picture: "the cable appeared, so Wi-Fi must stop" and "the cable went, so Wi-Fi must come
 * back" are two transitions that have to happen exactly once per event.
 */
class HiCarTransportArbiterTest {

    @Test
    fun `a cable that carries packets takes over from the wireless path`() {
        val arbiter = HiCarTransportArbiter()
        assertEquals(HiCarTransport.WIRELESS, arbiter.current)

        val actions = arbiter.wiredUp("192.168.66.137")

        assertEquals(HiCarTransport.WIRED, arbiter.current)
        assertEquals(2, actions.size)
        assertTrue(actions[0] is HiCarTransportAction.DropWireless)
        assertEquals(HiCarTransportAction.StartWired("192.168.66.137"), actions[1])
    }

    @Test
    fun `a second attachment only rebinds the cable address`() {
        val arbiter = HiCarTransportArbiter()
        arbiter.wiredUp("192.168.66.137")

        val actions = arbiter.wiredUp("192.168.66.140")

        assertEquals(HiCarTransport.WIRED, arbiter.current)
        // Dropping the wireless session again would close a picture that is already on the cable.
        assertEquals(listOf(HiCarTransportAction.StartWired("192.168.66.140")), actions)
    }

    @Test
    fun `unplugging goes back to wireless`() {
        val arbiter = HiCarTransportArbiter()
        arbiter.wiredUp("192.168.66.137")

        val actions = arbiter.wiredDown()

        assertEquals(HiCarTransport.WIRELESS, arbiter.current)
        assertEquals(
            listOf(HiCarTransportAction.StopWired, HiCarTransportAction.ResumeWireless),
            actions,
        )
    }

    @Test
    fun `a detach without a link does nothing`() {
        val arbiter = HiCarTransportArbiter()
        // A USB device that never reached NCM layout reports detach too; that must not restart a
        // wireless round that was never interrupted.
        assertTrue(arbiter.wiredDown().isEmpty())
        assertEquals(HiCarTransport.WIRELESS, arbiter.current)
    }

    @Test
    fun `a stopped receiver decides again from scratch`() {
        val arbiter = HiCarTransportArbiter()
        arbiter.wiredUp("192.168.66.137")
        arbiter.reset()
        assertEquals(HiCarTransport.WIRELESS, arbiter.current)
        assertFalse(arbiter.isWired())
    }

    @Test
    fun `the cable offer carries the address the phone announced`() {
        val offer = HiCarNetworkOffer.cable("192.168.66.137", 4005, "192.168.66.5")

        assertEquals("192.168.66.137", offer.ownerAddress)
        assertEquals("192.168.66.5", offer.clientAddress)
        assertEquals(4005, offer.controlPort)
        assertEquals(4007, offer.controlPort + 2)
        // The fake network still has to be one the phone's capability bitmap accepts.
        assertNotEquals(0, HiCarNetworkOffer.frequencyMask(offer.frequencyMhz))
    }

    @Test
    fun `an unknown phone gets the next host as a placeholder`() {
        val offer = HiCarNetworkOffer.cable("192.168.66.137", 4005, phoneAddress = null)
        assertEquals("192.168.66.138", offer.clientAddress)
    }

    @Test
    fun `a phone that announces the car's own address is not used`() {
        // Cannot happen on a real link, but the offer's invariants would throw and take the whole
        // wired transport down; falling back keeps the listener bound.
        val offer = HiCarNetworkOffer.cable("192.168.66.137", 4005, "192.168.66.137")
        assertNotEquals("192.168.66.137", offer.clientAddress)
    }

    @Test
    fun `a phone outside the car's subnet is not used as the peer`() {
        // Nothing routes it: the tunnel only carries the car's own /24.
        val offer = HiCarNetworkOffer.cable("192.168.66.137", 4005, "192.168.67.5")
        assertEquals("192.168.66.138", offer.clientAddress)
    }

    @Test
    fun `the last host borrows the one below it`() {
        val offer = HiCarNetworkOffer.cable("192.168.66.254", 4005, phoneAddress = null)
        assertEquals("192.168.66.253", offer.clientAddress)
    }

    @Test
    fun `the peer rule only accepts the car's own slash 24`() {
        assertEquals("192.168.66", subnetOf("192.168.66.137"))
        assertEquals("10.0.0", subnetOf("10.0.0.1"))
    }
}
