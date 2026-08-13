package com.shongjoto.app.capture

import com.shongjoto.app.overlay.BlurOverlayController
import java.util.ArrayDeque

/**
 * Decides when to show/hide the blur overlay based on classifier confidence. Confidence is
 * smoothed over the last [SMOOTHING_WINDOW] readings before comparison — a single raw frame's
 * value is noisy enough that comparing it directly to the threshold causes both false triggers
 * and flicker. On top of that, showing requires [CONSECUTIVE_EXPLICIT_READS_REQUIRED]
 * consecutive above-threshold readings (mirroring the existing hide-side rule): with tiled
 * classification running 7 checks per frame and taking the max, a single spurious high reading
 * on one tile (a cropped thumbnail, an odd framing) is otherwise enough to trigger — requiring
 * it to persist rules out one-off misfires from a quick scroll, while genuinely sustained
 * content still triggers within a couple of seconds. Hiding likewise only happens after
 * [CONSECUTIVE_CLEAN_READS_REQUIRED] consecutive below-threshold readings, so it clears
 * promptly once content is actually gone without flickering while it isn't.
 *
 * The overlay itself is click-through while showing (see [BlurOverlayController.show]) so the
 * user can scroll/navigate away from content without seeing it — that's the real escape path;
 * once the screen shows something clean, this clears the blur on its own.
 */
class AutoBlurController(private val overlay: BlurOverlayController) {

    private var consecutiveExplicitReads = 0
    private var consecutiveCleanReads = 0
    private val recentReadings = ArrayDeque<Float>()

    fun onClassification(explicitConfidence: Float) {
        recentReadings.addLast(explicitConfidence)
        while (recentReadings.size > SMOOTHING_WINDOW) {
            recentReadings.removeFirst()
        }
        val smoothedConfidence = recentReadings.average().toFloat()

        if (smoothedConfidence >= EXPLICIT_THRESHOLD) {
            consecutiveCleanReads = 0
            if (overlay.isShowing) return

            consecutiveExplicitReads++
            if (consecutiveExplicitReads >= CONSECUTIVE_EXPLICIT_READS_REQUIRED) {
                overlay.show(touchable = false)
                consecutiveExplicitReads = 0
            }
            return
        }

        consecutiveExplicitReads = 0
        if (!overlay.isShowing) {
            return
        }

        consecutiveCleanReads++
        if (consecutiveCleanReads >= CONSECUTIVE_CLEAN_READS_REQUIRED) {
            overlay.hide()
            consecutiveCleanReads = 0
        }
    }

    companion object {
        // Tunables.
        // 0.35 based on real on-device calibration: ordinary content read ~0.05-0.08, real
        // nudity read a sustained 0.424 — 0.7 never would have triggered on that sample.
        // Keep tuning as more real content gets tested.
        const val EXPLICIT_THRESHOLD = 0.35f
        const val CONSECUTIVE_CLEAN_READS_REQUIRED = 2
        const val CONSECUTIVE_EXPLICIT_READS_REQUIRED = 2

        // Moving-average window (in ~1s captures) applied before comparing to the threshold.
        // Higher = less flicker/false-triggers on borderline content, but slower to react in
        // both directions.
        const val SMOOTHING_WINDOW = 3
    }
}
