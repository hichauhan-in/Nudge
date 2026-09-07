package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.SessionHistory
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
class DashboardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun zeroCountsStayVisibleInsideFixedCard() {
        compose.setContent { MyApplicationTheme { InterventionBehaviorCard(DashboardStats(), 0) } }
        compose.onNodeWithTag("behavior-card").assertHeightIsEqualTo(208.dp)
        listOf("Resisted", "Extended", "Bypassed").forEach {
            compose.onNodeWithContentDescription("$it: 0").assertIsDisplayed()
        }
        val score = compose.onNodeWithTag("behavior-score").fetchSemanticsNode().boundsInRoot
        val counts = compose.onNodeWithTag("behavior-counts").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("behavior-card").fetchSemanticsNode().boundsInRoot
        assertTrue(counts.top > score.bottom)
        assertTrue(counts.bottom < card.bottom)
        compose.onAllNodesWithText("0", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            assertTrue(node.boundsInRoot.height > 0)
            assertTrue(node.boundsInRoot.bottom < card.bottom)
        }
        compose.onNodeWithTag("behavior-card").savePreview("behavior-empty")
    }

    @Test
    fun narrowSarcasticCardFitsAtLargerFontScale() {
        val logs = listOf("CLOSED", "EXTENDED", "BYPASSED").mapIndexed { index, action ->
            SessionHistory(id = index, packageName = "test", appName = "Test", durationSeconds = 0, actionTaken = action)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MyApplicationTheme {
                    Box(Modifier.width(288.dp)) { InterventionBehaviorCard(DashboardStats(recentLogs = logs), 0, isSarcasticMode = true) }
                }
            }
        }
        compose.onNodeWithTag("behavior-card").assertHeightIsEqualTo(208.dp)
        listOf("Resisted", "Extended", "Bypassed").forEach {
            compose.onNodeWithContentDescription("$it: 1").assertIsDisplayed()
        }
        val score = compose.onNodeWithTag("behavior-score").fetchSemanticsNode().boundsInRoot
        val counts = compose.onNodeWithTag("behavior-counts").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("behavior-card").fetchSemanticsNode().boundsInRoot
        assertTrue(counts.top >= score.bottom)
        assertTrue(counts.bottom < card.bottom)
        compose.onNodeWithTag("behavior-card").savePreview("behavior-narrow-sarcastic")
    }

    @Test
    fun sharedDaySelectorHasSevenLabeledChoicesWithoutGrowing() {
        var selected = 0
        val stats = DashboardStats()
        compose.setContent { MyApplicationTheme { DaySelectorBars(stats, selected) { selected = it } } }
        compose.onAllNodes(isSelectable()).assertCountEquals(7)
        val yesterday = stats.referenceDate.minusDays(1)
        compose.onNodeWithContentDescription("Yesterday, 0 minutes, $yesterday").performClick()
        assertEquals(1, selected)
        compose.onRoot().assertHeightIsEqualTo(48.dp)
        compose.onRoot().savePreview("day-selector")
    }
}