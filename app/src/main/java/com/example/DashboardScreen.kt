package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.util.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.MonitoredApp
import com.example.data.ScreenGuardRepository
import com.example.data.SessionHistory
import com.example.domain.SessionManager
import com.example.domain.AccessibilityConsent
import com.example.domain.HistoryDates
import com.example.domain.HistoryIndex
import com.example.domain.SessionAction
import com.example.domain.SARCASTIC_DISABLE
import com.example.ui.AccessibilityDisclosure
import com.example.service.AppAccessibilityService
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.GuardBlack
import com.example.ui.theme.GuardSurface
import com.example.ui.theme.GuardSurfaceItem
import com.example.ui.theme.GuardMintAccent
import com.example.ui.theme.GuardTextPrimary
import com.example.ui.theme.GuardTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlin.math.absoluteValue
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup

@Composable
fun DashboardView(viewModel: MainViewModel, isServiceEnabled: Boolean, context: Context, onRequestAccessibility: () -> Unit) {
    var sarcasticDisableAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    if (sarcasticDisableAction != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sarcasticDisableAction = null },
            containerColor = GuardSurface,
            titleContentColor = GuardTextPrimary,
            textContentColor = GuardTextSecondary,
            title = { Text("Are you sure?") },
            text = { 
                val phrase = remember { SARCASTIC_DISABLE.random() }
                Text(phrase) 
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { 
                    sarcasticDisableAction?.invoke() 
                    sarcasticDisableAction = null
                }) {
                    Text("Disable", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { sarcasticDisableAction = null }) {
                    Text("Rethink", color = GuardMintAccent)
                }
            }
        )
    }
    val stats by viewModel.statisticsState.collectAsStateWithLifecycle()
    val isMasterGuardEnabled by SessionManager.isMasterGuardEnabled.collectAsStateWithLifecycle()
    val prefs = context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE)
    var isSarcasticMode by remember { mutableStateOf(prefs.getBoolean("sarcastic_mode", false)) }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.example.R.string.ui_home),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GuardTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (isMasterGuardEnabled && isServiceEnabled) "MONITORING ACTIVE" else "MONITORING PAUSED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isMasterGuardEnabled) GuardMintAccent else GuardTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )
            }
            Switch(
                checked = isMasterGuardEnabled && isServiceEnabled,
                onCheckedChange = { enabled ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (enabled && !isServiceEnabled) onRequestAccessibility()
                    else SessionManager.setMasterGuardEnabled(enabled)
                },
                modifier = Modifier.semantics { contentDescription = "Automatic monitoring" },
                colors = SwitchDefaults.colors(checkedThumbColor = GuardBlack, checkedTrackColor = GuardMintAccent)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Onboarding Warning if service is not running
        if (!isServiceEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardTextPrimary.copy(alpha = 0.03f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRequestAccessibility)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color.Red,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Accessibility Inactive",
                            fontWeight = FontWeight.Bold,
                            color = GuardTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Review accessibility access to enable reminders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GuardTextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Arrow",
                        tint = Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        com.example.ui.MonitoringControls(isServiceEnabled, onRequestAccessibility)
        Spacer(Modifier.height(8.dp))

        // One universal day selector (its graph sits just below the carousel) that drives the
        // whole dashboard: the carousel cards AND the intercept log further down all read from
        // this single selected day, so there's only one graph and no per-card duplicates.
        var selectedDayOffset by remember { mutableStateOf(0) }

        // Insights Carousel (circular: wraps from the last card back to the first; always
        // starts on the first template each time the dashboard is shown).
        val templateCount = 3
        val carouselStartPage = remember { (Int.MAX_VALUE / 2).let { it - it % templateCount } }
        val pagerState = rememberPagerState(initialPage = carouselStartPage, pageCount = { Int.MAX_VALUE })
        
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Subtle mint radial glow that lifts the active card off the pure-black background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(GuardMintAccent.copy(alpha = 0.07f), Color.Transparent)
                            )
                        )
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSpacing = 16.dp
                ) { page ->
                    Box(
                        modifier = Modifier.graphicsLayer {
                            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                .absoluteValue.coerceIn(0f, 1f)
                            val scale = lerp(0.90f, 1f, 1f - pageOffset)
                            scaleX = scale
                            scaleY = scale
                            alpha = lerp(0.4f, 1f, 1f - pageOffset)
                        }
                    ) {
                        when (page % templateCount) {
                            0 -> MindfulUsageCard(stats, selectedOffset = selectedDayOffset, isSarcasticMode = isSarcasticMode)
                            1 -> AppUsageInsightCard(stats, selectedOffset = selectedDayOffset, isSarcasticMode = isSarcasticMode)
                            else -> InterventionBehaviorCard(stats, selectedOffset = selectedDayOffset, isSarcasticMode = isSarcasticMode)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Pager indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(templateCount) { index ->
                    val active = pagerState.currentPage % templateCount == index
                    val color = if (active) GuardMintAccent else GuardTextPrimary.copy(alpha = 0.2f)
                    val width = if (active) 16.dp else 6.dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = width, height = 6.dp)
                            .background(color, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Shared 7-day activity graph — universal day selector, placed right below the carousel.
        DaySelectorBars(stats, selectedDayOffset) { selectedDayOffset = it }

        Spacer(modifier = Modifier.height(16.dp))

        // Live stats metrics grid
        Text(
            text = "Metrics",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = GuardTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        // Metrics — three compact tiles that fit the screen (no horizontal scroll)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.TouchApp,
                value = stats.totalMindfulPauses.toString(),
                label = if (isSarcasticMode) "Plot Twists" else "Decisions",
                accent = if (isSarcasticMode) Color(0xFFEF5350) else GuardMintAccent
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Shield,
                value = stats.guardedAppsCount.toString(),
                label = if (isSarcasticMode) "Temptations" else "Guarded Apps",
                accent = if (isSarcasticMode) Color(0xFFEF5350) else Color(0xFF81D4FA)
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.TimerOff,
                value = stats.bypassedInterventions.toString(),
                label = if (isSarcasticMode) "Times Caved" else "Timer Ignored",
                accent = Color(0xFFEF5350)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Weekly progress summary — best day, resisted count, and screen time over the last 7 days
        WeeklySummaryCard(stats, onWeekChange = viewModel::selectWeekEnding)
        Spacer(Modifier.height(16.dp))
        com.example.ui.TrendsPanel(stats)

        Spacer(modifier = Modifier.height(16.dp))

        // On-Device and Offline Guarantee (Cybersecurity Aesthetic)
        Card(
            colors = CardDefaults.cardColors(containerColor = GuardSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.15f)), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(GuardMintAccent.copy(alpha = 0.08f), CircleShape)
                        .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.2f)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "On-Device Guarantee",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "100% LOCAL & OFFLINE GUARANTEED",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardMintAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "No telemetry or app-operated uploads. You control any exported files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardTextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // History Log Title — shares the universal day selector at the top of the dashboard.
        val dayLogs = remember(stats.historyIndex, stats.referenceDate, selectedDayOffset) {
            logsForDay(stats, selectedDayOffset).filter { SessionAction.isChoice(it.actionTaken) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Intercepts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = GuardTextSecondary,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Text(
                text = dayLabel(selectedDayOffset),
                color = GuardMintAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (dayLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.03f)), RoundedCornerShape(16.dp))
                    .padding(vertical = 32.dp, horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        // Soft mint glow halo
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .background(GuardMintAccent.copy(alpha = 0.06f), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(GuardMintAccent.copy(alpha = 0.10f), CircleShape)
                                .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.25f)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = GuardMintAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No intercepts on this day.",
                        color = GuardTextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "No recorded decisions.",
                        color = GuardMintAccent.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = GuardSurfaceItem),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.03f)), RoundedCornerShape(16.dp))
            ) {
                // Show at most 10 intercept entries at once. Extra entries scroll inside
                // this section only — the page itself never grows.
                val maxVisibleLogs = 10
                val logRowHeight = 56.dp
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = logRowHeight * maxVisibleLogs)
                        .padding(horizontal = 8.dp)
                ) {
                    items(dayLogs, key = { it.id }) { log ->
                        InterceptLogRow(log = log, rowHeight = logRowHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun InterceptLogRow(log: SessionHistory, rowHeight: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.height(rowHeight)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.appName,
                    fontWeight = FontWeight.Bold,
                    color = GuardTextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = log.packageName + " • " + log.actionTaken,
                    color = GuardTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = SessionAction.label(log.actionTaken),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (log.actionTaken == "BYPASSED") Color.Red.copy(alpha = 0.6f) else GuardMintAccent
            )
        }
        HorizontalDivider(color = GuardTextPrimary.copy(alpha = 0.05f))
    }
}

