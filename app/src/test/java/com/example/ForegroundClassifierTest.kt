package com.example

import com.example.domain.ForegroundClassifier
import com.example.domain.ForegroundKind
import com.example.domain.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundClassifierTest {
    @Test
    fun launchableGoogleAndLensAppsAreNotIgnored() {
        listOf("com.google.android.googlequicksearchbox", "com.google.android.apps.lens", "com.miui.securitycenter").forEach {
            assertEquals(ForegroundKind.APP, ForegroundClassifier.classify(it, "MainActivity", emptySet()))
        }
    }

    @Test
    fun ordinaryActivityNamesCannotEscapeMonitoring() {
        assertEquals(ForegroundKind.APP, ForegroundClassifier.classify("test.notifications", "NotificationActivity", emptySet()))
        assertEquals(ForegroundKind.APP, ForegroundClassifier.classify("test.accessibility", "PopupActivity", emptySet()))
    }

    @Test
    fun keyboardInputAndNotificationShadeAreDifferent() {
        assertEquals(ForegroundKind.INPUT_METHOD, ForegroundClassifier.classify("keyboard", "InputMethod", setOf("keyboard")))
        assertEquals(ForegroundKind.APP, ForegroundClassifier.classify("keyboard", "SettingsActivity", setOf("keyboard")))
        assertEquals(ForegroundKind.SYSTEM_OVERLAY, ForegroundClassifier.classify("com.android.systemui", "NotificationShade", emptySet()))
    }

    @Test
    fun historyLabelsDescribeDecisionsInsteadOfDuration() {
        assertEquals("Started", SessionAction.label(SessionAction.STARTED))
        assertEquals("Resisted", SessionAction.label(SessionAction.CLOSED))
        assertEquals("Extended", SessionAction.label(SessionAction.EXTENDED))
        assertEquals("Bypassed", SessionAction.label(SessionAction.BYPASSED))
    }
}