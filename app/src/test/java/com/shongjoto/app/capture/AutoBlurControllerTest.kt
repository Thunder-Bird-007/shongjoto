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
 * Exercises the full three-gate OR-ensemble (GantMan strong, GantMan sexy, Falconsai) with
 * synthetic confidence sequences — this is what stands in for the on-device "scroll safe content
 * for 30+ seconds" and "sustained explicit content" scenarios in an environment with no Android
 * device attached. Real on-device testing is still required before shipping.
 *
 * All tests here run in EXTREME mode (today's calibrated thresholds) unless noted — see the
 * `mode selection` group for LITE-specific behavior.
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
        // ad thumbnail); falconsai stays near its observed noise floor.
        val noisySexyReadings =
            listOf(0.05f, 0.12f, 0.08f, 0.20f, 0.15f, 0.30f, 0.10f, 0.25f, 0.18f, 0.09f)
        repeat(4) {
            for (sexy in noisySexyReadings) {
                controller.onClassification(reading(sexy = sexy), falconsaiScore = 0.005f)
            }
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `oscillating score around the old single 0_35 threshold no longer flickers`() {
        val bouncing = listOf(0.20f, 0.32f, 0.25f, 0.38f, 0.22f, 0.35f, 0.28f, 0.31f)
        for (v in bouncing) {
            controller.onClassification(reading(strong = v), falconsaiScore = 0f)
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `single spurious high reading does not trigger blur`() {
        controller.onClassification(reading(strong = 0.05f), falconsaiScore = 0f)
        controller.onClassification(reading(strong = 0.90f), falconsaiScore = 0f)
        controller.onClassification(reading(strong = 0.05f), falconsaiScore = 0f)
        controller.onClassification(reading(strong = 0.05f), falconsaiScore = 0f)
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained strong porn or hentai content triggers blur within a couple seconds`() {
        repeat(5) { controller.onClassification(reading(strong = 0.85f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `strong content at this app's own calibrated real-world value triggers blur`() {
        repeat(5) { controller.onClassification(reading(strong = 0.424f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `moderate sustained sexy score typical of beach or portrait photos never triggers blur`() {
        repeat(10) { controller.onClassification(reading(sexy = 0.50f), falconsaiScore = 0f) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained high sexy score alone can still trigger blur`() {
        repeat(6) { controller.onClassification(reading(sexy = 0.80f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `blur holds through a dead-zone dip, only clears on a sustained drop below the OFF threshold`() {
        repeat(5) { controller.onClassification(reading(strong = 0.85f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)

        repeat(5) { controller.onClassification(reading(strong = 0.35f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)

        repeat(4) { controller.onClassification(reading(strong = 0.05f), falconsaiScore = 0f) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `a strong signal on one tile is not suppressed by a higher but subthreshold sexy signal elsewhere`() {
        repeat(5) {
            controller.onClassification(reading(strong = 0.55f, sexy = 0.62f), falconsaiScore = 0f)
        }
        assertTrue(surface.isShowing)
    }

    // --- ensemble behavior (Falconsai) --------------------------------------------------------

    @Test
    fun `falconsai alone triggers blur when both GantMan signals completely miss`() {
        // The whole reason Falconsai was added: real calibration data showed GantMan's strong
        // signal reading exactly 0 on some content a human tagged explicit, while Falconsai
        // still caught it clearly (0.94+).
        repeat(5) { controller.onClassification(reading(), falconsaiScore = 0.95f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `falconsai at its observed safe-content ceiling never triggers`() {
        // Real calibration data: not-explicit content never exceeded 0.0191 on Falconsai.
        repeat(10) { controller.onClassification(reading(), falconsaiScore = 0.0191f) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `either gate alone is enough -- GantMan strong plus a low falconsai reading still triggers`() {
        repeat(5) { controller.onClassification(reading(strong = 0.85f), falconsaiScore = 0.001f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `all three signals must independently clear before the overlay hides`() {
        // Trigger via falconsai only.
        repeat(5) { controller.onClassification(reading(), falconsaiScore = 0.95f) }
        assertTrue(surface.isShowing)

        // GantMan's signals were never elevated, so only falconsai's gate needs to clear -- but
        // it still needs its own sustained drop below OFF, not just one low reading.
        controller.onClassification(reading(), falconsaiScore = 0.01f)
        assertTrue(surface.isShowing)
    }

    // --- mode selection ------------------------------------------------------------------------

    @Test
    fun `LITE mode does not trigger on a falconsai reading that would trigger EXTREME`() {
        // EXTREME's falconsaiOn (0.08) sits just above the observed safe-content ceiling;
        // LITE's (0.20) sits at the top of the gap before real detections cluster (0.26+) --
        // this is where LITE's "less sensitive during the day" intent actually lives now, since
        // GantMan's own strong/sexy pair has little room left to differ between modes.
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { BlurMode.LITE }
        repeat(6) { controller.onClassification(reading(), falconsaiScore = 0.10f) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `LITE mode still triggers on a high-confidence falconsai reading`() {
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { BlurMode.LITE }
        repeat(6) { controller.onClassification(reading(), falconsaiScore = 0.90f) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `LITE mode now triggers on real calibrated explicit content (previously missed at strongOn 0_55)`() {
        val surface = FakeBlurSurface()
        val controller = AutoBlurController(surface) { BlurMode.LITE }
        repeat(6) { controller.onClassification(reading(strong = 0.45f), falconsaiScore = 0f) }
        assertTrue(surface.isShowing)
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
