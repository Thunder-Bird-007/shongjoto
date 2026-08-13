package com.shongjoto.app.capture

import com.shongjoto.app.classifier.ClassificationResult
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
 */
class AutoBlurControllerTest {

    private lateinit var surface: FakeBlurSurface
    private lateinit var controller: AutoBlurController

    @Before
    fun setUp() {
        surface = FakeBlurSurface()
        controller = AutoBlurController(surface)
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
                controller.onClassification(classification(sexy = sexy))
            }
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `oscillating score around the old single 0_35 threshold no longer flickers`() {
        // Regression test for the flicker bug: the old code compared one smoothed value
        // directly to a single 0.35 threshold, so this exact sequence used to trip the overlay
        // by its 3rd reading. With per-signal hysteresis (ON=0.50 for strong) and a dead zone,
        // none of these readings are ever mistaken for sustained explicit content.
        val bouncing = listOf(0.30f, 0.45f, 0.35f, 0.50f, 0.32f, 0.48f, 0.38f, 0.44f)
        for (v in bouncing) {
            controller.onClassification(classification(porn = v))
        }
        assertFalse(surface.isShowing)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun `single spurious high reading does not trigger blur`() {
        // One bad tile crop (odd lighting/framing) reading high, surrounded by low readings,
        // must not be enough on its own — smoothing dilutes it and a single frame can't satisfy
        // the consecutive-reads requirement either way.
        controller.onClassification(classification(porn = 0.05f))
        controller.onClassification(classification(porn = 0.90f))
        controller.onClassification(classification(porn = 0.05f))
        controller.onClassification(classification(porn = 0.05f))
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained strong porn or hentai content triggers blur within a couple seconds`() {
        repeat(5) { controller.onClassification(classification(porn = 0.85f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `moderate sustained sexy score typical of beach or portrait photos never triggers blur`() {
        // GantMan's model routinely scores plainly-safe skin-heavy photos 0.3-0.55 on "sexy".
        // This must stay under SEXY_ON_THRESHOLD (0.65) even when sustained for a while.
        repeat(10) { controller.onClassification(classification(sexy = 0.50f)) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `sustained high sexy score alone can still trigger blur`() {
        repeat(6) { controller.onClassification(classification(sexy = 0.80f)) }
        assertTrue(surface.isShowing)
    }

    @Test
    fun `blur holds through a dead-zone dip, only clears on a sustained drop below the OFF threshold`() {
        repeat(5) { controller.onClassification(classification(porn = 0.85f)) }
        assertTrue(surface.isShowing)

        // Dips into the dead zone (below ON, but not below OFF) must not clear it.
        repeat(5) { controller.onClassification(classification(porn = 0.35f)) }
        assertTrue(surface.isShowing)

        // Only a sustained drop below the OFF threshold actually clears it.
        repeat(4) { controller.onClassification(classification(porn = 0.05f)) }
        assertFalse(surface.isShowing)
    }

    @Test
    fun `drawings and neutral labels never contribute to blur`() {
        repeat(10) { controller.onClassification(classification(drawings = 0.9f, neutral = 0.1f)) }
        assertFalse(surface.isShowing)
    }

    private fun classification(
        drawings: Float = 0f,
        hentai: Float = 0f,
        neutral: Float = 1f,
        porn: Float = 0f,
        sexy: Float = 0f
    ): ClassificationResult {
        val scores = mapOf(
            "drawings" to drawings,
            "hentai" to hentai,
            "neutral" to neutral,
            "porn" to porn,
            "sexy" to sexy
        )
        return ClassificationResult(scores, maxOf(hentai, porn, sexy))
    }

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
