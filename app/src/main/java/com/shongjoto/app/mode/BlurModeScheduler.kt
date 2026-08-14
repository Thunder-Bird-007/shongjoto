package com.shongjoto.app.mode

import java.time.Clock
import java.time.LocalTime

/**
 * Picks [BlurMode.EXTREME] during the 11:00pm–6:00am window and [BlurMode.LITE] the rest of the
 * day, purely from wall-clock time. Takes a [Clock] (defaulting to the system clock in the
 * device's default time zone) so tests can pin the "current" time instead of depending on when
 * they happen to run.
 */
object BlurModeScheduler {
    private val EXTREME_START: LocalTime = LocalTime.of(23, 0)
    private val EXTREME_END: LocalTime = LocalTime.of(6, 0)

    fun currentMode(clock: Clock = Clock.systemDefaultZone()): BlurMode =
        if (isWithinExtremeWindow(LocalTime.now(clock))) BlurMode.EXTREME else BlurMode.LITE

    /** The window wraps midnight (23:00 -> 06:00 the next day), so it's "at or after start, OR
     * before end" rather than a simple range comparison. */
    private fun isWithinExtremeWindow(time: LocalTime): Boolean =
        !time.isBefore(EXTREME_START) || time.isBefore(EXTREME_END)
}
