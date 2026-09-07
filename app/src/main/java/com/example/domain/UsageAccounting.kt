package com.example.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageSlice(val date: LocalDate, val startMillis: Long, val seconds: Int)

fun splitUsageByDay(startMillis: Long, elapsedMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<UsageSlice> {
    if (elapsedMillis < 1_000L) return emptyList()
    val endMillis = startMillis + elapsedMillis
    val slices = mutableListOf<UsageSlice>()
    var cursor = startMillis
    while (cursor < endMillis) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sliceEnd = minOf(endMillis, nextMidnight)
        val seconds = ((sliceEnd - cursor) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (seconds > 0) slices.add(UsageSlice(date, cursor, seconds))
        cursor = sliceEnd
    }
    return slices
}