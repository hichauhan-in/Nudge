package com.example.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.FocusSettings
import com.example.domain.*
import com.example.ui.theme.*
import java.util.UUID

@Composable
fun AppRuleControls(packageName: String) {
    val configuration by FocusSettings.configuration.collectAsStateWithLifecycle()
    val rule = configuration.rule(packageName)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_schedule), rule.profileId, listOf(null to androidx.compose.ui.res.stringResource(com.example.R.string.ui_always)) + configuration.profiles.map { it.id to it.name }) {
            FocusSettings.updateRule(packageName, rule.copy(profileId = it))
        }
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_preferred_timer), rule.preferredMinutes, listOf(2, 5, 10, 15, 20, 30, 45, 60).map { it to "$it min" }) {
            FocusSettings.updateRule(packageName, rule.copy(preferredMinutes = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_remember_duration), Modifier.weight(1f), color = GuardTextPrimary)
            Switch(rule.rememberDuration, onCheckedChange = { FocusSettings.updateRule(packageName, rule.copy(rememberDuration = it)) })
        }
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_tone), rule.tone, listOf(PromptTone.DEFAULT to androidx.compose.ui.res.stringResource(com.example.R.string.ui_default_setting), PromptTone.GENTLE to androidx.compose.ui.res.stringResource(com.example.R.string.ui_gentle), PromptTone.SARCASTIC to androidx.compose.ui.res.stringResource(com.example.R.string.ui_sarcastic))) {
            FocusSettings.updateRule(packageName, rule.copy(tone = it))
        }
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_extension_limit), rule.maxExtensions, listOf(0 to androidx.compose.ui.res.stringResource(com.example.R.string.ui_unlimited)) + (1..5).map { it to it.toString() }) {
            FocusSettings.updateRule(packageName, rule.copy(maxExtensions = it))
        }
        if (rule.maxExtensions > 0) {
            RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cooldown_after), rule.cooldownMinutes, listOf(0 to androidx.compose.ui.res.stringResource(com.example.R.string.ui_off)) + listOf(5, 10, 15, 30, 60).map { it to "$it min" }) {
                FocusSettings.updateRule(packageName, rule.copy(cooldownMinutes = it))
            }
        }
    }
}

@Composable
internal fun <Value> RuleMenu(label: String, selected: Value, options: List<Pair<Value, String>>, onSelect: (Value) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = GuardTextSecondary, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString(), Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, "Choose $label")
            }
        }
    }
    if (expanded) {
        SettingsChoiceDialog(label, selected, options, onDismiss = { expanded = false }) {
            expanded = false
            onSelect(it)
        }
    }
}

@Composable
fun ScheduleProfilesControls() {
    val configuration by FocusSettings.configuration.collectAsStateWithLifecycle()
    var showProfiles by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FocusProfile?>(null) }
    val title = stringResource(com.example.R.string.ui_profiles)
    SettingsBlock(title, stringResource(com.example.R.string.ui_profiles_summary), Icons.Default.Schedule,
        onClick = { showProfiles = true }, modifier = Modifier.testTag("setting-profiles"))
    if (showProfiles && editing == null) {
        EditorDialog(
            onDismissRequest = { showProfiles = false },
            title = { Text(title, color = GuardTextPrimary) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    configuration.profiles.forEach { profile ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, color = GuardTextPrimary)
                                Text("${clockLabel(profile.schedule.startMinute)} - ${clockLabel(profile.schedule.endMinute)}", color = GuardTextSecondary)
                            }
                            IconButton(onClick = { editing = profile }) { Icon(Icons.Default.Edit, "Edit ${profile.name}") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = FocusProfile(UUID.randomUUID().toString(), "", FocusSchedule()) },
                    enabled = configuration.profiles.size < 30) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(com.example.R.string.ui_add_profile))
                }
            },
            dismissButton = { TextButton(onClick = { showProfiles = false }) { Text(stringResource(com.example.R.string.ui_close)) } }
        )
    }
    editing?.let { profile ->
        ProfileEditor(profile, onDismiss = { editing = null }, onSave = { updated ->
            FocusSettings.update(configuration.copy(profiles = configuration.profiles.filterNot { it.id == updated.id } + updated))
            editing = null
        }, onDelete = if (configuration.profiles.any { it.id == profile.id }) ({
            FocusSettings.update(configuration.copy(profiles = configuration.profiles.filterNot { it.id == profile.id },
                rules = configuration.rules.mapValues { (_, rule) -> if (rule.profileId == profile.id) rule.copy(profileId = null) else rule }))
            editing = null
        }) else null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(profile: FocusProfile, onDismiss: () -> Unit, onSave: (FocusProfile) -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var days by remember(profile.id) { mutableStateOf(profile.schedule.days) }
    var start by remember(profile.id) { mutableIntStateOf(profile.schedule.startMinute) }
    var end by remember(profile.id) { mutableIntStateOf(profile.schedule.endMinute) }
    var confirmDelete by remember { mutableStateOf(false) }
    EditorDialog(onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, onValueChange = { name = it.take(40) }, label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_name)) }, singleLine = true)
                java.time.DayOfWeek.values().toList().chunked(4).forEach { weekDays ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weekDays.forEach { day ->
                            FilterChip(selected = day.value in days, onClick = {
                                days = if (day.value in days) days - day.value else days + day.value
                            }, label = { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                                maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> start = hour * 60 + minute }, start / 60, start % 60, true).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("Start: ${clockLabel(start)}") }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> end = hour * 60 + minute }, end / 60, end % 60, true).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("End: ${clockLabel(end)}") }
                Text(if (start == end) "All day on selected days" else if (end < start) "Ends the following day" else "Same-day window", color = GuardTextSecondary)
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_delete_profile), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && days.isNotEmpty(), onClick = {
            onSave(profile.copy(name = name.trim(), schedule = FocusSchedule(days, start, end)))
        }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete This Profile?") },
            text = { Text("Apps assigned to this profile will return to Always monitoring.") },
            confirmButton = { TextButton(onClick = { onDelete?.invoke() }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
    }
}

private fun clockLabel(minute: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

@Composable
fun CooldownScreen(state: SessionState.Cooldown, onClose: () -> Unit) {
    var remaining by remember(state.packageName) { mutableLongStateOf(SessionManager.cooldownRemainingMillis(state.packageName)) }
    LaunchedEffect(state.packageName, state.until) {
        while (remaining > 0L) {
            kotlinx.coroutines.delay(1_000L)
            remaining = SessionManager.cooldownRemainingMillis(state.packageName)
        }
        if (SessionManager.sessionState.value is SessionState.Cooldown) {
            SessionManager.resetState()
            SessionManager.startPrompt(state.packageName, state.appName)
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(Icons.Default.HourglassEmpty, null, tint = GuardMintAccent, modifier = Modifier.size(48.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cooldown), style = MaterialTheme.typography.headlineSmall, color = GuardTextPrimary)
        Text(state.appName, color = GuardTextSecondary)
        Text("${(remaining + 999) / 60_000}m ${((remaining + 999) / 1000) % 60}s", color = GuardMintAccent)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_close_app)) }
        Text("You can change this app's rules or pause monitoring in Nudge!.", color = GuardTextSecondary)
    }
}