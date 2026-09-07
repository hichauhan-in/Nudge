package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyTwoPaymentOptionsAreAvailable() {
        var chosen = ""
        compose.setContent {
            MyApplicationTheme {
                SupportOptionsDialog({}, { chosen = "UPI" }, { chosen = "Ko-fi" })
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithText("UPI").assertIsDisplayed().performClick()
        assertEquals("UPI", chosen)
        compose.onNodeWithText("Ko-Fi").assertIsDisplayed().performClick()
        assertEquals("Ko-fi", chosen)
        compose.onRoot().savePreview("support-two-options")
    }
}