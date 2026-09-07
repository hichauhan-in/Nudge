package com.example.data

import kotlinx.coroutines.flow.Flow

class ScreenGuardRepository(private val dao: ScreenGuardDao) {
    val allMonitoredApps: Flow<List<MonitoredApp>> = dao.getAllMonitoredAppsFlow()
    val allSessions: Flow<List<SessionHistory>> = dao.getAllSessionsFlow()
    val historyTotals: Flow<HistoryTotals> = dao.getHistoryTotals()

    fun dashboardSessions(recentStart: Long, recentEnd: Long, weekStart: Long, weekEnd: Long) =
        dao.getDashboardSessions(recentStart, recentEnd, weekStart, weekEnd)

    suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        return dao.getMonitoredApp(packageName)
    }

    suspend fun insertMonitoredApp(app: MonitoredApp) {
        dao.insertMonitoredApp(app)
    }

    suspend fun toggleMonitoring(packageName: String, appName: String) = dao.toggleMonitoring(packageName, appName)

    suspend fun updateDailyQuota(packageName: String, appName: String, enabled: Boolean, minutes: Int) =
        dao.updateDailyQuota(packageName, appName, enabled, minutes)

    suspend fun deleteMonitoredApp(packageName: String) {
        dao.deleteMonitoredApp(packageName)
    }

    suspend fun insertSession(history: SessionHistory) {
        dao.insertSession(history)
    }

    suspend fun clearHistory() {
        dao.clearHistory()
    }
}
