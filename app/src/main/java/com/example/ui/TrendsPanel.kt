package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.DashboardStats
import com.example.R
import com.example.data.FocusSettings
import com.example.ui.theme.*

@Composable
fun TrendsPanel(stats: DashboardStats, modifier: Modifier = Modifier) {
    val configuration by FocusSettings.configuration.collectAsStateWithLifecycle()
    val days = remember(stats.historyIndex, stats.referenceDate) {
        (0L..27L).map { stats.historyIndex.on(stats.referenceDate.minusDays(it)) }
    }
    val thisWeek = days.take(7).sumOf { it.seconds }
    val previousWeek = days.drop(7).take(7).sumOf { it.seconds }
    val recordedDays = days.take(7).count { it.records.isNotEmpty() }
    Card(
        colors = CardDefaults.cardColors(containerColor = GuardSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.15f)),
        modifier = modifier.fillMaxWidth().testTag("trends-card")
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(GuardMintAccent.copy(alpha = 0.08f), CircleShape)
                        .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.2f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Insights, contentDescription = null, tint = GuardMintAccent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_trends), color = GuardMintAccent,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(stringResource(R.string.ui_trends_summary), color = GuardTextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(2.dp))
            TrendMetric(stringResource(R.string.ui_trends_current_week), stringResource(R.string.ui_minutes_short, thisWeek / 60))
            TrendMetric(stringResource(R.string.ui_trends_previous_week), stringResource(R.string.ui_minutes_short, previousWeek / 60))
            TrendMetric(stringResource(R.string.ui_trends_month), stringResource(R.string.ui_minutes_short, days.sumOf { it.seconds } / 60))
            HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.05f))
            TrendMetric(stringResource(R.string.ui_trends_recorded_days), "$recordedDays / 7")
            if (configuration.weeklyGoalMinutes > 0) {
                val target = configuration.weeklyGoalMinutes * 60L
                LinearProgressIndicator(progress = { (thisWeek.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ui_trends_goal_progress, thisWeek / 60, configuration.weeklyGoalMinutes),
                    color = GuardTextPrimary, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.ui_trends_caveat), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrendMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, color = GuardTextPrimary, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}