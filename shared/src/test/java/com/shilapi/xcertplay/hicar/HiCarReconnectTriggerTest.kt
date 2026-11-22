package com.shilapi.xcertplay.hicar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this side is allowed to dial the phone.
 *
 * A redial that fires at the wrong moment is worse than none: it opens a Bluetooth connection the
 * driver did not ask for, on a radio that may be carrying a call. Every rule here is one of the
 * reasons not to.
 */
class HiCarReconnectTriggerTest {
    private val phone = "02:66:77:88:99:AA"

    private fun decide(
        connected: Boolean = true,
        address: String = phone,
        known: Set<String> = setOf(phone),
        running: Boolean = true,
        enabled: Boolean = true,
        lastAttempt: Long = 0L,
        now: Long = 100_000L,
    ) = HiCarReconnectTrigger.decide(connected, address, known, running, enabled, lastAttempt, now)

    @Test
    fun `a known phone with the link up is dialled`() {
        val verdict = decide()
        assertTrue(verdict.reason, verdict.dial)
    }

    @Test
    fun `a disconnect, a stopped receiver or the switch off all stay quiet`() {
        assertFalse(decide(connected = false).dial)
        assertFalse(decide(running = false).dial)
        assertFalse(decide(enabled = false).dial)
    }

    @Test
    fun `an unknown phone is never dialled`() {
        val verdict = decide(known = emptySet())
        assertFalse(verdict.dial)
        // The reason has to point at the first connection, or the driver reads it as a failure.
        assertTrue(verdict.reason.contains("dial once"))
    }

    @Test
    fun `the address comparison is case insensitive`() {
        assertTrue(decide(address = phone.lowercase()).dial)
    }

    @Test
    fun `the cooldown stops a phone that bounces its link from becoming a dial loop`() {
        val first = decide(lastAttempt = 0L, now = 100_000L)
        assertTrue(first.dial)
        // 5 s later: still cooling down.
        val second = decide(lastAttempt = 100_000L, now = 105_000L)
        assertFalse(second.dial)
        assertTrue(second.reason.contains("5s"))
        // After the cooldown it is allowed again.
        assertTrue(decide(lastAttempt = 100_000L, now = 100_000L + HiCarReconnectTrigger.COOLDOWN_MILLIS).dial)
    }

    @Test
    fun `an empty address is refused before it reaches the stack`() {
        assertFalse(decide(address = "  ").dial)
    }
}
