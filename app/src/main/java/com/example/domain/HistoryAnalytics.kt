package com.example.domain

import com.example.data.SessionHistory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object SessionAction {
    const val STARTED = "STARTED"
    const val CLOSED = "CLOSED"
    const val EXTENDED = "EXTENDED"
    const val BYPASSED = "BYPASSED"
    const val USAGE = "USAGE"
    const val TIMER_FINISHED = "TIMER_FINISHED"
    const val TIMER_CANCELLED = "TIMER_CANCELLED"
    const val LEGACY_COMPLETED = "COMPLETED"

    fun isChoice(action: String): Boolean = action in setOf(STARTED, CLOSED, EXTENDED, BYPASSED)
}

object HistoryDates {
    fun dayBounds(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun pickerDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun offsetFromPicker(millis: Long, today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.DAYS.between(pickerDate(millis), today).toInt().coerceAtLeast(0)

    fun weekRange(end: LocalDate): Pair<Long, Long> = pickerMillis(end.minusDays(6)) to pickerMillis(end)

    fun canSelect(millis: Long, today: LocalDate): Boolean = !pickerDate(millis).isAfter(today)
}

data class BehaviorCounts(val closed: Int = 0, val extended: Int = 0, val bypassed: Int = 0) {
    val total: Int get() = closed + extended + bypassed
    val stopRate: Int? get() = if (total == 0) null else (closed * 100L / total).toInt()
}

data class DayHistory(val records: List<SessionHistory> = emptyList()) {
    val seconds: Long = records.sumOf { it.durationSeconds.coerceAtLeast(0).toLong() }
    val choices: Int = records.count { SessionAction.isChoice(it.actionTaken) }
    val behavior = BehaviorCounts(
        closed = records.count { it.actionTaken == SessionAction.CLOSED },
        extended = records.count { it.actionTaken == SessionAction.EXTENDED },
        bypassed = records.count { it.actionTaken == SessionAction.BYPASSED }
    )
    val topApps: List<Pair<String, Long>> = records.groupBy { it.packageName }
        .map { (_, appRecords) -> appRecords.first().appName to appRecords.sumOf { it.durationSeconds.coerceAtLeast(0).toLong() } / 60 }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        .take(3)
}

data class WeekHistory(val seconds: Long, val resisted: Int, val bestDay: LocalDate?)

class HistoryIndex(records: List<SessionHistory>, zone: ZoneId = ZoneId.systemDefault()) {
    private val emptyDay = DayHistory()
    val days: Map<LocalDate, DayHistory> = records.groupBy {
        Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate()
    }.mapValues { (_, dayRecords) -> DayHistory(dayRecords) }

    fun on(date: LocalDate): DayHistory = days[date] ?: emptyDay

    fun weekEnding(end: LocalDate): WeekHistory {
        val week = (0L..6L).map { end.minusDays(it) to on(end.minusDays(it)) }
        val bestResisted = week.filter { it.second.behavior.closed > 0 }
            .maxByOrNull { it.second.behavior.closed }?.first
        val leastUsage = week.filter { it.second.records.isNotEmpty() }
            .minByOrNull { it.second.seconds }?.first
        return WeekHistory(
            seconds = week.sumOf { it.second.seconds },
            resisted = week.sumOf { it.second.behavior.closed },
            bestDay = bestResisted ?: leastUsage
        )
    }
}