package com.example

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppearanceControls
import com.example.ui.PrivacyControls
import com.example.ui.RuleMenu
import com.example.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsStyleTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetAppearance() {
        context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "dark").putString("app_language", "").commit()
    }

    @Test fun appearanceUsesOutlinedBlocksAndThemeOpensADialog() {
        compose.setContent {
            MyApplicationTheme { Surface(color = GuardBlack) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) { AppearanceControls() }
            } }
        }
        compose.onNodeWithTag("setting-theme").assertIsDisplayed()
        compose.onNodeWithTag("setting-language").assertIsDisplayed()
        compose.onNodeWithText("Theme").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Nudge Light").performClick()
        assertEquals("light", context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE).getString("theme_mode", null))
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onRoot().savePreview("appearance-settings-blocks")
    }

    @Test fun languageOpensAPopupAndCancelDoesNotChangeLanguage() {
        compose.setContent { MyApplicationTheme { AppearanceControls() } }
        compose.onNodeWithText("Language").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("Hindi (core screens)").assertIsDisplayed()
        compose.onRoot().savePreview("language-choice-dialog")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("", context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE).getString("app_language", null))
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun ruleSelectionUsesPopupAndKeepsCallbackBehavior() {
        var selected = 5
        compose.setContent {
            MyApplicationTheme { RuleMenu("Preferred Timer", selected, listOf(5 to "5 min", 10 to "10 min")) { selected = it } }
        }
        compose.onNodeWithText("5 min").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("10 min").performClick()
        assertEquals(10, selected)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun privacyActionsUseBlocksAndKeepTheirConfirmations() {
        compose.setContent {
            MyApplicationTheme { Surface(color = GuardBlack) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PrivacyControls() }
            } }
        }
        listOf("setting-data-transfer", "setting-privacy", "setting-withdraw").forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithTag("setting-recovery").assertDoesNotExist()
        compose.onNodeWithText("Pause, reset or uninstall").assertDoesNotExist()
        listOf("setting-export", "setting-backup", "setting-restore").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onRoot().savePreview("privacy-settings-blocks")
        compose.onNodeWithText("Backup And Restore").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        listOf("setting-export", "setting-backup", "setting-restore").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        compose.onRoot().savePreview("backup-and-restore-popup")
        compose.onNodeWithText("Export History (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Export Local History?").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("setting-export").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Privacy And Data Policy").performScrollTo().performClick()
        compose.onNode(hasText("Privacy And Data Policy") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Withdraw Accessibility Consent").performScrollTo().performClick()
        compose.onNodeWithText("Withdraw Consent?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun configurationBlocksFitTheExistingScreenAtLargerText() {
        com.example.data.FocusSettings.init(context)
        com.example.data.FocusSettings.update(com.example.domain.FocusConfiguration())
        val model = MainViewModel(com.example.data.ScreenGuardRepository(com.example.data.AppDatabase.getDatabase(context).dao()), context)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MyApplicationTheme { Surface(color = GuardBlack) { SettingsView(model, true, context, {}, {}) } }
            }
        }
        compose.onNodeWithTag("setting-guard-service").assertIsDisplayed()
        compose.onRoot().savePreview("configure-system-blocks")
        listOf("setting-profiles", "setting-shared-budgets", "setting-theme", "setting-language").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertWidthIsEqualTo(288.dp)
        }
        compose.onRoot().savePreview("configure-appearance-blocks")
        listOf("setting-data-transfer", "setting-privacy", "setting-withdraw").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertWidthIsEqualTo(288.dp)
        }
        compose.onRoot().savePreview("configure-recovery-blocks")
    }
}