@Composable
fun AppIconView(
    icon: Drawable?,
    appName: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    if (icon != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(icon)
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(GuardTextPrimary.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.06f)), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.firstOrNull()?.uppercase() ?: "",
                color = if (isSelected) GuardMintAccent else GuardTextSecondary,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun MetricTile(icon: ImageVector, value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GuardSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.18f)), RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = GuardTextPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = GuardTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun QuotaRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    stroke: Dp = 4.dp,
    label: String? = null
) {
    val trackColor = GuardTextPrimary.copy(alpha = 0.08f)
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        if (label != null) {
            Text(
                text = label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ---- Day-wise usage helpers for the dashboard carousel -------------------------------------

private fun dayBoundsMillis(daysAgo: Int): Pair<Long, Long> {
    return HistoryDates.dayBounds(LocalDate.now().minusDays(daysAgo.toLong()))
}

private fun logsForDay(stats: DashboardStats, daysAgo: Int): List<SessionHistory> =
    stats.historyIndex.on(stats.referenceDate.minusDays(daysAgo.toLong())).records

private fun dailyUsageMinutes(stats: DashboardStats, daysAgo: Int): Int =
    (stats.historyIndex.on(stats.referenceDate.minusDays(daysAgo.toLong())).seconds / 60)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun dayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> "$daysAgo days ago"
}

/** Days-ago offset for a date picked in the Material date picker (which reports UTC midnight). */
private fun offsetFromPickedUtcMillis(utcMillis: Long): Int {
    return HistoryDates.offsetFromPicker(utcMillis)
}

/** e.g. "1 Jul – 7 Jul" for the 7-day window ending [weekEndOffset] days ago. */
private fun weekRangeLabel(weekEndOffset: Int): String {
    val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, -weekEndOffset)
    val end = fmt.format(cal.time)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
    val start = fmt.format(cal.time)
    return "$start – $end"
}

/** A tappable 7-day bar strip (oldest → today). Heights scale with each day's usage. */
@Composable
internal fun DaySelectorBars(
    stats: DashboardStats,
    selectedOffset: Int,
    onSelect: (Int) -> Unit
) {
    val daySeconds = remember(stats.historyIndex, stats.referenceDate) {
        (6 downTo 0).map { stats.historyIndex.on(stats.referenceDate.minusDays(it.toLong())).seconds }
    }
    val maxSeconds = (daySeconds.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        (6 downTo 0).forEachIndexed { index, daysAgo ->
            val seconds = daySeconds[index]
            val date = stats.referenceDate.minusDays(daysAgo.toLong())
            val heightFrac = (seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0.06f, 1f)
            val isSelected = daysAgo == selectedOffset
            val animatedHeightFrac by animateFloatAsState(
                targetValue = heightFrac,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                label = "barHeight"
            )
            val animatedColor by animateColorAsState(
                targetValue = if (isSelected) GuardMintAccent else GuardTextPrimary.copy(alpha = if (seconds > 0) 0.22f else 0.08f),
                animationSpec = tween(durationMillis = 300),
                label = "barColor"
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(daysAgo) })
                    .semantics { contentDescription = "${dayLabel(daysAgo)}, ${seconds / 60} minutes, $date" },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(animatedHeightFrac)
                            .background(animatedColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    color = if (isSelected) GuardMintAccent else GuardTextSecondary,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun MindfulUsageCard(stats: DashboardStats, selectedOffset: Int, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSarcasticMode) "MINDLESS USAGE" else "USAGE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = GuardTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Usage icon",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val dayMinutes = dailyUsageMinutes(stats, selectedOffset)
            val daySessions = stats.historyIndex.on(stats.referenceDate.minusDays(selectedOffset.toLong())).choices

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val animatedMinutes by androidx.compose.animation.core.animateIntAsState(
                    targetValue = dayMinutes,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "minutesCounter"
                )
                Text(
                    text = animatedMinutes.toString(),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    color = GuardTextPrimary,
                    lineHeight = 52.sp
                )
                Text(
                    text = "min",
                    fontSize = 20.sp,
                    color = GuardTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSarcasticMode)
                    "${dayLabel(selectedOffset)} · $daySessions plot twist${if (daySessions == 1) "" else "s"}"
                else
                    "${dayLabel(selectedOffset)} · $daySessions decision${if (daySessions == 1) "" else "s"}",
                color = GuardTextSecondary,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun AppUsageInsightCard(stats: DashboardStats, selectedOffset: Int, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isSarcasticMode) "ATTENTION INVOICES" else "APP USAGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GuardTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = dayLabel(selectedOffset),
                        color = GuardMintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "App Usage",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = selectedOffset,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "appUsageDay"
            ) { offset ->
                val appTimes = stats.historyIndex.on(stats.referenceDate.minusDays(offset.toLong())).topApps
                if (appTimes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (isSarcasticMode) "No records to comment on." else "No recorded usage on this day.",
                            color = GuardTextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        appTimes.forEach { (appName, minutes) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appName,
                                    color = GuardTextPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$minutes min",
                                    color = GuardMintAccent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun InterventionBehaviorCard(stats: DashboardStats, selectedOffset: Int, modifier: Modifier = Modifier, isSarcasticMode: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSarcasticMode) Color.Red.copy(alpha = 0.15f) else GuardSurface),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .testTag("behavior-card")
            .border(BorderStroke(1.dp, if (isSarcasticMode) Color.Red.copy(alpha = 0.5f) else GuardTextPrimary.copy(alpha = 0.05f)), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSarcasticMode) "THE RECEIPTS" else "INTERVENTION BEHAVIOR",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GuardTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.sp
                        )
                    )
                    Text(
                        text = dayLabel(selectedOffset),
                        color = GuardMintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(GuardBlack, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = "Behavior",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = selectedOffset,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "behaviorDay"
            ) { offset ->
                val behavior = stats.historyIndex.on(stats.referenceDate.minusDays(offset.toLong())).behavior
                val resistedCount = behavior.closed
                val extendedCount = behavior.extended
                val bypassedCount = behavior.bypassed
                val total = behavior.total
                val score = behavior.stopRate ?: 0
                val scoreColor = when {
                    total == 0 -> GuardTextSecondary
                    score >= 80 -> GuardMintAccent
                    score >= 50 -> Color(0xFF81D4FA)
                    else -> Color(0xFFEF5350)
                }
                val scoreLabel = if (isSarcasticMode) {
                    when {
                        total == 0 -> "Nothing to judge... yet"
                        score >= 80 -> "Ugh, fine. Impressive."
                        score >= 50 -> "Barely holding on"
                        else -> "Goalposts on wheels"
                    }
                } else {
                    when {
                        total == 0 -> "No intercepts on this day"
                        score >= 80 -> "Strong self-control"
                        score >= 50 -> "Holding the line"
                        else -> "Room to improve"
                    }
                }
                val animatedScore by androidx.compose.animation.core.animateIntAsState(
                    targetValue = score,
                    animationSpec = androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "behaviorScore"
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.testTag("behavior-score")) {
                            Text(
                                text = if (total == 0) "—" else animatedScore.toString(),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Light,
                                color = GuardTextPrimary,
                                lineHeight = 32.sp
                            )
                            if (total > 0) {
                                Text(
                                    text = "%",
                                    fontSize = 13.sp,
                                    color = GuardTextSecondary,
                                    modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = scoreLabel,
                                color = scoreColor,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isSarcasticMode) "Times you meant it" else "Stop rate",
                                color = GuardTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("behavior-counts"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BehaviorStat(androidx.compose.ui.res.stringResource(com.example.R.string.ui_resisted), resistedCount, GuardMintAccent, Modifier.weight(1f))
                        BehaviorStat(androidx.compose.ui.res.stringResource(com.example.R.string.ui_extended), extendedCount, Color(0xFF81D4FA), Modifier.weight(1f))
                        BehaviorStat(androidx.compose.ui.res.stringResource(com.example.R.string.ui_bypassed), bypassedCount, Color(0xFFEF5350), Modifier.weight(1f))
                    }
                }
            }

        }
    }
}

@Composable
fun BehaviorStat(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $count" }, horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = count.toString(),
                color = GuardTextPrimary,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = GuardTextSecondary,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklySummaryCard(stats: DashboardStats, modifier: Modifier = Modifier, onWeekChange: (LocalDate) -> Unit = {}) {
    val logs = stats.recentLogs

    var selectedWeekEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var showWeekPicker by remember { mutableStateOf(false) }
    val weekEnd = selectedWeekEnd?.let(LocalDate::ofEpochDay)?.coerceAtMost(stats.referenceDate) ?: stats.referenceDate
    LaunchedEffect(weekEnd) { onWeekChange(weekEnd) }
    val weekEndOffset = ChronoUnit.DAYS.between(weekEnd, stats.referenceDate).toInt()
    val summary = remember(stats.historyIndex, weekEnd) { stats.historyIndex.weekEnding(weekEnd) }
    val weekResisted = summary.resisted
    val weekMinutes = summary.seconds / 60
    val bestDayLabel = summary.bestDay?.let { date ->
        if (weekEndOffset == 0) dayLabel(ChronoUnit.DAYS.between(date, stats.referenceDate).toInt())
        else date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    } ?: "—"
    val screenTimeLabel = if (weekMinutes >= 60) "${weekMinutes / 60}h ${weekMinutes % 60}m" else "${weekMinutes}m"

    if (showWeekPicker) {
        val today = stats.referenceDate
        val firstYear = minOf(stats.firstRecordedAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year
        } ?: today.year, today.year - 1) - 1
        val selectableDates = remember(today, firstYear) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    HistoryDates.canSelect(utcTimeMillis, today) &&
                        HistoryDates.pickerDate(utcTimeMillis).minusDays(6).year >= firstYear
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        }
        val initialRange = remember(weekEnd) { HistoryDates.weekRange(weekEnd) }
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initialRange.first,
            initialSelectedEndDateMillis = initialRange.second,
            initialDisplayedMonthMillis = initialRange.second,
            yearRange = firstYear..today.year,
            selectableDates = selectableDates
        )
        // A single tap should highlight the whole 7-day week ending on that day. Whenever the
        // picker reports only a start (a fresh tap), snap the selection to [tap-6days, tap] so
        // the week fills in. Once the end is set we stop overriding, so there's no loop.
        LaunchedEffect(rangeState) {
            snapshotFlow { rangeState.selectedStartDateMillis to rangeState.selectedEndDateMillis }
                .collect { (start, end) ->
                    if (start != null) {
                        val selection = HistoryDates.weekRange(HistoryDates.pickerDate(end ?: start))
                        if (start != selection.first || end != selection.second) {
                            rangeState.setSelection(selection.first, selection.second)
                        }
                    }
                }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showWeekPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.75f),
                shape = RoundedCornerShape(24.dp),
                color = GuardSurface,
                border = BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DateRangePicker(
                        state = rangeState,
                        modifier = Modifier.weight(1f),
                        showModeToggle = false,
                        title = {
                            Text(
                                text = "SELECTED WEEK",
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                                color = GuardTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        },
                        headline = {
                            val end = rangeState.selectedEndDateMillis
                            Text(
                                text = if (end != null) weekRangeLabel(offsetFromPickedUtcMillis(end)) else "Pick a day",
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                                color = GuardTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = DatePickerDefaults.colors(
                            containerColor = GuardSurface,
                            titleContentColor = GuardTextSecondary,
                            headlineContentColor = GuardTextPrimary,
                            weekdayContentColor = GuardTextSecondary,
                            subheadContentColor = GuardMintAccent,
                            dayContentColor = GuardTextPrimary,
                            selectedDayContainerColor = GuardMintAccent,
                            selectedDayContentColor = GuardBlack,
                            todayContentColor = GuardMintAccent,
                            todayDateBorderColor = GuardMintAccent,
                            dayInSelectionRangeContainerColor = GuardMintAccent.copy(alpha = 0.22f),
                            dayInSelectionRangeContentColor = GuardTextPrimary
                        )
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showWeekPicker = false }) {
                            Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel), color = GuardTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val end = rangeState.selectedEndDateMillis
                                if (end != null && HistoryDates.canSelect(end, LocalDate.now())) {
                                    selectedWeekEnd = HistoryDates.pickerDate(end).toEpochDay()
                                }
                                showWeekPicker = false
                            },
                            enabled = rangeState.selectedEndDateMillis != null
                        ) {
                            Text("Select week", color = GuardMintAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = GuardSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.15f)), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GuardMintAccent.copy(alpha = 0.08f))
                        .border(BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.2f)), CircleShape)
                        .clickable { showWeekPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Select week",
                        tint = GuardMintAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (weekEndOffset == 0) "THIS WEEK" else "SELECTED WEEK",
                        style = MaterialTheme.typography.labelSmall,
                        color = GuardMintAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (weekEndOffset == 0) "Your last 7 days" else weekRangeLabel(weekEndOffset),
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardTextSecondary
                    )
                }
                if (weekEndOffset != 0) {
                    Text(
                        text = "This week",
                        color = GuardMintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedWeekEnd = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WeeklyStatItem(label = "Best Day", value = bestDayLabel)
                WeeklyStatItem(label = androidx.compose.ui.res.stringResource(com.example.R.string.ui_resisted), value = weekResisted.toString())
                WeeklyStatItem(label = "Screen Time", value = screenTimeLabel)
            }
        }
    }
}

@Composable
private fun WeeklyStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = GuardTextPrimary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = GuardTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}
