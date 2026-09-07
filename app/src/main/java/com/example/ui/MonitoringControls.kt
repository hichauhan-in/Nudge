package com.example.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.AccessibilityConsent
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
    var showStatus by rememberSaveable { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(com.example.areNotificationsEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = com.example.areNotificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsBlock(
        title = stringResource(if (serviceEnabled) R.string.ui_guard_service else R.string.ui_enable_guard_service),
        subtitle = stringResource(when {
            serviceEnabled && enabled -> R.string.ui_guard_active_summary
            serviceEnabled -> R.string.ui_guard_paused_summary
            else -> R.string.ui_guard_disabled_summary
        }),
        icon = if (serviceEnabled) Icons.Default.Check else Icons.Default.Warning,
        onClick = { showStatus = true },
        modifier = Modifier.testTag("setting-guard-service")
    )
    if (showStatus && !showDisable) {
        EditorDialog(
            onDismissRequest = { showStatus = false },
            title = { Text(stringResource(R.string.ui_monitoring_status), color = GuardTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(if (serviceEnabled) "Android accessibility: enabled" else "Android accessibility: not enabled", color = GuardTextPrimary)
                    Text(if (connected) "Service: connected" else "Service: not connected", color = GuardTextPrimary)
                    Text(if (notificationsEnabled) "Notifications: allowed" else "Notifications: not allowed", color = GuardTextPrimary)
                    Text(lastEvent?.let { "Last app event: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it))}" }
                        ?: "No foreground events received yet", color = GuardTextSecondary)
                    if (!serviceEnabled || !connected) {
                        val hasConsent = AccessibilityConsent.isAccepted(context)
                        OutlinedButton(onClick = {
                            if (hasConsent) com.example.openAccessibilitySettings(context)
                            else { showStatus = false; onRequestAccessibility() }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (hasConsent) R.string.ui_open_accessibility_settings else R.string.ui_review_access))
                        }
                    }
                    OutlinedButton(onClick = { com.example.openAppNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_notifications))
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, OverlayActivity::class.java).putExtra("preview", true))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Visibility, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_preview_prompt))
                    }
                    pauseUntil?.let { deadline ->
                        Text("Paused until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(deadline))}", color = GuardMintAccent)
                    }
                    if (serviceEnabled && !enabled) {
                        Button(onClick = { SessionManager.setMasterGuardEnabled(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ui_resume))
                        }
                    }
                    if (serviceEnabled && enabled) {
                        HorizontalDivider(color = GuardTextSecondary.copy(alpha = 0.2f))
                        Text(stringResource(R.string.ui_pause), color = GuardTextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { minutes ->
                                OutlinedButton(onClick = { SessionManager.pauseFor(minutes) }, modifier = Modifier.weight(1f)) {
                                    Text("${minutes}m")
                                }
                            }
                        }
                        OutlinedButton(onClick = { SessionManager.setMasterGuardEnabled(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ui_pause))
                        }
                    }
                    if (serviceEnabled || AccessibilityConsent.isAccepted(context)) {
                        TextButton(onClick = { showDisable = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ui_disable_guard), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showStatus = false }) { Text(stringResource(R.string.ui_close)) } }
        )
    }
    if (showDisable) {
        AlertDialog(onDismissRequest = { showDisable = false }, containerColor = GuardSurface,
            title = { Text(stringResource(R.string.ui_disable_guard_question)) },
            text = { Text(stringResource(R.string.ui_disable_guard_detail)) },
            confirmButton = { TextButton(onClick = {
                SessionManager.withdrawConsent()
                showDisable = false
                showStatus = false
            }) { Text(stringResource(R.string.ui_disable_guard), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDisable = false }) { Text(stringResource(R.string.ui_cancel)) } })
    }
}