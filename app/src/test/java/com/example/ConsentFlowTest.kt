package com.example

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.example.domain.AccessibilityConsent
import com.example.domain.SessionManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstallDeclineNeverOpensSettingsOrEnablesMonitoring() {
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("View Permission Details").performClick()
        compose.onNodeWithText("Decline").performClick()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertFalse(SessionManager.isMasterGuardEnabled.value)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
        compose.onNodeWithText("Skip / Proceed").assertIsDisplayed()
    }

    @Test
    fun affirmativeButtonPrecedesAndroidPermissionRequest() {
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithTag("accessibility-intro").assertIsDisplayed()
        compose.onNodeWithText("Agree And Enable").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.hasDecision(compose.activity))
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertFalse(SessionManager.isMasterGuardEnabled.value)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
        compose.onNodeWithText("View Permission Details").performClick()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
        compose.onNodeWithText("Agree And Enable").performClick()
        compose.runOnIdle {
            assertTrue(AccessibilityConsent.isAccepted(compose.activity))
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity?.action)
        }
    }

    @Test
    fun leavingForHomeAndReturningDoesNotGrantConsent() {
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("View Permission Details").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Agree And Enable").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertFalse(SessionManager.isMasterGuardEnabled.value)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
    }

    @Test
    fun configuredUserOpensMonitoringSettingsWithoutRepeatingConsent() {
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("View Permission Details").performClick()
        compose.onNodeWithText("Agree And Enable").performClick()
        compose.runOnIdle {
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity?.action)
            Settings.Secure.putString(compose.activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                android.content.ComponentName(compose.activity, com.example.service.AppAccessibilityService::class.java).flattenToString())
            com.example.service.AppAccessibilityService.connected.value = true
        }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Skip / Proceed").performClick()
            compose.onNodeWithText("Monitoring Status").assertDoesNotExist()
            compose.onNodeWithText("Configure").performClick()
            compose.onNodeWithText("Guard System Service").performClick()
            compose.onNodeWithText("Monitoring Status").assertIsDisplayed()
            compose.onNodeWithText("Agree And Enable").assertDoesNotExist()
            compose.onNodeWithText("Decline").assertDoesNotExist()
            compose.runOnIdle { assertNull(shadowOf(compose.activity).nextStartedActivity) }
            compose.onNodeWithText("Disable Guard Service").performScrollTo().performClick()
            compose.onNodeWithText("Disable Guard Service").performClick()
            compose.runOnIdle {
                assertFalse(AccessibilityConsent.isAccepted(compose.activity))
                assertFalse(SessionManager.isMasterGuardEnabled.value)
            }
            compose.onNodeWithText("Enable Guard System Service").performClick()
            compose.onNodeWithText("Review Accessibility Access").performClick()
            compose.onNodeWithText("View Permission Details").performClick()
            compose.onNodeWithText("Agree And Enable").assertIsDisplayed()
            compose.onNodeWithText("Decline").assertIsDisplayed()
        } finally {
            com.example.service.AppAccessibilityService.connected.value = false
        }
    }

    @Test
    fun introductorySplashCanBeSkippedWithoutEnablingAccess() {
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Not Now").performClick()
        compose.onNodeWithText("Skip / Proceed").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertFalse(SessionManager.isMasterGuardEnabled.value)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
    }

    @Test
    fun leavingAndRecreatingTheIntroDoesNotAdvanceOrAuthorizeAccess() {
        compose.onNodeWithText("Continue").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("accessibility-intro").assertIsDisplayed()
        compose.onNodeWithText("View Permission Details").assertIsDisplayed()
        compose.onNodeWithText("Not Now").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertFalse(SessionManager.isMasterGuardEnabled.value)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
    }

    @Test
    fun backFromIntroDoesNotRequestPermission() {
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Skip / Proceed").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(AccessibilityConsent.isAccepted(compose.activity))
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
    }
}