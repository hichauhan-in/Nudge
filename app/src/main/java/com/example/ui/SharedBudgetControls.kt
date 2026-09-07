package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AppDisplayItem
import com.example.data.FocusSettings
import com.example.domain.SharedBudget
import com.example.domain.SessionManager
import com.example.ui.theme.*
import java.util.UUID

@Composable
fun SharedBudgetControls(apps: List<AppDisplayItem>) {
    val configuration by FocusSettings.configuration.collectAsStateWithLifecycle()
    val usageRevision by SessionManager.usageRevision.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SharedBudget?>(null) }
    var deleting by remember { mutableStateOf<SharedBudget?>(null) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Apps, null)
        Spacer(Modifier.width(12.dp))
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_shared_goals), Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (expanded) {
        configuration.budgets.forEach { budget ->
            val used = remember(budget, usageRevision) { budget.packages.sumOf { SessionManager.getQuotaConsumedSecondsToday(it).toLong() } / 60 }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(budget.name, color = GuardTextPrimary)
                    Text("$used / ${budget.minutes} min today", color = GuardTextSecondary)
                }
                IconButton(onClick = { editing = budget }) { Icon(Icons.Default.Edit, "Edit ${budget.name}") }
                IconButton(onClick = { deleting = budget }) { Icon(Icons.Default.Delete, "Delete ${budget.name}") }
            }
        }
        OutlinedButton(onClick = { editing = SharedBudget(UUID.randomUUID().toString(), "", 45, emptySet()) },
            enabled = apps.isNotEmpty() && configuration.budgets.size < 30) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_add_budget))
        }
        if (apps.isEmpty()) Text("No monitored apps", color = GuardTextSecondary)
        Spacer(Modifier.height(12.dp))
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_week_goal), configuration.weeklyGoalMinutes,
            listOf(0 to androidx.compose.ui.res.stringResource(com.example.R.string.ui_off)) + listOf(210, 420, 630, 840, 1260, 1680, 2520).map { it to "${it / 60}h ${it % 60}m" }) {
            FocusSettings.update(configuration.copy(weeklyGoalMinutes = it))
        }
    }
    editing?.let { budget ->
        BudgetEditor(budget, apps, configuration.budgets.filterNot { it.id == budget.id }.flatMap { it.packages }.toSet(),
            onDismiss = { editing = null }, onSave = { updated ->
                FocusSettings.update(configuration.copy(budgets = configuration.budgets.filterNot { it.id == updated.id } + updated))
                editing = null
            })
    }
    deleting?.let { budget ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${budget.name}?") },
            text = { Text("Individual app limits and recorded history will stay.") },
            confirmButton = { TextButton(onClick = {
                FocusSettings.update(configuration.copy(budgets = configuration.budgets.filterNot { it.id == budget.id }))
                deleting = null
            }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_delete)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
    }
}

@Composable
private fun BudgetEditor(budget: SharedBudget, apps: List<AppDisplayItem>, unavailable: Set<String>, onDismiss: () -> Unit, onSave: (SharedBudget) -> Unit) {
    var name by remember(budget.id) { mutableStateOf(budget.name) }
    var minutes by remember(budget.id) { mutableStateOf(budget.minutes.toString()) }
    var packages by remember(budget.id) { mutableStateOf(budget.packages) }
    val valid = name.isNotBlank() && (minutes.toIntOrNull() ?: 0) in 5..1440 && packages.isNotEmpty()
    EditorDialog(onDismissRequest = onDismiss, title = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_shared_budget)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, onValueChange = { name = it.take(40) }, label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_name)) }, singleLine = true)
                OutlinedTextField(minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) }, label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_minutes_daily)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = (minutes.toIntOrNull() ?: 0) !in 5..1440)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = app.packageName in packages, enabled = app.packageName !in unavailable,
                                onCheckedChange = { selected -> packages = if (selected) packages + app.packageName else packages - app.packageName })
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, color = GuardTextPrimary)
                                if (app.packageName in unavailable) Text("In another budget", color = GuardTextSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(budget.copy(name = name.trim(), minutes = minutes.toInt(), packages = packages)) }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
}