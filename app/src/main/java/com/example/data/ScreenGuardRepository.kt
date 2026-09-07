package com.example.data

import kotlinx.coroutines.flow.Flow

class ScreenGuardRepository(private val dao: ScreenGuardDao) {
    val allMonitoredApps: Flow<List<MonitoredApp>> = dao.getAllMonitoredAppsFlow()
    val allSessions: Flow<List<SessionHistory>> = dao.getAllSessionsFlow()

    suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        return dao.getMonitoredApp(packageName)
    }

    suspend fun insertMonitoredApp(app: MonitoredApp) {
        dao.insertMonitoredApp(app)
    }

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
