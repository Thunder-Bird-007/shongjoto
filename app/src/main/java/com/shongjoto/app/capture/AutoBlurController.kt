package com.shongjoto.app.capture

import com.shongjoto.app.classifier.FrameReading
import com.shongjoto.app.mode.BlurMode
import com.shongjoto.app.mode.BlurModeScheduler
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
 * - [FrameReading.strongConfidence] (max of hentai/porn, maxed across the full frame and every
 *   tile independently — see [com.shongjoto.app.classifier.ExplicitContentClassifier.classifyTiled])
 *   is a strong, low-ambiguity signal — a tighter hysteresis band is fine because this pair
 *   rarely fires on safe content by accident.
 * - [FrameReading.sexyConfidence] is GantMan's noisiest class — beach/swimwear/gym/portrait
 *   photos routinely land in its mid-range despite being completely safe — so it gets a higher
 *   ON bar and a wider dead zone, and leans on the hysteresis/debounce logic above the most.
 * "drawings" and "neutral" never enter this calculation at all; they're implicitly safe.
 *
 * Which [ThresholdProfile] applies is picked fresh on every call from [modeProvider] (defaulting
 * to [BlurModeScheduler]) rather than cached — the smoothing/debounce state above carries across
 * a mode change mid-session so there's no discontinuity at the 11pm/6am boundary, but the
 * thresholds compared against it do switch immediately. See [PROFILES] for what actually differs
 * between modes.
 *
 * The overlay itself is click-through while showing (see [BlurSurface.show]) so the user can
 * scroll/navigate away from content without seeing it — that's the real escape path; once the
 * screen shows something clean, this clears the blur on its own.
 */
class AutoBlurController(
    private val overlay: BlurSurface,
    private val modeProvider: () -> BlurMode = { BlurModeScheduler.currentMode() }
) {

    private var consecutiveExplicitReads = 0
    private var consecutiveCleanReads = 0
    private val recentStrong = ArrayDeque<Float>()
    private val recentSexy = ArrayDeque<Float>()

    fun onClassification(result: FrameReading) {
        val profile = PROFILES.getValue(modeProvider())
        val smoothedStrong = smooth(recentStrong, result.strongConfidence)
        val smoothedSexy = smooth(recentSexy, result.sexyConfidence)

        val clearlyExplicit =
            smoothedStrong >= profile.strongOn || smoothedSexy >= profile.sexyOn
        val clearlyClean =
            smoothedStrong < profile.strongOff && smoothedSexy < profile.sexyOff

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

    /** One ON/OFF threshold pair per signal. See [PROFILES] for the values used per [BlurMode]. */
    data class ThresholdProfile(
        val strongOn: Float,
        val strongOff: Float,
        val sexyOn: Float,
        val sexyOff: Float
    )

    companion object {
        const val CONSECUTIVE_CLEAN_READS_REQUIRED = 2
        const val CONSECUTIVE_EXPLICIT_READS_REQUIRED = 2

        // Moving-average window (in captures) applied to each signal before comparing to its
        // thresholds. Higher = less flicker/false-triggers on borderline content, but slower to
        // react in both directions.
        const val SMOOTHING_WINDOW = 3

        /**
         * EXTREME is this app's original, already-calibrated profile, unchanged: ON/OFF here
         * straddle the on-device calibration point for real content (~0.42 sustained strong
         * signal, per prior tuning), and the sexy pair matches the ON > 0.65 / OFF < 0.30 spec
         * directly.
         *
         * LITE's strongOn/strongOff were recalibrated from real CalibrationLog data: labeled
         * explicit content in that batch consistently scored 0.42-0.50 on the strong signal and
         * never higher, so the original placeholder (0.55) would have missed all of it. There
         * isn't much room left to be meaningfully less sensitive than EXTREME on the strong
         * signal without dropping below real content's observed floor — so LITE's actual
         * leniency now mostly lives in its sexy threshold instead, which still has real headroom
         * (observed non-explicit sexy readings topped out around 0.58, comfortably under both
         * 0.65 and 0.78). Revisit both profiles as more CalibrationLog data comes in — this is
         * one batch, not a settled calibration.
         */
        val PROFILES: Map<BlurMode, ThresholdProfile> = mapOf(
            BlurMode.EXTREME to ThresholdProfile(
                strongOn = 0.40f, strongOff = 0.20f,
                sexyOn = 0.65f, sexyOff = 0.30f
            ),
            BlurMode.LITE to ThresholdProfile(
                strongOn = 0.42f, strongOff = 0.20f,
                sexyOn = 0.78f, sexyOff = 0.45f
            )
        )
    }
}
