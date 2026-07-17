package com.example.domain

import android.content.Context
import android.content.Intent
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.data.SessionHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central owner of the mindful timers.
 *
 * Each monitored app has its own independent countdown (see [activeTimers]); several apps can be
 * timed at the same time. A single [sessionState] drives the prompt / "time's up" overlay, since
 * only one of those is ever on screen at a time.
 */
object SessionManager {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var appContext: Context? = null

    private val _isMasterGuardEnabled = MutableStateFlow(true)
    val isMasterGuardEnabled: StateFlow<Boolean> = _isMasterGuardEnabled

    // Timer behavior (chosen in the "Timer Behavior" setting).
    const val TIMER_MODE_PERSISTENT = 0     // runs until it expires, no matter what (default)
    const val TIMER_MODE_CLEAR_ON_LOCK = 2  // all timers end when the phone is locked
    private const val KEY_TIMER_MODE = "timer_mode"
    private val _timerMode = MutableStateFlow(TIMER_MODE_CLEAR_ON_LOCK)
    val timerMode: StateFlow<Int> = _timerMode

    // Strict mode: when on, an app whose daily quota is spent is blocked entirely.
    private const val KEY_STRICT_MODE = "strict_mode"
    private val _strictModeEnabled = MutableStateFlow(false)
    val strictModeEnabled: StateFlow<Boolean> = _strictModeEnabled

    // Independent per-app countdowns. Drives the status notification and the
    // "is this app already timed" checks.
    private val _activeTimers = MutableStateFlow<Map<String, ActiveTimer>>(emptyMap())
    val activeTimers: StateFlow<Map<String, ActiveTimer>> = _activeTimers
    private val timerJobs = mutableMapOf<String, Job>()
    private val extensionCounts = mutableMapOf<String, Int>()

    // Single overlay/prompt state (only one prompt or expiry sheet is shown at a time).
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState

    val bypassedAppsTemp = mutableSetOf<String>()
    var lastUserAppPackage: String? = null
    var isDonationFlowActive: Boolean = false

    private val isPromptInFlight = AtomicBoolean(false)
    private var promptInFlightPackage: String? = null

