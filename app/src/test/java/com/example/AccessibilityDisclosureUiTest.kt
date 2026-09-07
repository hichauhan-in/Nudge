package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.AccessibilityDisclosure
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityDisclosureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disclosureHasSeparateVisibleChoicesAndNoAutomaticConsent() {
        var agreed = 0
        var declined = 0
        compose.setContent {
            MyApplicationTheme { AccessibilityDisclosure(onAgree = { agreed++ }, onDecline = { declined++ }) }
        }
        compose.onNodeWithText("Agree and enable").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, agreed)
        compose.onRoot().savePreview("accessibility-disclosure")
        compose.onNodeWithText("Decline").performClick()
        assertEquals(1, declined)
        assertEquals(0, agreed)
    }

    @Test
    fun onlyAgreeInvokesConsentCallback() {
        var agreed = false
        compose.setContent {
            MyApplicationTheme { AccessibilityDisclosure(onAgree = { agreed = true }, onDecline = {}) }
        }
        compose.onNodeWithText("Agree and enable").performClick()
        assertTrue(agreed)
    }

    @Test
    fun backDeclinesAndCannotGrantConsent() {
        var agreed = false
        var declined = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyApplicationTheme { AccessibilityDisclosure(onAgree = { agreed = true }, onDecline = { declined = true }) }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        assertTrue(declined)
        assertFalse(agreed)
    }

    @Test
    fun bothChoicesRemainVisibleWithLargeText() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)
            ) {
                MyApplicationTheme { AccessibilityDisclosure(onAgree = {}, onDecline = {}) }
            }
        }
        compose.onNodeWithText("Agree and enable").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.onRoot().savePreview("accessibility-disclosure-large-text")
    }

    @Test
    fun waitingAndScrollingNeverGrantConsent() {
        var agreed = 0
        var declined = 0
        compose.setContent {
            MyApplicationTheme { AccessibilityDisclosure(onAgree = { agreed++ }, onDecline = { declined++ }) }
        }
        compose.mainClock.advanceTimeBy(60_000L)
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
        compose.onNodeWithText("Agree and enable").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        assertEquals(0, agreed)
        assertEquals(0, declined)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactScreenKeepsBothConsentActionsVisibleAtDoubleFontSize() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)
            ) {
                MyApplicationTheme { AccessibilityDisclosure(onAgree = {}, onDecline = {}) }
            }
        }
        compose.onNodeWithText("Agree and enable").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        compose.onRoot().savePreview("accessibility-disclosure-compact-large-text")
    }
}