package com.example

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.domain.*
import com.example.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScrollTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ViewModelStore()
    private lateinit var database: AppDatabase
    private lateinit var model: MainViewModel

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val start = HistoryDates.dayBounds(LocalDate.now()).first
        database.dao().insertSessions((1..300).map { index ->
            SessionHistory(id = index, packageName = "test", appName = "Example $index", startTime = start + index * 1000L,
                durationSeconds = 0, actionTaken = SessionAction.STARTED)
        })
        model = ViewModelProvider(store, ViewModelFactory(ScreenGuardRepository(database.dao()), context))[MainViewModel::class.java]
        FocusSettings.init(context)
        FocusSettings.update(FocusConfiguration())
        compose.setContent {
            MyApplicationTheme { Surface(color = GuardBlack) { DashboardView(model, true, context, {}) } }
        }
        compose.waitUntil(5_000) { model.statisticsState.value.recentLogs.size == 300 }
    }

    @After fun teardown() {
        store.clear()
        database.close()
    }

    @Test fun historySharesOneLazyFeedAndOnlyVisibleRowsAreComposed() {
        val feed = compose.onNodeWithTag("dashboard-feed")
        feed.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        compose.onNodeWithTag("intercept-log:1").assertDoesNotExist()
        feed.performScrollToKey("log:1")
        compose.onNodeWithTag("intercept-log:1").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        val renderedRows = compose.onAllNodes(SemanticsMatcher("intercept rows") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("intercept-log:")
        }).fetchSemanticsNodes()
        assertTrue(renderedRows.size in 1..20)
        compose.onNodeWithTag("trends-card").assertDoesNotExist()
        feed.performScrollToKey("header")
        compose.onNodeWithContentDescription("Automatic monitoring").assertIsDisplayed()
        compose.onNodeWithTag("intercept-log:1").assertDoesNotExist()
    }

    @Test fun dayAndCarouselSelectionSurviveScrollingOffScreen() {
        val feed = compose.onNodeWithTag("dashboard-feed")
        feed.performScrollToKey("day-selector")
        val description = "Yesterday, 0 minutes, ${LocalDate.now().minusDays(1)}"
        compose.onNodeWithContentDescription(description).performClick()
        feed.performScrollToKey("insights")
        compose.onNodeWithTag("dashboard-insights").performScrollToIndex(151)
        compose.onNodeWithText("APP USAGE").assertIsDisplayed()
        feed.performScrollToKey("empty-intercepts")
        compose.onNodeWithText("No intercepts on this day.").assertIsDisplayed()
        feed.performScrollToKey("insights")
        val matchingPages = compose.onAllNodesWithText("APP USAGE")
        assertTrue(matchingPages.fetchSemanticsNodes().indices.any { matchingPages[it].isDisplayed() })
        feed.performScrollToKey("day-selector")
        compose.onNodeWithContentDescription(description).assertIsSelected()
    }
}