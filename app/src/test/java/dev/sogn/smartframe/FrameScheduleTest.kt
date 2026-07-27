package dev.sogn.smartframe

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameScheduleTest {
    @Test
    fun daytimeScheduleIncludesStartAndExcludesEnd() {
        assertFalse(FrameSchedule.isDisplayTime(6 * 60 + 59, 7 * 60, 21 * 60))
        assertTrue(FrameSchedule.isDisplayTime(7 * 60, 7 * 60, 21 * 60))
        assertTrue(FrameSchedule.isDisplayTime(20 * 60 + 59, 7 * 60, 21 * 60))
        assertFalse(FrameSchedule.isDisplayTime(21 * 60, 7 * 60, 21 * 60))
    }

    @Test
    fun overnightScheduleHandlesMidnight() {
        assertTrue(FrameSchedule.isDisplayTime(22 * 60, 21 * 60, 7 * 60))
        assertTrue(FrameSchedule.isDisplayTime(6 * 60 + 59, 21 * 60, 7 * 60))
        assertFalse(FrameSchedule.isDisplayTime(7 * 60, 21 * 60, 7 * 60))
        assertFalse(FrameSchedule.isDisplayTime(12 * 60, 21 * 60, 7 * 60))
    }

    @Test
    fun nextOccurrenceUsesTodayWhenTargetIsStillAhead() {
        val zone = ZoneId.of("Asia/Seoul")
        val now = LocalDateTime.of(2026, 7, 27, 6, 30).atZone(zone).toInstant().toEpochMilli()
        val expected =
            LocalDateTime.of(2026, 7, 27, 7, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            expected,
            FrameSchedule.nextOccurrenceMillis(now, 7 * 60, zone),
        )
    }

    @Test
    fun nextOccurrenceMovesToTomorrowAfterTarget() {
        val zone = ZoneId.of("Asia/Seoul")
        val now = LocalDateTime.of(2026, 7, 27, 21, 0).atZone(zone).toInstant().toEpochMilli()
        val expected =
            LocalDateTime.of(2026, 7, 28, 21, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            expected,
            FrameSchedule.nextOccurrenceMillis(now, 21 * 60, zone),
        )
    }
}
