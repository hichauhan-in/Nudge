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

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.example.ui.AppLanguage.wrap(newBase))
    }
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
            if (reviewPrefs.getBoolean("first_launch_done", false) && AccessibilityConsent.isAccepted(this)) {
                AppReviewManager.maybeRequestReview(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SessionManager.endDonationFlow()
        SessionManager.resumeIfDue()
        SessionManager.flushForegroundUsage()
        SessionManager.lastUserAppPackage = null
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
    PermissionIntro,
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
    var consentDecisionMade by remember { mutableStateOf(AccessibilityConsent.hasDecision(context)) }
    var hasConsent by remember { mutableStateOf(AccessibilityConsent.isAccepted(context)) }
    var disclosureRequested by remember { mutableStateOf(false) }
    var disclosureIntroSeen by rememberSaveable { mutableStateOf(false) }
    val requestAccessibility = {
        if (AccessibilityConsent.isAccepted(context)) openAccessibilitySettings(context)
        else {
            disclosureIntroSeen = false
            disclosureRequested = true
        }
    }
    val declineAccessibility = {
        AccessibilityConsent.decline(context)
        SessionManager.setMasterGuardEnabled(false)
        hasConsent = false
        consentDecisionMade = true
        disclosureRequested = false
        disclosureIntroSeen = false
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            hasConsent = AccessibilityConsent.isAccepted(context)
            consentDecisionMade = AccessibilityConsent.hasDecision(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

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
        !welcomeDone && !firstLaunchDone -> InitialScreenState.Welcome
        disclosureRequested || !consentDecisionMade ->
            if (disclosureIntroSeen) InitialScreenState.Permission else InitialScreenState.PermissionIntro
        firstLaunchDone -> InitialScreenState.HomeApp
        else -> InitialScreenState.Onboarding
    }

    LaunchedEffect(currentInitialState) {
        if (currentInitialState == InitialScreenState.Onboarding || currentInitialState == InitialScreenState.HomeApp) {
            viewModel.loadInstalledApps()
        }
    }

    Crossfade(targetState = currentInitialState, label = "OnboardingCrossfade") { state ->
        when (state) {
            InitialScreenState.Welcome -> {
                WelcomeSplashScreen {
                    prefs.edit().putBoolean("welcome_done", true).apply()
                    welcomeDone = true
                }
            }
            InitialScreenState.PermissionIntro -> {
                com.example.ui.AccessibilityIntroScreen(
                    onContinue = { disclosureIntroSeen = true },
                    onNotNow = declineAccessibility
                )
            }
            InitialScreenState.Permission -> {
                AccessibilityDisclosure(
                    onAgree = {
                        if (AccessibilityConsent.accept(context)) {
                            hasConsent = true
                            consentDecisionMade = true
                            disclosureRequested = false
                            disclosureIntroSeen = false
                            SessionManager.setMasterGuardEnabled(true)
                            openAccessibilitySettings(context)
                        } else {
                            android.widget.Toast.makeText(context, "Could not save consent. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    onDecline = declineAccessibility
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
        val isServiceEnabled = hasPermissionOnStart && hasConsent

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
                // Keep the black bar tight around the buttons: shrink its content height so the
                // top border sits just a few px above the icons, while still reserving room for
                // the system gesture bar inset at the bottom.
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                // The info screen reuses the exact same bar footprint: instead of hiding the nav
                // bar (which made the layout jump), we cross-fade its three buttons into a matching
                // "Back" bar so the transition stays smooth and in place.
                Crossfade(
                    targetState = currentScreen == NavigationScreen.AppInfo,
                    label = "BottomBarTransition"
                ) { isAppInfo ->
                    if (isAppInfo) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp + bottomInset)
                                .background(GuardBlack)
                                .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                .clickable { currentScreen = NavigationScreen.Settings }
                                .padding(bottom = bottomInset),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Settings",
                                    tint = GuardMintAccent
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Back",
                                    color = GuardMintAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    } else {
                        NavigationBar(
                            containerColor = GuardBlack,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .height(72.dp + bottomInset)
                                .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        ) {
                            NavigationBarItem(
                                selected = currentScreen == NavigationScreen.Dashboard,
                                onClick = { currentScreen = NavigationScreen.Dashboard },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                                label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_home)) },
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
                                label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_monitor)) },
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
                                icon = { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(com.example.R.string.ui_settings)) },
                                label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_configure)) },
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
                        NavigationScreen.Dashboard -> DashboardView(viewModel, isServiceEnabled, context, requestAccessibility)
                        NavigationScreen.MonitoredApps -> MonitoredAppsView(viewModel)
                        NavigationScreen.Settings -> SettingsView(
                            viewModel, isServiceEnabled, context,
                            onRequestAccessibility = requestAccessibility,
                            onNavigateToAppInfo = { currentScreen = NavigationScreen.AppInfo }
                        )
                        NavigationScreen.AppInfo -> HowItWorksScrollView()
                    }
                }
            }
        }
            }
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
            titleContentColor = GuardTextPrimary,
            textContentColor = GuardTextSecondary,
            title = { Text("Are You Sure?") },
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
            color = GuardTextPrimary,
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = GuardTextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
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
                    tint = if (isSarcasticMode) GuardTextPrimary else GuardBlack
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
                    Box(contentAlignment = Alignment.Center) {
                        // Soft mint glow halo
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .background(GuardMintAccent.copy(alpha = 0.06f), CircleShape)
                        )
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
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Active Guards",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GuardTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No apps selected yet. Add apps in the Monitor Console.",
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
                            .border(BorderStroke(1.dp, GuardTextPrimary.copy(0.03f)), RoundedCornerShape(16.dp))
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
                                        color = GuardTextPrimary,
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
                                        uncheckedThumbColor = GuardTextPrimary.copy(alpha = 0.4f),
                                        uncheckedTrackColor = GuardTextPrimary.copy(alpha = 0.08f),
                                        uncheckedBorderColor = GuardTextPrimary.copy(alpha = 0.15f)
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
                            color = GuardTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = { showAddAppsDialog = false },
                            modifier = Modifier
                                .background(GuardTextPrimary.copy(alpha = 0.05f), CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Dialog",
                                tint = GuardTextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = searchApps,
                        onValueChange = { searchApps = it },
                        placeholder = { Text("Search installed apps...", color = GuardTextSecondary) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = GuardTextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
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
                            .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.04f)), RoundedCornerShape(16.dp))
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
                                                color = GuardTextPrimary,
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
                                                uncheckedThumbColor = GuardTextPrimary.copy(alpha = 0.4f),
                                                uncheckedTrackColor = GuardTextPrimary.copy(alpha = 0.08f),
                                                uncheckedBorderColor = GuardTextPrimary.copy(alpha = 0.15f)
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
                        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_done), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}


