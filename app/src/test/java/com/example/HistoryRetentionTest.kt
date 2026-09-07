package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.SessionHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryRetentionTest {
    @Test
    fun historyIncludesOlderDaysAfterMoreThanOneHundredRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            val dayMillis = 86_400_000L
            val firstDay = 1_767_225_600_000L
            repeat(14) { day ->
                repeat(20) { entry ->
                    dao.insertSession(
                        SessionHistory(
                            packageName = "com.example.testapp",
                            appName = "Test app",
                            startTime = firstDay + day * dayMillis + entry * 1_000L,
                            durationSeconds = 60,
                            actionTaken = "COMPLETED"
                        )
                    )
                }
            }

            val history = dao.getAllSessionsFlow().first()
            assertEquals(280, history.size)
            assertEquals(14, history.groupBy { (it.startTime - firstDay) / dayMillis }.size)
            assertTrue(history.zipWithNext().all { (newer, older) -> newer.startTime >= older.startTime })
            assertEquals(firstDay, history.last().startTime)

            dao.clearHistory()
            assertTrue(dao.getAllSessionsFlow().first().isEmpty())
        } finally {
            database.close()
        }
    }
}