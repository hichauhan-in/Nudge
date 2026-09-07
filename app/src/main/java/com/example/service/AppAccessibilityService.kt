package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.data.MonitoredApp
import com.example.domain.AccessibilityConsent
import com.example.domain.AppSafety
import com.example.domain.SessionManager
import com.example.domain.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: ScreenGuardRepository
    private var launcherPackagesCache: Set<String>? = null
    private var monitoredApps: Map<String, MonitoredApp> = emptyMap()
    private var monitoredAppsReady = false
    private var pendingForeground: Pair<String, String>? = null
    private var lastOverlayPackage: String? = null
    private var lastOverlayLaunch = 0L
    private var foreground: Pair<String, String>? = null
    private var boundaryJob: kotlinx.coroutines.Job? = null
    private val inputMethods: Set<String> by lazy {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.inputMethodList?.map { it.packageName }?.toSet().orEmpty()
    }
    private val consentListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AccessibilityConsent.ACCEPTED_KEY && !AccessibilityConsent.isAccepted(this)) {
            SessionManager.setMasterGuardEnabled(false)
            disableSelf()
        }
    }

    // Resets timers when the phone is locked (only acts in CLEAR_ON_LOCK mode).
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // Screen off = the user stopped using the app; bank its foreground time.
                SessionManager.flushForegroundUsage()
                foreground = null
                boundaryJob?.cancel()
                SessionManager.lastUserAppPackage = null
                if (SessionManager.timerMode.value == SessionManager.TIMER_MODE_CLEAR_ON_LOCK) {
                    SessionManager.resetAll()
                }
            }
        }
    }

    companion object {
        val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val lastEventAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(this)
        repository = ScreenGuardRepository(database.dao())
        SessionManager.init(this)
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        getSharedPreferences(AccessibilityConsent.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(consentListener)

        // Keep the set of quota-enabled packages current so foreground usage is charged correctly.
        serviceScope.launch {
            repository.allMonitoredApps.collect { apps ->
                val eligible = apps.filter { !AppSafety.isProtected(it.packageName, packageName) && !isSystemLauncher(it.packageName) }
                monitoredApps = eligible.filter { it.isEnabled }.associateBy { it.packageName }
                SessionManager.setMonitoredApps(eligible)
                monitoredAppsReady = true
                pendingForeground?.let { (foregroundPackage, foregroundClass) ->
                    pendingForeground = null
                    handleForeground(foregroundPackage, foregroundClass)
                }
            }
        }
        serviceScope.launch {
            com.example.data.FocusSettings.configuration.collect {
                if (monitoredAppsReady) {
                    SessionManager.setMonitoredApps(monitoredApps.values.toList())
                    foreground?.let { (pkg, cls) -> handleForeground(pkg, cls) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AccessibilityConsent.isAccepted(this)) {
            disableSelf()
            return
        }
        connected.value = true
        // Pin the process in memory so the running countdown survives leaving a monitored app.
        if (SessionManager.isMasterGuardEnabled.value) {
            MonitorService.start(this)
        }
    }

    override fun onDestroy() {
        connected.value = false
        serviceScope.cancel()
        SessionManager.flushForegroundUsage()
        SessionManager.lastUserAppPackage = null
        getSharedPreferences(AccessibilityConsent.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(consentListener)
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!AccessibilityConsent.isAccepted(this)) return
        SessionManager.resumeIfDue()
        if (!SessionManager.isMasterGuardEnabled.value) return

        // We only care about which app comes to the foreground.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        lastEventAt.value = System.currentTimeMillis()

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        if (!monitoredAppsReady) {
            pendingForeground = packageName to className
            return
        }
        handleForeground(packageName, className)
    }

    private fun handleForeground(packageName: String, className: String) {
        if (!AccessibilityConsent.isAccepted(this) || !SessionManager.isMasterGuardEnabled.value) return
        if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != true ||
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return
        if (SessionManager.donationInProgress()) return
        if (packageName == this.packageName) {
            foreground = null
            boundaryJob?.cancel()
            SessionManager.flushForegroundUsage()
            return
        }
        when (com.example.domain.ForegroundClassifier.classify(packageName, className, inputMethods)) {
            com.example.domain.ForegroundKind.INPUT_METHOD -> return
            com.example.domain.ForegroundKind.SYSTEM_OVERLAY -> {
                foreground = null
                boundaryJob?.cancel()
                SessionManager.flushForegroundUsage()
                SessionManager.lastUserAppPackage = null
                return
            }
            com.example.domain.ForegroundKind.APP -> Unit
        }

        if (isSystemLauncher(packageName) || AppSafety.isProtected(packageName, this.packageName)) {
            // On the home screen; the monitored app is no longer in front. A still-valid
            // countdown keeps running silently in the background; only an already-expired
            // session (or a dismissed quota gate) is discarded so the next open starts fresh.
            SessionManager.resetState()
            SessionManager.flushForegroundUsage()
            clearBypassExcept(null)
            SessionManager.lastUserAppPackage = null
            foreground = null
            boundaryJob?.cancel()
            return
        }

        // A real, user-facing app is now in the foreground.
        SessionManager.lastUserAppPackage = packageName
        foreground = packageName to className
        boundaryJob?.cancel()
        val boundary = com.example.data.FocusSettings.configuration.value.schedule(packageName)
            ?.nextBoundary(java.time.ZonedDateTime.now())
        if (boundary != null) {
            boundaryJob = serviceScope.launch {
                kotlinx.coroutines.delay((boundary.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1L))
                if (foreground?.first == packageName) handleForeground(packageName, className)
            }
        }
        clearBypassExcept(packageName)
        val monitoredApp = monitoredApps[packageName]
        if (monitoredApp == null) {
            SessionManager.flushForegroundUsage()
            SessionManager.resetState()
            return
        }
        if (!SessionManager.isScheduledNow(packageName)) {
            SessionManager.flushForegroundUsage()
            SessionManager.resetSessionForPackage(packageName)
            SessionManager.resetState()
            return
        }
        if (SessionManager.showCooldown(packageName, monitoredApp.appName)) {
            launchOverlay(packageName)
            return
        }
        if (SessionManager.strictModeEnabled.value && SessionManager.isDailyQuotaExhausted(packageName, monitoredApp.dailyQuotaMinutes)) {
            SessionManager.startQuotaBlock(packageName, monitoredApp.appName, true)
            launchOverlay(packageName)
            return
        }
        val previousState = SessionManager.sessionState.value
        if (previousState is SessionState.QuotaExhausted && !SessionManager.isDailyQuotaExhausted(packageName, monitoredApp.dailyQuotaMinutes)) {
            SessionManager.resetState()
        }
        if (previousState is SessionState.Cooldown && SessionManager.cooldownRemainingMillis(packageName) == 0L) {
            SessionManager.resetState()
        }
        val state = SessionManager.sessionState.value
        val pendingPackage = when (state) {
            is SessionState.Prompting -> state.packageName
            is SessionState.Expired -> state.packageName
            is SessionState.QuotaExhausted -> state.packageName
            else -> null
        }
        if (pendingPackage == packageName) {
            launchOverlay(packageName)
            return
        }
        if (pendingPackage != null) SessionManager.resetState()
        if (SessionManager.hasValidActiveTimer(packageName) || SessionManager.bypassedAppsTemp.contains(packageName)) {
            SessionManager.noteForegroundUsage(packageName)
            return
        }
        SessionManager.flushForegroundUsage()
        if (SessionManager.isDailyQuotaExhausted(packageName, monitoredApp.dailyQuotaMinutes)) {
            SessionManager.startQuotaBlock(packageName, monitoredApp.appName, SessionManager.strictModeEnabled.value)
        } else {
            SessionManager.startPrompt(packageName, monitoredApp.appName)
        }
        launchOverlay(packageName)
    }

    /**
     * Removes temporary bypass entries that don't belong to [keepPackage]. A bypass lasts
     * only while the user stays on the bypassed app; navigating elsewhere clears it.
     */
    private fun clearBypassExcept(keepPackage: String?) {
        if (SessionManager.bypassedAppsTemp.isEmpty() || SessionManager.donationInProgress()) return
        val iterator = SessionManager.bypassedAppsTemp.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() != keepPackage) iterator.remove()
        }
    }

    override fun onInterrupt() {
        SessionManager.flushForegroundUsage()
        SessionManager.lastUserAppPackage = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected.value = false
        SessionManager.setMasterGuardEnabled(false)
        return super.onUnbind(intent)
    }

    /**
     * Launch the OverlayActivity for a given package.
     */
    private fun launchOverlay(packageName: String) {
        if (!AccessibilityConsent.isAccepted(this) || !SessionManager.isMasterGuardEnabled.value || SessionManager.overlayVisible) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastOverlayPackage == packageName && now - lastOverlayLaunch < 600L) return
        lastOverlayPackage = packageName
        lastOverlayLaunch = now
        SessionManager.flushForegroundUsage()
        val appLabel = getAppLabel(packageName)
        val overlayIntent = Intent(this@AppAccessibilityService, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("pkg", packageName)
            putExtra("name", appLabel)
        }
        try {
            startActivity(overlayIntent)
        } catch (exception: android.content.ActivityNotFoundException) {
            SessionManager.resetState()
        } catch (exception: SecurityException) {
            SessionManager.resetState()
        }
    }

    private fun getLauncherPackages(): Set<String> {
        var cached = launcherPackagesCache
        if (cached == null) {
            cached = try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                }
                val list = packageManager.queryIntentActivities(intent, 0)
                list.mapNotNull { it.activityInfo?.packageName }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            launcherPackagesCache = cached
        }
        return cached
    }

    private fun isSystemLauncher(packageName: String): Boolean {
        val launchers = getLauncherPackages()
        return launchers.contains(packageName)
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }
}
