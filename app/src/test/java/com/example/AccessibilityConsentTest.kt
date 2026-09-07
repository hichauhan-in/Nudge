package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.AccessibilityConsent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AccessibilityConsentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun existingOnboardingDoesNotImplyConsent() {
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("first_launch_done", true).commit()
        assertFalse(AccessibilityConsent.isAccepted(context))
        assertFalse(AccessibilityConsent.hasDecision(context))
    }

    @Test
    fun declineDoesNotAuthorizeMonitoring() {
        AccessibilityConsent.decline(context)
        assertTrue(AccessibilityConsent.hasDecision(context))
        assertFalse(AccessibilityConsent.isAccepted(context))
    }

    @Test
    fun affirmativeConsentCanBeWithdrawn() {
        assertTrue(AccessibilityConsent.accept(context))
        assertTrue(AccessibilityConsent.isAccepted(context))
        AccessibilityConsent.decline(context)
        assertFalse(AccessibilityConsent.isAccepted(context))
    }

    @Test
    fun outdatedDisclosureRequiresNewConsent() {
        AccessibilityConsent.accept(context)
        context.getSharedPreferences(AccessibilityConsent.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(AccessibilityConsent.VERSION_KEY, AccessibilityConsent.VERSION - 1).commit()
        assertFalse(AccessibilityConsent.isAccepted(context))
    }
}