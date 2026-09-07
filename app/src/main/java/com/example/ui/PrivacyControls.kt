package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.SessionManager
import com.example.ui.theme.*

@Composable
fun PrivacyControls() {
    var showPrivacy by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }

    Text(stringResource(com.example.R.string.ui_privacy_section), style = MaterialTheme.typography.labelSmall, color = GuardTextSecondary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    DataTransferControls()
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsBlock(stringResource(com.example.R.string.ui_privacy_data), stringResource(com.example.R.string.ui_privacy_summary),
            Icons.Default.PrivacyTip, onClick = { showPrivacy = true }, modifier = Modifier.testTag("setting-privacy"))
        SettingsBlock(stringResource(com.example.R.string.ui_withdraw), stringResource(com.example.R.string.ui_withdraw_summary),
            Icons.Default.DoNotDisturbOn, onClick = { showWithdraw = true }, modifier = Modifier.testTag("setting-withdraw"))
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            containerColor = GuardSurface,
            title = { Text(stringResource(com.example.R.string.ui_privacy_data)) },
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
}