package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private val password = "test-only-long-passphrase".toCharArray()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionManager.stopForProcessRecreationTest()
        SessionManager.init(context)
        FocusSettings.update(FocusConfiguration())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After fun teardown() {
        SessionManager.stopForProcessRecreationTest()
        Dispatchers.resetMain()
        database.close()
    }

    @Test fun backupRestoresRecordsAndRulesButNeverConsent() = runBlocking {
        val dao = database.dao()
        dao.insertMonitoredApp(MonitoredApp("test", "Test", dailyQuotaMinutes = 30))
        dao.insertSession(SessionHistory(packageName = "test", appName = "Test", durationSeconds = 60, actionTaken = "USAGE", eventId = "one"))
        FocusSettings.updateRule("test", AppRule(profileId = "work"))
        AccessibilityConsent.accept(context)
        val encrypted = LocalDataTransfer.backup(context, password, database)
        dao.clearHistory()
        FocusSettings.update(FocusConfiguration())
        LocalDataTransfer.restore(context, encrypted, password, database)
        assertEquals(60, dao.getAllSessionsFlow().first().single().durationSeconds)
        assertEquals("work", FocusSettings.configuration.value.rule("test").profileId)
        assertFalse(AccessibilityConsent.isAccepted(context))
        assertFalse(SessionManager.isMasterGuardEnabled.value)
    }

    @Test fun invalidAuthenticatedSnapshotRollsBackDatabaseChanges() = runBlocking {
        val dao = database.dao()
        dao.insertSession(SessionHistory(packageName = "test", appName = "Test", durationSeconds = 90, actionTaken = "USAGE", eventId = "keep"))
        val encrypted = LocalDataTransfer.backup(context, password, database)
        val root = JSONObject(String(BackupEncryption.decrypt(encrypted, password), Charsets.UTF_8))
        root.getJSONArray("history").getJSONObject(0).put("seconds", -1)
        val invalid = BackupEncryption.encrypt(root.toString().toByteArray(), password)
        assertTrue(runCatching { LocalDataTransfer.restore(context, invalid, password, database) }.isFailure)
        assertEquals("keep", dao.getAllSessionsFlow().first().single().eventId)
    }
}