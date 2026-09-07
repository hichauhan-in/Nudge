package com.example

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.domain.AccessibilityConsent
import com.example.domain.SessionManager
import com.example.service.AppAccessibilityService
import com.example.ui.MonitoringControls
import com.example.ui.theme.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitoringSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        SessionManager.stopForProcessRecreationTest()
        AccessibilityConsent.accept(context)
        SessionManager.init(context)
        SessionManager.setMasterGuardEnabled(true)
        AppAccessibilityService.connected.value = true
    }

    @After fun teardown() {
        SessionManager.withdrawConsent()
        SessionManager.stopForProcessRecreationTest()
        AppAccessibilityService.connected.value = false
    }

    @Test fun enabledServiceShowsStatusAndDisableWithoutRequestingConsentAgain() {
        var requested = false
        compose.setContent { MyApplicationTheme { Surface(color = GuardBlack) { MonitoringControls(true) { requested = true } } } }
        compose.onNodeWithText("Guard System Service").performClick()
        compose.onNodeWithText("Monitoring status").assertIsDisplayed()
        compose.onNodeWithText("Agree and enable").assertDoesNotExist()
        compose.onNodeWithText("Decline").assertDoesNotExist()
        compose.onNodeWithText("Review accessibility access").assertDoesNotExist()
        compose.onNodeWithContentDescription("Android accessibility: Enabled").assertIsDisplayed()
        compose.onRoot().savePreview("guard-service-status-dialog")
        compose.onNodeWithText("Preview prompt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disable guard service").performScrollTo().assertIsDisplayed()
        assertFalse(requested)
        compose.onNodeWithTag("monitoring-recovery-section").performScrollTo().assertIsDisplayed()
        compose.onRoot().savePreview("guard-service-recovery-section")
    }

    @Test fun disablingRequiresConfirmationAndWithdrawsConsent() {
        compose.setContent { MyApplicationTheme { MonitoringControls(true) {} } }
        compose.onNodeWithText("Guard System Service").performClick()
        compose.onNodeWithText("Disable guard service").performScrollTo().performClick()
        compose.onNodeWithText("Disable guard service?").assertIsDisplayed()
        assertTrue(AccessibilityConsent.isAccepted(context))
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(AccessibilityConsent.isAccepted(context))
        compose.onNodeWithText("Disable guard service").performScrollTo().performClick()
        compose.onNodeWithText("Disable guard service").performClick()
        assertFalse(AccessibilityConsent.isAccepted(context))
        assertFalse(SessionManager.isMasterGuardEnabled.value)
    }

    @Test fun missingConsentIsNotGrantedByTheServiceBlock() {
        SessionManager.withdrawConsent()
        AppAccessibilityService.connected.value = false
        var requested = false
        compose.setContent { MyApplicationTheme { MonitoringControls(false) { requested = true } } }
        compose.onNodeWithText("Enable Guard System Service").performClick()
        assertFalse(requested)
        compose.onNodeWithText("Review accessibility access").performClick()
        assertTrue(requested)
        assertFalse(AccessibilityConsent.isAccepted(context))
        assertFalse(SessionManager.isMasterGuardEnabled.value)
    }

    @Test fun acceptedButDisconnectedServiceOffersAndroidSettingsNotAnotherDisclosure() {
        AppAccessibilityService.connected.value = false
        compose.setContent { MyApplicationTheme { MonitoringControls(false) {} } }
        compose.onNodeWithText("Enable Guard System Service").performClick()
        compose.onNodeWithText("Open Android accessibility settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review accessibility access").assertDoesNotExist()
        compose.onNodeWithText("Agree and enable").assertDoesNotExist()
    }

    @Test fun recoveryIsAvailableWithoutConsentAndResetStillNeedsConfirmation() {
        SessionManager.withdrawConsent()
        AppAccessibilityService.connected.value = false
        compose.setContent { MyApplicationTheme { MonitoringControls(false) {} } }
        compose.onNodeWithText("Enable Guard System Service").performClick()
        listOf("monitoring-status-section", "monitoring-controls-section", "monitoring-reminders-section", "monitoring-recovery-section").forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithText("Open Android app settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear all local app data").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Erase all local data?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear all local app data").performScrollTo().assertIsDisplayed()
        assertFalse(AccessibilityConsent.isAccepted(context))
    }

    @Test fun androidRecoveryOpensThisAppsSettingsWithoutDisablingMonitoring() {
        compose.setContent { MyApplicationTheme { MonitoringControls(true) {} } }
        compose.onNodeWithText("Guard System Service").performClick()
        compose.onNodeWithText("Open Android app settings").performScrollTo().performClick()
        val intent = org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertTrue(AccessibilityConsent.isAccepted(context))
        assertTrue(SessionManager.isMasterGuardEnabled.value)
    }

    @Test fun pauseAndResumeStayInMonitoringWithoutAnotherConsentRequest() {
        compose.setContent { MyApplicationTheme { MonitoringControls(true) {} } }
        compose.onNodeWithText("Guard System Service").performClick()
        compose.onNodeWithText("Pause monitoring").performScrollTo().performClick()
        assertFalse(SessionManager.isMasterGuardEnabled.value)
        assertTrue(AccessibilityConsent.isAccepted(context))
        compose.onNodeWithText("Resume now").performScrollTo().performClick()
        assertTrue(SessionManager.isMasterGuardEnabled.value)
        compose.onNodeWithText("Agree and enable").assertDoesNotExist()
    }
}