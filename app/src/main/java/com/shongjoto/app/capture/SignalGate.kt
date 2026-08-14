package com.shongjoto.app.capture

import java.util.ArrayDeque

/**
 * Smoothing + two-threshold hysteresis + consecutive-reads debounce for ONE raw confidence
 * signal, deciding whether *this* signal currently wants the blur overlay shown. Doesn't touch
 * any overlay itself — [AutoBlurController] holds one gate per independent signal (GantMan's
 * strong, GantMan's sexy, Falconsai's nsfw score) and shows the overlay if ANY gate wants it
 * shown, hiding only once ALL of them agree it's clean. That's a deliberate ensemble choice: it
 * can only ever catch more than a single signal would, never less — the tradeoff is it inherits
 * whichever signal is noisiest, unless that signal's own thresholds are set well past its noise
 * floor (see AutoBlurController.PROFILES).
 *
 * ON/OFF thresholds are passed into [onReading] rather than fixed at construction, because they
 * depend on the current [com.shongjoto.app.mode.BlurMode], which can change mid-session — the
 * smoothing window and debounce counters below are meant to keep carrying across that switch
 * unbroken; only the thresholds compared against them should change immediately.
 */
class SignalGate(
    private val smoothingWindow: Int,
    private val consecutiveReadsRequired: Int
) {
    var wantsShown: Boolean = false
        private set

    private var consecutiveExplicitReads = 0
    private var consecutiveCleanReads = 0
    private val recent = ArrayDeque<Float>()

    fun onReading(value: Float, onThreshold: Float, offThreshold: Float) {
        // A NaN/Infinite reading (e.g. a model choking on degenerate input — an all-black
        // FLAG_SECURE-redacted region, say) must never enter the smoothing window: NaN
        // compares false against both >= and <, which would land in the dead-zone branch below
        // and then, since NaN poisons every subsequent average it's part of, could hold whatever
        // state this gate was already in indefinitely. Treat it as "no signal" instead.
        val safeValue = if (value.isFinite()) value else 0f
        recent.addLast(safeValue)
        while (recent.size > smoothingWindow) {
            recent.removeFirst()
        }
        val smoothed = recent.average().toFloat()

        val clearlyExplicit = smoothed >= onThreshold
        val clearlyClean = smoothed < offThreshold

        if (clearlyExplicit) {
            consecutiveCleanReads = 0
            if (wantsShown) return

            consecutiveExplicitReads++
            if (consecutiveExplicitReads >= consecutiveReadsRequired) {
                wantsShown = true
                consecutiveExplicitReads = 0
            }
            return
        }

        consecutiveExplicitReads = 0

        if (!clearlyClean) {
            // Dead zone: neither clearly explicit nor clearly clean. Hold whatever state this
            // gate is already in — this is what prevents a score oscillating around a single
            // fixed threshold from flickering.
            consecutiveCleanReads = 0
            return
        }

        if (!wantsShown) return

        consecutiveCleanReads++
        if (consecutiveCleanReads >= consecutiveReadsRequired) {
            wantsShown = false
            consecutiveCleanReads = 0
        }
    }
}
