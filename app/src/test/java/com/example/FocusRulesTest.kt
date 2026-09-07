package com.example

import com.example.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class FocusRulesTest {
    @Test fun sharedBudgetAddsMemberUsageAndUsesTheTighterLimit() {
        val configuration = FocusConfiguration(budgets = listOf(SharedBudget("social", "Social", 45, setOf("first", "second"))))
        val consumed = mapOf("first" to 600, "second" to 1200)
        val shared = configuration.budgetProgress("first", 60) { consumed[it] ?: 0 }!!
        assertEquals("Social", shared.label)
        assertEquals(900L, shared.remainingSeconds)
        val personal = configuration.budgetProgress("first", 12) { consumed[it] ?: 0 }!!
        assertEquals("Daily quota", personal.label)
        assertEquals(120L, personal.remainingSeconds)
        assertNull(configuration.budgetProgress("third", 0) { 0 })
    }

    @Test fun weekdayWindowHasInclusiveStartAndExclusiveEnd() {
        val schedule = FocusSchedule((1..5).toSet(), 540, 1020)
        assertTrue(schedule.activeAt(ZonedDateTime.parse("2026-09-07T09:00:00+05:30[Asia/Kolkata]")))
        assertFalse(schedule.activeAt(ZonedDateTime.parse("2026-09-07T17:00:00+05:30[Asia/Kolkata]")))
        assertFalse(schedule.activeAt(ZonedDateTime.parse("2026-09-12T10:00:00+05:30[Asia/Kolkata]")))
    }

    @Test fun overnightProfileBelongsToTheStartingWeekday() {
        val schedule = FocusSchedule(setOf(5), 1320, 420)
        assertTrue(schedule.activeAt(ZonedDateTime.parse("2026-09-12T06:59:00+05:30[Asia/Kolkata]")))
        assertFalse(schedule.activeAt(ZonedDateTime.parse("2026-09-12T07:00:00+05:30[Asia/Kolkata]")))
        assertFalse(schedule.activeAt(ZonedDateTime.parse("2026-09-12T23:00:00+05:30[Asia/Kolkata]")))
    }

    @Test fun boundaryRespectsDaylightSavingAndDoesNotLoopAtTheBoundary() {
        val schedule = FocusSchedule(startMinute = 120, endMinute = 240)
        val before = ZonedDateTime.parse("2026-03-08T01:59:00-05:00[America/New_York]")
        val boundary = schedule.nextBoundary(before)!!
        assertEquals(3, boundary.hour)
        assertTrue(schedule.nextBoundary(boundary)!!.isAfter(boundary))
    }

    @Test fun absentProfileKeepsExistingAlwaysOnBehavior() {
        assertTrue(FocusConfiguration().isActive("test", ZonedDateTime.now()))
        assertEquals(5, FocusConfiguration().rule("test").preferredMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScheduleCannotBeSaved() {
        FocusConfiguration(profiles = listOf(FocusProfile("bad", "Bad", FocusSchedule(emptySet())))).validated()
    }
}