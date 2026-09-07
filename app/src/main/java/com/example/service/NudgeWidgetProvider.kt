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
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
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

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
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
            views.setTextViewText(R.id.widget_pauses_count, "0 pauses")
            views.setTextViewText(R.id.widget_savings, "0 min saved")
            appWidgetManager.updateAppWidget(appWidgetId, views)

            // Compute statistics and update with real data post-launch
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val sessions = db.dao().getAllSessionsFlow().first()
                    
                    val totalPauses = sessions.size
                    val minutesSaved = (totalPauses * 4.5f).toInt()

                    withContext(Dispatchers.Main) {
                        views.setTextViewText(R.id.widget_pauses_count, "$totalPauses pauses")
                        views.setTextViewText(R.id.widget_savings, "$minutesSaved min saved")
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        views.setTextViewText(R.id.widget_pauses_count, "0 pauses")
                        views.setTextViewText(R.id.widget_savings, "0 min saved")
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            }
        }

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, NudgeWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
    }
}
