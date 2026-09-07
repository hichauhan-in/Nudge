package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AccessibilityIntroScreen
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
class AccessibilityIntroUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun darkSplashWaitsForNavigationAndExplainsTheNextStep() {
        var continued = 0
        var skipped = 0
        compose.setContent {
            MyApplicationTheme(darkTheme = true) { AccessibilityIntroScreen({ continued++ }, { skipped++ }) }
        }
        compose.onNodeWithText("Accessibility Permission").assertIsDisplayed()
        compose.onNodeWithText("Continuing does not grant permission or enable monitoring.").assertIsDisplayed()
        compose.onNodeWithText("View Permission Details").assertIsDisplayed()
        compose.onNodeWithText("Not Now").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000L)
        assertEquals(0, continued)
        assertEquals(0, skipped)
        compose.onRoot().savePreview("accessibility-intro-dark")
        compose.onNodeWithText("View Permission Details").performClick()
        assertEquals(1, continued)
        assertEquals(0, skipped)
    }

    private fun showLargeIntro(dark: Boolean, scale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MyApplicationTheme(darkTheme = dark) { AccessibilityIntroScreen({}, {}) }
            }
        }
        val details = compose.onNodeWithText(context.getString(R.string.accessibility_intro_continue)).assertIsDisplayed()
        val skip = compose.onNodeWithText(context.getString(R.string.accessibility_intro_not_now)).assertIsDisplayed()
        val bounds = compose.onNodeWithTag("accessibility-intro").fetchSemanticsNode().boundsInRoot
        val detailsBounds = details.fetchSemanticsNode().boundsInRoot
        val skipBounds = skip.fetchSemanticsNode().boundsInRoot
        assertTrue(detailsBounds.bottom < skipBounds.top)
        assertTrue(detailsBounds.left >= bounds.left && detailsBounds.right <= bounds.right)
        assertTrue(skipBounds.bottom <= bounds.bottom)
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.accessibility_intro_title)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(titleLayouts) }
        val titleLayout = titleLayouts.single()
        assertFalse("Title bounds: ${titleLayout.size}; text: ${titleLayout.multiParagraph.width} x ${titleLayout.multiParagraph.height}; width overflow: ${titleLayout.didOverflowWidth}",
            titleLayout.hasVisualOverflow)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactLightSplashKeepsBothActionsAtDoubleFontSize() {
        showLargeIntro(false, 2f)
        compose.onRoot().savePreview("accessibility-intro-compact-light")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun landscapeSplashKeepsBothActionsReachable() {
        showLargeIntro(true, 1.3f)
        compose.onRoot().savePreview("accessibility-intro-landscape")
    }

    @Test
    @Config(qualifiers = "hi-rIN-w320dp-h640dp")
    fun hindiSplashUsesTranslatedActionsAtLargeText() {
        showLargeIntro(false, 1.5f)
        compose.onRoot().savePreview("accessibility-intro-hindi")
    }

    @Test fun settingsAndActionTitlesCapitalizeEveryWord() {
        assertEquals("Withdraw Accessibility Consent", context.getString(R.string.ui_withdraw))
        assertEquals("Schedules And Profiles", context.getString(R.string.ui_profiles))
        assertEquals("Shared Budgets And Goals", context.getString(R.string.ui_shared_goals))
        assertEquals("Backup And Restore", context.getString(R.string.ui_backup_and_restore))
        assertEquals("Privacy And Data Policy", context.getString(R.string.ui_privacy_data))
        assertEquals("Agree And Enable", context.getString(R.string.accessibility_agree))
    }
}