package com.shongjoto.app.capture

import com.shongjoto.app.classifier.FrameReading
import com.shongjoto.app.mode.BlurMode
import com.shongjoto.app.overlay.BlurSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the hysteresis/smoothing/debounce state machine directly with synthetic confidence
 * sequences — this is what stands in for the on-device "scroll safe content for 30+ seconds" and
 * "sustained explicit content" scenarios in an environment with no Android device attached. Real
 * on-device / real-photo testing is still required before shipping (see PR description).
 *
 * All tests here run in EXTREME mode (today's originally-calibrated thresholds) unless noted —
 * see the `mode selection` group at the bottom for LITE-specific behavior.
 */
class AutoBlurControllerTest {

    private lateinit var surface: FakeBlurSurface
    private lateinit var controller: AutoBlurController

    @Before
    fun setUp() {
        surface = FakeBlurSurface()
        controller = AutoBlurController(surface) { BlurMode.EXTREME }
    }

    @Test
    fun `noisy safe content over 40 captures never triggers blur`() {
        // ~40s of scrolling at 1 capture/sec. "sexy" occasionally ticks up (a portrait, a beach
        // ad thumbnail) but stays well under the threshold that beach/swim/portrait photos
        // realistically hit on GantMan's model.
        val noisySexyReadings =
            listOf(0.05f, 0.12f, 0.08f, 0.20f, 0.15f, 0.30f, 0.10f, 0.25f, 0.18f, 0.09f)
        repeat(4) {
            for (sexy in noisySexyReadings) {
                controller.onClassification(reading(sexy = sexy))
            }
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `oscillating score around the old single 0_35 threshold no longer flickers`() {
        // Regression test for the flicker bug: the old code compared one smoothed value
        // directly to a single 0.35 threshold, so scores bouncing on either side of it used to
        // flip the overlay. This sequence bounces in that same "used to be ambiguous" range but
        // stays comfortably under STRONG_ON_THRESHOLD once smoothed, so it now holds steady.
        val bouncing = listOf(0.20f, 0.32f, 0.25f, 0.38f, 0.22f, 0.35f, 0.28f, 0.31f)
        for (v in bouncing) {
            controller.onClassification(reading(strong = v))
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `single spurious high reading does not trigger blur`() {
        // One bad tile crop (odd lighting/framing) reading high, surrounded by low readings,
        // must not be enough on its own — even though smoothing lets it briefly cross the ON
        // threshold, a single frame can't satisfy the consecutive-reads requirement.
        controller.onClassification(reading(strong = 0.05f))
        controller.onClassification(reading(strong = 0.90f))
        controller.onClassification(reading(strong = 0.05f))
        controller.onClassification(reading(strong = 0.05f))
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained strong porn or hentai content triggers blur within a couple seconds`() {
        repeat(5) { controller.onClassification(reading(strong = 0.85f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `strong content at this app's own calibrated real-world value triggers blur`() {
        // Regression test: prior tuning found real nudity content reads a sustained ~0.424 on
        // this pipeline. EXTREME's strongOn must stay below that or this app regresses on the
        // exact content it was calibrated against.
        repeat(5) { controller.onClassification(reading(strong = 0.424f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `moderate sustained sexy score typical of beach or portrait photos never triggers blur`() {
        // GantMan's model routinely scores plainly-safe skin-heavy photos 0.3-0.55 on "sexy".
        // This must stay under EXTREME's sexyOn (0.65) even when sustained for a while.
        repeat(10) { controller.onClassification(reading(sexy = 0.50f)) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained high sexy score alone can still trigger blur`() {
        repeat(6) { controller.onClassification(reading(sexy = 0.80f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `blur holds through a dead-zone dip, only clears on a sustained drop below the OFF threshold`() {
        repeat(5) { controller.onClassification(reading(strong = 0.85f)) }
        assertTrue(surface.isShowing)

        // Dips into the dead zone (below ON, but not below OFF) must not clear it.
        repeat(5) { controller.onClassification(reading(strong = 0.35f)) }
        assertTrue(surface.isShowing)

        // Only a sustained drop below the OFF threshold actually clears it.
        repeat(4) { controller.onClassification(reading(strong = 0.05f)) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `a strong signal on one tile is not suppressed by a higher but subthreshold sexy signal elsewhere`() {
        // Regression test for the tile-selection bug: classifyTiled() used to pick a single
        // "best" tile by blended explicit-confidence, so a tile with porn=0.55 could lose the
        // selection to a different tile with sexy=0.62 — discarding the porn signal even though
        // it alone was strong enough to matter. FrameReading tracks both independently, so this
        // must trigger via the strong signal regardless of what sexy is doing elsewhere.
        repeat(5) { controller.onClassification(reading(strong = 0.55f, sexy = 0.62f)) }
        assertTrue(surface.isShowing)
    }

    // --- mode selection -----------------------------------------------------------------

    @Test
    fun `LITE mode does not trigger on content that would trigger EXTREME`() {
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { BlurMode.LITE }
        // 0.424 triggers EXTREME (see the calibration test above) but must not trigger LITE,
        // which is deliberately less sensitive.
        repeat(6) { controller.onClassification(reading(strong = 0.424f)) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `LITE mode still triggers on unambiguous strong content`() {
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { BlurMode.LITE }
        repeat(6) { controller.onClassification(reading(strong = 0.90f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `switching from EXTREME to LITE mid-session does not instantly clear an active blur`() {
        // The smoothing/debounce state (the last few raw readings, and how many consecutive
        // clean reads have been seen) carries across a mode switch; only the thresholds being
        // compared against change. Content dropping to safe right as the mode switches still has
        // to clear the smoothing window and the consecutive-reads debounce before it hides —
        // switching modes is not itself a reason to hide.
        var mode = BlurMode.EXTREME
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { mode }

        repeat(5) { controller.onClassification(reading(strong = 0.85f)) }
        assertTrue(surface.isShowing)

        mode = BlurMode.LITE
        // Smoothed value is still diluted by the trailing 0.85 readings for the next couple of
        // calls, so the overlay must stay up.
        controller.onClassification(reading(strong = 0.10f))
        assertTrue(surface.isShowing)
        controller.onClassification(reading(strong = 0.10f))
        assertTrue(surface.isShowing)
        controller.onClassification(reading(strong = 0.10f))
        assertTrue(surface.isShowing)

        // Only once the smoothed value has actually settled below LITE's OFF threshold for
        // CONSECUTIVE_CLEAN_READS_REQUIRED calls in a row does it clear.
        controller.onClassification(reading(strong = 0.10f))
        assertFalse(surface.isShowing)
    }

    private fun reading(strong: Float = 0f, sexy: Float = 0f) = FrameReading(strong, sexy)

    private class FakeBlurSurface : BlurSurface {
        override var isShowing: Boolean = false
            private set
        var showCount = 0
            private set

        override fun show(touchable: Boolean, onTap: (() -> Unit)?) {
            isShowing = true
            showCount++
        }

        override fun hide() {
            isShowing = false
        }
    }
}
