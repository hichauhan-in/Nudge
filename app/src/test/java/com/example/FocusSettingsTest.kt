package com.example

import com.example.data.FocusSettings
import com.example.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FocusSettingsTest {
    @Test fun preferencesRoundTripWithoutLosingSchedulesOrBudgets() {
        val config = FocusConfiguration(rules = mapOf("test" to AppRule(10, false, PromptTone.GENTLE, "work", 2, 15)),
            budgets = listOf(SharedBudget("social", "Social", 45, setOf("test", "other"))), weeklyGoalMinutes = 300)
        assertEquals(config, FocusSettings.decode(FocusSettings.encode(config)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anAppCannotBelongToTwoSharedBudgets() {
        FocusConfiguration(budgets = listOf(SharedBudget("first", "First", 30, setOf("test")),
            SharedBudget("second", "Second", 40, setOf("test")))).validated()
    }
}