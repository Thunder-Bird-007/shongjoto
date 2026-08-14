package com.shongjoto.app.capture

import com.shongjoto.app.classifier.FrameReading
import com.shongjoto.app.mode.BlurMode
import com.shongjoto.app.mode.BlurModeScheduler
import com.shongjoto.app.overlay.BlurSurface

/**
 * Decides when to show/hide the blur overlay from THREE independent signals, ensembled via OR:
 * - [FrameReading.strongConfidence] — GantMan's max(hentai, porn), tiled across the full frame
 *   and every region (see
 *   [com.shongjoto.app.classifier.ExplicitContentClassifier.classifyTiled]) — the signal that
 *   currently catches the large majority of real content, including small regions in a feed.
 * - [FrameReading.sexyConfidence] — GantMan's noisiest class, kept separate with its own much
 *   higher bar (see [PROFILES]).
 * - Falconsai's nsfw probability (single full-frame pass, not tiled — see
 *   [com.shongjoto.app.classifier.FalconsaiClassifier]'s doc for why) — added specifically
 *   because on real calibration data it never came close to GantMan's strong-signal false
 *   positives (max ~0.02 on content labeled safe, vs. GantMan's strong reaching ~0.5-0.7 on the
 *   same kind of content), even though on its own it misses roughly 60% of real explicit content
 *   GantMan's tiled strong signal already catches. Ensembling via OR means it can only ever add
 *   catches GantMan's signals miss — it cannot, by construction, suppress a GantMan-driven
 *   false positive on its own. If GantMan's own false-positive rate needs to come down further,
 *   that's a separate change (e.g. tightening its own thresholds against more data), not
 *   something this ensemble does for free.
 *
 * Each signal gets its own [SignalGate] — its own smoothing, hysteresis dead zone, and
 * consecutive-reads debounce, so one signal's noise can never reset another's debounce counters.
 * The overlay is shown the moment *any* gate wants it shown, and hidden only once *all* gates
 * agree it's clean.
 *
 * Which [ThresholdProfile] applies is picked fresh on every call from [modeProvider] (defaulting
 * to [BlurModeScheduler]) — each gate's internal smoothing/debounce state carries across a mode
 * change mid-session so there's no discontinuity at the 11pm/6am boundary, but the thresholds
 * compared against it switch immediately.
 *
 * The overlay itself is click-through while showing (see [BlurSurface.show]) so the user can
 * scroll/navigate away from content without seeing it — that's the real escape path; once the
 * screen shows something clean by every signal's own lights, this clears the blur on its own.
 */
class AutoBlurController(
    private val overlay: BlurSurface,
    private val modeProvider: () -> BlurMode = { BlurModeScheduler.currentMode() }
) {
    private val strongGate = SignalGate(SMOOTHING_WINDOW, CONSECUTIVE_READS_REQUIRED)
    private val sexyGate = SignalGate(SMOOTHING_WINDOW, CONSECUTIVE_READS_REQUIRED)
    private val falconsaiGate = SignalGate(SMOOTHING_WINDOW, CONSECUTIVE_READS_REQUIRED)

    fun onClassification(gantman: FrameReading, falconsaiScore: Float) {
        val profile = PROFILES.getValue(modeProvider())

        strongGate.onReading(gantman.strongConfidence, profile.strongOn, profile.strongOff)
        sexyGate.onReading(gantman.sexyConfidence, profile.sexyOn, profile.sexyOff)
        falconsaiGate.onReading(falconsaiScore, profile.falconsaiOn, profile.falconsaiOff)

        val shouldShow = strongGate.wantsShown || sexyGate.wantsShown || falconsaiGate.wantsShown
        if (shouldShow && !overlay.isShowing) {
            overlay.show(touchable = false)
        } else if (!shouldShow && overlay.isShowing) {
            overlay.hide()
        }
    }

    /** One ON/OFF threshold pair per signal. See [PROFILES] for the values used per [BlurMode]. */
    data class ThresholdProfile(
        val strongOn: Float,
        val strongOff: Float,
        val sexyOn: Float,
        val sexyOff: Float,
        val falconsaiOn: Float,
        val falconsaiOff: Float
    )

    companion object {
        const val CONSECUTIVE_READS_REQUIRED = 2

        // Moving-average window (in captures) applied to each signal before comparing to its
        // thresholds. Higher = less flicker/false-triggers on borderline content, but slower to
        // react in both directions.
        const val SMOOTHING_WINDOW = 3

        /**
         * EXTREME is this app's original, already-calibrated profile for GantMan's two signals,
         * unchanged. falconsaiOn/Off are new, picked from real CalibrationLog data: labeled
         * not-explicit content never scored above 0.0191 on Falconsai, while labeled explicit
         * content was clearly bimodal — roughly half near-zero (misses), half in 0.26-0.9997.
         * 0.08 sits in the gap between those, ~4x above the observed noise floor, catching what
         * Falconsai actually signals without adding false positives of its own.
         *
         * LITE's falconsaiOn is set at the *top* of that same gap (0.20, just under where the
         * high cluster starts at 0.26) rather than just above the noise floor like EXTREME —
         * this is where LITE's "less sensitive during the day" intent actually lives now, since
         * GantMan's own strong/sexy pair has very little room left to differ between modes (see
         * their own doc history).
         */
        val PROFILES: Map<BlurMode, ThresholdProfile> = mapOf(
            BlurMode.EXTREME to ThresholdProfile(
                strongOn = 0.40f, strongOff = 0.20f,
                sexyOn = 0.65f, sexyOff = 0.30f,
                falconsaiOn = 0.08f, falconsaiOff = 0.03f
            ),
            BlurMode.LITE to ThresholdProfile(
                strongOn = 0.42f, strongOff = 0.20f,
                sexyOn = 0.78f, sexyOff = 0.45f,
                falconsaiOn = 0.20f, falconsaiOff = 0.06f
            )
        )
    }
}
