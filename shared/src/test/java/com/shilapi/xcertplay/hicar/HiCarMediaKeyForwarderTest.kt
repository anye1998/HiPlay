package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicar.HiCarMediaKeyForwarder.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forwarding rules, ported with the logic from `GGBondLab/OpenHC` (GPL-3.0-only).
 *
 * Every case here is a way the steering wheel misbehaves on a real car: a held key becoming twenty
 * "next track" presses, a key left down on the phone, the same event counted twice because the window
 * and the media session both received it.
 */
class HiCarMediaKeyForwarderTest {
    private fun ready() = HiCarMediaKeyForwarder().apply { ready = true }

    /** Sent keys as "down87"/"up87", so an assertion reads like the wire. */
    private fun HiCarMediaKeyForwarder.Result.sent(): List<String> =
        keys.map { (if (it.down) "down" else "up") + it.code }

    @Test
    fun `media keys keep their android key codes`() {
        val forwarder = ready()
        var time = 1_000L
        for (code in listOf(85, 86, 87, 88, 89, 90, 126, 127)) {
            val down = forwarder.onKey(code, down = true, repeatCount = 0, eventTime = ++time)
            val up = forwarder.onKey(code, down = false, repeatCount = 0, eventTime = ++time)
            assertTrue("key $code down", down.handled)
            assertTrue("key $code up", up.handled)
            assertEquals(listOf("down$code"), down.sent())
            assertEquals(listOf("up$code"), up.sent())
        }
    }

    @Test
    fun `other keys and an idle receiver leave the key to the car`() {
        val forwarder = ready()
        // Back, volume, headset hook, d-pad: none of these are ours to take.
        for (code in listOf(4, 24, 25, 79, 19)) {
            assertFalse("key $code", forwarder.onKey(code, true, 0, 1).handled)
        }
        val idle = HiCarMediaKeyForwarder()
        assertFalse(idle.onKey(87, true, 0, 1).handled)
        assertFalse(idle.onCommand(Command.NEXT).handled)
        assertTrue(idle.onKey(87, true, 0, 1).keys.isEmpty())
    }

    @Test
    fun `auto repeat is swallowed instead of forwarded`() {
        val forwarder = ready()
        assertEquals(listOf("down87"), forwarder.onKey(87, true, 0, 10).sent())
        val repeat = forwarder.onKey(87, true, 1, 11)
        assertTrue("a repeat is consumed so the system does not act on it", repeat.handled)
        assertEquals(emptyList<String>(), repeat.sent())
        assertEquals(listOf("up87"), forwarder.onKey(87, false, 0, 12).sent())
    }

    @Test
    fun `a missing up is patched and an orphan up goes back to the system`() {
        val forwarder = ready()
        forwarder.onKey(88, true, 0, 10)
        assertEquals(
            listOf("up88", "down88"),
            forwarder.onKey(88, true, 0, 20).sent(),
        )
        assertEquals(listOf("up88"), forwarder.onKey(88, false, 0, 21).sent())
        val orphan = forwarder.onKey(88, false, 0, 22)
        assertFalse("its down went to the system, so its up must too", orphan.handled)
        assertEquals(emptyList<String>(), orphan.sent())
    }

    @Test
    fun `the same event from both entries is forwarded once`() {
        val forwarder = ready()
        assertEquals(listOf("down87"), forwarder.onKey(87, true, 0, 500).sent())
        val again = forwarder.onKey(87, true, 0, 500)
        assertTrue(again.handled)
        assertEquals(emptyList<String>(), again.sent())
        assertEquals(listOf("up87"), forwarder.onKey(87, false, 0, 600).sent())
        assertEquals(emptyList<String>(), forwarder.onKey(87, false, 0, 600).sent())
    }

    @Test
    fun `a session command is synthesized as a press`() {
        val forwarder = ready()
        val expected = mapOf(
            Command.PLAY to 126,
            Command.PAUSE to 127,
            Command.PLAY_PAUSE to 85,
            Command.NEXT to 87,
            Command.PREVIOUS to 88,
            Command.STOP to 86,
        )
        for ((command, code) in expected) {
            val result = forwarder.onCommand(command)
            assertTrue(result.handled)
            assertEquals(listOf("down$code", "up$code"), result.sent())
        }
        // While the key itself is held, the key events carry it; a command would double-press it.
        forwarder.onKey(87, true, 0, 1)
        assertEquals(emptyList<String>(), forwarder.onCommand(Command.NEXT).sent())
    }

    @Test
    fun `losing the session forgets the keys it was holding`() {
        val forwarder = ready()
        forwarder.onKey(87, true, 0, 1)
        forwarder.ready = false
        assertFalse("with no session the up belongs to the car", forwarder.onKey(87, false, 0, 2).handled)
        forwarder.ready = true
        assertEquals(listOf("down87"), forwarder.onKey(87, true, 0, 3).sent())
    }

    @Test
    fun `the phone is told which key is let go when a session ends mid press`() {
        val forwarder = ready()
        forwarder.onKey(88, true, 0, 1)
        assertEquals(listOf(88), forwarder.pressedKeys())
        forwarder.ready = false
        assertEquals(emptyList<Int>(), forwarder.pressedKeys())
    }

    @Test
    fun `labels read like the log lines`() {
        assertEquals("next(87)", HiCarMediaKeyForwarder.label(87))
        assertEquals("previous(88)", HiCarMediaKeyForwarder.label(88))
        assertEquals("play/pause(85)", HiCarMediaKeyForwarder.label(85))
    }
}
