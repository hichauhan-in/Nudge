package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SessionManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: ScreenGuardRepository

    @Before
    fun setup() = runBlocking {
        Dispatchers.setMain(dispatcher)
        AccessibilityConsent.accept(context)
        SessionManager.init(context)
        SessionManager.resetAll()
        SessionManager.setMasterGuardEnabled(true)
        SessionManager.setMonitoredApps(listOf(MonitoredApp("test", "Test")))
        repository = ScreenGuardRepository(AppDatabase.getDatabase(context).dao())
        repository.clearHistory()
    }

    @After
    fun teardown() {
        SessionManager.setMasterGuardEnabled(false)
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun extensionsAreRecordedImmediatelyAndPauseCancelsTimers() = runBlocking {
        SessionManager.extendSession("test", "Test", 2, repository)
        dispatcher.scheduler.runCurrent()
        val history = repository.allSessions.first { rows -> rows.any { it.actionTaken == SessionAction.EXTENDED } }
        assertEquals(1, history.count { it.actionTaken == SessionAction.EXTENDED })
        assertTrue(SessionManager.hasValidActiveTimer("test"))
        SessionManager.setMasterGuardEnabled(false)
        assertTrue(SessionManager.activeTimers.value.isEmpty())
        assertEquals(SessionState.Idle, SessionManager.sessionState.value)
    }

    @Test
    fun absentConsentPreventsMonitoringAndPrompts() {
        SessionManager.withdrawConsent()
        SessionManager.setMasterGuardEnabled(true)
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        assertFalse(SessionManager.startPrompt("test", "Test"))
        SessionManager.startSession("test", "Test", 5, repository)
        assertTrue(SessionManager.activeTimers.value.isEmpty())
    }

    @Test
    fun disablingAnAppCancelsItsActiveTimer() {
        SessionManager.startSession("test", "Test", 5, repository)
        SessionManager.setMonitoredApps(listOf(MonitoredApp("test", "Test", isEnabled = false)))
        assertFalse(SessionManager.hasValidActiveTimer("test"))
    }

    @Test
    fun settingsAndUninstallRoutesCannotBeGuarded() {
        assertTrue(AppSafety.isProtected("com.android.settings", "in.hichauhan.nudge"))
        assertTrue(AppSafety.isProtected("com.google.android.packageinstaller", "in.hichauhan.nudge"))
        assertTrue(AppSafety.isProtected("in.hichauhan.nudge", "in.hichauhan.nudge"))
        assertFalse(AppSafety.isProtected("test", "in.hichauhan.nudge"))
    }

    @Test
    fun restoringAnExtensionPreservesItsIdentityAndDoesNotDoubleCount() = runBlocking {
        SessionManager.extendSession("test", "Test", 5, repository)
        dispatcher.scheduler.runCurrent()
        repository.allSessions.first { rows -> rows.any { it.actionTaken == SessionAction.EXTENDED } }
        val before = SessionManager.activeTimers.value.getValue("test")
        SessionManager.stopForProcessRecreationTest()
        SessionManager.init(context)
        dispatcher.scheduler.runCurrent()
        val restored = SessionManager.activeTimers.value.getValue("test")
        assertEquals(before.eventId, restored.eventId)
        assertEquals(SessionAction.EXTENDED, restored.actionLabel)
        assertEquals(1, SessionManager.extensionCountFor("test"))
        assertEquals(1, repository.allSessions.first().count { it.actionTaken == SessionAction.EXTENDED })
    }

    @Test
    fun clearingHistoryWaitsForPendingWritesAndPausesMonitoring() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionManager.startSession("test", "Test", 5, repository)
        repository.allSessions.first { rows -> rows.isNotEmpty() }
        SessionManager.clearHistory(repository)
        assertTrue(repository.allSessions.first().isEmpty())
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        assertTrue(SessionManager.activeTimers.value.isEmpty())
    }
}