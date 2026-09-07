package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.SessionHistory
import com.example.data.MonitoredApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DatabaseMigrationTest {
    @Test
    fun upgradesPreserveHistoryAndAppChoices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE monitored_apps (packageName TEXT NOT NULL PRIMARY KEY, appName TEXT NOT NULL, isEnabled INTEGER NOT NULL, limitMinutes INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE session_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, appName TEXT NOT NULL, startTime INTEGER NOT NULL, durationSeconds INTEGER NOT NULL, actionTaken TEXT NOT NULL)")
            old.execSQL("INSERT INTO monitored_apps VALUES ('test', 'Test app', 1, 5)")
            old.execSQL("INSERT INTO session_history VALUES (1, 'test', 'Test app', 123456, 60, 'COMPLETED')")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries().build()
        try {
            val history = migrated.dao().getAllSessionsFlow().first()
            assertEquals(1, history.size)
            assertEquals(60, history.single().durationSeconds)
            assertNull(history.single().eventId)
            val app = migrated.dao().getMonitoredApp("test")!!
            assertTrue(app.isEnabled)
            assertEquals(5, app.limitMinutes)
            assertEquals(0, app.dailyQuotaMinutes)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun replayingAnEventDoesNotDoubleCountIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val event = SessionHistory(packageName = "test", appName = "Test", durationSeconds = 0, actionTaken = "EXTENDED", eventId = "timer-1:extended")
            database.dao().insertSession(event)
            database.dao().insertSession(event)
            assertEquals(1, database.dao().getAllSessionsFlow().first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun monitoringAndQuotaUpdatesPreserveOtherFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.insertMonitoredApp(MonitoredApp("test", "Test", limitMinutes = 5, dailyQuotaMinutes = 30))
            dao.toggleMonitoring("test", "Test")
            dao.updateDailyQuota("test", "Test", true, 60)
            val updated = dao.getMonitoredApp("test")!!
            assertFalse(updated.isEnabled)
            assertEquals(60, updated.dailyQuotaMinutes)
            assertEquals(5, updated.limitMinutes)
            dao.toggleMonitoring("test", "Test")
            assertTrue(dao.getMonitoredApp("test")!!.isEnabled)
        } finally {
            database.close()
        }
    }
}