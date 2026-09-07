package com.example

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.FocusSettings
import com.example.data.ScreenGuardRepository
import com.example.data.SessionHistory
import com.example.domain.HistoryDates
import com.example.domain.SessionAction
import com.example.ui.theme.GuardBlack
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DashboardGestureTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ViewModelStore()
    private lateinit var database: AppDatabase

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val start = HistoryDates.dayBounds(LocalDate.now()).first
        database.dao().insertSessions((1..300).map { index ->
            SessionHistory(id = index, packageName = "scroll.test", appName = "Example $index",
                startTime = start + index * 1000L, durationSeconds = 0, actionTaken = SessionAction.STARTED)
        })
        val model = ViewModelProvider(store, ViewModelFactory(ScreenGuardRepository(database.dao()), context))[MainViewModel::class.java]
        FocusSettings.init(context)
        compose.setContent {
            MyApplicationTheme { Surface(color = GuardBlack) { DashboardView(model, true, context, {}) } }
        }
        compose.waitUntil(10_000) { model.statisticsState.value.recentLogs.size == 300 }
    }

    @After fun teardown() {
        compose.runOnIdle { store.clear() }
        database.close()
    }

    @Test fun verticalSwipeMovesTheSameFeedOverCardsAndHistory() {
        val feed = compose.onNodeWithTag("dashboard-feed")
        val beforeCards = feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        feed.performTouchInput { swipeUp(durationMillis = 350) }
        compose.waitForIdle()
        assertTrue(feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > beforeCards)
        feed.performScrollToKey("log:150")
        val beforeHistory = feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        feed.performTouchInput { swipeUp(durationMillis = 350) }
        compose.waitForIdle()
        assertTrue(feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > beforeHistory)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
    }

    @Test fun horizontalCarouselGesturePreservesVerticalScrolling() {
        val feed = compose.onNodeWithTag("dashboard-feed")
        feed.performScrollToKey("insights")
        val carousel = compose.onNodeWithTag("dashboard-insights")
        val before = carousel.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        carousel.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(carousel.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > before)
        feed.performTouchInput { swipeUp() }
        compose.waitForIdle()
        feed.performScrollToKey("log:1")
        compose.onNodeWithTag("intercept-log:1").assertIsDisplayed()
    }
}