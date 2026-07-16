package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.domain.SessionManager
import com.example.domain.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var repository: ScreenGuardRepository
    private var launcherPackagesCache: Set<String>? = null

    // Resets timers when the phone is locked (only acts in CLEAR_ON_LOCK mode).
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                SessionManager.timerMode.value == SessionManager.TIMER_MODE_CLEAR_ON_LOCK
            ) {
                SessionManager.resetAll()
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
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Pin the process in memory so the running countdown survives leaving a monitored app.
        if (SessionManager.isMasterGuardEnabled.value) {
            MonitorService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SessionManager.isMasterGuardEnabled.value) return

        // We only care about which app comes to the foreground.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        val isOurApp = packageName == this.packageName

        // Transient system overlays (notification shade, Circle to Search, keyboard,
        // permission dialogs, screenshots, etc.) and our own overlay must NEVER affect a
        // running session. The app behind them is still "open", so ignore them entirely.
        if (isOurApp || isTransientOverlay(packageName, className)) return

        if (isSystemLauncher(packageName)) {
            // On the home screen; the monitored app is no longer in front. A still-valid
            // countdown keeps running silently in the background; only an already-expired
            // session is discarded so the next open starts fresh.
            if (SessionManager.sessionState.value is SessionState.Expired) SessionManager.resetState()
            clearBypassExcept(null)
            SessionManager.lastUserAppPackage = null
            return
        }

        // Ignore background noise such as heads-up notifications: their event can report
        // another app's package even though the focused window hasn't changed. If the window
        // that actually has focus belongs to a DIFFERENT app that still has a valid timer,
        // this is not a real switch — leave the running session untouched.
        val focusedPkg = currentForegroundPackage()
        if (focusedPkg != null && focusedPkg != packageName && SessionManager.hasValidActiveTimer(focusedPkg)) return

        // A real, user-facing app is now in the foreground.
        SessionManager.lastUserAppPackage = packageName
        clearBypassExcept(packageName)

        // 1. This app already has a valid, unexpired timer -> never re-prompt. Checked against
        //    the persisted end time, so it holds even if the in-memory session was lost
        //    (service restart / process reclaimed while the app sat behind the shade).
        if (SessionManager.hasValidActiveTimer(packageName)) return

        val state = SessionManager.sessionState.value

        // 2. A prompt is pending for this app. Seeing the app's own window here means the
        //    prompt overlay is not in front (it was dismissed / minimized / back-pressed),
        //    so re-show it instead of silently swallowing the open.
        if (state is SessionState.Prompting && state.packageName == packageName) {
            launchOverlay(packageName)
            return
        }

        // 3. Timer expired while this app was in the foreground -> offer to extend.
        if (state is SessionState.Expired && state.packageName == packageName) {
            launchOverlay(packageName)
            return
        }

        // 4. This app was bypassed for the current session -> allow free use.
        if (SessionManager.bypassedAppsTemp.contains(packageName)) return

        // 5. A leftover expired session from another app -> discard it.
        if (state is SessionState.Expired) SessionManager.resetState()

        // 6. Prompt for a fresh timer only if this app is monitored and enabled.
        serviceScope.launch {
            val monitoredApp = repository.getMonitoredApp(packageName)
            if (monitoredApp == null || !monitoredApp.isEnabled) return@launch

            // Re-validate now that we're off the event thread (state may have changed).
            if (SessionManager.hasValidActiveTimer(packageName)) return@launch
            val now = SessionManager.sessionState.value
            if (now is SessionState.Prompting && now.packageName == packageName) {
                launchOverlay(packageName)
                return@launch
            }
            if (now is SessionState.Expired && now.packageName == packageName) {
                launchOverlay(packageName)
                return@launch
            }
            if (SessionManager.bypassedAppsTemp.contains(packageName)) return@launch

            val appLabel = getAppLabel(packageName)
            if (!SessionManager.startPrompt(packageName, appLabel)) return@launch
            launchOverlay(packageName)
        }
    }

    /**
     * Package name of the window that currently holds focus, or null if it can't be
     * determined. Used to tell a real app switch apart from background noise like a
     * heads-up notification (whose event may carry another app's package).
     */
    private fun currentForegroundPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Removes temporary bypass entries that don't belong to [keepPackage]. A bypass lasts
     * only while the user stays on the bypassed app; navigating elsewhere clears it.
     */
    private fun clearBypassExcept(keepPackage: String?) {
        if (SessionManager.bypassedAppsTemp.isEmpty() || SessionManager.isDonationFlowActive) return
        val iterator = SessionManager.bypassedAppsTemp.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() != keepPackage) iterator.remove()
        }
    }

    override fun onInterrupt() {}

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
        val appLabel = getAppLabel(packageName)
        val overlayIntent = Intent(this@AppAccessibilityService, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("pkg", packageName)
            putExtra("name", appLabel)
        }
        startActivity(overlayIntent)
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
        if (launchers.contains(packageName)) return true
        return packageName.contains("launcher", ignoreCase = true) || packageName.contains("home", ignoreCase = true)
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
