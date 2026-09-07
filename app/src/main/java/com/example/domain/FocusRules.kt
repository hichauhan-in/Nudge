package com.example.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class FocusSchedule(
    val days: Set<Int> = (1..7).toSet(),
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun activeAt(now: ZonedDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        if (startMinute == endMinute) return now.dayOfWeek.value in days
        if (startMinute < endMinute) return now.dayOfWeek.value in days && minute in startMinute until endMinute
        return (now.dayOfWeek.value in days && minute >= startMinute) ||
            (now.minusDays(1).dayOfWeek.value in days && minute < endMinute)
    }

    fun nextBoundary(now: ZonedDateTime): ZonedDateTime? = (0L..8L).flatMap { offset ->
        val date = now.toLocalDate().plusDays(offset - 1L)
        if (date.dayOfWeek.value !in days) emptyList() else {
            val endDate = if (endMinute <= startMinute) date.plusDays(1) else date
            listOf(atMinute(date, startMinute, now), atMinute(endDate, endMinute, now))
        }
    }.filter { it.isAfter(now) }.minOrNull()

    private fun atMinute(date: LocalDate, minute: Int, now: ZonedDateTime) =
        date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(now.zone)
}

enum class PromptTone { DEFAULT, GENTLE, SARCASTIC }

data class AppRule(
    val preferredMinutes: Int = 5,
    val rememberDuration: Boolean = true,
    val tone: PromptTone = PromptTone.DEFAULT,
    val profileId: String? = null,
    val maxExtensions: Int = 0,
    val cooldownMinutes: Int = 0
)

data class FocusProfile(val id: String, val name: String, val schedule: FocusSchedule)
data class SharedBudget(val id: String, val name: String, val minutes: Int, val packages: Set<String>)

data class BudgetProgress(val label: String, val limitSeconds: Long, val consumedSeconds: Long) {
    val remainingSeconds: Long get() = limitSeconds - consumedSeconds
}

data class FocusConfiguration(
    val rules: Map<String, AppRule> = emptyMap(),
    val profiles: List<FocusProfile> = listOf(
        FocusProfile("work", "Work", FocusSchedule((1..5).toSet(), 9 * 60, 17 * 60)),
        FocusProfile("evening", "Evening", FocusSchedule(startMinute = 18 * 60, endMinute = 22 * 60)),
        FocusProfile("bedtime", "Bedtime", FocusSchedule(startMinute = 22 * 60, endMinute = 7 * 60))
    ),
    val budgets: List<SharedBudget> = emptyList(),
    val weeklyGoalMinutes: Int = 0
) {
    fun rule(packageName: String) = rules[packageName] ?: AppRule()
    fun schedule(packageName: String): FocusSchedule? = profiles.firstOrNull { it.id == rule(packageName).profileId }?.schedule
    fun isActive(packageName: String, now: ZonedDateTime) = schedule(packageName)?.activeAt(now) ?: true
    fun budget(packageName: String): SharedBudget? = budgets.firstOrNull { packageName in it.packages }

    fun budgetProgress(packageName: String, personalMinutes: Int, consumed: (String) -> Int): BudgetProgress? {
        val personal = if (personalMinutes > 0) BudgetProgress("Daily quota", personalMinutes * 60L, consumed(packageName).coerceAtLeast(0).toLong()) else null
        val shared = budget(packageName)?.let { budget ->
            BudgetProgress(budget.name, budget.minutes * 60L, budget.packages.sumOf { consumed(it).coerceAtLeast(0).toLong() })
        }
        return listOfNotNull(personal, shared).minByOrNull { it.remainingSeconds }
    }

    fun validated(): FocusConfiguration {
        require(profiles.size <= 30 && rules.size <= 1000 && budgets.size <= 30)
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(budgets.map { it.id }.distinct().size == budgets.size)
        require(weeklyGoalMinutes in 0..10_080)
        profiles.forEach {
            require(it.id.isNotBlank() && it.id.length <= 100 && it.name.isNotBlank() && it.name.length <= 40)
            require(it.schedule.days.isNotEmpty() && it.schedule.days.all { day -> day in 1..7 })
            require(it.schedule.startMinute in 0..1439 && it.schedule.endMinute in 0..1439)
        }
        rules.forEach { (packageName, rule) ->
            require(packageName.isNotBlank() && packageName.length <= 255)
            require(rule.preferredMinutes in 1..60 && rule.maxExtensions in 0..10 && rule.cooldownMinutes in 0..60)
            require(rule.profileId == null || profiles.any { it.id == rule.profileId })
        }
        budgets.forEach {
            require(it.id.isNotBlank() && it.id.length <= 100 && it.name.isNotBlank() && it.name.length <= 40)
            require(it.minutes in 5..1440 && it.packages.isNotEmpty() && it.packages.size <= 1000)
            require(it.packages.all { name -> name.isNotBlank() && name.length <= 255 })
        }
        val members = budgets.flatMap { it.packages }
        require(members.distinct().size == members.size)
        return this
    }
}