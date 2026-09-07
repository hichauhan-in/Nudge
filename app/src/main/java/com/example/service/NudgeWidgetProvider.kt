package com.example.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NudgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bounds = com.example.domain.HistoryDates.dayBounds(java.time.LocalDate.now())
                val today = AppDatabase.getDatabase(context).dao().getWidgetTotals(bounds.first, bounds.second)
                for (appWidgetId in appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId, today)
                }
            } catch (exception: Exception) {
                android.util.Log.e("NudgeWidget", "Could not update local widget statistics", exception)
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, NudgeWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "com.example.action.UPDATE_WIDGET"
        private val updatePending = java.util.concurrent.atomic.AtomicBoolean(false)

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            today: com.example.data.WidgetTotals = com.example.data.WidgetTotals()
        ) {
            val views = RemoteViews(context.packageName, R.layout.nudge_widget_layout)

            // Dynamic PendingIntent to launch MainActivity when clicking the widget card
            val configIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configPendingIntent = PendingIntent.getActivity(
                context,
                0,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nudge_widget_root, configPendingIntent)
            views.setImageViewResource(R.id.widget_logo, R.drawable.ic_widget_lock)
            views.setTextViewText(R.id.widget_pauses_count, "${today.resisted} resisted today")
            views.setTextViewText(R.id.widget_savings, "${today.seconds / 60} min recorded today")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun triggerUpdate(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            if (manager.getAppWidgetIds(ComponentName(appContext, NudgeWidgetProvider::class.java)).isEmpty()) return
            if (!updatePending.compareAndSet(false, true)) return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updatePending.set(false)
                appContext.sendBroadcast(Intent(appContext, NudgeWidgetProvider::class.java).apply {
                    action = ACTION_UPDATE_WIDGET
                })
            }, 1_000L)
        }
    }
}
