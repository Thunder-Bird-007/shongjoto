package com.shongjoto.app.mode

/**
 * Which threshold profile [com.shongjoto.app.capture.AutoBlurController] is currently gating
 * with. Picked purely by time of day — see [BlurModeScheduler] — not by anything about the
 * content itself.
 */
enum class BlurMode(val label: String) {
    /** Daytime default: less sensitive than [EXTREME] — willing to let more borderline content
     * through in exchange for fewer interruptions. */
    LITE("Lite"),

    /** Late-night window (see [BlurModeScheduler]): today's originally-calibrated thresholds,
     * unchanged — this is the profile the app shipped with before Lite existed. */
    EXTREME("Extreme")
}
