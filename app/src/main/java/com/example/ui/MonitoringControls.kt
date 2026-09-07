package com.example.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var showReset by remember { mutableStateOf(false) }
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
    if (showStatus && !showDisable && !showReset) {
        EditorDialog(
            onDismissRequest = { showStatus = false },
            title = { Text(stringResource(R.string.ui_monitoring_status), color = GuardTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    MonitoringSection(stringResource(R.string.ui_status_section), "monitoring-status-section") {
                        MonitoringStatusRow(stringResource(R.string.ui_status_accessibility),
                            stringResource(if (serviceEnabled) R.string.ui_status_enabled else R.string.ui_status_not_enabled), serviceEnabled)
                        MonitoringStatusRow(stringResource(R.string.ui_status_service),
                            stringResource(if (connected) R.string.ui_status_connected else R.string.ui_status_not_connected), connected)
                        MonitoringStatusRow(stringResource(R.string.ui_status_notifications),
                            stringResource(if (notificationsEnabled) R.string.ui_status_allowed else R.string.ui_status_not_allowed), notificationsEnabled)
                        Text(lastEvent?.let { stringResource(R.string.ui_last_app_event, java.text.DateFormat.getTimeInstance().format(java.util.Date(it))) }
                            ?: stringResource(R.string.ui_no_app_events), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    MonitoringSection(stringResource(R.string.ui_monitoring_section), "monitoring-controls-section") {
                        if (!serviceEnabled || !connected) {
                            val hasConsent = AccessibilityConsent.isAccepted(context)
                            OutlinedButton(onClick = {
                                if (hasConsent) com.example.openAccessibilitySettings(context)
                                else { showStatus = false; onRequestAccessibility() }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Accessibility, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (hasConsent) R.string.ui_open_accessibility_settings else R.string.ui_review_access))
                            }
                        }
                        pauseUntil?.let { deadline ->
                            Text(stringResource(R.string.ui_paused_until, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(deadline))),
                                color = GuardMintAccent, style = MaterialTheme.typography.bodySmall)
                        }
                        if (serviceEnabled && !enabled) {
                            Text(stringResource(R.string.ui_monitoring_paused), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { SessionManager.setMasterGuardEnabled(true) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ui_resume))
                            }
                        }
                        if (serviceEnabled && enabled) {
                            Text(stringResource(R.string.ui_timed_pause), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30, 60).forEach { minutes ->
                                    OutlinedButton(onClick = { SessionManager.pauseFor(minutes) }, modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.ui_minutes_short, minutes))
                                    }
                                }
                            }
                            OutlinedButton(onClick = { SessionManager.setMasterGuardEnabled(false) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Pause, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ui_pause))
                            }
                        }
                        if (serviceEnabled || AccessibilityConsent.isAccepted(context)) {
                            TextButton(onClick = { showDisable = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.DoNotDisturbOn, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ui_disable_guard), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    MonitoringSection(stringResource(R.string.ui_reminders_section), "monitoring-reminders-section") {
                        OutlinedButton(onClick = { com.example.openAppNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Notifications, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_notifications))
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(context, OverlayActivity::class.java).putExtra("preview", true))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Visibility, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_preview_prompt))
                        }
                    }
                    MonitoringSection(stringResource(R.string.ui_recovery_section), "monitoring-recovery-section") {
                        Text(stringResource(R.string.ui_android_recovery_summary), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, context.getString(R.string.ui_android_recovery_fallback), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_android_app_settings))
                        }
                        TextButton(onClick = { showReset = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_clear_app_data), color = MaterialTheme.colorScheme.error)
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
    if (showReset) {
        AlertDialog(onDismissRequest = { showReset = false }, containerColor = GuardSurface,
            title = { Text(stringResource(R.string.ui_erase_data_question)) },
            text = { Text(stringResource(R.string.ui_erase_data_detail)) },
            confirmButton = { TextButton(onClick = {
                SessionManager.withdrawConsent()
                context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
            }) { Text(stringResource(R.string.ui_erase_data), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.ui_cancel)) } })
    }
}

@Composable
private fun MonitoringSection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.08f))
        Text(title, modifier = Modifier.padding(top = 4.dp).semantics { heading() }, color = GuardMintAccent,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        content()
    }
}

@Composable
private fun MonitoringStatusRow(label: String, value: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, color = if (active) GuardMintAccent else GuardTextSecondary,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}