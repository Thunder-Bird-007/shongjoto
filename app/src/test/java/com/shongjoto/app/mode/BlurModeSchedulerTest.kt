package com.shongjoto.app.mode

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The extreme window (11pm-6am) wraps midnight, which is the one place this kind of
 * time-of-day check is easy to get backwards — these pin every boundary explicitly rather than
 * trusting a visual read of the implementation.
 */
class BlurModeSchedulerTest {

    @Test
    fun `just before 11pm is LITE`() = assertModeAt(22, 59, 59, BlurMode.LITE)

    @Test
    fun `exactly 11pm is EXTREME`() = assertModeAt(23, 0, 0, BlurMode.EXTREME)

    @Test
    fun `midnight is EXTREME`() = assertModeAt(0, 0, 0, BlurMode.EXTREME)

    @Test
    fun `just before 6am is EXTREME`() = assertModeAt(5, 59, 59, BlurMode.EXTREME)

    @Test
    fun `exactly 6am is LITE`() = assertModeAt(6, 0, 0, BlurMode.LITE)

    @Test
    fun `midday is LITE`() = assertModeAt(13, 0, 0, BlurMode.LITE)

    private fun assertModeAt(hour: Int, minute: Int, second: Int, expected: BlurMode) {
        val zone = ZoneId.of("UTC")
        val instant = LocalDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(hour, minute, second))
            .atZone(zone)
            .toInstant()
        val clock = Clock.fixed(instant, zone)
        assertEquals(expected, BlurModeScheduler.currentMode(clock))
    }
}
