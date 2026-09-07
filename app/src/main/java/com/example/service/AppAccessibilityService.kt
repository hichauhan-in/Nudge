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
                SessionManager.lastUserAppPackage = null
                if (SessionManager.timerMode.value == SessionManager.TIMER_MODE_CLEAR_ON_LOCK) {
                    SessionManager.resetAll()
                }
            }
        }
    }

    companion object {
        // Packages that are transient system overlays and should NOT reset a session
        private val TRANSIENT_OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            // Google Assistant / Search / Circle to Search / Lens
            "com.google.android.googlequicksearchbox",
            "com.google.android.search.quicksearchbox",
            "com.google.android.apps.lens",
            "com.google.android.as",  // Android System Intelligence (Circle to Search host)
            "com.google.android.apps.search.omnient",
            // Google services that may overlay
            "com.google.android.gms",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            // Clipboard / text selection
            "com.android.clipboardui",
            "com.samsung.android.clipboarduiservice",
            // Samsung-specific system overlays
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.edgelighting",
            // MIUI/Xiaomi overlays
            "com.miui.securitycenter",
            "com.miui.notification",
            // OPPO/ColorOS overlays
            "com.coloros.notificationmanager",
            // OnePlus overlays
            "com.oneplus.systemui.support"
        )
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AccessibilityConsent.isAccepted(this)) {
            disableSelf()
            return
        }
        // Pin the process in memory so the running countdown survives leaving a monitored app.
        if (SessionManager.isMasterGuardEnabled.value) {
            MonitorService.start(this)
        }
    }

    override fun onDestroy() {
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
        if (!SessionManager.isMasterGuardEnabled.value) return

        // We only care about which app comes to the foreground.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

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
            SessionManager.flushForegroundUsage()
            return
        }
        if (isTransientOverlay(packageName, className)) return

        if (isSystemLauncher(packageName) || AppSafety.isProtected(packageName, this.packageName)) {
            // On the home screen; the monitored app is no longer in front. A still-valid
            // countdown keeps running silently in the background; only an already-expired
            // session (or a dismissed quota gate) is discarded so the next open starts fresh.
            SessionManager.resetState()
            SessionManager.flushForegroundUsage()
            clearBypassExcept(null)
            SessionManager.lastUserAppPackage = null
            return
        }

        // A real, user-facing app is now in the foreground.
        SessionManager.lastUserAppPackage = packageName
        clearBypassExcept(packageName)
        val monitoredApp = monitoredApps[packageName]
        if (monitoredApp == null) {
            SessionManager.flushForegroundUsage()
            SessionManager.resetState()
            return
        }
        if (SessionManager.strictModeEnabled.value && SessionManager.isDailyQuotaExhausted(packageName, monitoredApp.dailyQuotaMinutes)) {
            SessionManager.startQuotaBlock(packageName, monitoredApp.appName, true)
            launchOverlay(packageName)
            return
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

    /**
     * Determines if a package/class represents a transient system overlay that should
     * NOT be treated as the user leaving a monitored app.
     * This includes: notification shade, keyboard, Circle to Search, text selection handles,
     * permission dialogs, Google Assistant overlays, OEM system overlays, etc.
     */
    private fun isTransientOverlay(packageName: String, className: String): Boolean {
        // Check exact known transient packages
        if (TRANSIENT_OVERLAY_PACKAGES.contains(packageName)) return true

        // Check partial matches for system UI / input method variants across OEMs
        val lowerPkg = packageName.lowercase()
        if (lowerPkg.contains("systemui") ||
            lowerPkg.contains("inputmethod") ||
            lowerPkg.contains("keyboard") ||
            lowerPkg.contains("ime.") ||
            lowerPkg.contains(".ime") ||
            lowerPkg.contains("permissioncontroller") ||
            lowerPkg.contains("permissionmanager") ||
            lowerPkg.contains("clipboardui") ||
            lowerPkg.contains("screenshot") ||
            lowerPkg.contains("smartcapture") ||
            lowerPkg.contains("accessibility")
        ) return true

        // Check class names for known transient activities/panels
        val lowerClass = className.lowercase()
        if (lowerClass.contains("popup") ||
            lowerClass.contains("notification") ||
            lowerClass.contains("toast") ||
            lowerClass.contains("permission") ||
            lowerClass.contains("clipboard") ||
            lowerClass.contains("handleview") ||
            lowerClass.contains("selectionactionmode") ||
            lowerClass.contains("floatingtoolbar") ||
            lowerClass.contains("actionmode") ||
            lowerClass.contains("insertionhandle") ||
            lowerClass.contains("cursoranchor")
        ) return true

        return false
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
