package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "monitored_apps")
data class MonitoredApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
    val limitMinutes: Int = 0, // 0 means custom on each open or no strict limit
    val dailyQuotaMinutes: Int = 0 // 0 means no daily quota configured
)

@Entity(tableName = "session_history", indices = [Index("startTime"), Index(value = ["eventId"], unique = true)])
data class SessionHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String,
    val startTime: Long = System.currentTimeMillis(),
    val durationSeconds: Int,
    val actionTaken: String,
    val eventId: String? = null
)

@Dao
interface ScreenGuardDao {
    @Query("SELECT * FROM monitored_apps ORDER BY appName ASC")
    fun getAllMonitoredAppsFlow(): Flow<List<MonitoredApp>>

    @Query("SELECT * FROM monitored_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getMonitoredApp(packageName: String): MonitoredApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMonitoredApp(app: MonitoredApp)

    @Transaction
    suspend fun toggleMonitoring(packageName: String, appName: String) {
        val existing = getMonitoredApp(packageName)
        insertMonitoredApp(existing?.copy(isEnabled = !existing.isEnabled) ?: MonitoredApp(packageName, appName))
    }

    @Transaction
    suspend fun updateDailyQuota(packageName: String, appName: String, enabled: Boolean, minutes: Int) {
        val existing = getMonitoredApp(packageName) ?: MonitoredApp(packageName, appName, isEnabled = enabled)
        insertMonitoredApp(existing.copy(dailyQuotaMinutes = minutes.coerceIn(0, 180)))
    }

    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    suspend fun deleteMonitoredApp(packageName: String)

    @Query("SELECT * FROM session_history ORDER BY startTime DESC, id DESC")
    fun getAllSessionsFlow(): Flow<List<SessionHistory>>

    @Query("SELECT * FROM session_history WHERE (startTime >= :recentStart AND startTime < :recentEnd) OR (startTime >= :weekStart AND startTime < :weekEnd) ORDER BY startTime DESC, id DESC")
    fun getDashboardSessions(recentStart: Long, recentEnd: Long, weekStart: Long, weekEnd: Long): Flow<List<SessionHistory>>

    @Query("SELECT COUNT(*) AS records, COALESCE(SUM(CASE WHEN durationSeconds > 0 THEN durationSeconds ELSE 0 END), 0) AS seconds, COALESCE(SUM(CASE WHEN actionTaken IN ('STARTED','CLOSED','EXTENDED','BYPASSED') THEN 1 ELSE 0 END), 0) AS decisions, COALESCE(SUM(CASE WHEN actionTaken = 'CLOSED' THEN 1 ELSE 0 END), 0) AS resisted, COALESCE(SUM(CASE WHEN actionTaken = 'EXTENDED' THEN 1 ELSE 0 END), 0) AS extended, COALESCE(SUM(CASE WHEN actionTaken = 'BYPASSED' THEN 1 ELSE 0 END), 0) AS bypassed, MIN(startTime) AS firstRecordedAt FROM session_history")
    fun getHistoryTotals(): Flow<HistoryTotals>

    @Query("SELECT COALESCE(SUM(CASE WHEN durationSeconds > 0 THEN durationSeconds ELSE 0 END), 0) AS seconds, COALESCE(SUM(CASE WHEN actionTaken = 'CLOSED' THEN 1 ELSE 0 END), 0) AS resisted FROM session_history WHERE startTime >= :start AND startTime < :end")
    suspend fun getWidgetTotals(start: Long, end: Long): WidgetTotals

    @Query("SELECT * FROM session_history WHERE id > :afterId AND id <= :throughId ORDER BY id LIMIT :limit")
    suspend fun getHistoryPage(afterId: Int, throughId: Int, limit: Int = 500): List<SessionHistory>

    @Query("SELECT COALESCE(MAX(id), 0) FROM session_history")
    suspend fun getLastHistoryId(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(history: SessionHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(history: List<SessionHistory>)

    @Query("DELETE FROM monitored_apps")
    suspend fun clearMonitoredApps()

    @Query("DELETE FROM session_history")
    suspend fun clearHistory()
}

data class HistoryTotals(
    val records: Long = 0,
    val seconds: Long = 0,
    val decisions: Int = 0,
    val resisted: Int = 0,
    val extended: Int = 0,
    val bypassed: Int = 0,
    val firstRecordedAt: Long? = null
)

data class WidgetTotals(val seconds: Long = 0, val resisted: Int = 0)

@Database(entities = [MonitoredApp::class, SessionHistory::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ScreenGuardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monitored_apps ADD COLUMN dailyQuotaMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_history ADD COLUMN eventId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_history_startTime ON session_history(startTime)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_session_history_eventId ON session_history(eventId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screenguard_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
