package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.util.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.MonitoredApp
import com.example.data.ScreenGuardRepository
import com.example.data.SessionHistory
import com.example.domain.SessionManager
import com.example.domain.AccessibilityConsent
import com.example.domain.HistoryDates
import com.example.domain.HistoryIndex
import com.example.domain.SessionAction
import com.example.domain.SARCASTIC_DISABLE
import com.example.ui.AccessibilityDisclosure
import com.example.service.AppAccessibilityService
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.GuardBlack
import com.example.ui.theme.GuardSurface
import com.example.ui.theme.GuardSurfaceItem
import com.example.ui.theme.GuardMintAccent
import com.example.ui.theme.GuardTextPrimary
import com.example.ui.theme.GuardTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlin.math.absoluteValue
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup

class MainViewModel(private val repository: ScreenGuardRepository, context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _installedApps = MutableStateFlow<List<AppDisplayItem>>(emptyList())
    val installedApps: StateFlow<List<AppDisplayItem>> = combine(_installedApps, repository.allMonitoredApps) { installed, monitored ->
        val saved = monitored.associateBy { it.packageName }
        installed.map { app ->
            val config = saved[app.packageName]
            app.copy(isEnabled = config?.isEnabled == true, isMonitored = config != null, dailyQuotaMinutes = config?.dailyQuotaMinutes ?: 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps

    val monitoredAppsFlow: Flow<List<MonitoredApp>> = repository.allMonitoredApps
    val sessionHistoryFlow: Flow<List<SessionHistory>> = repository.allSessions

    private val calendarClock = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            emit(LocalDate.now(zone) to zone)
            kotlinx.coroutines.delay(60_000L)
        }
    }.distinctUntilChanged()

    private val selectedWeekEnd = MutableStateFlow<LocalDate?>(null)

    fun selectWeekEnding(date: LocalDate) {
        selectedWeekEnd.value = date
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val statisticsState = combine(calendarClock, selectedWeekEnd) { clock, selected -> clock to selected }
        .flatMapLatest { (clock, selected) ->
            val today = clock.first
            val zone = clock.second
            val weekEnd = selected?.coerceAtMost(today) ?: today
            combine(
                repository.dashboardSessions(
                    HistoryDates.dayBounds(today.minusDays(27), zone).first,
                    HistoryDates.dayBounds(today, zone).second,
                    HistoryDates.dayBounds(weekEnd.minusDays(6), zone).first,
                    HistoryDates.dayBounds(weekEnd, zone).second
                ), repository.historyTotals, monitoredAppsFlow
            ) { history, totals, monitored ->
                DashboardStats(
                    totalMindfulPauses = totals.decisions,
                    totalTimeSpentMinutes = (totals.seconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    guardedAppsCount = monitored.count { it.isEnabled },
                    bypassedInterventions = totals.bypassed,
                    successPercentage = com.example.domain.BehaviorCounts(totals.resisted, totals.extended, totals.bypassed).stopRate ?: 0,
                    recentLogs = history,
                    historyIndex = HistoryIndex(history, zone),
                    referenceDate = today,
                    firstRecordedAt = totals.firstRecordedAt
                )
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadInstalledApps() {
        if (_isLoadingApps.value) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = appContext.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0) ?: emptyList()
                    
                    val homePackages = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                        .map { it.activityInfo.packageName }.toSet()

                    resolveInfos.mapNotNull { info ->
                        try {
                            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                            if (com.example.domain.AppSafety.isProtected(packageName, appContext.packageName)) return@mapNotNull null
                            if (packageName in homePackages) return@mapNotNull null

                            val rawAppName = try {
                                info.loadLabel(pm).toString()
                            } catch (e: Exception) {
                                packageName.substringAfterLast(".")
                            }

                            val appName = rawAppName
                            val icon = try {
                                info.loadIcon(pm)
                            } catch (e: Exception) {
                                null
                            }

                            AppDisplayItem(
                                packageName = packageName,
                                appName = appName,
                                isEnabled = false,
                                icon = icon
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _installedApps.value = apps
            _isLoadingApps.value = false
        }
    }

    fun toggleAppMonitoring(packageName: String, appName: String, currentlyEnabled: Boolean) {
        viewModelScope.launch {
            repository.toggleMonitoring(packageName, appName)
            if (SessionManager.isMasterGuardEnabled.value) {
                com.example.service.MonitorService.refresh(appContext)
            }
        }
    }

    fun setAppDailyQuota(packageName: String, appName: String, isEnabled: Boolean, quotaMinutes: Int) {
        viewModelScope.launch {
            repository.updateDailyQuota(packageName, appName, isEnabled, quotaMinutes)
        }
    }

    fun deleteAppFromMonitoring(packageName: String) {
        viewModelScope.launch {
            repository.deleteMonitoredApp(packageName)
            com.example.data.FocusSettings.removeApp(packageName)
            SessionManager.resetSessionForPackage(packageName)
            if (SessionManager.isMasterGuardEnabled.value) {
                com.example.service.MonitorService.refresh(appContext)
            }
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            SessionManager.clearHistory(repository)
        }
    }
}

data class DashboardStats(
    val totalMindfulPauses: Int = 0,
    val totalTimeSpentMinutes: Int = 0,
    val guardedAppsCount: Int = 0,
    val bypassedInterventions: Int = 0,
    val successPercentage: Int = 100,
    val recentLogs: List<SessionHistory> = emptyList(),
    val historyIndex: HistoryIndex = HistoryIndex(recentLogs),
    val referenceDate: LocalDate = LocalDate.now(),
    val firstRecordedAt: Long? = null
)

// Simple ViewModel Factory without external framework injection
class ViewModelFactory(private val repository: ScreenGuardRepository, private val context: Context) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