@Composable
fun SettingsView(viewModel: MainViewModel, isServiceEnabled: Boolean, context: Context, onRequestAccessibility: () -> Unit, onNavigateToAppInfo: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val contextCurrent = androidx.compose.ui.platform.LocalContext.current
    var showClearHistory by remember { mutableStateOf(false) }
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            containerColor = GuardSurface,
            title = { Text("Clear Local History?") },
            text = { Text("All recorded usage and decisions will be deleted. Monitoring and active timers will pause. Your monitored apps and preferences stay. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showClearHistory = false; viewModel.clearAllLogs() }) {
                    Text("Clear History", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } }
        )
    }

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
                text = androidx.compose.ui.res.stringResource(com.example.R.string.ui_settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = GuardTextPrimary,
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

            com.example.ui.MonitoringControls(isServiceEnabled, onRequestAccessibility)

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                            color = GuardTextPrimary
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
            val currentModeLabel = if (timerMode == SessionManager.TIMER_MODE_CLEAR_ON_LOCK) "Clear On Lock" else "Persistent"

            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                                color = GuardTextPrimary
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
                                title = "Clear On Lock",
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
                        BorderStroke(1.dp, if (strictMode) Color.Red.copy(alpha = 0.4f) else GuardTextPrimary.copy(alpha = 0.05f)),
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
                            color = GuardTextPrimary
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
                            uncheckedThumbColor = GuardTextPrimary.copy(alpha = 0.4f),
                            uncheckedTrackColor = GuardTextPrimary.copy(alpha = 0.08f),
                            uncheckedBorderColor = GuardTextPrimary.copy(alpha = 0.15f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            com.example.ui.ScheduleProfilesControls()
            Spacer(modifier = Modifier.height(12.dp))
            val appsForBudgets by viewModel.installedApps.collectAsStateWithLifecycle()
            com.example.ui.SharedBudgetControls(appsForBudgets.filter { it.isMonitored })

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

            com.example.ui.AppearanceControls()
            Spacer(Modifier.height(12.dp))

            // Blurred prompt background — neutral card, independent of sarcastic mode.
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
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
                            color = GuardTextPrimary
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sarcastic mode — turns red only when enabled, entirely on its own.
            Card(
                colors = CardDefaults.cardColors(containerColor = if (sarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, if (sarcasticMode) Color.Red.copy(alpha = 0.5f) else GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
            ) {
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
                            color = GuardTextPrimary
                        )
                        Text(
                            text = if (sarcasticMode) "Your limits now come with commentary." else "Optional, escalating wit about your scrolling choices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sarcasticMode) Color(0xFFEF5350).copy(alpha = 0.9f) else GuardTextSecondary
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = sarcasticMode,
                        onCheckedChange = { isChecked ->
                            sarcasticMode = isChecked
                            prefs.edit().putBoolean("sarcastic_mode", isChecked).apply()
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = GuardTextPrimary,
                            checkedTrackColor = Color.Red.copy(alpha = 0.7f)
                        )
                    )
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
                                "Clear Local History",
                                fontWeight = FontWeight.Bold,
                                color = GuardTextPrimary
                            )
                            Text(
                                "Delete recorded usage and decisions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        Button(
                            onClick = { showClearHistory = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Clear", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            com.example.ui.PrivacyControls()

            Spacer(modifier = Modifier.height(24.dp))

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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                                contentDescription = "Optional Support",
                                tint = GuardMintAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Buy Me A Coffee",
                                fontWeight = FontWeight.Bold,
                                color = GuardTextPrimary
                            )
                            Text(
                                text = "Fuel the development of utilities.",
                                style = MaterialTheme.typography.bodySmall,
                                color = GuardTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Support options",
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                                text = "About The Developer",
                                fontWeight = FontWeight.Bold,
                                color = GuardTextPrimary,
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

                    androidx.compose.material3.HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.05f))

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
                                text = "App Specific Information",
                                fontWeight = FontWeight.Bold,
                                color = GuardTextPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Lets Understand Nudge!",
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
fun HowItWorksScrollView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Screen title (the back action lives in the bottom bar, replacing the nav buttons)
            Column {
                Text(
                    text = "Security & Mechanics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GuardTextPrimary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Under the hood of Nudge!",
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardTextSecondary,
                    fontFamily = FontFamily.Monospace
                )
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                            color = GuardTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Most digital platforms are designed to trigger dopamine loops, keeping you engaged through mindless scrolling. Nudge! introduces conscious pauses back into your routine.\n\n" +
                                "By introducing an immediate conscious choice with optional timer limits when opening target apps, we break the automatic hand-to-screen muscle memory and shift your mental state from passive consumption to active decision making.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardTextPrimary.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Default,
                        lineHeight = 22.sp
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                            color = GuardTextPrimary,
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
                                    color = GuardTextPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GuardTextPrimary.copy(alpha = 0.7f),
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                            color = GuardTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "After your in-app consent and Android approval, Nudge! uses AccessibilityService package-change events while running in the background.\n\n" +
                            "Selected apps trigger reminders, session timers, and daily quotas. Foreground usage intervals and your choices stay in local history for the dashboard and weekly summaries.\n\n" +
                            "Nudge! does not request screen-content access, read messages or passwords, or click controls in other apps. Android Settings and uninstall routes stay available. Reminder timing depends on Android and your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardTextPrimary.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Default,
                        lineHeight = 22.sp
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(16.dp))
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
                        title = "Event-driven monitoring",
                        description = "Android notifies Nudge! of app changes. While a monitored app is active, usage is checkpointed every 30 seconds and a quota check is scheduled. Timers use a foreground service. Battery use and reminder timing vary by device; there is no fixed battery-drain guarantee."
                    )
                }
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
                color = GuardTextPrimary,
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
                .border(BorderStroke(1.dp, if (isCompleted) GuardMintAccent else GuardTextPrimary.copy(alpha = 0.3f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(Icons.Default.Check, contentDescription = androidx.compose.ui.res.stringResource(com.example.R.string.ui_done), tint = GuardBlack, modifier = Modifier.size(16.dp))
            } else {
                Text(stepNumber, color = GuardTextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = GuardTextPrimary)
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
    return enabledServicesSetting.split(':').any { ComponentName.unflattenFromString(it) == expectedService }
}

fun openAccessibilitySettings(context: Context) {
    if (!AccessibilityConsent.isAccepted(context)) return
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

@Composable
private fun formatQuotaLabel(minutes: Int): String {
    if (minutes <= 0) return androidx.compose.ui.res.stringResource(com.example.R.string.ui_off)
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
    val usageRevision by SessionManager.usageRevision.collectAsStateWithLifecycle()
    val usedMinutes = remember(item.packageName, item.dailyQuotaMinutes, usageRevision) {
        SessionManager.getQuotaConsumedMinutesToday(item.packageName)
    }

    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
        HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.05f))
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Daily Quota",
                fontWeight = FontWeight.Bold,
                color = GuardTextPrimary,
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
                inactiveTrackColor = GuardTextPrimary.copy(alpha = 0.1f)
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
                        .background(if (selected) GuardMintAccent.copy(alpha = 0.15f) else GuardTextPrimary.copy(alpha = 0.04f))
                        .border(
                            BorderStroke(1.dp, if (selected) GuardMintAccent.copy(alpha = 0.4f) else GuardTextPrimary.copy(alpha = 0.06f)),
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
                        text = if (preset == 0) androidx.compose.ui.res.stringResource(com.example.R.string.ui_off) else formatQuotaLabel(preset),
                        color = if (selected) GuardMintAccent else GuardTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (quotaMinutes > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            val usedFraction = (usedMinutes.toFloat() / quotaMinutes).coerceIn(0f, 1f)
            val ringColor = if (usedMinutes >= quotaMinutes) Color(0xFFEF5350) else GuardMintAccent
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuotaRing(
                    fraction = usedFraction,
                    color = ringColor,
                    diameter = 46.dp,
                    stroke = 5.dp,
                    label = "${(usedFraction * 100).toInt()}%"
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Used today",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "$usedMinutes / $quotaMinutes min",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (usedMinutes >= quotaMinutes) Color(0xFFEF5350) else GuardTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Foreground time during the selected schedule counts toward this budget.",
            style = MaterialTheme.typography.bodySmall,
            color = GuardTextSecondary,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(16.dp))
        com.example.ui.AppRuleControls(item.packageName)
    }
}

fun launchUpiDonation(context: Context) {
    // Opens any installed UPI app pre-filled with the merchant VPA. Entirely voluntary.
    val uri = android.net.Uri.parse("upi://pay").buildUpon()
        .appendQueryParameter("pa", "gpay-12199931519@okbizaxis")
        .appendQueryParameter("pn", "Nudge")
        .appendQueryParameter("cu", "INR")
        .build()
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
internal fun SupportOptionsDialog(onDismiss: () -> Unit, onUpi: () -> Unit, onKofi: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = GuardSurface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Let's Have A Coffee",
                    fontWeight = FontWeight.Bold,
                    color = GuardTextPrimary,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Entirely voluntary and unlocks nothing - no extra features and no changes to the app. Nudge! stays completely free and ad-free for everyone, with every feature already included. Think of it as an optional/voluntary gesture, nothing more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardTextSecondary,
                    fontFamily = FontFamily.Default,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(20.dp))

                PaymentMethodRow(
                    iconRes = R.drawable.ic_pay_upi,
                    name = "UPI",
                    subtitle = "Pay via any UPI app",
                    onClick = onUpi
                )
                Spacer(modifier = Modifier.height(10.dp))
                PaymentMethodRow(
                    iconRes = R.drawable.ic_pay_kofi,
                    name = "Ko-Fi",
                    subtitle = "Share some Ko-Fi",
                    onClick = onKofi
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(GuardSurfaceItem)
            .border(
                BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.3f)),
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GuardTextPrimary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = iconRes),
                contentDescription = name,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontWeight = FontWeight.Bold,
                color = GuardTextPrimary,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = GuardTextSecondary
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = GuardMintAccent,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun TimerModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.05f))
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
                color = if (selected) GuardMintAccent else GuardTextPrimary,
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
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(GuardMintAccent.copy(alpha = 0.08f), Color.Transparent)
                                    )
                                )
                        )
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
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Nudge!",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = GuardTextPrimary
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = GuardTextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
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
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.04f)), RoundedCornerShape(16.dp))
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
                                        color = GuardTextPrimary,
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
                                        uncheckedColor = GuardTextPrimary.copy(alpha = 0.2f),
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
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(GuardMintAccent.copy(alpha = 0.08f), Color.Transparent)
                            )
                        )
                )
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Nudge!",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = GuardTextPrimary
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
                        BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)),
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
                        color = GuardTextPrimary,
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
                    text = androidx.compose.ui.res.stringResource(com.example.R.string.ui_continue),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
