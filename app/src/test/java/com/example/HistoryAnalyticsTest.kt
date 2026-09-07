package com.example

import com.example.data.SessionHistory
import com.example.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class HistoryAnalyticsTest {
    @Test
    fun localDaysHandleDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        val spring = HistoryDates.dayBounds(LocalDate.of(2026, 3, 8), zone)
        val autumn = HistoryDates.dayBounds(LocalDate.of(2026, 11, 1), zone)
        assertEquals(23 * 3_600_000L, spring.second - spring.first)
        assertEquals(25 * 3_600_000L, autumn.second - autumn.first)
    }

    @Test
    fun pickerDatesAreCivilDatesNotElapsedTwentyFourHourPeriods() {
        val today = LocalDate.of(2026, 3, 9)
        val picked = HistoryDates.pickerMillis(LocalDate.of(2026, 3, 7))
        assertEquals(2, HistoryDates.offsetFromPicker(picked, today))
        assertTrue(HistoryDates.canSelect(HistoryDates.pickerMillis(today), today))
        assertFalse(HistoryDates.canSelect(HistoryDates.pickerMillis(today.plusDays(1)), today))
    }

    @Test
    fun weekIncludesExactlySevenDatesAcrossYearBoundary() {
        val end = LocalDate.of(2026, 1, 3)
        val range = HistoryDates.weekRange(end)
        assertEquals(LocalDate.of(2025, 12, 28), HistoryDates.pickerDate(range.first))
        assertEquals(end, HistoryDates.pickerDate(range.second))
    }

    @Test
    fun summariesKeepOlderRecordsAndRoundOnlyAfterAddingSeconds() {
        val end = LocalDate.of(2026, 7, 10)
        val records = (0L..13L).map { offset ->
            SessionHistory(
                id = offset.toInt(), packageName = "test", appName = "Test",
                startTime = HistoryDates.pickerMillis(end.minusDays(offset)),
                durationSeconds = 59, actionTaken = SessionAction.CLOSED
            )
        }
        val index = HistoryIndex(records, ZoneOffset.UTC)
        val week = index.weekEnding(end)
        assertEquals(413L, week.seconds)
        assertEquals(6L, week.seconds / 60)
        assertEquals(7, week.resisted)
        assertEquals(end, week.bestDay)
        assertEquals(7, index.weekEnding(end.minusDays(7)).resisted)
        assertEquals(0, index.weekEnding(end.minusDays(30)).resisted)
        assertNull(index.weekEnding(end.minusDays(30)).bestDay)
    }

    @Test
    fun stopRateUsesExplicitChoicesAndNotTimerExpirations() {
        assertNull(BehaviorCounts().stopRate)
        assertEquals(100, BehaviorCounts(closed = 4).stopRate)
        assertEquals(50, BehaviorCounts(closed = 2, extended = 1, bypassed = 1).stopRate)
        assertEquals(0, BehaviorCounts(extended = 3).stopRate)
        val day = DayHistory(listOf(SessionHistory(packageName = "test", appName = "Test", durationSeconds = 0, actionTaken = SessionAction.TIMER_FINISHED)))
        assertEquals(0, day.choices)
        assertNull(day.behavior.stopRate)
    }
}