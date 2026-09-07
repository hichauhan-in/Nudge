package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.service.SupportMenu
import com.example.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w240dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoPaymentOptionsRemainLeftwardAtLargeTextAndInRtl() {
        var chosen = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                var expanded by remember { mutableStateOf(true) }
                MyApplicationTheme(darkTheme = false) { Surface(color = GuardBlack) {
                    Box(Modifier.fillMaxWidth().height(120.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                        SupportMenu(expanded, { expanded = !expanded }, { expanded = false }, { chosen = "UPI" }, { chosen = "Ko-fi" })
                    }
                } }
            }
        }
        val container = compose.onNodeWithTag("support-actions").fetchSemanticsNode().boundsInRoot
        val anchor = compose.onNodeWithTag("support-toggle").fetchSemanticsNode().boundsInRoot
        val upi = compose.onNodeWithTag("support-upi").fetchSemanticsNode().boundsInRoot
        val kofi = compose.onNodeWithTag("support-kofi").fetchSemanticsNode().boundsInRoot
        assertTrue(kofi.left >= container.left && anchor.right <= container.right)
        assertTrue(kofi.right < upi.left && upi.right < anchor.left)
        compose.onRoot().savePreview("support-left-light-compact")
        compose.onNodeWithContentDescription("UPI").performClick()
        assertEquals("UPI", chosen)
        compose.onNodeWithTag("support-upi").assertDoesNotExist()
        compose.onNodeWithTag("support-toggle").performClick()
        compose.onNodeWithContentDescription("Ko-fi").performClick()
        assertEquals("Ko-fi", chosen)
        compose.onNodeWithTag("support-kofi").assertDoesNotExist()
    }

    @Test fun backClosesOptionsWithoutSelectingAPayment() {
        var chosen = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            var expanded by remember { mutableStateOf(true) }
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MyApplicationTheme { SupportMenu(expanded, { expanded = !expanded }, { expanded = false }, { chosen = true }, { chosen = true }) }
        }
        compose.onNodeWithContentDescription("UPI").assertIsDisplayed()
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag("support-upi").assertDoesNotExist()
        compose.onNodeWithTag("support-toggle").assertIsDisplayed()
        assertFalse(chosen)
    }
}