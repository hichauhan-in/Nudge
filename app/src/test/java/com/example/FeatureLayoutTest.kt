package com.example

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        compose.onNodeWithText("Monitoring Status").assertDoesNotExist()
        compose.onNodeWithText("Preview Prompt").assertDoesNotExist()
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
        compose.onNodeWithText("Start Timer").performScrollTo().assertIsDisplayed()
        compose.onRoot().savePreview("prompt-light-large-text")
        compose.onNodeWithText("Start Timer").performClick()
        assertEquals(15, selected)
    }

    @Test fun extensionLimitHidesExtensionControlsButKeepsExitActions() {
        compose.setContent {
            MyApplicationTheme { Surface { ExpirySheet(extensionsAllowed = false, appName = "Example", packageName = "test", onMinimize = {}, onExtend = {}, onNoTimer = {}) } }
        }
        compose.onNodeWithText("Extend Timer").assertDoesNotExist()
        compose.onNodeWithText("Close App").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue Without Timer").performScrollTo().assertIsDisplayed()
        compose.onRoot().savePreview("expiry-extension-limit")
    }

    @Test fun supportOptionsFitLeftOfAnUnmovingCoffeeButton() {
        compose.setContent {
            var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            MyApplicationTheme {
                Box(Modifier.width(288.dp).height(400.dp), contentAlignment = androidx.compose.ui.Alignment.BottomEnd) {
                    com.example.service.SupportMenu(expanded, { expanded = !expanded }, { expanded = false }, {}, {})
                }
            }
        }
        val initial = compose.onNodeWithTag("support-toggle").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("support-toggle").performClick()
        val anchor = compose.onNodeWithTag("support-toggle").fetchSemanticsNode().boundsInRoot
        val upi = compose.onNodeWithTag("support-upi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val kofi = compose.onNodeWithTag("support-kofi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(initial, anchor)
        assertTrue(kofi.right < upi.left && upi.right < anchor.left)
        assertEquals(anchor.top, upi.top)
        assertEquals(anchor.top, kofi.top)
        listOf("support-toggle", "support-upi", "support-kofi").forEach {
            compose.onNodeWithTag(it).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            compose.onNodeWithTag("$it-icon", useUnmergedTree = true).assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onRoot().savePreview("support-options-left")
        compose.onNodeWithTag("support-toggle").performClick()
        compose.onNodeWithTag("support-upi").assertDoesNotExist()
        compose.onNodeWithTag("support-kofi").assertDoesNotExist()
    }

    @Test fun profileEditorAndBudgetControlsFitNarrowSurfaces() {
        compose.setContent {
            MyApplicationTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                ScheduleProfilesControls()
                SharedBudgetControls(listOf(AppDisplayItem("test", "Example app", true, true)))
                AppRuleControls("test")
            } } }
        }
        compose.onNodeWithTag("setting-profiles").assertIsDisplayed()
        compose.onNodeWithTag("setting-shared-budgets").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Work").assertDoesNotExist()
        compose.onNodeWithText("Schedules And Profiles").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Edit Work").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onRoot().savePreview("profile-editor")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Edit Work").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Shared Budgets And Goals").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Add Shared Budget").assertIsDisplayed().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
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