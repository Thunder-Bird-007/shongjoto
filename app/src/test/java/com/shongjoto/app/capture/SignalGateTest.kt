package com.shongjoto.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Isolated tests for the hysteresis/smoothing/debounce primitive, independent of how many gates
 * AutoBlurController ensembles or what their thresholds are. */
class SignalGateTest {

    private lateinit var gate: SignalGate

    @Before
    fun setUp() {
        gate = SignalGate(smoothingWindow = 3, consecutiveReadsRequired = 2)
    }

    @Test
    fun `starts not wanting shown`() {
        assertFalse(gate.wantsShown)
    }

    @Test
    fun `single high reading does not trigger without a second consecutive one`() {
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        assertFalse(gate.wantsShown)
    }

    @Test
    fun `two consecutive high readings trigger`() {
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        assertTrue(gate.wantsShown)
    }

    @Test
    fun `dead zone holds current state either direction`() {
        // Trigger on first.
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        assertTrue(gate.wantsShown)

        // A reading in the dead zone (below ON, not below OFF) must not clear it, no matter how
        // many times it repeats.
        repeat(5) { gate.onReading(0.35f, onThreshold = 0.5f, offThreshold = 0.2f) }
        assertTrue(gate.wantsShown)
    }

    @Test
    fun `clears only after consecutive readings actually below OFF`() {
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        gate.onReading(0.9f, onThreshold = 0.5f, offThreshold = 0.2f)
        assertTrue(gate.wantsShown)

        // Smoothing means it takes a few low readings before the average actually drops below
        // OFF; then two consecutive clean reads are required.
        repeat(6) { gate.onReading(0.05f, onThreshold = 0.5f, offThreshold = 0.2f) }
        assertFalse(gate.wantsShown)
    }

    @Test
    fun `thresholds can change between calls without resetting smoothing state`() {
        // Simulates a mode switch mid-session: the underlying readings/debounce carry over,
        // only the thresholds compared against them change.
        gate.onReading(0.3f, onThreshold = 0.5f, offThreshold = 0.2f) // dead zone under old thresholds
        gate.onReading(0.3f, onThreshold = 0.1f, offThreshold = 0.05f) // now clearly explicit
        gate.onReading(0.3f, onThreshold = 0.1f, offThreshold = 0.05f)
        assertTrue(gate.wantsShown)
    }
}
