package com.example.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.SessionManager
import com.example.service.AppAccessibilityService
import com.example.service.OverlayActivity
import com.example.ui.theme.*

@Composable
fun MonitoringControls(serviceEnabled: Boolean, onRequestAccessibility: () -> Unit) {
    val context = LocalContext.current
    val connected by AppAccessibilityService.connected.collectAsStateWithLifecycle()
    val lastEvent by AppAccessibilityService.lastEventAt.collectAsStateWithLifecycle()
    val enabled by SessionManager.isMasterGuardEnabled.collectAsStateWithLifecycle()
    val pauseUntil by SessionManager.pauseUntil.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (connected && enabled) Icons.Default.CheckCircle else Icons.Default.Info, null)
        Spacer(Modifier.width(8.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_monitoring_status), modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse" else "Expand")
    }
    if (pauseUntil != null) {
        Text("Paused until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(pauseUntil!!))}", color = GuardMintAccent)
        TextButton(onClick = { SessionManager.setMasterGuardEnabled(true) }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_resume)) }
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (serviceEnabled) "Android accessibility: enabled" else "Android accessibility: not enabled", color = GuardTextPrimary)
            Text(if (connected) "Service: connected" else "Service: not connected", color = GuardTextPrimary)
            Text(if (com.example.areNotificationsEnabled(context)) "Notifications: allowed" else "Notifications: not allowed", color = GuardTextPrimary)
            Text(lastEvent?.let { "Last app event: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it))}" }
                ?: "No foreground events received yet", color = GuardTextSecondary)
            if (!serviceEnabled || !connected) {
                OutlinedButton(onClick = onRequestAccessibility) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_review_access)) }
            }
            OutlinedButton(onClick = { com.example.openAppNotificationSettings(context) }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_notifications)) }
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, OverlayActivity::class.java).putExtra("preview", true))
            }) {
                Icon(Icons.Default.Visibility, null)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_preview_prompt))
            }
            if (enabled) {
                Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_pause), color = GuardTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        OutlinedButton(onClick = { SessionManager.pauseFor(minutes) }, modifier = Modifier.weight(1f)) {
                            Text("${minutes}m")
                        }
                    }
                }
            }
            HorizontalDivider(color = GuardTextSecondary.copy(alpha = 0.2f))
        }
    }
}