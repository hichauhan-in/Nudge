package com.example

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.domain.*
import com.example.service.DurationSelectionScreen
import com.example.service.ExpirySheet
import com.example.ui.*
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
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun initializeRules() {
        FocusSettings.init(context)
        FocusSettings.update(FocusConfiguration())
    }

    @Test fun lightDashboardRendersWithAnAccessibleMonitoringSwitch() {
        val repository = ScreenGuardRepository(AppDatabase.getDatabase(context).dao())
        val model = MainViewModel(repository, context)
        compose.setContent {
            MyApplicationTheme(darkTheme = false) { Surface(color = GuardBlack) { DashboardView(model, false, context, {}) } }
        }
        compose.onNodeWithContentDescription("Automatic monitoring").assertIsOff()
        compose.onNodeWithText("Monitoring status").assertIsDisplayed()
        compose.onRoot().savePreview("dashboard-light")
    }

    @Test fun darkDashboardKeepsExistingTheme() {
        val model = MainViewModel(ScreenGuardRepository(AppDatabase.getDatabase(context).dao()), context)
        compose.setContent { MyApplicationTheme(darkTheme = true) { Surface(color = GuardBlack) { DashboardView(model, false, context, {}) } } }
        compose.onRoot().savePreview("dashboard-dark")
    }

    @Test fun longAppNameAndLargeTextKeepTimerActionsReachable() {
        var selected = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyApplicationTheme(darkTheme = false) {
                    Surface { Box(Modifier.width(320.dp).height(620.dp).padding(16.dp)) {
                        DurationSelectionScreen(packageName = "test", appName = "A very long application name for a narrow phone screen",
                            preferredMinutes = 15, onSelected = { selected = it }, onBypass = {}, onMinimize = {})
                    } }
                }
            }
        }
        compose.onNodeWithText("Start timer").performScrollTo().assertIsDisplayed()
        compose.onRoot().savePreview("prompt-light-large-text")
        compose.onNodeWithText("Start timer").performClick()
        assertEquals(15, selected)
    }

    @Test fun extensionLimitHidesExtensionControlsButKeepsExitActions() {
        compose.setContent {
            MyApplicationTheme { Surface { ExpirySheet(extensionsAllowed = false, appName = "Example", packageName = "test", onMinimize = {}, onExtend = {}, onNoTimer = {}) } }
        }
        compose.onNodeWithText("Extend timer").assertDoesNotExist()
        compose.onNodeWithText("Close app").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue without timer").performScrollTo().assertIsDisplayed()
        compose.onRoot().savePreview("expiry-extension-limit")
    }

    @Test fun supportMenuFitsNarrowScreenWithoutAHorizontalButtonRow() {
        compose.setContent {
            MyApplicationTheme {
                Box(Modifier.width(288.dp).height(400.dp)) {
                    com.example.service.SupportMenu(true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("UPI").assertIsDisplayed()
        compose.onNodeWithText("Ko-fi").assertIsDisplayed()
        compose.onNodeWithText("Playto unavailable").assertIsNotEnabled()
    }

    @Test fun profileEditorAndBudgetControlsFitNarrowSurfaces() {
        compose.setContent {
            MyApplicationTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                ScheduleProfilesControls()
                SharedBudgetControls(listOf(AppDisplayItem("test", "Example app", true, true)))
                AppRuleControls("test")
            } } }
        }
        compose.onNodeWithText("Schedules and profiles").performClick()
        compose.onNodeWithContentDescription("Edit Work").performClick()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onRoot().savePreview("profile-editor")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Shared budgets and goals").performScrollTo().performClick()
        compose.onNodeWithText("Add shared budget").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onRoot().savePreview("shared-budget-editor")
    }

    @Test
    @Config(qualifiers = "hi-rIN-w320dp-h640dp")
    fun hindiDisclosureHasVisibleConsentAndRefusalWithLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyApplicationTheme(darkTheme = false) { AccessibilityDisclosure({}, {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.accessibility_agree)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.accessibility_decline)).assertIsDisplayed()
        compose.onRoot().savePreview("disclosure-hindi-large-text")
    }
}