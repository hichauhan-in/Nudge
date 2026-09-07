package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TimedPauseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        SessionManager.stopForProcessRecreationTest()
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        AccessibilityConsent.accept(context)
        SessionManager.init(context)
        SessionManager.setMasterGuardEnabled(true)
    }

    @After fun teardown() {
        SessionManager.setMasterGuardEnabled(false)
        SessionManager.stopForProcessRecreationTest()
        Dispatchers.resetMain()
    }

    @Test fun pauseRestoresAndResumesAtItsMonotonicDeadline() {
        SessionManager.pauseFor(15)
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        val deadline = SessionManager.pauseUntil.value
        assertNotNull(deadline)
        SessionManager.stopForProcessRecreationTest()
        SessionManager.init(context)
        assertEquals(deadline, SessionManager.pauseUntil.value)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(16))
        SessionManager.resumeIfDue()
        assertTrue(SessionManager.isMasterGuardEnabled.value)
        assertNull(SessionManager.pauseUntil.value)
    }

    @Test fun withdrawalCancelsAutomaticResume() {
        SessionManager.pauseFor(15)
        SessionManager.withdrawConsent()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(16))
        SessionManager.resumeIfDue()
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        assertNull(SessionManager.pauseUntil.value)
    }

    @Test fun manualPauseReplacesTimedPauseWithoutResumingLater() {
        SessionManager.pauseFor(15)
        SessionManager.setMasterGuardEnabled(false)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(16))
        SessionManager.resumeIfDue()
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        assertNull(SessionManager.pauseUntil.value)
    }
}