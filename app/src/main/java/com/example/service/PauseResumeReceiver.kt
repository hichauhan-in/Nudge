package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.SessionManager

class PauseResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SessionManager.init(context)
        SessionManager.resumeIfDue()
    }
}