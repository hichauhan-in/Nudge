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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(history: SessionHistory)

    @Query("DELETE FROM session_history")
    suspend fun clearHistory()
}

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
