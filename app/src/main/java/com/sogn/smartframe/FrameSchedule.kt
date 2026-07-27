package com.sogn.smartframe

import java.time.Instant
import java.time.ZoneId

object FrameSchedule {
    const val MINUTES_PER_DAY = 24 * 60

    fun isDisplayTime(
        currentMinutes: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        if (startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun currentMinutes(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        return now.hour * 60 + now.minute
    }

    fun nextOccurrenceMillis(
        nowMillis: Long,
        targetMinutes: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        require(targetMinutes in 0 until MINUTES_PER_DAY)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var next = now.toLocalDate()
            .atTime(targetMinutes / 60, targetMinutes % 60)
            .atZone(zoneId)
        if (!next.toInstant().isAfter(now.toInstant())) {
            next = next.plusDays(1)
        }
        return next.toInstant().toEpochMilli()
    }
}
