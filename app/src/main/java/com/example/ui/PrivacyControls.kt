package com.example.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.SessionManager
import com.example.ui.theme.*

@Composable
fun PrivacyControls() {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    Text("PRIVACY & RECOVERY", style = MaterialTheme.typography.labelSmall, color = GuardTextSecondary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    DataTransferControls()
    TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PrivacyTip, null)
        Spacer(Modifier.width(12.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_privacy_data), modifier = Modifier.weight(1f))
    }
    TextButton(onClick = { showWithdraw = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.DoNotDisturbOn, null)
        Spacer(Modifier.width(12.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_withdraw), modifier = Modifier.weight(1f))
    }
    TextButton(onClick = { showRecovery = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.SettingsBackupRestore, null)
        Spacer(Modifier.width(12.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_recovery), modifier = Modifier.weight(1f))
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            containerColor = GuardSurface,
            title = { Text("Privacy policy") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nudge! by hichauhan. Updated 7 September 2026.")
                    Text("After your consent and Android approval, Nudge! uses AccessibilityService foreground package names and window identifiers while running in the background. It records monitored app names, package names, foreground usage times, timer choices, schedules, and daily limits on this device. This powers reminders, individual and shared budgets, cooldowns, history, and summaries.")
                    Text("Nudge! does not request screen-content access, read messages or passwords, or click controls in other apps. Launchable apps and home-screen apps are visible through scoped package queries. There is no QUERY_ALL_PACKAGES or Internet permission, no ads, analytics SDK, account, or data upload by Nudge!.")
                    Text("History stays in private app storage until you clear it or uninstall. Android backup and transfer of app data are disabled. Older releases recorded timer-duration estimates; those records are retained without being relabeled as measured usage.")
                    Text("Optional CSV export writes readable history to a location you select in Android's document picker. Optional backups encrypt history, app selections, rules, and selected preferences with AES-256-GCM and a key derived from your passphrase using PBKDF2-HMAC-SHA256. Consent, active timers, and cooldowns are not included. Backups are limited to 32 MB of plaintext; CSV export is paged. We cannot recover your passphrase.")
                    Text("Your chosen storage provider may upload exported files under its own policy. These exports are separate from Android automatic backup. Deleting app history or uninstalling does not delete exported files; remove them through your storage provider. Restoring replaces history and app selections, leaves monitoring off, and requires new accessibility consent. Existing quota usage for today is not reduced by restore.")
                    Text("The Quick Settings tile pauses monitoring for 15 minutes or resumes it only after existing consent. Timed pause uses an inexact alarm, so Android power management can delay resuming. No exact-alarm, storage, usage-access, or Internet permission is requested.")
                    Text("Optional UPI, Ko-fi, website links, and Google Play reviews use other apps or Google Play. Those providers process their own data under their policies. Nudge! does not receive payment credentials or review text. A tip unlocks nothing.")
                    Text("You can pause monitoring, withdraw consent, delete local history, clear app storage, or uninstall at any time. Nudge! is not a parental-control or device-administration app. No PIN, uninstall block, or factory reset is required.")
                    Text("Privacy contact: hichauhan.in@gmail.com\nWebsite: https://hichauhan.in")
                }
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_close)) } }
        )
    }
    if (showWithdraw) {
        AlertDialog(
            onDismissRequest = { showWithdraw = false },
            containerColor = GuardSurface,
            title = { Text("Withdraw consent?") },
            text = { Text("Monitoring and active timers will stop. Existing local history stays until you delete it. You can enable monitoring again only after reviewing the disclosure and agreeing.") },
            confirmButton = {
                TextButton(onClick = {
                    SessionManager.withdrawConsent()
                    showWithdraw = false
                }) { Text("Withdraw consent") }
            },
            dismissButton = { TextButton(onClick = { showWithdraw = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } }
        )
    }
    if (showRecovery) {
        AlertDialog(
            onDismissRequest = { showRecovery = false },
            containerColor = GuardSurface,
            title = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_recovery)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("You remain in control. Android Settings and uninstall screens are never guarded by Nudge!.")
                    Text("To remove access: Android Settings > Accessibility > Installed apps > Nudge! > Off. Labels vary by device.")
                    Text("To reset or uninstall: Android Settings > Apps > Nudge! > Storage > Clear storage, or Uninstall. Force stop is available if the app is unresponsive. A factory reset is not needed.")
                    OutlinedButton(onClick = { SessionManager.setMasterGuardEnabled(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_pause))
                    }
                    OutlinedButton(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(context, "Open Android Settings > Apps > Nudge!", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open Android app settings") }
                    TextButton(onClick = { showRecovery = false; showReset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear all local app data", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecovery = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_close)) } }
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = GuardSurface,
            title = { Text("Erase all local data?") },
            text = { Text("This permanently deletes your history, monitored apps, timers, preferences, and consent. Nudge! will close and start fresh next time. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    SessionManager.withdrawConsent()
                    context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
                }) { Text("Erase all data", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } }
        )
    }
}