    private const val PREFS_NAME = "focus_time_prefs"
    private const val KEY_ACTIVE_SESSIONS = "active_sessions_json"
    private const val KEY_QUOTA_USED_PREFIX = "quota_used_"
    private const val KEY_QUOTA_DAY_PREFIX = "quota_day_"

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isMasterGuardEnabled.value = prefs.getBoolean("master_guard_enabled", true)
        _timerMode.value = prefs.getInt(KEY_TIMER_MODE, TIMER_MODE_CLEAR_ON_LOCK)
        _strictModeEnabled.value = prefs.getBoolean(KEY_STRICT_MODE, false)
        restoreSessions()
    }

    fun setMasterGuardEnabled(enabled: Boolean) {
        _isMasterGuardEnabled.value = enabled
        appContext?.let { ctx ->
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("master_guard_enabled", enabled).apply()
            if (enabled) refreshNotification() else com.example.service.MonitorService.stop(ctx)
        }
    }

    fun setTimerMode(mode: Int) {
        _timerMode.value = mode
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_TIMER_MODE, mode)?.apply()
    }

    fun setStrictModeEnabled(enabled: Boolean) {
        _strictModeEnabled.value = enabled
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_STRICT_MODE, enabled)?.apply()
    }

    private fun todayKey(): Long = java.time.LocalDate.now().toEpochDay()

    /** Seconds of timer granted to [packageName] so far today (resets at local midnight). */
    fun getQuotaConsumedSecondsToday(packageName: String): Int {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return 0
        val day = prefs.getLong(KEY_QUOTA_DAY_PREFIX + packageName, -1L)
        return if (day == todayKey()) prefs.getInt(KEY_QUOTA_USED_PREFIX + packageName, 0) else 0
    }

    fun getQuotaConsumedMinutesToday(packageName: String): Int =
        getQuotaConsumedSecondsToday(packageName) / 60

    /** True if [packageName] has a daily quota and today's actual usage meets/exceeds it. */
    fun isDailyQuotaExhausted(packageName: String, quotaMinutes: Int): Boolean {
        if (quotaMinutes <= 0) return false
        return liveConsumedSeconds(packageName) >= quotaMinutes * 60
    }

    private fun consumeQuota(packageName: String, seconds: Int) {
        if (seconds <= 0) return
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val today = todayKey()
        val storedDay = prefs.getLong(KEY_QUOTA_DAY_PREFIX + packageName, -1L)
        val current = if (storedDay == today) prefs.getInt(KEY_QUOTA_USED_PREFIX + packageName, 0) else 0
        prefs.edit()
            .putLong(KEY_QUOTA_DAY_PREFIX + packageName, today)
            .putInt(KEY_QUOTA_USED_PREFIX + packageName, current + seconds)
            .apply()
    }

    // --- Actual foreground-usage accounting for daily quotas ------------------------------
    // Quota is charged by the REAL time spent in the app (foreground dwell), never by the timer
    // the user grants. Set a 30-min timer but leave after 5 min -> only 5 min is deducted.

    @Volatile private var quotaMinutesByPackage: Map<String, Int> = emptyMap()
    private var usageTrackedPkg: String? = null
    private var usageStartTs: Long = 0L

    /** Packages that currently have a daily quota (> 0) mapped to their limit. Synced by the service. */
    fun setQuotaConfig(config: Map<String, Int>) {
        quotaMinutesByPackage = config
    }

    /** Configured daily quota (minutes) for [packageName], or 0 if none. */
    fun getQuotaMinutes(packageName: String): Int = quotaMinutesByPackage[packageName] ?: 0

    /** [packageName] is now the foreground app; bank the previous app's elapsed dwell first. */
    fun noteForegroundUsage(packageName: String) {
        if (usageTrackedPkg == packageName) return
        flushForegroundUsage()
        usageTrackedPkg = packageName
        usageStartTs = System.currentTimeMillis()
    }

    /** Adds the time spent in the tracked foreground app to its daily quota consumption. */
    fun flushForegroundUsage() {
        val pkg = usageTrackedPkg ?: return
        val elapsedSeconds = ((System.currentTimeMillis() - usageStartTs) / 1000L).toInt()
        usageTrackedPkg = null
        usageStartTs = 0L
        if (elapsedSeconds > 0 && quotaMinutesByPackage.containsKey(pkg)) {
            consumeQuota(pkg, elapsedSeconds)
        }
    }

    /** Consumed seconds today including the in-progress foreground dwell (if any) for [packageName]. */
    private fun liveConsumedSeconds(packageName: String): Int {
        var consumed = getQuotaConsumedSecondsToday(packageName)
        if (usageTrackedPkg == packageName && usageStartTs > 0L) {
            consumed += ((System.currentTimeMillis() - usageStartTs) / 1000L).toInt()
        }
        return consumed
    }

    /** Consumed minutes today including the live foreground dwell. */
    fun getQuotaConsumedMinutesTodayLive(packageName: String): Int = liveConsumedSeconds(packageName) / 60

    /** Minutes of daily quota still available for [packageName] (rounded up), 0 if none/spent. */
    fun getQuotaRemainingMinutes(packageName: String): Int {
        val quota = getQuotaMinutes(packageName)
        if (quota <= 0) return 0
        val remainingSeconds = quota * 60 - liveConsumedSeconds(packageName)
        return if (remainingSeconds <= 0) 0 else (remainingSeconds + 59) / 60
    }

    fun extensionCountFor(packageName: String): Int = extensionCounts[packageName] ?: 0

    /** True if [packageName] currently has a running, unexpired timer. */
    fun hasValidActiveTimer(packageName: String): Boolean {
        val timer = _activeTimers.value[packageName] ?: return false
        return timer.endTimeStamp > System.currentTimeMillis()
    }

    /**
     * Attempts to show the prompt for [packageName]. Returns false if a prompt for the same app
     * is already in flight (duplicate suppression).
     */
    fun startPrompt(packageName: String, appName: String): Boolean {
        val current = _sessionState.value
        if (current is SessionState.Prompting && current.packageName == packageName) return false
        if (isPromptInFlight.get() && promptInFlightPackage == packageName) return false

        isPromptInFlight.set(true)
        promptInFlightPackage = packageName
        _sessionState.value = SessionState.Prompting(packageName, appName)

        scope.launch {
            delay(2000L)
            if (promptInFlightPackage == packageName) {
                isPromptInFlight.set(false)
                promptInFlightPackage = null
            }
        }
        return true
    }

    /**
     * Shows the "daily quota spent" gate for [packageName]. Mirrors [startPrompt]'s duplicate
     * suppression so the red overlay isn't rebuilt on every foreground event.
     */
    fun startQuotaBlock(packageName: String, appName: String, strict: Boolean): Boolean {
        val current = _sessionState.value
        if (current is SessionState.QuotaExhausted && current.packageName == packageName) return false
        if (isPromptInFlight.get() && promptInFlightPackage == packageName) return false

        isPromptInFlight.set(true)
        promptInFlightPackage = packageName
        _sessionState.value = SessionState.QuotaExhausted(packageName, appName, strict)

        scope.launch {
            delay(2000L)
            if (promptInFlightPackage == packageName) {
                isPromptInFlight.set(false)
                promptInFlightPackage = null
            }
        }
        return true
    }

    /** User chose to continue past the quota gate -> fall through to the normal timer prompt. */
    fun proceedPastQuota(packageName: String, appName: String) {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        _sessionState.value = SessionState.Prompting(packageName, appName)
    }

    fun startSession(packageName: String, appName: String, durationMinutes: Int, repository: ScreenGuardRepository) {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        extensionCounts[packageName] = 0
        addOrReplaceTimer(packageName, appName, durationMinutes * 60, "COMPLETED", repository)
        _sessionState.value = SessionState.Idle
    }

    fun extendSession(packageName: String, appName: String, extraMinutes: Int, repository: ScreenGuardRepository) {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        extensionCounts[packageName] = (extensionCounts[packageName] ?: 0) + 1
        addOrReplaceTimer(packageName, appName, extraMinutes * 60, "EXTENDED", repository)
        _sessionState.value = SessionState.Idle
    }

    private fun addOrReplaceTimer(
        packageName: String,
        appName: String,
        durationSeconds: Int,
        actionLabel: String,
        repository: ScreenGuardRepository
    ) {
        val endTimeStamp = System.currentTimeMillis() + durationSeconds * 1000L
        _activeTimers.value = _activeTimers.value +
            (packageName to ActiveTimer(packageName, appName, durationSeconds, endTimeStamp))
        persistSessions()
        launchTimerJob(packageName, appName, durationSeconds, endTimeStamp, actionLabel, repository)
        refreshNotification()
    }

    private fun launchTimerJob(
        packageName: String,
        appName: String,
        durationSeconds: Int,
        endTimeStamp: Long,
        actionLabel: String,
        repository: ScreenGuardRepository
    ) {
        timerJobs.remove(packageName)?.cancel()
        val job = scope.launch {
            val wait = endTimeStamp - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // Abort if this timer was replaced or reset while we were waiting.
            if (_activeTimers.value[packageName]?.endTimeStamp != endTimeStamp) return@launch

            _activeTimers.value = _activeTimers.value - packageName
            timerJobs.remove(packageName)
            persistSessions()
            try {
                repository.insertSession(
                    SessionHistory(
                        packageName = packageName,
                        appName = appName,
                        durationSeconds = durationSeconds,
                        actionTaken = actionLabel
                    )
                )
            } catch (e: Exception) {
                // ignore logging failures
            }
            appContext?.let { com.example.service.NudgeWidgetProvider.triggerUpdate(it) }

            if (lastUserAppPackage == packageName) {
                // Still in the foreground. If the app's daily quota is now spent, gate with the
                // red quota screen; otherwise offer to extend as usual.
                val quota = getQuotaMinutes(packageName)
                _sessionState.value = if (quota > 0 && isDailyQuotaExhausted(packageName, quota)) {
                    SessionState.QuotaExhausted(packageName, appName, _strictModeEnabled.value)
                } else {
                    SessionState.Expired(packageName, appName)
                }
                launchExpiredOverlay(packageName, appName)
            }
            // Otherwise the app is in the background -> silently discard.
            refreshNotification()
        }
        timerJobs[packageName] = job
    }

    private fun launchExpiredOverlay(packageName: String, appName: String) {
        val ctx = appContext ?: return
        val overlayIntent = Intent(ctx, com.example.service.OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("pkg", packageName)
            putExtra("name", appName)
        }
        ctx.startActivity(overlayIntent)
    }

    fun bypassApp(packageName: String, appName: String, repository: ScreenGuardRepository) {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        _sessionState.value = SessionState.Idle
        bypassedAppsTemp.add(packageName)
        scope.launch {
            try {
                repository.insertSession(
                    SessionHistory(
                        packageName = packageName,
                        appName = appName,
                        durationSeconds = 0,
                        actionTaken = "BYPASSED"
                    )
                )
            } catch (e: Exception) {
                // ignore logging failures
            }
            appContext?.let { com.example.service.NudgeWidgetProvider.triggerUpdate(it) }
        }
    }

    /** Clears the current prompt/expiry overlay. Does not touch running timers. */
    fun resetState() {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        _sessionState.value = SessionState.Idle
    }

    /** Terminates the running timer for [packageName] (e.g. the notification's Reset button). */
    fun resetSessionForPackage(packageName: String) {
        bypassedAppsTemp.remove(packageName)
        timerJobs.remove(packageName)?.cancel()
        val timer = _activeTimers.value[packageName]
        if (timer != null) {
            _activeTimers.value = _activeTimers.value - packageName
            persistSessions()
            logPartialUsage(timer)
        }
        val s = _sessionState.value
        if ((s is SessionState.Prompting && s.packageName == packageName) ||
            (s is SessionState.Expired && s.packageName == packageName)
        ) {
            _sessionState.value = SessionState.Idle
        }
        refreshNotification()
    }

    /** Terminates every running timer (clear-on-lock behavior). */
    fun resetAll() {
        bypassedAppsTemp.clear()
        val timers = _activeTimers.value.values.toList()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        _activeTimers.value = emptyMap()
        persistSessions()
        _sessionState.value = SessionState.Idle
        timers.forEach { logPartialUsage(it) }
        refreshNotification()
    }

    private fun logPartialUsage(timer: ActiveTimer) {
        val spent = timer.totalSeconds - ((timer.endTimeStamp - System.currentTimeMillis()) / 1000).toInt()
        if (spent <= 0) return
        val ctx = appContext ?: return
        scope.launch {
            try {
                val repository = ScreenGuardRepository(AppDatabase.getDatabase(ctx).dao())
                repository.insertSession(
                    SessionHistory(
                        packageName = timer.packageName,
                        appName = timer.appName,
                        durationSeconds = spent,
                        actionTaken = "COMPLETED"
                    )
                )
                com.example.service.NudgeWidgetProvider.triggerUpdate(ctx)
            } catch (e: Exception) {
                // ignore logging failures
            }
        }
    }

    private fun refreshNotification() {
        appContext?.let { com.example.service.MonitorService.refresh(it) }
    }

    // --- Persistence of running timers across process death --------------------------------

    private fun persistSessions() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val array = JSONArray()
        for (timer in _activeTimers.value.values) {
            array.put(
                JSONObject()
                    .put("pkg", timer.packageName)
                    .put("name", timer.appName)
                    .put("endTs", timer.endTimeStamp)
                    .put("total", timer.totalSeconds)
            )
        }
        prefs.edit().putString(KEY_ACTIVE_SESSIONS, array.toString()).commit()
    }

    private fun restoreSessions() {
        val ctx = appContext ?: return
        if (_activeTimers.value.isNotEmpty()) return
        val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SESSIONS, null) ?: return

        val now = System.currentTimeMillis()
        val restored = mutableMapOf<String, ActiveTimer>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val endTs = obj.getLong("endTs")
                if (endTs > now) {
                    val pkg = obj.getString("pkg")
                    restored[pkg] = ActiveTimer(pkg, obj.getString("name"), obj.getInt("total"), endTs)
                }
            }
        } catch (e: Exception) {
            return
        }

        if (restored.isEmpty()) {
            persistSessions()
            return
        }

        _activeTimers.value = restored
        val repository = ScreenGuardRepository(AppDatabase.getDatabase(ctx).dao())
        for (timer in restored.values) {
            launchTimerJob(timer.packageName, timer.appName, timer.totalSeconds, timer.endTimeStamp, "COMPLETED", repository)
        }
        persistSessions()
        refreshNotification()
    }
}

data class ActiveTimer(
    val packageName: String,
    val appName: String,
    val totalSeconds: Int,
    val endTimeStamp: Long
)

sealed class SessionState {
    object Idle : SessionState()
    data class Prompting(val packageName: String, val appName: String) : SessionState()
    data class Expired(val packageName: String, val appName: String) : SessionState()
    data class QuotaExhausted(val packageName: String, val appName: String, val strict: Boolean) : SessionState()
}
