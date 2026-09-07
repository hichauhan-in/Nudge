package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryQueryTest {
    @Test
    fun dashboardOnlyLoadsRequestedWindowsButTotalsKeepAllHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = database.dao()
            repeat(300) { index ->
                dao.insertSession(SessionHistory(packageName = "test", appName = "Test", startTime = index.toLong(), durationSeconds = 30, actionTaken = "CLOSED"))
            }
            val rows = dao.getDashboardSessions(280, 300, 10, 17).first()
            assertEquals(27, rows.size)
            assertTrue(rows.none { it.startTime in 17L..279L })
            val totals = dao.getHistoryTotals().first()
            assertEquals(300L, totals.records)
            assertEquals(9_000L, totals.seconds)
            assertEquals(300, totals.resisted)
            assertEquals(0L, totals.firstRecordedAt)
            assertEquals(600L, dao.getWidgetTotals(280, 300).seconds)
            assertEquals(20, dao.getWidgetTotals(280, 300).resisted)
            val first = dao.getHistoryPage(0, dao.getLastHistoryId(), 100)
            val second = dao.getHistoryPage(first.last().id, dao.getLastHistoryId(), 100)
            assertEquals(100, first.size)
            assertEquals(100, second.size)
            assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun emptyDatabaseHasZeroTotals() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            assertEquals(HistoryTotals(), database.dao().getHistoryTotals().first())
            assertEquals(WidgetTotals(), database.dao().getWidgetTotals(0, Long.MAX_VALUE))
        } finally {
            database.close()
        }
    }
}