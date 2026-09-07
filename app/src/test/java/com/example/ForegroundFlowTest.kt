package com.example

import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.domain.*
import com.example.service.AppAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForegroundFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: ServiceController<AppAccessibilityService>
    private lateinit var repository: ScreenGuardRepository
    private val googlePackage = "com.google.android.googlequicksearchbox"

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionManager.stopForProcessRecreationTest()
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        AccessibilityConsent.accept(context)
        SessionManager.init(context)
        SessionManager.setMasterGuardEnabled(true)
        SessionManager.setMonitoredApps(emptyList())
        SessionManager.setOverlayVisible(false)
        FocusSettings.update(FocusConfiguration())
        val dao = AppDatabase.getDatabase(context).dao()
        dao.clearHistory()
        dao.clearMonitoredApps()
        dao.insertMonitoredApp(MonitoredApp(googlePackage, "Google", dailyQuotaMinutes = 30))
        repository = ScreenGuardRepository(dao)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context as android.app.Application).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        controller = Robolectric.buildService(AppAccessibilityService::class.java)
        controller.create()
        withTimeout(5000) { while (SessionManager.getQuotaMinutes(googlePackage) != 30) delay(10) }
    }

    @After fun teardown() {
        if (::controller.isInitialized) controller.destroy()
        SessionManager.setMasterGuardEnabled(false)
        SessionManager.stopForProcessRecreationTest()
        Dispatchers.resetMain()
    }

    private fun event(packageName: String, className: String = "MainActivity") {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = packageName
        event.className = className
        controller.get().onAccessibilityEvent(event)
    }

    @Test fun googleForegroundTriggersOnePromptAndRefusalStopsEvents() {
        event(googlePackage)
        val first = SessionManager.sessionState.value
        assertEquals(SessionState.Prompting(googlePackage, "Google"), first)
        event(googlePackage)
        assertEquals(first, SessionManager.sessionState.value)
        SessionManager.withdrawConsent()
        event(googlePackage)
        assertEquals(SessionState.Idle, SessionManager.sessionState.value)
        assertFalse(SessionManager.isMasterGuardEnabled.value)
    }

    @Test fun notificationShadeStopsUsageButDoesNotResetTheTimer() {
        event(googlePackage)
        SessionManager.startSession(googlePackage, "Google", 10, repository)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(40))
        event("com.android.systemui", "NotificationShade")
        val consumed = SessionManager.getQuotaConsumedSecondsToday(googlePackage)
        assertTrue(consumed >= 40)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertEquals(consumed, SessionManager.getQuotaConsumedSecondsToday(googlePackage))
        assertTrue(SessionManager.hasValidActiveTimer(googlePackage))
        assertNull(SessionManager.lastUserAppPackage)
        event(googlePackage)
        assertEquals(googlePackage, SessionManager.lastUserAppPackage)
        assertEquals(SessionState.Idle, SessionManager.sessionState.value)
    }

    @Test fun outOfScheduleAppDoesNotShowAnIntervention() {
        val yesterday = java.time.ZonedDateTime.now().minusDays(1).dayOfWeek.value
        FocusSettings.update(FocusConfiguration(
            profiles = listOf(FocusProfile("inactive", "Inactive", FocusSchedule(setOf(yesterday)))),
            rules = mapOf(googlePackage to AppRule(profileId = "inactive"))))
        event(googlePackage)
        assertEquals(SessionState.Idle, SessionManager.sessionState.value)
        assertTrue(SessionManager.activeTimers.value.isEmpty())
    }

    @Test fun sharedBudgetExhaustionBlocksAnotherMemberInStrictMode() {
        FocusSettings.update(FocusConfiguration(budgets = listOf(SharedBudget("social", "Social", 30, setOf(googlePackage, "other")))))
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("quota_day_other", java.time.LocalDate.now().toEpochDay()).putInt("quota_used_other", 1800).commit()
        SessionManager.setStrictModeEnabled(true)
        event(googlePackage)
        assertEquals(SessionState.QuotaExhausted(googlePackage, "Google", true), SessionManager.sessionState.value)
    }

    @Test fun unmonitoredAppsDoNotCreateUsageOrPrompts() = runBlocking {
        event("unmonitored.app", "NotificationActivity")
        assertEquals(SessionState.Idle, SessionManager.sessionState.value)
        assertTrue(repository.allSessions.first().isEmpty())
    }
}