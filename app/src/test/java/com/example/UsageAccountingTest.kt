package com.example

import com.example.domain.splitUsageByDay
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageAccountingTest {
    @Test
    fun foregroundUsageCrossingMidnightBelongsToBothDays() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 9, 7)
        val start = date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val slices = splitUsageByDay(start, 180_000L, zone)
        assertEquals(listOf(date, date.plusDays(1)), slices.map { it.date })
        assertEquals(listOf(60, 120), slices.map { it.seconds })
    }

    @Test
    fun usageDoesNotAssumeADayHasTwentyFourHours() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val slices = splitUsageByDay(start, 24 * 3_600_000L, zone)
        assertEquals(listOf(23 * 3_600, 3_600), slices.map { it.seconds })
    }

    @Test
    fun invalidOrSubSecondUsageDoesNotCreateRecords() {
        assertTrue(splitUsageByDay(0, -1).isEmpty())
        assertTrue(splitUsageByDay(0, 999).isEmpty())
    }
}