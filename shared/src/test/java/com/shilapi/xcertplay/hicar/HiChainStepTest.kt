package com.shilapi.xcertplay.hicar

import com.shilapi.xcertplay.hicarauth.HiChainNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's `hc_result` is a phase, not a verdict.
 *
 * The first working pair on the car unit failed at exactly this point: the key was derived, the
 * result was `KEY_AGREEMENT_END` (3) because a bind never maps onto `END_SUCCESS` (0), and the gate
 * that demanded 0 threw the key away. The phone reported it as a failed connection.
 */
class HiChainStepTest {

    private fun step(result: Int, keyLength: Int, error: Int = 0) =
        HiChainNative.Companion.Step(0, result, keyLength, error, emptyList())

    @Test
    fun aDerivedKeyIsTheEndOfTheExchangeEvenWhenThePhaseIsNotSuccess() {
        val step = step(result = 3, keyLength = 16)

        assertTrue(step.finished)
        assertEquals("KEY_AGREEMENT_END", step.describe())
    }

    @Test
    fun aFailedExchangeNeverSurrendersAKeyEvenIfOneIsPresent() {
        assertFalse(step(result = 1, keyLength = 32).finished)
    }

    @Test
    fun noKeyMeansTheExchangeIsStillRunning() {
        assertFalse(step(result = 3, keyLength = 0).finished)
    }

    @Test
    fun anEngineErrorOutranksAPhaseThatLooksFinished() {
        assertFalse(step(result = 3, keyLength = 16, error = 1).finished)
    }
}
