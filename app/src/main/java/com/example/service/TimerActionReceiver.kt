package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.SessionManager

/**
 * Handles the "Reset timer" action shown on each app's row in the status notification.
 * Terminates that app's running timer so the next time it is opened a fresh limit is asked.
 */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESET_TIMER) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        // The process may have just been recreated to deliver this broadcast.
        SessionManager.init(context.applicationContext)
        SessionManager.resetSessionForPackage(packageName)
    }

    companion object {
        const val ACTION_RESET_TIMER = "com.example.action.RESET_TIMER"
        const val EXTRA_PACKAGE = "extra_package"
    }
}
