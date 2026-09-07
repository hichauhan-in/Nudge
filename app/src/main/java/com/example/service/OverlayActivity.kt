package com.example.service

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.domain.SessionManager
import com.example.domain.SessionState
import com.example.domain.SARCASTIC_BYPASS
import com.example.domain.SARCASTIC_QUOTA
import com.example.domain.SARCASTIC_LONG_DURATION
import com.example.domain.SARCASTIC_EXTEND_BUTTONS
import com.example.domain.extensionRemark
import com.example.ui.theme.GuardBlack
import com.example.ui.theme.GuardSurface
import com.example.ui.theme.GuardSurfaceItem
import com.example.ui.theme.GuardMintAccent
import com.example.ui.theme.GuardTextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class OverlayActivity : ComponentActivity() {
    private lateinit var repository: ScreenGuardRepository

    private fun safeFinish() {
        if (!isFinishing && !isDestroyed) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        SessionManager.endDonationFlow()
        SessionManager.setOverlayVisible(true)
        if (!com.example.domain.AccessibilityConsent.isAccepted(this) || !SessionManager.isMasterGuardEnabled.value) safeFinish()
    }

    override fun onStop() {
        SessionManager.setOverlayVisible(false)
        super.onStop()
        // Do NOT reset the prompt state here, as it allows bypass on lock screen / system minimization.
        // AppAccessibilityService handles resetting the state when the user actually navigates to another app.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        val database = AppDatabase.getDatabase(this)
        repository = ScreenGuardRepository(database.dao())

        setContent {
            val sessionState by SessionManager.sessionState.collectAsStateWithLifecycle()
            val prefs = LocalContext.current.getSharedPreferences("focus_time_prefs", android.content.Context.MODE_PRIVATE)
            val useBlurredBackground = prefs.getBoolean("use_blurred_background", false)
            val isSarcasticMode = prefs.getBoolean("sarcastic_mode", false)

            LaunchedEffect(useBlurredBackground) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (useBlurredBackground) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.attributes.blurBehindRadius = 50
                        window.attributes = window.attributes
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                }
            }

            // Close overlay if session returns to Idle
            LaunchedEffect(sessionState) {
                if (sessionState is SessionState.Idle) {
                    safeFinish()
                }
            }

            // Lock physical Back button to close the host app instead of simple dismissal
            BackHandler {
                triggerHomeMinimize()
            }

            // Deliberate "pause" entrance: quick scale-in + fade so the prompt doesn't pop harshly
            var contentVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { contentVisible = true }
            val entranceScale by animateFloatAsState(
                targetValue = if (contentVisible) 1f else 0.92f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "overlayScale"
            )
            val entranceAlpha by animateFloatAsState(
                targetValue = if (contentVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "overlayAlpha"
            )

            val quotaActive = sessionState is SessionState.QuotaExhausted
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            useBlurredBackground && quotaActive -> Color(0xFF2A0000).copy(alpha = 0.5f)
                            useBlurredBackground -> Color.Black.copy(alpha = 0.35f)
                            quotaActive -> Color(0xFF160303)
                            else -> GuardBlack
                        }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = entranceScale
                            scaleY = entranceScale
                            alpha = entranceAlpha
                        }
                        .padding(if (useBlurredBackground) 16.dp else 24.dp)
                        .padding(bottom = 56.dp), // add spacing so design elements don't overlap with the bottom donate button
                    contentAlignment = Alignment.Center
                ) {
                    val innerContent = @Composable {
                        when (val state = sessionState) {
                            is SessionState.Prompting -> {
                                MindfulPromptFlow(
                                    isSarcasticMode = isSarcasticMode,
                                    packageName = state.packageName,
                                    appName = state.appName,
                                    onMinimize = {
                                        SessionManager.logPromptResisted(state.packageName, state.appName, repository)
                                        triggerHomeMinimize()
                                    },
                                    onAccept = { minutes ->
                                        SessionManager.startSession(state.packageName, state.appName, minutes, repository)
                                        safeFinish()
                                    },
                                    onBypass = {
                                        SessionManager.bypassApp(state.packageName, state.appName, repository)
                                        safeFinish()
                                    }
                                )
                            }
                            is SessionState.Expired -> {
                                ExpirySheet(
                                    isSarcasticMode = isSarcasticMode,
                                    extensionCount = SessionManager.extensionCountFor(state.packageName),
                                    appName = state.appName,
                                    packageName = state.packageName,
                                    onMinimize = {
                                        triggerHomeMinimize()
                                    },
                                    onExtend = { minutes ->
                                        SessionManager.extendSession(state.packageName, state.appName, minutes, repository)
                                        safeFinish()
                                    },
                                    onNoTimer = {
                                        SessionManager.bypassApp(state.packageName, state.appName, repository)
                                        safeFinish()
                                    }
                                )
                            }
                            is SessionState.QuotaExhausted -> {
                                QuotaExhaustedScreen(
                                    isSarcasticMode = isSarcasticMode,
                                    appName = state.appName,
                                    packageName = state.packageName,
                                    strict = state.strict,
                                    onClose = {
                                        triggerHomeMinimize()
                                    },
                                    onContinue = {
                                        SessionManager.proceedPastQuota(state.packageName, state.appName)
                                    }
                                )
                            }
                            else -> {
                                // For Active / Idle status, finish layout
                                Box(modifier = Modifier.size(1.dp)) {
                                    LaunchedEffect(Unit) {
                                        safeFinish()
                                    }
                                }
                            }
                        }
                    }

                    if (useBlurredBackground && sessionState !is SessionState.Idle) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = GuardSurface.copy(alpha = 0.95f)),
                            shape = RoundedCornerShape(28.dp),
                            border = BorderStroke(1.dp, if (quotaActive) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Box(modifier = Modifier.padding(24.dp)) {
                                innerContent()
                            }
                        }
                    } else {
                        innerContent()
                    }
                }

                // Small Donate button on the bottom right — bubbles up payment options
                if (sessionState is SessionState.Prompting || sessionState is SessionState.Expired) {
                    val contextCurrent = LocalContext.current
                    var donateExpanded by remember { mutableStateOf(false) }

                    // Tap-outside scrim to collapse the options
                    if (donateExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { donateExpanded = false }
                        )
                    }

                    val launchKofi = {
                        donateExpanded = false
                        SessionManager.beginDonationFlow()
                        val uri = android.net.Uri.parse("https://ko-fi.com/hichauhan")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            contextCurrent.startActivity(intent)
                        } catch (e: Exception) {
                            SessionManager.endDonationFlow()
                            android.widget.Toast.makeText(contextCurrent, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }

                    val launchUpi = {
                        donateExpanded = false
                        SessionManager.beginDonationFlow()
                        val uri = android.net.Uri.parse("upi://pay").buildUpon()
                            .appendQueryParameter("pa", "gpay-12199931519@okbizaxis")
                            .appendQueryParameter("pn", "Nudge")
                            .appendQueryParameter("cu", "INR")
                            .build()
                        val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Pay with...").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            contextCurrent.startActivity(chooser)
                        } catch (e: Exception) {
                            SessionManager.endDonationFlow()
                            android.widget.Toast.makeText(contextCurrent, "No UPI app found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Third (furthest left): Playto — coming soon
                            DonateOptionChip(
                                visible = donateExpanded,
                                delayMillis = 140,
                                iconRes = com.example.R.drawable.ic_pay_playto,
                                label = "Playto",
                                enabled = false,
                                onClick = {}
                            )
                            // Second: Ko-fi
                            DonateOptionChip(
                                visible = donateExpanded,
                                delayMillis = 70,
                                iconRes = com.example.R.drawable.ic_pay_kofi,
                                label = "Ko-fi",
                                enabled = true,
                                onClick = { launchKofi() }
                            )
                            // First (nearest the button): UPI
                            DonateOptionChip(
                                visible = donateExpanded,
                                delayMillis = 0,
                                iconRes = com.example.R.drawable.ic_pay_upi,
                                label = "UPI",
                                enabled = true,
                                onClick = { launchUpi() }
                            )
                            // Anchor: Donate toggle (rightmost, same size/style as the chips)
                            Row(
                                modifier = Modifier
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(GuardMintAccent.copy(alpha = 0.15f))
                                    .border(
                                        BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.4f)),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { donateExpanded = !donateExpanded }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Coffee,
                                        contentDescription = "Coffee",
                                        tint = Color(0xFF6F4E37),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Coffee?",
                                    color = GuardMintAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerHomeMinimize() {
        when (val state = SessionManager.sessionState.value) {
            is SessionState.Prompting -> SessionManager.logPromptResisted(state.packageName, state.appName, repository)
            is SessionState.Expired -> SessionManager.logPromptResisted(state.packageName, state.appName, repository)
            is SessionState.QuotaExhausted -> SessionManager.logPromptResisted(state.packageName, state.appName, repository)
            else -> SessionManager.resetState()
        }
        SessionManager.flushForegroundUsage()
        SessionManager.lastUserAppPackage = null
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        safeFinish()
    }
}

@Composable
private fun DonateOptionChip(
    visible: Boolean,
    delayMillis: Int,
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200, delayMillis)) +
            slideInHorizontally(animationSpec = tween(300, delayMillis)) { it } +
            scaleIn(
                animationSpec = tween(300, delayMillis),
                initialScale = 0.7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
            ),
        exit = fadeOut(animationSpec = tween(120)) +
            slideOutHorizontally(animationSpec = tween(160)) { it / 2 }
    ) {
        Row(
            modifier = Modifier
                .padding(end = 8.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) GuardMintAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                .border(
                    BorderStroke(1.dp, if (enabled) GuardMintAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)),
                    RoundedCornerShape(14.dp)
                )
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = iconRes),
                    contentDescription = label,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(if (enabled) 1f else 0.5f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (enabled) GuardMintAccent else GuardTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun QuotaRemainingSection(packageName: String) {
    val quota = remember(packageName) { SessionManager.getQuotaMinutes(packageName) }
    if (quota <= 0) return
    val remaining = remember(packageName) { SessionManager.getQuotaRemainingMinutes(packageName) }
    val exceeded = remaining <= 0
    val accent = if (exceeded) Color(0xFFEF5350) else GuardMintAccent
    val usedFraction = ((quota - remaining).toFloat() / quota).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuotaRing(
                fraction = usedFraction,
                color = accent,
                diameter = 40.dp,
                stroke = 4.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (exceeded) Icons.Default.Warning else Icons.Default.Timer,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (exceeded) "Daily quota spent" else "$remaining min left in daily quota",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun QuotaRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    stroke: Dp = 4.dp,
    label: String? = null
) {
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
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

@Composable
fun MindfulPromptFlow(
    isSarcasticMode: Boolean = false,
    packageName: String,
    appName: String,
    onMinimize: () -> Unit,
    onAccept: (Int) -> Unit,
    onBypass: () -> Unit
) {
    DurationSelectionScreen(
        isSarcasticMode = isSarcasticMode,
        packageName = packageName,
        appName = appName,
        onSelected = onAccept,
        onBypass = onBypass,
        onMinimize = onMinimize
    )
}



@Composable
fun DurationSelectionScreen(
    isSarcasticMode: Boolean = false,
    packageName: String,
    appName: String,
    onSelected: (Int) -> Unit,
    onBypass: () -> Unit,
    onMinimize: () -> Unit
) {
    var customMinutes by remember { mutableStateOf(5f) }
    var showBypassAlert by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    if (showBypassAlert) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBypassAlert = false },
            containerColor = com.example.ui.theme.GuardSurface,
            titleContentColor = androidx.compose.ui.graphics.Color.White,
            textContentColor = com.example.ui.theme.GuardTextSecondary,
            title = { androidx.compose.material3.Text("Are you sure?") },
            text = {
                val phrase = remember { SARCASTIC_BYPASS.random() }
                androidx.compose.material3.Text(phrase)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showBypassAlert = false
                    onBypass()
                }) {
                    androidx.compose.material3.Text("Ignore limit", color = androidx.compose.ui.graphics.Color(0xFFEF5350))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBypassAlert = false }) {
                    androidx.compose.material3.Text("Keep limit", color = com.example.ui.theme.GuardMintAccent)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Usage Threshold",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val isLongSarcastic = isSarcasticMode && customMinutes > 10
        // One remark per 10-minute band (11-20, 21-30, ...), remembered unconditionally so the
        // composition slot table stays stable and the remark doesn't change on every minute.
        val durationBand = ((customMinutes.toInt() - 1) / 10).coerceAtLeast(0)
        val sarcasticLongRemark = remember(packageName, durationBand) { SARCASTIC_LONG_DURATION.random() }
        val promptText = if (isLongSarcastic) "$appName: $sarcasticLongRemark" else "Commit to a healthy limit for $appName"
        Text(
            text = promptText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isLongSarcastic) androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.8f) else GuardTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        QuotaRemainingSection(packageName)

        // Fast Pill Options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(2, 5, 10, 20).forEach { mins ->
                Button(
                    onClick = { 
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isSarcasticMode && mins > 10) {
                            customMinutes = mins.toFloat()
                        } else {
                            onSelected(mins) 
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardSurfaceItem,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("$mins min", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Custom Slider Selection
        Card(
            colors = CardDefaults.cardColors(
                containerColor = GuardSurface
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Custom: ${customMinutes.toInt()} minutes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = customMinutes,
                    onValueChange = { customMinutes = it },
                    valueRange = 1f..60f,
                    steps = 58,
                    colors = SliderDefaults.colors(
                        thumbColor = GuardMintAccent,
                        activeTrackColor = GuardMintAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { 
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelected(customMinutes.toInt()) 
            },
            colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            val sarcasticStartText = remember { com.example.domain.SARCASTIC_START_BUTTONS.random() }
            val startText = if (isSarcasticMode && customMinutes > 10) sarcasticStartText else "Start Conscious Period"
            Text(startText, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onMinimize,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Minimize $appName", fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(20.dp))

        TextButton(onClick = { 
            if (isSarcasticMode) {
                showBypassAlert = true
            } else {
                onBypass()
            }
        }) {
            Text("Ignore limit for this session", color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExpirySheet(
    isSarcasticMode: Boolean = false,
    extensionCount: Int = 0,
    appName: String,
    packageName: String,
    onMinimize: () -> Unit,
    onExtend: (Int) -> Unit,
    onNoTimer: () -> Unit
) {
    var customMinutes by remember { mutableStateOf(5f) }
    var showBypassAlert by remember { mutableStateOf(false) }
    if (showBypassAlert) {
        val remark = remember(packageName) { SARCASTIC_BYPASS.random() }
        AlertDialog(
            onDismissRequest = { showBypassAlert = false },
            containerColor = GuardSurface,
            titleContentColor = Color.White,
            textContentColor = GuardTextSecondary,
            title = { Text("Ignore this limit?") },
            text = { Text(remark) },
            confirmButton = {
                TextButton(onClick = { showBypassAlert = false; onNoTimer() }) {
                    Text("Ignore limit", color = Color(0xFFEF5350))
                }
            },
            dismissButton = { TextButton(onClick = { showBypassAlert = false }) { Text("Keep limit", color = GuardMintAccent) } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(GuardMintAccent.copy(alpha = 0.08f), CircleShape)
                .border(1.dp, GuardMintAccent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Expired Icon",
                tint = GuardMintAccent,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        val titleText = if (isSarcasticMode) "Encore ${extensionCount + 1}?" else "Time is Up!"
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Remembered unconditionally (keyed by the extension count) so each expiry shows a
        // fresh, escalating remark and the slot table stays stable in sarcastic mode.
        val sarcasticExpiryRemark = remember(packageName, extensionCount) { extensionRemark(extensionCount) }
        val promptText = if (isSarcasticMode) "$appName: $sarcasticExpiryRemark" else "Your conscious window for $appName has expired."
        Text(
            text = promptText,
            style = MaterialTheme.typography.bodyMedium,
            color = GuardTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        QuotaRemainingSection(packageName)

        // Quick Extend Options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(2, 5, 10).forEach { mins ->
                Button(
                    onClick = { onExtend(mins) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardSurfaceItem,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("+ $mins min", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Custom Slider Selection
        Card(
            colors = CardDefaults.cardColors(
                containerColor = GuardSurface
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Extend: ${customMinutes.toInt()} minutes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = customMinutes,
                    onValueChange = { customMinutes = it },
                    valueRange = 1f..60f,
                    steps = 58,
                    colors = SliderDefaults.colors(
                        thumbColor = GuardMintAccent,
                        activeTrackColor = GuardMintAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onExtend(customMinutes.toInt()) },
            colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            val sarcasticExtendText = remember(packageName, extensionCount) { SARCASTIC_EXTEND_BUTTONS.random() }
            val extendText = if (isSarcasticMode) sarcasticExtendText else "Extend Conscious Period"
            Text(extendText, fontWeight = FontWeight.Bold)
        }


        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onMinimize,
            colors = ButtonDefaults.buttonColors(containerColor = GuardSurfaceItem, contentColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(if (isSarcasticMode) "End the sequel" else "Close $appName", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { if (isSarcasticMode) showBypassAlert = true else onNoTimer() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Continue without timer", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun QuotaExhaustedScreen(
    isSarcasticMode: Boolean = false,
    appName: String,
    packageName: String,
    strict: Boolean,
    onClose: () -> Unit,
    onContinue: () -> Unit
) {
    val consumedMinutes = remember(packageName) { SessionManager.getQuotaConsumedMinutesTodayLive(packageName) }
    val sarcasticQuota = remember { SARCASTIC_QUOTA.random() }
    val dangerRed = Color(0xFFEF5350)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(dangerRed.copy(alpha = 0.12f), CircleShape)
                .border(1.5.dp, dangerRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (strict) Icons.Default.Lock else Icons.Default.Warning,
                contentDescription = "Quota Exhausted",
                tint = dangerRed,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (strict) "Locked for Today" else "Daily Limit Reached",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val body = when {
            isSarcasticMode -> sarcasticQuota
            strict -> "You've used your full daily quota for $appName. Strict Mode is on, so it's locked until tomorrow."
            else -> "You've used your full daily quota for $appName today. You can still continue, but be honest with yourself."
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSarcasticMode) dangerRed.copy(alpha = 0.9f) else GuardTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(dangerRed.copy(alpha = 0.10f))
                .border(BorderStroke(1.dp, dangerRed.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Used today: $consumedMinutes min",
                color = dangerRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (strict) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Close $appName", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "To override, disable Strict Mode or raise this app's daily quota in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = GuardTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        } else {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = dangerRed.copy(alpha = 0.9f), contentColor = Color.White),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Continue Anyway", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onClose,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Close $appName", fontWeight = FontWeight.Medium)
            }
        }
    }
}
