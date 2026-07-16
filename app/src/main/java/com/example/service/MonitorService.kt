package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import com.example.MainActivity
import com.example.R
import com.example.domain.ActiveTimer
import com.example.domain.SessionManager

/**
 * Foreground service that (a) keeps the app process alive while any timer is running so the
 * countdowns are never lost, and (b) shows a single collapsible status notification.
 *
 * Collapsed, the summary lists the apps that are currently being timed. Expanded, it shows one
 * row per app with a live countdown and a "Reset timer" action. When no timers are running the
 * service shows nothing and stops itself.
 */
class MonitorService : Service() {

    private val postedChildIds = mutableSetOf<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Make sure timers are restored if the process was just recreated.
        SessionManager.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timers = SessionManager.activeTimers.value.values.sortedBy { it.appName.lowercase() }
        try {
            startForeground(SUMMARY_ID, buildSummary(timers))
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (timers.isEmpty()) {
            cancelChildren()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        postChildren(timers)
        return START_STICKY
    }

    private fun postChildren(timers: List<ActiveTimer>) {
        val nm = getSystemService(NotificationManager::class.java)
        val currentIds = timers.map { childId(it.packageName) }.toSet()
        // Drop notifications for timers that are no longer running.
        for (id in postedChildIds.toSet()) {
            if (id !in currentIds) {
                nm.cancel(id)
                postedChildIds.remove(id)
            }
        }
        for (timer in timers) {
            val id = childId(timer.packageName)
            nm.notify(id, buildChild(timer))
            postedChildIds.add(id)
        }
    }

    private fun cancelChildren() {
        val nm = getSystemService(NotificationManager::class.java)
        for (id in postedChildIds.toSet()) nm.cancel(id)
        postedChildIds.clear()
    }

    private fun buildSummary(timers: List<ActiveTimer>): Notification {
        ensureChannel()
        val names = timers.joinToString(", ") { it.appName }
        val title = when (timers.size) {
            0 -> "Focus guard active"
            1 -> "1 timer running"
            else -> "${timers.size} timers running"
        }
        val text = if (timers.isEmpty()) "No active timers" else names
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_widget_lock)
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openAppIntent())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun buildChild(timer: ActiveTimer): Notification {
        ensureChannel()
        val resetAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_widget_lock),
            "Reset timer",
            resetIntent(timer.packageName)
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(timer.appName)
            .setContentText("Time remaining")
            .setSmallIcon(R.drawable.ic_widget_lock)
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setShowWhen(true)
            .setWhen(timer.endTimeStamp)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openAppIntent())
            .addAction(resetAction)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active timers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the apps with a running mindful timer."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

    private fun resetIntent(packageName: String): PendingIntent {
        val intent = Intent(this, TimerActionReceiver::class.java).apply {
            action = TimerActionReceiver.ACTION_RESET_TIMER
            putExtra(TimerActionReceiver.EXTRA_PACKAGE, packageName)
        }
        return PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun childId(packageName: String): Int {
        val id = packageName.hashCode()
        return if (id == SUMMARY_ID) id + 1 else id
    }

    companion object {
        private const val SUMMARY_ID = 4711
        private const val CHANNEL_ID = "nudge_active_timers"
        private const val GROUP_KEY = "com.example.ACTIVE_TIMERS"

        /** Starts or refreshes the status notification from the current set of running timers. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                // Background start can be rejected on newer Android; correctness is preserved by
                // the in-memory + persisted timers regardless of the notification.
            }
        }

        /** Rebuilds the notification (or stops the service) after the timer set changes. */
        fun refresh(context: Context) = start(context)

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
