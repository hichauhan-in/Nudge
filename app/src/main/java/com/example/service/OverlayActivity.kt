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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.ScreenGuardRepository
import com.example.domain.SessionManager
import com.example.domain.SessionState
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
        SessionManager.isDonationFlowActive = false
    }

    override fun onStop() {
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
                                        SessionManager.resetState()
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
                                        SessionManager.resetState()
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

                    val launchUpi = {
                        donateExpanded = false
                        SessionManager.isDonationFlowActive = true
                        val uri = android.net.Uri.parse("upi://pay?pa=hichauhan.in@okhdfcbank&pn=Himanshu%20Chauhan&cu=INR")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        try {
                            contextCurrent.startActivity(Intent.createChooser(intent, "Pay with..."))
                        } catch (e: Exception) {
                            SessionManager.isDonationFlowActive = false
                            android.widget.Toast.makeText(contextCurrent, "No UPI app found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }

                    val launchKofi = {
                        donateExpanded = false
                        SessionManager.isDonationFlowActive = true
                        val uri = android.net.Uri.parse("https://ko-fi.com/hichauhan")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            contextCurrent.startActivity(intent)
                        } catch (e: Exception) {
                            SessionManager.isDonationFlowActive = false
                            android.widget.Toast.makeText(contextCurrent, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
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
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Donate",
                                    tint = GuardMintAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Donate",
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
        Spacer(modifier = Modifier.height(20.dp))
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
                    androidx.compose.material3.Text("Continue", color = androidx.compose.ui.graphics.Color(0xFFEF5350))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBypassAlert = false }) {
                    androidx.compose.material3.Text("Rethink", color = com.example.ui.theme.GuardMintAccent)
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
        val sarcasticLongRemark = remember(durationBand) { SARCASTIC_LONG_DURATION.random() }
        val promptText = if (isLongSarcastic) sarcasticLongRemark else "Commit to a healthy limit for $appName"
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
                    steps = 59,
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
            onClick = { onSelected(customMinutes.toInt()) },
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

        val titleText = if (isSarcasticMode) "Really?" else "Time is Up!"
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
        val sarcasticExpiryRemark = remember(extensionCount) {
            when {
                extensionCount == 0 -> com.example.domain.SARCASTIC_EXTENSION_L1.random()
                extensionCount == 1 -> com.example.domain.SARCASTIC_EXTENSION_L2.random()
                extensionCount == 2 -> com.example.domain.SARCASTIC_EXTENSION_L3.random()
                else -> com.example.domain.SARCASTIC_EXTENSION_L4.random()
            }
        }
        val promptText = if (isSarcasticMode) sarcasticExpiryRemark else "Your conscious window for $appName has expired."
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
                    steps = 59,
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
            val sarcasticExtendText = remember { com.example.domain.SARCASTIC_START_BUTTONS.random() }
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
            Text(if (isSarcasticMode) "Directly Close" else "Close $appName", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onNoTimer,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(if (isSarcasticMode) "Do you really want to go ahead without a limit?" else "Continue without timer", fontWeight = FontWeight.Medium)
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

val SARCASTIC_QUOTA = listOf(
    "Your daily quota is gone. Impressive, really.",
    "You budgeted your own time and still blew past it.",
    "The limit you set? Yeah, that's toast.",
    "Out of quota. But sure, let's pretend that means nothing.",
    "You made a rule for yourself and here you are, breaking it.",
    "Daily allowance: spent. Self-control: also spent.",
    "This is exactly what 'just five minutes' turns into.",
    "You set the limit. You. Remember that.",
    "Quota exhausted. Willpower, optional apparently.",
    "Even your own boundaries can't save you today."
)

val SARCASTIC_LONG_DURATION = listOf(
    "Are you really going to use it for this long?",
    "Why not just move in with the app?",
    "You realize there's an outside world, right?",
    "I'm sure this is exactly what you need to do for the next eternity.",
    "That's a lot of time to achieve absolutely nothing.",
    "Your brain cells are already crying.",
    "Is this your new full-time job?",
    "Maybe take a break halfway through to blink?",
    "I'm judging you. Hard.",
    "This is why you don't accomplish your goals.",
    "Sure, 'just a quick check' turned into a marathon.",
    "Go ahead, let the algorithm consume you.",
    "You have terrible time management skills.",
    "Are you trying to set a record for procrastination?",
    "Do you even know what sunlight looks like?",
    "That's embarrassing, honestly.",
    "Your screen time report is going to need a therapist.",
    "I guess we're giving up on today.",
    "Who needs a life when you have this app?",
    "I'll start a stopwatch to see when you regret this.",
    "Just admit you have no self-control."
)

val SARCASTIC_BYPASS = listOf(
    "Do you really want to go timeless on this particular application?",
    "Going off the grid, huh? We both know how this ends.",
    "Bypassing the timer? Say goodbye to your productivity.",
    "No limits? Bold strategy for someone with zero self-control.",
    "Are you actively trying to waste your entire day?",
    "I'll prepare the 'I told you so' for later.",
    "Sure, let the algorithm completely consume your soul.",
    "Timeless? More like brainless.",
    "This is how you end up doomscrolling until 3 AM.",
    "Do you even remember what fresh air smells like?",
    "You are a cautionary tale in the making.",
    "Why even install this app if you're just going to bypass it?",
    "You're not fooling anyone. Not even yourself.",
    "Your attention span is officially a tragedy.",
    "I guess giving up is your default setting.",
    "The sad part is, you know you shouldn't be doing this.",
    "You are actively making yourself dumber.",
    "Are you allergic to productivity?",
    "Letting the screen win again, I see.",
    "This is why you have a backlog of unaccomplished dreams.",
    "I hope whatever you're looking at is worth your future.",
    "Complete and utter brain rot inbound.",
    "You are a walking manifestation of zero self-control.",
    "Just completely pathetic. There is no hope.",
    "You are wasting oxygen by staring at this screen."
)
