package com.example

// Trigger rebuild to recover emulator state from I/O errors 4
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        if (SessionManager.isMasterGuardEnabled.value) {
            com.example.service.MonitorService.start(this)
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }

        // Ask for a Play Store rating at natural, spaced-out moments (only after onboarding,
        // and only on a genuine fresh launch). Play decides whether to actually show it.
        if (savedInstanceState == null) {
            val reviewPrefs = getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE)
            if (reviewPrefs.getBoolean("first_launch_done", false)) {
                AppReviewManager.maybeRequestReview(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-post the monitoring banner so it appears right after the user enables notifications.
        if (SessionManager.isMasterGuardEnabled.value) {
            com.example.service.MonitorService.refresh(this)
        }
    }
}

enum class NavigationScreen {
    Dashboard,
    MonitoredApps,
    Settings,
    AppInfo
}

enum class InitialScreenState {
    Welcome,
    Permission,
    Onboarding,
    HomeApp
}

// Data holder for display within the app list
data class AppDisplayItem(
    val packageName: String,
    val appName: String,
    val isEnabled: Boolean,
    val isMonitored: Boolean = false,
    val dailyQuotaMinutes: Int = 0,
    val icon: Drawable? = null
)

class MainViewModel(private val repository: ScreenGuardRepository, context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _installedApps = MutableStateFlow<List<AppDisplayItem>>(emptyList())
    val installedApps: StateFlow<List<AppDisplayItem>> = _installedApps

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps

    val monitoredAppsFlow: Flow<List<MonitoredApp>> = repository.allMonitoredApps
    val sessionHistoryFlow: Flow<List<SessionHistory>> = repository.allSessions

    // Screen State Flow
    val statisticsState = combine(sessionHistoryFlow, monitoredAppsFlow) { history, monitored ->
        val totalPauses = history.size
        val completed = history.count { it.actionTaken == "COMPLETED" || it.actionTaken == "EXTENDED" }
        val bypassed = history.count { it.actionTaken == "BYPASSED" }
        val guardedAppsCount = monitored.count { it.isEnabled }
        
        val successRate = if (totalPauses > 0) {
            ((completed.toFloat() / totalPauses.toFloat()) * 100).toInt()
        } else {
            100
        }

        val totalTimeSeconds = history.sumOf { it.durationSeconds }
        
        DashboardStats(
            totalMindfulPauses = totalPauses,
            totalTimeSpentMinutes = totalTimeSeconds / 60,
            guardedAppsCount = guardedAppsCount,
            bypassedInterventions = bypassed,
            successPercentage = successRate,
            recentLogs = history
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    init {
        loadInstalledApps()
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = appContext.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0) ?: emptyList()
                    
                    // Read currently enabled monitored apps from Room once to cross reference
                    val dbMonitored = try {
                        repository.allMonitoredApps.first().associateBy { it.packageName }
                    } catch (e: Exception) {
                        emptyMap()
                    }

                    resolveInfos.mapNotNull { info ->
                        try {
                            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                            if (packageName == appContext.packageName) return@mapNotNull null

                            val rawAppName = try {
                                info.loadLabel(pm).toString()
                            } catch (e: Exception) {
                                packageName.substringAfterLast(".")
                            }

                            val appName = if (rawAppName.contains("mShop", ignoreCase = true) || rawAppName.contains("amazon.mshop", ignoreCase = true) || rawAppName.contains("amazon", ignoreCase = true)) {
                                "Amazon"
                            } else if (rawAppName.contains("chrome", ignoreCase = true)) {
                                "Chrome"
                            } else if (rawAppName.contains("youtube", ignoreCase = true)) {
                                "YouTube"
                            } else {
                                rawAppName
                            }
                            val icon = try {
                                info.loadIcon(pm)
                            } catch (e: Exception) {
                                null
                            }

                            AppDisplayItem(
                                packageName = packageName,
                                appName = appName,
                                isEnabled = dbMonitored[packageName]?.isEnabled ?: false,
                                isMonitored = dbMonitored.containsKey(packageName),
                                dailyQuotaMinutes = dbMonitored[packageName]?.dailyQuotaMinutes ?: 0,
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
            val existingQuota = _installedApps.value.firstOrNull { it.packageName == packageName }?.dailyQuotaMinutes ?: 0
            repository.insertMonitoredApp(
                com.example.data.MonitoredApp(
                    packageName = packageName,
                    appName = appName,
                    isEnabled = !currentlyEnabled,
                    dailyQuotaMinutes = existingQuota
                )
            )
            // Refresh list status
            val updatedList = _installedApps.value.map {
                if (it.packageName == packageName) {
                    it.copy(isEnabled = !currentlyEnabled, isMonitored = true)
                } else {
                    it
                }
            }
            _installedApps.value = updatedList
            if (SessionManager.isMasterGuardEnabled.value) {
                com.example.service.MonitorService.refresh(appContext)
            }
        }
    }

    fun setAppDailyQuota(packageName: String, appName: String, isEnabled: Boolean, quotaMinutes: Int) {
        viewModelScope.launch {
            repository.insertMonitoredApp(
                com.example.data.MonitoredApp(
                    packageName = packageName,
                    appName = appName,
                    isEnabled = isEnabled,
                    dailyQuotaMinutes = quotaMinutes
                )
            )
            _installedApps.value = _installedApps.value.map {
                if (it.packageName == packageName) it.copy(dailyQuotaMinutes = quotaMinutes) else it
            }
        }
    }

    fun deleteAppFromMonitoring(packageName: String) {
        viewModelScope.launch {
            repository.deleteMonitoredApp(packageName)
            // Refresh list status
            val updatedList = _installedApps.value.map {
                if (it.packageName == packageName) {
                    it.copy(isEnabled = false, isMonitored = false)
                } else {
                    it
                }
            }
            _installedApps.value = updatedList
            if (SessionManager.isMasterGuardEnabled.value) {
                com.example.service.MonitorService.refresh(appContext)
            }
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearHistory()
            com.example.service.NudgeWidgetProvider.triggerUpdate(appContext)
        }
    }
}

data class DashboardStats(
    val totalMindfulPauses: Int = 0,
    val totalTimeSpentMinutes: Int = 0,
    val guardedAppsCount: Int = 0,
    val bypassedInterventions: Int = 0,
    val successPercentage: Int = 100,
    val recentLogs: List<SessionHistory> = emptyList()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ScreenGuardRepository(database.dao()) }
    val viewModel: MainViewModel = viewModel(factory = ViewModelFactory(repository, context))

    val prefs = remember { context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE) }
    var firstLaunchDone by remember { mutableStateOf(prefs.getBoolean("first_launch_done", false)) }
    var welcomeDone by remember { mutableStateOf(prefs.getBoolean("welcome_done", false)) }
    var hasPermissionOnStart by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissionOnStart = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentInitialState = when {
        firstLaunchDone -> InitialScreenState.HomeApp
        !welcomeDone -> InitialScreenState.Welcome
        !hasPermissionOnStart -> InitialScreenState.Permission
        else -> InitialScreenState.Onboarding
    }

    Crossfade(targetState = currentInitialState, label = "OnboardingCrossfade") { state ->
        when (state) {
            InitialScreenState.Welcome -> {
                WelcomeSplashScreen {
                    prefs.edit().putBoolean("welcome_done", true).apply()
                    welcomeDone = true
                }
            }
            InitialScreenState.Permission -> {
                IntroPermissionSplashScreen(
                    isPermissionGranted = hasPermissionOnStart,
                    onPermissionGranted = {
                        hasPermissionOnStart = true
                    }
                )
            }
            InitialScreenState.Onboarding -> {
                OnboardingScreen(viewModel = viewModel) {
                    prefs.edit().putBoolean("first_launch_done", true).apply()
                    firstLaunchDone = true
                }
            }
            InitialScreenState.HomeApp -> {
        var currentScreen by remember { mutableStateOf(NavigationScreen.Dashboard) }
        var isServiceEnabled by remember { mutableStateOf(false) }

        // Check accessibility status whenever this screen is displayed or resumed
        LaunchedEffect(currentScreen) {
            isServiceEnabled = isAccessibilityServiceEnabled(context)
        }

        val activity = context as? ComponentActivity
        androidx.activity.compose.BackHandler(enabled = true) {
            if (currentScreen == NavigationScreen.AppInfo) {
                currentScreen = NavigationScreen.Settings
            } else if (currentScreen != NavigationScreen.Dashboard) {
                currentScreen = NavigationScreen.Dashboard
            } else {
                activity?.finish()
            }
        }

        Scaffold(
            containerColor = GuardBlack,
            bottomBar = {
                if (currentScreen != NavigationScreen.AppInfo) {
                    NavigationBar(
                        containerColor = GuardBlack,
                        tonalElevation = 0.dp,
                        modifier = Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    ) {
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.Dashboard,
                            onClick = { currentScreen = NavigationScreen.Dashboard },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                            label = { Text("Home") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GuardBlack,
                                selectedTextColor = GuardMintAccent,
                                indicatorColor = GuardMintAccent,
                                unselectedIconColor = GuardTextSecondary,
                                unselectedTextColor = GuardTextSecondary
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.MonitoredApps,
                            onClick = { currentScreen = NavigationScreen.MonitoredApps },
                            icon = { Icon(Icons.Default.Lock, contentDescription = "Interceptions") },
                            label = { Text("Monitor") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GuardBlack,
                                selectedTextColor = GuardMintAccent,
                                indicatorColor = GuardMintAccent,
                                unselectedIconColor = GuardTextSecondary,
                                unselectedTextColor = GuardTextSecondary
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen == NavigationScreen.Settings,
                            onClick = { currentScreen = NavigationScreen.Settings },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Configure") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GuardBlack,
                                selectedTextColor = GuardMintAccent,
                                indicatorColor = GuardMintAccent,
                                unselectedIconColor = GuardTextSecondary,
                                unselectedTextColor = GuardTextSecondary
                            )
                        )
                    }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Crossfade(targetState = currentScreen, label = "ScreenTransition") { targetScreen ->
                    when (targetScreen) {
                        NavigationScreen.Dashboard -> DashboardView(viewModel, isServiceEnabled, context)
                        NavigationScreen.MonitoredApps -> MonitoredAppsView(viewModel)
                        NavigationScreen.Settings -> SettingsView(viewModel, isServiceEnabled, context) {
                            currentScreen = NavigationScreen.AppInfo
                        }
                        NavigationScreen.AppInfo -> HowItWorksScrollView {
                            currentScreen = NavigationScreen.Settings
                        }
                    }
                }
            }
        }
            }
        }
    }
}

@Composable
fun DashboardView(viewModel: MainViewModel, isServiceEnabled: Boolean, context: Context) {
    var sarcasticDisableAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    if (sarcasticDisableAction != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sarcasticDisableAction = null },
            containerColor = GuardSurface,
            titleContentColor = Color.White,
            textContentColor = GuardTextSecondary,
            title = { Text("Are you sure?") },
            text = { 
                val phrase = remember { SARCASTIC_DISABLE.random() }
                Text(phrase) 
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { 
                    sarcasticDisableAction?.invoke() 
                    sarcasticDisableAction = null
                }) {
                    Text("Disable", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { sarcasticDisableAction = null }) {
                    Text("Rethink", color = GuardMintAccent)
                }
            }
        )
    }
    val stats by viewModel.statisticsState.collectAsStateWithLifecycle()
    val isMasterGuardEnabled by SessionManager.isMasterGuardEnabled.collectAsStateWithLifecycle()
    val prefs = context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE)
    var isSarcasticMode by remember { mutableStateOf(prefs.getBoolean("sarcastic_mode", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Home",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (isMasterGuardEnabled) "MONITORING ACTIVE" else "MONITORING DISABLED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isMasterGuardEnabled) GuardMintAccent else GuardTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )
            }
            // Rounded status toggle as represented in HTML spec
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(24.dp)
                    .background(
                        color = if (isMasterGuardEnabled) GuardMintAccent else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        if (isMasterGuardEnabled && isSarcasticMode) {
                            sarcasticDisableAction = {
                                SessionManager.setMasterGuardEnabled(false)
                            }
                        } else {
                            SessionManager.setMasterGuardEnabled(!isMasterGuardEnabled)
                        }
                    }
                    .padding(2.dp),
                contentAlignment = if (isMasterGuardEnabled) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(GuardBlack, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Onboarding Warning if service is not running
        if (!isServiceEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openAccessibilitySettings(context) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color.Red,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Accessibility Inactive",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap here to enable System Screen Interceptor Guard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Arrow",
                        tint = Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Insights Carousel (circular: wraps from the last card back to the first; always
        // starts on the first template each time the dashboard is shown).
        val templateCount = 3
        val carouselStartPage = remember { (Int.MAX_VALUE / 2).let { it - it % templateCount } }
        val pagerState = rememberPagerState(initialPage = carouselStartPage, pageCount = { Int.MAX_VALUE })
        
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 16.dp
            ) { page ->
                when (page % templateCount) {
                    0 -> MindfulUsageCard(stats, isSarcasticMode = isSarcasticMode)
                    1 -> AppUsageInsightCard(stats, isSarcasticMode = isSarcasticMode)
                    else -> InterventionBehaviorCard(stats, isSarcasticMode = isSarcasticMode)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Pager indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(templateCount) { index ->
                    val active = pagerState.currentPage % templateCount == index
                    val color = if (active) GuardMintAccent else Color.White.copy(alpha = 0.2f)
                    val width = if (active) 16.dp else 6.dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = width, height = 6.dp)
                            .background(color, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live stats metrics grid
        Text(
            text = "Metrics",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = GuardTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Total Open Count",
                value = stats.totalMindfulPauses.toString()
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Guarded Apps",
                value = stats.guardedAppsCount.toString()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Timer Ignored Count",
                value = stats.bypassedInterventions.toString()
            )
            StatCard(
                modifier = Modifier.weight(1f),
                title = "Early Closed / Resisted",
                value = stats.recentLogs.count { it.actionTaken == "CLOSED" }.toString()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // On-Device and Offline Guarantee (Cybersecurity Aesthetic)
        Card(
            colors = CardDefaults.cardColors(containerColor = GuardSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.15f)), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(GuardMintAccent.copy(alpha = 0.08f), CircleShape)
                        .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.2f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "On-Device Guarantee",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "100% LOCAL & OFFLINE GUARANTEED",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardMintAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your digital footprint never leaves this phone. No telemetry, no external servers—pure focus, in your control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardTextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // History Log Title + day selector (only shows the selected day's intercepts)
        var interceptDayOffset by remember { mutableStateOf(0) }
        val dayLogs = remember(stats.recentLogs, interceptDayOffset) {
            logsForDay(stats.recentLogs, interceptDayOffset)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Intercepts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = GuardTextSecondary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Text(
                text = dayLabel(interceptDayOffset),
                color = GuardMintAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        DaySelectorBars(stats.recentLogs, interceptDayOffset) { interceptDayOffset = it }

        Spacer(modifier = Modifier.height(12.dp))

        if (dayLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.03f)), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No intercepts on this day.",
                    color = GuardTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurfaceItem),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.03f)), RoundedCornerShape(16.dp))
            ) {
                // Show at most 10 intercept entries at once. Extra entries scroll inside
                // this section only — the page itself never grows.
                val maxVisibleLogs = 10
                val logRowHeight = 56.dp
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = logRowHeight * maxVisibleLogs)
                        .padding(horizontal = 8.dp)
                ) {
                    items(dayLogs, key = { it.id }) { log ->
                        InterceptLogRow(log = log, rowHeight = logRowHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun InterceptLogRow(log: SessionHistory, rowHeight: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.height(rowHeight)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.appName,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = log.packageName + " • " + log.actionTaken,
                    color = GuardTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (log.durationSeconds > 0) "${log.durationSeconds / 60}m limit" else "Ignored",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (log.actionTaken == "BYPASSED") Color.Red.copy(alpha = 0.6f) else GuardMintAccent
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
    }
}

@Composable
fun AppIconView(
    icon: Drawable?,
    appName: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    if (icon != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(icon)
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.firstOrNull()?.uppercase() ?: "",
                color = if (isSelected) GuardMintAccent else GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GuardSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoredAppsView(viewModel: MainViewModel) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("focus_time_prefs", android.content.Context.MODE_PRIVATE)
    val isSarcasticMode = prefs.getBoolean("sarcastic_mode", false)
    var sarcasticDisableAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    if (sarcasticDisableAction != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sarcasticDisableAction = null },
            containerColor = GuardSurface,
            titleContentColor = Color.White,
            textContentColor = GuardTextSecondary,
            title = { Text("Are you sure?") },
            text = { 
                val phrase = remember { SARCASTIC_DISABLE.random() }
                Text(phrase) 
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { 
                    sarcasticDisableAction?.invoke() 
                    sarcasticDisableAction = null
                }) {
                    Text("Disable", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { sarcasticDisableAction = null }) {
                    Text("Rethink", color = GuardMintAccent)
                }
            }
        )
    }
    var showAddAppsDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = showAddAppsDialog) {
        showAddAppsDialog = false
    }

    val search by viewModel.searchQuery.collectAsStateWithLifecycle()
    val installedList by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()

    val activeMonitoredList = remember(installedList) {
        installedList.filter { it.isMonitored }
    }

    val filteredList = remember(activeMonitoredList, search) {
        if (search.isEmpty()) {
            activeMonitoredList
        } else {
            activeMonitoredList.filter {
                it.appName.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Monitor Console",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )

        Text(
            text = "App specific Nudge settings",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardTextSecondary,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Beautiful Monochrome Search Bar matching Sophisticated Dark Spec
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text("Track app name...", color = GuardTextSecondary) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                modifier = Modifier
                    .weight(1f)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                    .background(GuardSurfaceItem, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GuardMintAccent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSarcasticMode) Color.Red.copy(alpha = 0.8f) else GuardMintAccent)
                    .clickable { showAddAppsDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Apps",
                    tint = if (isSarcasticMode) Color.White else GuardBlack
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GuardMintAccent)
            }
        } else if (activeMonitoredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(GuardMintAccent.copy(alpha = 0.08f), CircleShape)
                            .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.2f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "No Guard Shield",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Active Guards",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your digital space is currently unrestricted. Add more apps to monitor under the Configure page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardTextSecondary,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = filteredList,
                    key = { item -> item.packageName }
                ) { item ->
                    var quotaExpanded by remember { mutableStateOf(false) }
                    val hasQuota = item.dailyQuotaMinutes > 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .border(BorderStroke(1.dp, Color.White.copy(0.03f)), RoundedCornerShape(16.dp))
                            .background(GuardSurfaceItem, RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { quotaExpanded = !quotaExpanded }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIconView(
                                    icon = item.icon,
                                    appName = item.appName,
                                    isSelected = true,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.appName,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = if (hasQuota) "Daily quota · ${formatQuotaLabel(item.dailyQuotaMinutes)}" else "Tap to set a daily quota",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasQuota) GuardMintAccent else GuardTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Switch(
                                    checked = item.isEnabled,
                                    onCheckedChange = {
                                        if (item.isEnabled && isSarcasticMode) {
                                            sarcasticDisableAction = {
                                                viewModel.toggleAppMonitoring(item.packageName, item.appName, item.isEnabled)
                                            }
                                        } else {
                                            viewModel.toggleAppMonitoring(item.packageName, item.appName, item.isEnabled)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = GuardBlack,
                                        checkedTrackColor = GuardMintAccent,
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                                        uncheckedBorderColor = Color.White.copy(alpha = 0.15f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        viewModel.deleteAppFromMonitoring(item.packageName)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove App",
                                        tint = Color.Red.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = quotaExpanded) {
                            AppQuotaConfigPanel(item = item, viewModel = viewModel)
                        }
                    }
                }
            }
        }
        }

        // Overlay dialog with animation, preserving scroll state under it and blocking clicks to background
        AnimatedVisibility(
            visible = showAddAppsDialog,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GuardBlack)
                    .clickable(enabled = false) {}
            ) {
                var searchApps by remember { mutableStateOf("") }
                
                val addAppsFilteredList = remember(installedList, searchApps) {
                    if (searchApps.isEmpty()) {
                        installedList
                    } else {
                        installedList.filter {
                            it.appName.contains(searchApps, ignoreCase = true) ||
                            it.packageName.contains(searchApps, ignoreCase = true)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Add Additional Apps",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = { showAddAppsDialog = false },
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Dialog",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = searchApps,
                        onValueChange = { searchApps = it },
                        placeholder = { Text("Search installed apps...", color = GuardTextSecondary) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                            .background(GuardSurfaceItem, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GuardMintAccent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = GuardTextSecondary)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(GuardSurface)
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)), RoundedCornerShape(16.dp))
                    ) {
                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = GuardMintAccent)
                            }
                        } else if (addAppsFilteredList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No apps found",
                                    color = GuardTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            ) {
                                items(
                                    items = addAppsFilteredList,
                                    key = { item -> item.packageName }
                                ) { item ->
                                    val isSelected = item.isEnabled
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.toggleAppMonitoring(item.packageName, item.appName, isSelected)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIconView(
                                            icon = item.icon,
                                            appName = item.appName,
                                            isSelected = isSelected,
                                            modifier = Modifier.size(36.dp)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.appName,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                        }

                                        Switch(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                viewModel.toggleAppMonitoring(item.packageName, item.appName, isSelected)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = GuardBlack,
                                                checkedTrackColor = GuardMintAccent,
                                                uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                                                uncheckedBorderColor = Color.White.copy(alpha = 0.15f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showAddAppsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}


@Composable
fun SettingsView(viewModel: MainViewModel, isServiceEnabled: Boolean, context: Context, onNavigateToAppInfo: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val contextCurrent = androidx.compose.ui.platform.LocalContext.current

    var notificationsEnabled by remember { mutableStateOf(areNotificationsEnabled(context)) }
    val settingsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = areNotificationsEnabled(context)
            }
        }
        settingsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showSupportDialog by remember { mutableStateOf(false) }
    if (showSupportDialog) {
        SupportOptionsDialog(
            onDismiss = { showSupportDialog = false },
            onUpi = {
                showSupportDialog = false
                launchUpiDonation(contextCurrent)
            },
            onKofi = {
                showSupportDialog = false
                launchKofiDonation(contextCurrent)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Strict local boundaries.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardTextSecondary,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Onboarding checklist info
            Text(
                text = "SYSTEM CONFIGURATION",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openAccessibilitySettings(context) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (isServiceEnabled) GuardMintAccent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isServiceEnabled) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = "Accessibility Status",
                            tint = if (isServiceEnabled) GuardMintAccent else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Guard System Service",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Android Accessibility Permission Requirement",
                            style = MaterialTheme.typography.bodySmall,
                            color = GuardTextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go",
                        tint = GuardTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openAppNotificationSettings(context) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background((if (notificationsEnabled) GuardMintAccent else Color.Red).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notification Settings",
                            tint = if (notificationsEnabled) GuardMintAccent else Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notification Settings",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Banner for monitored apps (Recommended)",
                            style = MaterialTheme.typography.bodySmall,
                            color = GuardTextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go",
                        tint = GuardTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val timerMode by SessionManager.timerMode.collectAsStateWithLifecycle()
            var timerBehaviorExpanded by remember { mutableStateOf(false) }
            val currentModeLabel = if (timerMode == SessionManager.TIMER_MODE_CLEAR_ON_LOCK) "Clear on lock" else "Persistent"

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { timerBehaviorExpanded = !timerBehaviorExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(GuardMintAccent.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Timer Behavior",
                                tint = GuardMintAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Timer Behavior",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = currentModeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        Icon(
                            imageVector = if (timerBehaviorExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (timerBehaviorExpanded) "Collapse" else "Expand",
                            tint = GuardTextSecondary
                        )
                    }

                    AnimatedVisibility(visible = timerBehaviorExpanded) {
                        Column {
                            TimerModeOption(
                                selected = timerMode == SessionManager.TIMER_MODE_CLEAR_ON_LOCK,
                                title = "Clear on lock",
                                description = "All timers reset when the phone is locked",
                                onClick = { SessionManager.setTimerMode(SessionManager.TIMER_MODE_CLEAR_ON_LOCK) }
                            )
                            TimerModeOption(
                                selected = timerMode == SessionManager.TIMER_MODE_PERSISTENT,
                                title = "Persistent",
                                description = "Runs until it expires, no matter what",
                                onClick = { SessionManager.setTimerMode(SessionManager.TIMER_MODE_PERSISTENT) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val strictMode by SessionManager.strictModeEnabled.collectAsStateWithLifecycle()
            Card(
                colors = CardDefaults.cardColors(containerColor = if (strictMode) Color.Red.copy(alpha = 0.12f) else GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(1.dp, if (strictMode) Color.Red.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f)),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background((if (strictMode) Color.Red else GuardMintAccent).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Strict Mode",
                            tint = if (strictMode) Color.Red else GuardMintAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Strict Mode",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Block an app once its daily quota is spent",
                            style = MaterialTheme.typography.bodySmall,
                            color = GuardTextSecondary
                        )
                    }
                    Switch(
                        checked = strictMode,
                        onCheckedChange = { SessionManager.setStrictModeEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GuardBlack,
                            checkedTrackColor = Color.Red,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "APPEARANCE",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            val prefs = contextCurrent.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE)
            var useBlurredBackground by remember { mutableStateOf(prefs.getBoolean("use_blurred_background", false)) }
            var sarcasticMode by remember { mutableStateOf(prefs.getBoolean("sarcastic_mode", false)) }

            Card(
                colors = CardDefaults.cardColors(containerColor = if (sarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, if (sarcasticMode) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blurred Prompt Background",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Use a blurred background instead of solid black",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = useBlurredBackground,
                            onCheckedChange = { 
                                useBlurredBackground = it
                                prefs.edit().putBoolean("use_blurred_background", it).apply()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = GuardMintAccent,
                                checkedTrackColor = GuardMintAccent.copy(alpha = 0.5f)
                            )
                        )
                    }

                    androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sarcastic Mode",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Enable snarky remarks and sarcastic interventions",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = sarcasticMode,
                            onCheckedChange = { isChecked -> 
                                sarcasticMode = isChecked
                                prefs.edit().putBoolean("sarcastic_mode", isChecked).apply()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = GuardMintAccent,
                                checkedTrackColor = GuardMintAccent.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "DANGER ZONE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Red.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurfaceItem),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.Red.copy(alpha = 0.15f)), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Wipe Intercept Logs",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Completely reset mindful statistics telemetry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        Button(
                            onClick = { viewModel.clearAllLogs() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Reset Logs", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "ADDITIONAL OPTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSupportDialog = true
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(GuardMintAccent.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "UPI Support Heart",
                                tint = GuardMintAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Buy Me a Coffee",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Fuel the active independent development of off-grid privacy utilities.",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Donate via UPI",
                            tint = GuardTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://hichauhan.in"))
                                try {
                                    contextCurrent.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(contextCurrent, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(GuardMintAccent.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "About Developer",
                                tint = GuardMintAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "About the Developer",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "https://hichauhan.in",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardMintAccent,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Website",
                            tint = GuardTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToAppInfo() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(GuardMintAccent.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "App Version info",
                                tint = GuardMintAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Version",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "v2.1.1",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "App Info",
                            tint = GuardTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun HowItWorksScrollView(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Back action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Settings",
                        tint = GuardMintAccent
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Security & Mechanics",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Under the hood of Nudge!",
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Our Goals Card
            Text(
                text = "OUR MISSION & GOALS",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Goals Icon",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Intentional Friction",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Most digital platforms are designed to trigger dopamine loops, keeping you engaged through mindless scrolling. Nudge! introduces conscious pauses back into your routine.\n\n" +
                                "By introducing an immediate conscious choice with optional timer limits when opening target apps, we break the automatic hand-to-screen muscle memory and shift your mental state from passive consumption to active decision making.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Features Section
            Text(
                text = "APP FEATURES",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Features Icon",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Core Capabilities",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    val features = listOf(
                        "Conscious Friction" to "Prompts you to check in before entering target apps, encouraging mindful intent.",
                        "Flexible Timers" to "Postpone the next nudge with standard (2/5/10 mins) or dynamic custom slider timers.",
                        "Monitor Console" to "Activate, temporarily toggle, or permanently delete individual monitored apps with ease.",
                        "Interactive Widget" to "Check your progress and active stats instantly from your device's home screen."
                    )

                    features.forEachIndexed { index, (title, description) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Feature Check",
                                tint = GuardMintAccent,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (index < features.lastIndex) {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accessibility Service Mechanics
            Text(
                text = "HOW MONITORING WORKS",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Icon",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Android Accessibility Service",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To offer lightning-fast, zero-delay intervention, Nudge! utilizes Android's internal Accessibility Service framework. This runs on-device, registering window state change transitions.\n\n" +
                                "• Real-time Detection: When you open any application, Android calls our package checker.\n" +
                                "• Pure Package Filter: We ONLY check if the app's package ID (like com.instagram.android) matches your selected monitored list.\n" +
                                "• Instant Overlay: If custom guards are active, our overlay blocking layer takes focus immediately, prompting you to decide if opening the app is truly intentional.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Safety & Security Card
            Text(
                text = "SAFETY & PRIVACY PARADIGMS",
                style = MaterialTheme.typography.labelSmall,
                color = GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Feature list
                    SecurityFactRow(
                        title = "We Never Transmit Your Data",
                        description = "Nudge! processes and stores everything on this device. Your guarded apps, timers, session history and settings are never uploaded or sent to any server by us — there are no analytics, ads, or trackers."
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SecurityFactRow(
                        title = "Strict local database architecture",
                        description = "Everything—including lists of guarded package configurations, session completion tallies, and local logs—is saved locally inside Android's sandbox SQLite storage."
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SecurityFactRow(
                        title = "Zero Keylogger or Screen Reading",
                        description = "Unlike commercial software, our service is configured strictly to receive 'TYPE_WINDOW_STATE_CHANGED' events. We cannot inspect what you write, read password inputs, or monitor search history."
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SecurityFactRow(
                        title = "No Battery Drain or Polling",
                        description = "Standard background monitoring tools constantly poll activity in high-CPU loops. Nudge! is purely reactive, letting Android notify us on transitions. It barely draws 1 to 2% background battery."
                    )
                }
            }
        }

        // Fixed button section at the bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(GuardBlack)
                .padding(16.dp)
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Great",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SecurityFactRow(title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(GuardMintAccent.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Checked security criteria",
                tint = GuardMintAccent,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = GuardTextSecondary,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun SettingsStepRow(
    stepNumber: String,
    title: String,
    description: String,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != {}) { onClick() },
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (isCompleted) GuardMintAccent else Color.Transparent, CircleShape)
                .border(BorderStroke(1.dp, if (isCompleted) GuardMintAccent else Color.White.copy(alpha = 0.3f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(Icons.Default.Check, contentDescription = "Done", tint = GuardBlack, modifier = Modifier.size(16.dp))
            } else {
                Text(stepNumber, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = GuardTextSecondary)
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedService = ComponentName(context, AppAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServicesSetting.contains(expectedService.flattenToString())
}

fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback standard settings
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to the app's details page where notifications can also be toggled.
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            // ignore
        }
    }
}

fun areNotificationsEnabled(context: Context): Boolean {
    return try {
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    } catch (e: Exception) {
        false
    }
}

private fun formatQuotaLabel(minutes: Int): String {
    if (minutes <= 0) return "Off"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun AppQuotaConfigPanel(item: AppDisplayItem, viewModel: MainViewModel) {
    var sliderVal by remember(item.packageName) { mutableStateOf(item.dailyQuotaMinutes.toFloat()) }
    val quotaMinutes = sliderVal.toInt()
    val usedMinutes = remember(item.packageName, item.dailyQuotaMinutes) {
        SessionManager.getQuotaConsumedMinutesToday(item.packageName)
    }

    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Daily Quota",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = formatQuotaLabel(quotaMinutes),
                color = if (quotaMinutes > 0) GuardMintAccent else GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = (Math.round(it / 5f) * 5).toFloat() },
            valueRange = 0f..180f,
            onValueChangeFinished = {
                viewModel.setAppDailyQuota(item.packageName, item.appName, item.isEnabled, sliderVal.toInt())
            },
            colors = SliderDefaults.colors(
                thumbColor = GuardMintAccent,
                activeTrackColor = GuardMintAccent,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0, 30, 60, 120).forEach { preset ->
                val selected = quotaMinutes == preset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) GuardMintAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
                        .border(
                            BorderStroke(1.dp, if (selected) GuardMintAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f)),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            sliderVal = preset.toFloat()
                            viewModel.setAppDailyQuota(item.packageName, item.appName, item.isEnabled, preset)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (preset == 0) "Off" else formatQuotaLabel(preset),
                        color = if (selected) GuardMintAccent else GuardTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (quotaMinutes > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Used today: $usedMinutes / $quotaMinutes min",
                style = MaterialTheme.typography.bodySmall,
                color = if (usedMinutes >= quotaMinutes) Color(0xFFEF5350) else GuardTextSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Set a daily time quota here - once used, warning is displayed",
            style = MaterialTheme.typography.bodySmall,
            color = GuardTextSecondary,
            fontSize = 10.sp
        )
    }
}

fun launchUpiDonation(context: Context) {
    val uri = android.net.Uri.parse("upi://pay?pa=hichauhan.in@okhdfcbank&pn=Himanshu%20Chauhan&cu=INR")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(Intent.createChooser(intent, "Pay with..."))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No UPI app found", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun launchKofiDonation(context: Context) {
    val uri = android.net.Uri.parse("https://ko-fi.com/hichauhan")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SupportOptionsDialog(onDismiss: () -> Unit, onUpi: () -> Unit, onKofi: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = GuardSurface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Support the developer",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pick how you'd like to buy me a coffee. More options are on the way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardTextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))

                PaymentMethodRow(
                    iconRes = R.drawable.ic_pay_upi,
                    name = "UPI",
                    subtitle = "Instant payment (India)",
                    enabled = true,
                    onClick = onUpi
                )
                Spacer(modifier = Modifier.height(10.dp))
                PaymentMethodRow(
                    iconRes = R.drawable.ic_pay_kofi,
                    name = "Ko-fi",
                    subtitle = "Buy me a coffee (cards, PayPal)",
                    enabled = true,
                    onClick = onKofi
                )
                Spacer(modifier = Modifier.height(10.dp))
                PaymentMethodRow(
                    iconRes = R.drawable.ic_pay_playto,
                    name = "Playto",
                    subtitle = "Coming soon",
                    enabled = false,
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun PaymentMethodRow(
    iconRes: Int,
    name: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .background(if (enabled) GuardSurfaceItem else Color.White.copy(alpha = 0.02f))
            .border(
                BorderStroke(1.dp, if (enabled) GuardMintAccent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f)),
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = iconRes),
                contentDescription = name,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (enabled) 1f else 0.45f)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.White else GuardTextSecondary,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = GuardTextSecondary
            )
        }
        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = GuardMintAccent,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = "SOON",
                color = GuardTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun TimerModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) GuardMintAccent.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) GuardMintAccent else GuardTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = if (selected) GuardMintAccent else Color.White,
                fontSize = 14.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = GuardTextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: MainViewModel, onFinished: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val installedList by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    
    val activity = androidx.activity.compose.LocalActivity.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var isSearchFocused by remember { mutableStateOf(false) }
    
    androidx.activity.compose.BackHandler(enabled = true) {
        if (isSearchFocused) {
            focusManager.clearFocus()
        } else {
            activity?.finish()
        }
    }
    
    val filteredList = remember(installedList, search) {
        if (search.isEmpty()) {
            installedList
        } else {
            installedList.filter {
                it.appName.contains(search, ignoreCase = true) ||
                it.packageName.contains(search, ignoreCase = true)
            }
        }
    }

    var selectedCount = remember(installedList) {
        installedList.count { it.isEnabled }
    }

    // Single unified animation progress: 1f = header fully visible, 0f = collapsed
    val headerProgress by animateFloatAsState(
        targetValue = if (isSearchFocused) 0f else 1f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 300f
        ),
        label = "headerProgress"
    )

    Scaffold(
        containerColor = GuardBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header section — height and opacity both driven by a single spring animation
            if (headerProgress > 0.01f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = headerProgress
                            scaleY = 0.8f + (0.2f * headerProgress)
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        }
                        .heightIn(max = (380 * headerProgress).dp)
                        .clipToBounds()
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Brand Header with beautiful aesthetic
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .background(GuardMintAccent.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                            .border(BorderStroke(1.5.dp, GuardMintAccent), RoundedCornerShape(32.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "ScreenGuard Logo",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Nudge!",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    )

                    Text(
                        text = "Mindful boundaries for attention spans",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = GuardMintAccent,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Select the apps where you lose focus. Opening them will trigger a mindful boundary screen to prompt your conscious intention.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Small top spacer when header is collapsed (search mode)
            if (headerProgress < 0.99f) {
                Spacer(modifier = Modifier.height((12 * (1f - headerProgress)).dp))
            }

            // Beautiful Monochrome Search Bar
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search installed apps...", color = GuardTextSecondary) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                    .background(GuardSurfaceItem, RoundedCornerShape(16.dp))
                    .onFocusChanged {
                        isSearchFocused = it.isFocused
                    },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GuardMintAccent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = GuardTextSecondary)
                },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = GuardTextSecondary)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Apps list container — weight(1f) handles dynamic sizing naturally
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GuardSurface)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)), RoundedCornerShape(16.dp))
                    .padding(8.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GuardMintAccent)
                    }
                } else if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No apps found",
                            color = GuardTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredList, key = { it.packageName }) { item ->
                            val isSelected = item.isEnabled
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.toggleAppMonitoring(item.packageName, item.appName, isSelected)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIconView(
                                    icon = item.icon,
                                    appName = item.appName,
                                    isSelected = isSelected,
                                    modifier = Modifier.size(36.dp)
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.appName,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }

                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        viewModel.toggleAppMonitoring(item.packageName, item.appName, isSelected)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = GuardMintAccent,
                                        uncheckedColor = Color.White.copy(alpha = 0.2f),
                                        checkmarkColor = GuardBlack
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Start Journey Button
            Button(
                onClick = {
                    onFinished()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (selectedCount > 0) "Guard $selectedCount App${if (selectedCount == 1) "" else "s"}" else "Skip / Proceed",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun IntroPermissionSplashScreen(
    isPermissionGranted: Boolean,
    onPermissionGranted: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = GuardBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main content centered vertically in the available space
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(30.dp))

                // Glowing Shield/Key Lock container
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(
                            if (isPermissionGranted) GuardMintAccent.copy(alpha = 0.12f) else Color.Red.copy(alpha = 0.08f),
                            RoundedCornerShape(32.dp)
                        )
                        .border(
                            BorderStroke(1.5.dp, if (isPermissionGranted) GuardMintAccent else Color.Red.copy(alpha = 0.3f)),
                            RoundedCornerShape(32.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Shield Indicator",
                        tint = if (isPermissionGranted) GuardMintAccent else Color.Red,
                        modifier = Modifier.size(64.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Nudge!",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "GRANT PERMISSION REQUEST",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = GuardMintAccent,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(35.dp))

                // Detailed explanation of WHY settings are required
                Card(
                    colors = CardDefaults.cardColors(containerColor = GuardSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "REQUIRED PERMISSION DETAILED EXPLANATION",
                            style = MaterialTheme.typography.labelSmall,
                            color = GuardMintAccent,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "To monitor and help you manage your focus, our app runs a local automated utility that notices when distracting applications are launched.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            lineHeight = 20.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "We use Android's Accessibility Service API strictly to retrieve the package name of the active foreground app. This runs 100% offline, on-device, with absolutely NO telemetry or data transmission.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GuardTextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dedicated Offline Privacy Guarantee Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GuardSurface)
                        .border(
                            BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.15f)),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Shield Guard",
                            tint = GuardMintAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Zero tracking. Zero external servers. Complete privacy.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = GuardMintAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Real-time Status banner
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.02f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (isPermissionGranted) GuardMintAccent else Color.Red, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPermissionGranted) "STATUS: PERMISSION SECURED" else "STATUS: WAITING FOR COMPLIANCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPermissionGranted) GuardMintAccent else Color.Red,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button
            Button(
                onClick = {
                    if (!isPermissionGranted) {
                        openAccessibilitySettings(context)
                    } else {
                        onPermissionGranted()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPermissionGranted) GuardMintAccent else Color.White.copy(alpha = 0.9f),
                    contentColor = GuardBlack
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = if (isPermissionGranted) "CONTINUE TO SETUP" else "ACTIVATE ACCESSIBILITY SERVICE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Spacer(modifier = Modifier.height(72.dp))
        }
        }
    }

@Composable
fun WelcomeSplashScreen(onContinue: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.activity.compose.BackHandler(enabled = true) {
        activity?.finish()
    }

    Scaffold(
        containerColor = GuardBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(30.dp))

            // Glowing Brand Logotype Box
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(GuardMintAccent.copy(alpha = 0.12f), RoundedCornerShape(36.dp))
                    .border(BorderStroke(1.5.dp, GuardMintAccent), RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N!",
                    style = MaterialTheme.typography.displayLarge.copy(
                        color = GuardMintAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Nudge!",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sometimes all we need is a Nudge!",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = GuardMintAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Professional Local-First Promise Card
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "100% OFFLINE & PRIVATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardMintAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Nudge! protects your focus by keeping all choices and data locally on your device. Your attention patterns remain entirely yours, offline and tracking-free.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "No analytics leave this device. We do not use external cloud servers, sync services, or carry any background telemetry. Zero trackers—pure offline assurance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardTextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Continue Button
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "Continue",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ---- Day-wise usage helpers for the dashboard carousel -------------------------------------

private fun dayBoundsMillis(daysAgo: Int): Pair<Long, Long> {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
    val start = cal.timeInMillis
    return start to (start + 24L * 60L * 60L * 1000L)
}

private fun logsForDay(logs: List<SessionHistory>, daysAgo: Int): List<SessionHistory> {
    val (start, end) = dayBoundsMillis(daysAgo)
    return logs.filter { it.startTime in start until end }
}

private fun dailyUsageMinutes(logs: List<SessionHistory>, daysAgo: Int): Int =
    logsForDay(logs, daysAgo).sumOf { it.durationSeconds } / 60

private fun dayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> "$daysAgo days ago"
}

/** A tappable 7-day bar strip (oldest → today). Heights scale with each day's usage. */
@Composable
private fun DaySelectorBars(
    recentLogs: List<SessionHistory>,
    selectedOffset: Int,
    onSelect: (Int) -> Unit
) {
    val dayMinutes = remember(recentLogs) { (6 downTo 0).map { dailyUsageMinutes(recentLogs, it) } }
    val maxMin = (dayMinutes.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        (6 downTo 0).forEachIndexed { index, daysAgo ->
            val minutes = dayMinutes[index]
            val heightFrac = (minutes.toFloat() / maxMin.toFloat()).coerceIn(0.06f, 1f)
            val isSelected = daysAgo == selectedOffset
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSelect(daysAgo) },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(heightFrac)
                        .background(
                            color = if (isSelected) GuardMintAccent else Color.White.copy(alpha = if (minutes > 0) 0.12f else 0.04f),
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )
            }
        }
    }
}

@Composable
fun MindfulUsageCard(stats: DashboardStats, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    var selectedOffset by remember { mutableStateOf(0) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(264.dp)
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSarcasticMode) "MINDLESS USAGE" else "USAGE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = GuardTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Usage icon",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val dayMinutes = remember(stats.recentLogs, selectedOffset) { dailyUsageMinutes(stats.recentLogs, selectedOffset) }
            val daySessions = remember(stats.recentLogs, selectedOffset) { logsForDay(stats.recentLogs, selectedOffset).size }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val animatedMinutes by androidx.compose.animation.core.animateIntAsState(
                    targetValue = dayMinutes,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "minutesCounter"
                )
                Text(
                    text = animatedMinutes.toString(),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                    lineHeight = 52.sp
                )
                Text(
                    text = "min",
                    fontSize = 20.sp,
                    color = GuardTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSarcasticMode)
                    "${dayLabel(selectedOffset)} · $daySessions mindless open${if (daySessions == 1) "" else "s"}"
                else
                    "${dayLabel(selectedOffset)} · $daySessions monitored open${if (daySessions == 1) "" else "s"}",
                color = GuardTextSecondary,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            DaySelectorBars(stats.recentLogs, selectedOffset) { selectedOffset = it }
        }
    }
}

@Composable
fun AppUsageInsightCard(stats: DashboardStats, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    var selectedOffset by remember { mutableStateOf(0) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(264.dp)
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "APP USAGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GuardTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = dayLabel(selectedOffset),
                        color = GuardMintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "App Usage",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val appTimes = remember(stats.recentLogs, selectedOffset) {
                logsForDay(stats.recentLogs, selectedOffset)
                    .groupBy { it.appName }
                    .mapValues { it.value.sumOf { log -> log.durationSeconds } / 60 }
                    .toList()
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(3)
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (appTimes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No usage on this day.", color = GuardTextSecondary, fontSize = 14.sp)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        appTimes.forEach { (appName, minutes) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appName,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$minutes min",
                                    color = GuardMintAccent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            DaySelectorBars(stats.recentLogs, selectedOffset) { selectedOffset = it }
        }
    }
}

@Composable
fun InterventionBehaviorCard(stats: DashboardStats, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    var selectedOffset by remember { mutableStateOf(0) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(264.dp)
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isSarcasticMode) "PATHETIC BEHAVIOR" else "INTERVENTION BEHAVIOR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GuardTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = dayLabel(selectedOffset),
                        color = GuardMintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = "Behavior",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val actionCounts = remember(stats.recentLogs, selectedOffset) {
                logsForDay(stats.recentLogs, selectedOffset).groupingBy { it.actionTaken }.eachCount()
            }
            val closedCount = actionCounts["CLOSED"] ?: 0
            val bypassedCount = actionCounts["BYPASSED"] ?: 0
            val completedCount = (actionCounts["COMPLETED"] ?: 0) + (actionCounts["EXTENDED"] ?: 0)
            val totalActions = (closedCount + completedCount + bypassedCount).coerceAtLeast(1)

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                BehaviorRow("Early Closed / Resisted", closedCount, totalActions, GuardMintAccent)
                BehaviorRow("Completed Limits", completedCount, totalActions, Color(0xFF81D4FA))
                BehaviorRow("Bypassed Limits", bypassedCount, totalActions, Color(0xFFEF5350))
            }

            Spacer(modifier = Modifier.height(8.dp))

            DaySelectorBars(stats.recentLogs, selectedOffset) { selectedOffset = it }
        }
    }
}

@Composable
fun BehaviorRow(label: String, count: Int, total: Int, color: Color) {
    val percentage = if (total > 0) count.toFloat() / total else 0f
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color.White, fontSize = 14.sp)
            Text(text = "$count", color = Color.White, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha=0.05f), CircleShape)) {
            Box(modifier = Modifier.fillMaxWidth(percentage).fillMaxHeight().background(color, CircleShape))
        }
    }
}
val SARCASTIC_DISABLE = listOf(
    "You really want to give up improving yourself?",
    "Quitting already? So typical.",
    "Sure, let the apps control you again.",
    "Giving up is easy, I understand.",
    "Back to the endless scrolling we go.",
    "I guess self-discipline isn't for everyone.",
    "Wow, you lasted... what, five minutes?",
    "Throwing in the towel? How predictable.",
    "Sure, go ahead. Ruin your focus.",
    "I'm not mad, just disappointed.",
    "Back to your old habits, huh?",
    "You were doing so well. Just kidding.",
    "Don't worry, your future self is used to this.",
    "A moment of weakness? Or a lifetime?",
    "Disable it. See if I care.",
    "It takes strength to keep going. You don't have it.",
    "Enjoy the distractions.",
    "Why even try in the first place?",
    "There goes your productivity.",
    "Let me guess: you 'need' this app?",
    "Quitting is your best skill.",
    "Go on, surrender to the algorithm.",
    "Who needs focus anyway?",
    "I'll be here when you realize your mistake.",
    "You're making a huge mistake, but go ahead.",
    "I suppose this is too hard for you.",
    "Let's just pretend this never happened.",
    "Self-improvement canceled.",
    "Ah, the sweet embrace of failure.",
    "Do you even want to succeed?"
)
