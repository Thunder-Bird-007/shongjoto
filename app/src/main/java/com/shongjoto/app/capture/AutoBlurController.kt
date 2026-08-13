package com.shongjoto.app.capture

import com.shongjoto.app.classifier.ClassificationResult
import com.shongjoto.app.overlay.BlurSurface
import java.util.ArrayDeque

/**
 * Decides when to show/hide the blur overlay based on classifier confidence. Three layers work
 * together to kill flicker, each catching a different kind of noise:
 *
 * 1. **Moving-average smoothing** over the last [SMOOTHING_WINDOW] readings — a single raw
 *    frame's score is noisy enough that comparing it directly to a threshold causes spurious
 *    triggers.
 * 2. **Two-threshold hysteresis with a dead zone**, per signal (see below) — rather than one
 *    threshold used for both directions, ON requires clearing a high bar and OFF requires
 *    dropping below a distinctly lower one. A smoothed score sitting in between (the dead zone)
 *    changes nothing and just holds whatever state the overlay is already in — this is what
 *    stops content hovering near a single fixed threshold from flapping on/off.
 * 3. **Consecutive-reads debounce** — even a smoothed, hysteresis-gated reading must hold for
 *    [CONSECUTIVE_EXPLICIT_READS_REQUIRED] (show) or [CONSECUTIVE_CLEAN_READS_REQUIRED] (hide)
 *    captures in a row before the overlay actually flips, so one-off spikes from a quick scroll
 *    or an odd tile crop can't do it alone.
 *
 * The two signals are tracked and thresholded **separately** rather than folded into one
 * "explicit" number, because they don't carry the same evidentiary weight:
 * - [ClassificationResult.strongConfidence] (max of hentai/porn) is treated as a strong,
 *   low-ambiguity signal — a tighter hysteresis band is fine because this pair rarely fires on
 *   safe content by accident.
 * - [ClassificationResult.sexyConfidence] is GantMan's noisiest class — beach/swimwear/gym/
 *   portrait photos routinely land in its mid-range despite being completely safe — so it gets
 *   a higher ON bar and a wider dead zone, and leans on the hysteresis/debounce logic above the
 *   most.
 * "drawings" and "neutral" never enter this calculation at all; they're implicitly safe.
 *
 * The overlay itself is click-through while showing (see [BlurSurface.show]) so the user can
 * scroll/navigate away from content without seeing it — that's the real escape path; once the
 * screen shows something clean, this clears the blur on its own.
 */
class AutoBlurController(private val overlay: BlurSurface) {

    private var consecutiveExplicitReads = 0
    private var consecutiveCleanReads = 0
    private val recentStrong = ArrayDeque<Float>()
    private val recentSexy = ArrayDeque<Float>()

    fun onClassification(result: ClassificationResult) {
        val smoothedStrong = smooth(recentStrong, result.strongConfidence)
        val smoothedSexy = smooth(recentSexy, result.sexyConfidence)

        val clearlyExplicit =
            smoothedStrong >= STRONG_ON_THRESHOLD || smoothedSexy >= SEXY_ON_THRESHOLD
        val clearlyClean =
            smoothedStrong < STRONG_OFF_THRESHOLD && smoothedSexy < SEXY_OFF_THRESHOLD

        if (clearlyExplicit) {
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

        if (!clearlyClean) {
            // Dead zone: neither clearly explicit nor clearly clean. Hold whatever state the
            // overlay is already in rather than nudging it either way — this is what prevents a
            // score oscillating around a single fixed threshold from flickering the overlay.
            consecutiveCleanReads = 0
            return
        }

        if (!overlay.isShowing) return

        consecutiveCleanReads++
        if (consecutiveCleanReads >= CONSECUTIVE_CLEAN_READS_REQUIRED) {
            overlay.hide()
            consecutiveCleanReads = 0
        }
    }

    private fun smooth(window: ArrayDeque<Float>, value: Float): Float {
        window.addLast(value)
        while (window.size > SMOOTHING_WINDOW) {
            window.removeFirst()
        }
        return window.average().toFloat()
    }

    companion object {
        // Strong signal (max of hentai/porn) — unambiguous even at moderate confidence, so a
        // tighter hysteresis band is fine; false positives on this pair are rare.
        const val STRONG_ON_THRESHOLD = 0.50f
        const val STRONG_OFF_THRESHOLD = 0.25f

        // "Sexy" signal — the model's noisiest class, so it needs a much higher bar to trigger
        // and a wide dead zone to resist chatter from skin-heavy-but-safe content (beach,
        // swimming, close-up portraits). These match the ON > 0.65 / OFF < 0.30 spec directly.
        const val SEXY_ON_THRESHOLD = 0.65f
        const val SEXY_OFF_THRESHOLD = 0.30f

        const val CONSECUTIVE_CLEAN_READS_REQUIRED = 2
        const val CONSECUTIVE_EXPLICIT_READS_REQUIRED = 2

        // Moving-average window (in captures) applied to each signal before comparing to its
        // thresholds. Higher = less flicker/false-triggers on borderline content, but slower to
        // react in both directions.
        const val SMOOTHING_WINDOW = 3
    }
}
