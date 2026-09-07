package com.example.domain

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.data.SessionHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Central owner of the mindful timers.
 *
 * Each monitored app has its own independent countdown (see [activeTimers]); several apps can be
 * timed at the same time. A single [sessionState] drives the prompt / "time's up" overlay, since
 * only one of those is ever on screen at a time.
 */
object SessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var initialized = false
    private val historyJobs = mutableSetOf<Job>()
    private var bootCount = -1

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
    private var quotaJob: Job? = null
    private var usageCheckpointJob: Job? = null
    private var usageStartElapsed: Long = 0L
    private var monitoredNames: Map<String, String> = emptyMap()
    private var donationStartedAt: Long? = null
    var overlayVisible = false
        private set

    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        if (visible) flushForegroundUsage()
    }

    fun beginDonationFlow() {
        flushForegroundUsage()
        donationStartedAt = SystemClock.elapsedRealtime()
        isDonationFlowActive = true
    }

    fun endDonationFlow() {
        isDonationFlowActive = false
        donationStartedAt = null
    }

    fun donationInProgress(): Boolean {
        val started = donationStartedAt ?: return false
        if (SystemClock.elapsedRealtime() - started >= 300_000L) endDonationFlow()
        return isDonationFlowActive
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isMasterGuardEnabled.value = prefs.getBoolean("master_guard_enabled", true) && AccessibilityConsent.isAccepted(context)
        _timerMode.value = prefs.getInt(KEY_TIMER_MODE, TIMER_MODE_CLEAR_ON_LOCK)
        _strictModeEnabled.value = prefs.getBoolean(KEY_STRICT_MODE, false)
        if (_isMasterGuardEnabled.value) restoreSessions()
        else prefs.edit().remove(KEY_ACTIVE_SESSIONS).apply()
    }

    fun setMasterGuardEnabled(enabled: Boolean) {
        val allowed = enabled && appContext?.let(AccessibilityConsent::isAccepted) == true
        if (!allowed) {
            flushForegroundUsage()
            _isMasterGuardEnabled.value = false
            resetAll()
            lastUserAppPackage = null
            endDonationFlow()
        } else {
            _isMasterGuardEnabled.value = true
        }
        appContext?.let { ctx ->
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("master_guard_enabled", allowed).apply()
            if (allowed) refreshNotification() else com.example.service.MonitorService.stop(ctx)
        }
    }

    fun withdrawConsent() {
        setMasterGuardEnabled(false)
        appContext?.let(AccessibilityConsent::decline)
    }

    suspend fun clearHistory(repository: ScreenGuardRepository) {
        setMasterGuardEnabled(false)
        historyJobs.toList().joinAll()
        repository.clearHistory()
        appContext?.let { com.example.service.NudgeWidgetProvider.triggerUpdate(it) }
    }

    fun setTimerMode(mode: Int) {
        if (mode != TIMER_MODE_PERSISTENT && mode != TIMER_MODE_CLEAR_ON_LOCK) return
        _timerMode.value = mode
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_TIMER_MODE, mode)?.apply()
    }

    fun setStrictModeEnabled(enabled: Boolean) {
        _strictModeEnabled.value = enabled
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_STRICT_MODE, enabled)?.apply()
        scheduleQuotaCheck()
    }

    private fun todayKey(): Long = java.time.LocalDate.now().toEpochDay()

    /** Recorded foreground seconds for [packageName] today. */
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

    private fun consumeQuota(packageName: String, seconds: Int, day: Long = todayKey()) {
        if (seconds <= 0) return
        if (day != todayKey()) return
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val today = day
        val storedDay = prefs.getLong(KEY_QUOTA_DAY_PREFIX + packageName, -1L)
        val current = if (storedDay == today) prefs.getInt(KEY_QUOTA_USED_PREFIX + packageName, 0) else 0
        prefs.edit()
            .putLong(KEY_QUOTA_DAY_PREFIX + packageName, today)
            .putInt(KEY_QUOTA_USED_PREFIX + packageName, (current.toLong() + seconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
        scheduleQuotaCheck()
    }

    fun setMonitoredApps(apps: List<com.example.data.MonitoredApp>) {
        val enabled = apps.filter { it.isEnabled }
        val names = enabled.associate { it.packageName to it.appName }
        if (usageTrackedPkg != null && usageTrackedPkg !in names) flushForegroundUsage()
        monitoredNames = names
        quotaMinutesByPackage = enabled.filter { it.dailyQuotaMinutes > 0 }.associate { it.packageName to it.dailyQuotaMinutes }
        _activeTimers.value.keys.filter { it !in names }.forEach(::resetSessionForPackage)
        scheduleQuotaCheck()
    }

    /** Configured daily quota (minutes) for [packageName], or 0 if none. */
    fun getQuotaMinutes(packageName: String): Int = quotaMinutesByPackage[packageName] ?: 0

    /** [packageName] is now the foreground app; bank the previous app's elapsed dwell first. */
    fun noteForegroundUsage(packageName: String) {
        if (!_isMasterGuardEnabled.value || appContext?.let(AccessibilityConsent::isAccepted) != true) return
        if (packageName !in monitoredNames) {
            flushForegroundUsage()
            return
        }
        if (usageTrackedPkg == packageName) return
        flushForegroundUsage()
        usageTrackedPkg = packageName
        usageStartTs = System.currentTimeMillis()
        usageStartElapsed = SystemClock.elapsedRealtime()
        scheduleQuotaCheck()
        usageCheckpointJob = scope.launch {
            while (usageTrackedPkg == packageName && _isMasterGuardEnabled.value) {
                delay(30_000L)
                if (usageTrackedPkg == packageName) bankForegroundUsage()
            }
        }
    }

    /** Adds the time spent in the tracked foreground app to its daily quota consumption. */
    fun flushForegroundUsage() {
        bankForegroundUsage()
        usageTrackedPkg = null
        usageStartTs = 0L
        usageStartElapsed = 0L
        quotaJob?.cancel()
        quotaJob = null
        usageCheckpointJob?.cancel()
        usageCheckpointJob = null
    }

    private fun bankForegroundUsage() {
        val pkg = usageTrackedPkg ?: return
        val start = usageStartTs
        val elapsed = (SystemClock.elapsedRealtime() - usageStartElapsed).coerceAtLeast(0L)
        usageStartTs = System.currentTimeMillis()
        usageStartElapsed = SystemClock.elapsedRealtime()
        if (appContext == null) return
        val appName = monitoredNames[pkg] ?: pkg.substringAfterLast('.')
        val slices = splitUsageByDay(start, elapsed)
        for (slice in slices) {
            consumeQuota(pkg, slice.seconds, slice.date.toEpochDay())
            recordEvent(pkg, appName, SessionAction.USAGE, seconds = slice.seconds, timestamp = slice.startMillis)
        }
    }

    /** Consumed seconds today including the in-progress foreground dwell (if any) for [packageName]. */
    private fun liveConsumedSeconds(packageName: String): Int {
        var consumed = getQuotaConsumedSecondsToday(packageName)
        if (usageTrackedPkg == packageName && usageStartTs > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - usageStartElapsed).coerceAtLeast(0L)
            val live = splitUsageByDay(usageStartTs, elapsed).filter { it.date.toEpochDay() == todayKey() }.sumOf { it.seconds.toLong() }
            consumed = (consumed.toLong() + live).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return consumed
    }

    private fun scheduleQuotaCheck() {
        quotaJob?.cancel()
        quotaJob = null
        val pkg = usageTrackedPkg ?: return
        val quota = getQuotaMinutes(pkg)
        if (quota <= 0 || !_isMasterGuardEnabled.value) return
        val remaining = quota * 60L - liveConsumedSeconds(pkg)
        if (remaining <= 0 && !_strictModeEnabled.value) return
        quotaJob = scope.launch {
            delay(maxOf(remaining * 1_000L, 1L))
            if (usageTrackedPkg != pkg || !_isMasterGuardEnabled.value) return@launch
            if (!isDailyQuotaExhausted(pkg, getQuotaMinutes(pkg))) {
                scheduleQuotaCheck()
                return@launch
            }
            val name = monitoredNames[pkg] ?: return@launch
            flushForegroundUsage()
            if (startQuotaBlock(pkg, name, _strictModeEnabled.value)) launchExpiredOverlay(pkg, name)
        }
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
        return timer.endElapsedRealtime > SystemClock.elapsedRealtime()
    }

    /**
     * Attempts to show the prompt for [packageName]. Returns false if a prompt for the same app
     * is already in flight (duplicate suppression).
     */
    fun startPrompt(packageName: String, appName: String): Boolean {
        if (!_isMasterGuardEnabled.value || appContext?.let(AccessibilityConsent::isAccepted) != true) return false
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
        if (!_isMasterGuardEnabled.value || appContext?.let(AccessibilityConsent::isAccepted) != true) return false
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
        if (!_isMasterGuardEnabled.value || durationMinutes !in 1..180) return
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        extensionCounts[packageName] = 0
        addOrReplaceTimer(packageName, appName, durationMinutes * 60, SessionAction.STARTED, repository)
        _sessionState.value = SessionState.Idle
        noteForegroundUsage(packageName)
    }

    fun extendSession(packageName: String, appName: String, extraMinutes: Int, repository: ScreenGuardRepository) {
        if (!_isMasterGuardEnabled.value || extraMinutes !in 1..180) return
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        extensionCounts[packageName] = (extensionCounts[packageName] ?: 0) + 1
        addOrReplaceTimer(packageName, appName, extraMinutes * 60, SessionAction.EXTENDED, repository)
        _sessionState.value = SessionState.Idle
        noteForegroundUsage(packageName)
    }

    private fun addOrReplaceTimer(
        packageName: String,
        appName: String,
        durationSeconds: Int,
        actionLabel: String,
        repository: ScreenGuardRepository
    ) {
        val endTimeStamp = System.currentTimeMillis() + durationSeconds * 1000L
        val timer = ActiveTimer(packageName, appName, durationSeconds, endTimeStamp,
            actionLabel = actionLabel, extensionCount = extensionCountFor(packageName))
        _activeTimers.value = _activeTimers.value +
            (packageName to timer)
        persistSessions()
        recordEvent(packageName, appName, actionLabel, eventId = timer.eventId + ":choice", timestamp = timer.startTime)
        launchTimerJob(timer)
        refreshNotification()
    }

    private fun launchTimerJob(timer: ActiveTimer) {
        val packageName = timer.packageName
        val appName = timer.appName
        timerJobs.remove(packageName)?.cancel()
        val job = scope.launch {
            val wait = timer.endElapsedRealtime - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            // Abort if this timer was replaced or reset while we were waiting.
            if (_activeTimers.value[packageName]?.eventId != timer.eventId) return@launch

            _activeTimers.value = _activeTimers.value - packageName
            timerJobs.remove(packageName)
            persistSessions()
            recordEvent(packageName, appName, SessionAction.TIMER_FINISHED, eventId = timer.eventId + ":end", timestamp = timer.endTimeStamp)

            if (lastUserAppPackage == packageName && _isMasterGuardEnabled.value &&
                appContext?.let(AccessibilityConsent::isAccepted) == true && !overlayVisible && !donationInProgress()) {
                flushForegroundUsage()
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
        if (!_isMasterGuardEnabled.value || !AccessibilityConsent.isAccepted(ctx)) return
        if (ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
        val overlayIntent = Intent(ctx, com.example.service.OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("pkg", packageName)
            putExtra("name", appName)
        }
        try {
            ctx.startActivity(overlayIntent)
        } catch (exception: android.content.ActivityNotFoundException) {
            resetState()
        } catch (exception: SecurityException) {
            resetState()
        }
    }

    fun bypassApp(packageName: String, appName: String, repository: ScreenGuardRepository) {
        if (!_isMasterGuardEnabled.value) return
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        _sessionState.value = SessionState.Idle
        bypassedAppsTemp.add(packageName)
        recordEvent(packageName, appName, SessionAction.BYPASSED)
        noteForegroundUsage(packageName)
    }

    /** Clears the current prompt/expiry overlay. Does not touch running timers. */
    fun resetState() {
        isPromptInFlight.set(false)
        promptInFlightPackage = null
        _sessionState.value = SessionState.Idle
    }

    /** User dismissed the initial prompt without starting a timer -> logged as an early close/resist. */
    fun logPromptResisted(packageName: String, appName: String, repository: ScreenGuardRepository) {
        flushForegroundUsage()
        resetState()
        recordEvent(packageName, appName, SessionAction.CLOSED)
    }

    /** Terminates the running timer for [packageName] (e.g. the notification's Reset button). */
    fun resetSessionForPackage(packageName: String) {
        bypassedAppsTemp.remove(packageName)
        timerJobs.remove(packageName)?.cancel()
        val timer = _activeTimers.value[packageName]
        if (timer != null) {
            _activeTimers.value = _activeTimers.value - packageName
            persistSessions()
            recordEvent(timer.packageName, timer.appName, SessionAction.TIMER_CANCELLED, eventId = timer.eventId + ":end")
        }
        val s = _sessionState.value
        if ((s is SessionState.Prompting && s.packageName == packageName) ||
            (s is SessionState.Expired && s.packageName == packageName) ||
            (s is SessionState.QuotaExhausted && s.packageName == packageName)
        ) {
            _sessionState.value = SessionState.Idle
        }
        refreshNotification()
    }

    /** Terminates every running timer (clear-on-lock behavior). */
    fun resetAll() {
        flushForegroundUsage()
        bypassedAppsTemp.clear()
        val timers = _activeTimers.value.values.toList()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        _activeTimers.value = emptyMap()
        persistSessions()
        resetState()
        extensionCounts.clear()
        timers.forEach { recordEvent(it.packageName, it.appName, SessionAction.TIMER_CANCELLED, eventId = it.eventId + ":end") }
        refreshNotification()
    }

    internal fun stopForProcessRecreationTest() {
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        quotaJob?.cancel()
        usageCheckpointJob?.cancel()
        usageTrackedPkg = null
        _activeTimers.value = emptyMap()
        extensionCounts.clear()
        resetState()
        initialized = false
    }

    private fun recordEvent(
        packageName: String,
        appName: String,
        action: String,
        seconds: Int = 0,
        timestamp: Long = System.currentTimeMillis(),
        eventId: String = UUID.randomUUID().toString()
    ) {
        val ctx = appContext ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val repository = ScreenGuardRepository(AppDatabase.getDatabase(ctx).dao())
                repository.insertSession(
                    SessionHistory(
                        packageName = packageName,
                        appName = appName,
                        startTime = timestamp,
                        durationSeconds = seconds,
                        actionTaken = action,
                        eventId = eventId
                    )
                )
                com.example.service.NudgeWidgetProvider.triggerUpdate(ctx)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                android.util.Log.e("NudgeHistory", "Could not save local history", exception)
            }
        }
        historyJobs.add(job)
        job.invokeOnCompletion { historyJobs.remove(job) }
        job.start()
    }

    private fun refreshNotification() {
        appContext?.let {
            if (_isMasterGuardEnabled.value && AccessibilityConsent.isAccepted(it)) com.example.service.MonitorService.refresh(it)
            else com.example.service.MonitorService.stop(it)
        }
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
                    .put("start", timer.startTime)
                    .put("eventId", timer.eventId)
                    .put("action", timer.actionLabel)
                    .put("extensions", timer.extensionCount)
                    .put("endElapsed", timer.endElapsedRealtime)
                    .put("boot", bootCount)
            )
        }
        prefs.edit().putString(KEY_ACTIVE_SESSIONS, array.toString()).apply()
    }

    private fun restoreSessions() {
        val ctx = appContext ?: return
        if (_activeTimers.value.isNotEmpty()) return
        val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SESSIONS, null) ?: return

        val restored = mutableMapOf<String, ActiveTimer>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val endTs = obj.getLong("endTs")
                val pkg = obj.getString("pkg")
                val total = obj.getInt("total")
                if (total !in 1..10_800) continue
                val sameBoot = bootCount >= 0 && obj.optInt("boot", -2) == bootCount
                val remaining = if (sameBoot) obj.optLong("endElapsed", 0L) - SystemClock.elapsedRealtime()
                    else (endTs - System.currentTimeMillis()).coerceAtMost(total * 1_000L)
                val timer = ActiveTimer(
                    pkg, obj.getString("name"), total, endTs,
                    actionLabel = obj.optString("action", SessionAction.STARTED),
                    eventId = obj.optString("eventId", "legacy:$pkg:$endTs"),
                    extensionCount = obj.optInt("extensions", 0),
                    startTime = obj.optLong("start", endTs - total * 1_000L),
                    endElapsedRealtime = SystemClock.elapsedRealtime() + remaining
                )
                if (obj.has("action")) recordEvent(pkg, timer.appName, timer.actionLabel, eventId = timer.eventId + ":choice", timestamp = timer.startTime)
                if (remaining > 0) {
                    restored[pkg] = timer
                    extensionCounts[pkg] = timer.extensionCount
                } else {
                    recordEvent(pkg, timer.appName, SessionAction.TIMER_FINISHED, eventId = timer.eventId + ":end", timestamp = endTs)
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
        for (timer in restored.values) {
            launchTimerJob(timer)
        }
        persistSessions()
        refreshNotification()
    }
}

data class ActiveTimer(
    val packageName: String,
    val appName: String,
    val totalSeconds: Int,
    val endTimeStamp: Long,
    val actionLabel: String = SessionAction.STARTED,
    val eventId: String = UUID.randomUUID().toString(),
    val extensionCount: Int = 0,
    val startTime: Long = endTimeStamp - totalSeconds * 1_000L,
    val endElapsedRealtime: Long = SystemClock.elapsedRealtime() + (endTimeStamp - System.currentTimeMillis()).coerceIn(0L, totalSeconds * 1_000L)
)

sealed class SessionState {
    object Idle : SessionState()
    data class Prompting(val packageName: String, val appName: String) : SessionState()
    data class Expired(val packageName: String, val appName: String) : SessionState()
    data class QuotaExhausted(val packageName: String, val appName: String, val strict: Boolean) : SessionState()
}
