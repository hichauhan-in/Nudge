package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.DashboardStats
import com.example.data.FocusSettings
import com.example.ui.theme.*

@Composable
fun TrendsPanel(stats: DashboardStats) {
    val configuration by FocusSettings.configuration.collectAsStateWithLifecycle()
    val days = remember(stats.historyIndex, stats.referenceDate) {
        (0L..27L).map { stats.historyIndex.on(stats.referenceDate.minusDays(it)) }
    }
    val thisWeek = days.take(7).sumOf { it.seconds }
    val previousWeek = days.drop(7).take(7).sumOf { it.seconds }
    val recordedDays = days.take(7).count { it.records.isNotEmpty() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_trends), color = GuardTextPrimary, style = MaterialTheme.typography.titleSmall)
        Text("Last 7 days: ${thisWeek / 60} min", color = GuardMintAccent)
        Text("Previous 7 days: ${previousWeek / 60} min", color = GuardTextSecondary)
        Text("Last 28 days: ${days.sumOf { it.seconds } / 60} min", color = GuardTextSecondary)
        Text("Days with records this week: $recordedDays / 7", color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
        if (configuration.weeklyGoalMinutes > 0) {
            val target = configuration.weeklyGoalMinutes * 60L
            LinearProgressIndicator(progress = { (thisWeek.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Weekly goal: ${thisWeek / 60} / ${configuration.weeklyGoalMinutes} min recorded", color = GuardTextPrimary)
        }
        Text("Selected apps only. Paused periods and missing records are not zero usage. Older records may include timer estimates.",
            color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}