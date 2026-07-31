package com.shongjoto.app.capture

import com.shongjoto.app.overlay.BlurOverlayController

/**
 * Decides when to show/hide the blur overlay based on classifier confidence: shows
 * immediately once confidence crosses [EXPLICIT_THRESHOLD], but only hides again after
 * [CONSECUTIVE_CLEAN_READS_REQUIRED] consecutive readings back below threshold. This avoids
 * flicker from a single borderline frame while still clearing quickly (~2-3s at the ~1s
 * capture cadence) once content is actually gone.
 *
 * The overlay itself is click-through while showing (see [BlurOverlayController.show]) so the
 * user can scroll/navigate away from content without seeing it — that's the real escape path;
 * once the screen shows something clean, this clears the blur on its own.
 */
class AutoBlurController(private val overlay: BlurOverlayController) {

    private var consecutiveCleanReads = 0

    fun onClassification(explicitConfidence: Float) {
        if (explicitConfidence >= EXPLICIT_THRESHOLD) {
            consecutiveCleanReads = 0
            if (!overlay.isShowing) {
                overlay.show(touchable = false)
            }
            return
        }

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
    }
}
