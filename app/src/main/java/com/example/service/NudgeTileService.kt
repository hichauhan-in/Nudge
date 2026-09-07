package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.domain.AccessibilityConsent
import com.example.domain.SessionManager

class NudgeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        SessionManager.init(this)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            SessionManager.init(this)
            if (!AccessibilityConsent.isAccepted(this) || !com.example.isAccessibilityServiceEnabled(this)) {
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 1802, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    startActivity(intent)
                }
            } else if (SessionManager.isMasterGuardEnabled.value) {
                SessionManager.pauseFor(15)
            } else {
                SessionManager.setMasterGuardEnabled(true)
            }
            updateTile()
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            val enabled = SessionManager.isMasterGuardEnabled.value && com.example.isAccessibilityServiceEnabled(this@NudgeTileService)
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Nudge!"
            subtitle = if (enabled) "Pause 15 min" else "Resume monitoring"
            contentDescription = "Nudge! ${subtitle}"
            updateTile()
        }
    }
}