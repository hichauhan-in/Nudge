package com.example

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.FocusSettings
import com.example.data.SessionHistory
import com.example.domain.FocusConfiguration
import com.example.domain.HistoryDates
import com.example.domain.SessionAction
import com.example.ui.TrendsPanel
import com.example.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrendsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setup() {
        FocusSettings.init(ApplicationProvider.getApplicationContext<Context>())
        FocusSettings.update(FocusConfiguration(weeklyGoalMinutes = 420))
    }

    private fun showTrends(dark: Boolean) {
        val today = LocalDate.now()
        val logs = listOf(0L to 3600, 8L to 1800, 20L to 600).mapIndexed { index, (offset, seconds) ->
            SessionHistory(id = index, packageName = "test", appName = "Example", startTime = HistoryDates.dayBounds(today.minusDays(offset)).first,
                durationSeconds = seconds, actionTaken = SessionAction.USAGE)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyApplicationTheme(darkTheme = dark) { Surface(color = GuardBlack) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) { TrendsPanel(DashboardStats(recentLogs = logs, referenceDate = today)) }
                } }
            }
        }
    }

    private fun verifyContents() {
        val card = compose.onNodeWithTag("trends-card").fetchSemanticsNode().boundsInRoot
        listOf("Tracked-time trends", "60 min", "30 min", "100 min", "1 / 7", "Weekly goal: 60 / 420 min recorded").forEach { text ->
            val node = compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(node.left >= card.left && node.right <= card.right)
            assertTrue(node.top >= card.top && node.bottom <= card.bottom)
        }
        val label = compose.onNodeWithText("Last 7 days").fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("60 min").fetchSemanticsNode().boundsInRoot
        assertTrue(label.right < value.left)
    }

    @Test fun darkTrendsCardFitsLargeTextAndPreservesTotals() {
        showTrends(true)
        verifyContents()
        compose.onRoot().savePreview("trends-card-dark-large-text")
    }

    @Test fun lightTrendsCardFitsLargeTextAndPreservesTotals() {
        showTrends(false)
        verifyContents()
        compose.onRoot().savePreview("trends-card-light-large-text")
    }
}