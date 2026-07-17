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
import androidx.compose.ui.draw.scale
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
import com.example.ui.theme.GuardTextPrimary
import com.example.ui.theme.GuardTextSecondary
import kotlinx.coroutines.launch
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

        val pkgName = intent.getStringExtra("pkg") ?: ""
        val appName = intent.getStringExtra("name") ?: ""

        setContent {
            val sessionState by SessionManager.sessionState.collectAsStateWithLifecycle()
            val coroutineScope = rememberCoroutineScope()
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (useBlurredBackground) Color.Black.copy(alpha = 0.35f) else GuardBlack)
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
                                        SessionManager.resetState()
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
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
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
                        val uri = android.net.Uri.parse("upi://pay?pa=9418575661@hdfc&pn=Developer&mc=0000&mode=02&purpose=00")
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
                        Row(verticalAlignment = Alignment.Bottom) {
                            // Furthest left: Ko-fi
                            DonateOptionChip(
                                visible = donateExpanded,
                                delayMillis = 140,
                                iconRes = com.example.R.drawable.ic_pay_kofi,
                                label = "Ko-fi",
                                enabled = true,
                                horizontal = true,
                                onClick = { launchKofi() }
                            )
                            // Left of the button: Playto
                            DonateOptionChip(
                                visible = donateExpanded,
                                delayMillis = 70,
                                iconRes = com.example.R.drawable.ic_pay_playto,
                                label = "Playto",
                                enabled = false,
                                horizontal = true,
                                onClick = {}
                            )
                            // Right stack: UPI directly above the Donate button
                            Column(horizontalAlignment = Alignment.End) {
                                DonateOptionChip(
                                    visible = donateExpanded,
                                    delayMillis = 0,
                                    iconRes = com.example.R.drawable.ic_pay_upi,
                                    label = "UPI",
                                    enabled = true,
                                    horizontal = false,
                                    onClick = { launchUpi() }
                                )

                                Button(
                                    onClick = { donateExpanded = !donateExpanded },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GuardMintAccent.copy(alpha = 0.15f),
                                        contentColor = GuardMintAccent
                                    ),
                                    border = BorderStroke(1.dp, GuardMintAccent.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Donate",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Donate",
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
    horizontal: Boolean = false,
    onClick: () -> Unit
) {
    val enterTransition = if (horizontal) {
        fadeIn(animationSpec = tween(200, delayMillis)) +
            slideInHorizontally(animationSpec = tween(300, delayMillis)) { it } +
            scaleIn(
                animationSpec = tween(300, delayMillis),
                initialScale = 0.7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
            )
    } else {
        fadeIn(animationSpec = tween(200, delayMillis)) +
            slideInVertically(animationSpec = tween(300, delayMillis)) { it } +
            scaleIn(
                animationSpec = tween(300, delayMillis),
                initialScale = 0.7f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            )
    }
    val exitTransition = if (horizontal) {
        fadeOut(animationSpec = tween(120)) +
            slideOutHorizontally(animationSpec = tween(160)) { it / 2 }
    } else {
        fadeOut(animationSpec = tween(120)) +
            slideOutVertically(animationSpec = tween(160)) { it / 2 }
    }
    AnimatedVisibility(
        visible = visible,
        enter = enterTransition,
        exit = exitTransition
    ) {
        Row(
            modifier = Modifier
                .padding(if (horizontal) PaddingValues(end = 8.dp) else PaddingValues(bottom = 8.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) GuardMintAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                .border(
                    BorderStroke(1.dp, if (enabled) GuardMintAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)),
                    RoundedCornerShape(14.dp)
                )
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = iconRes),
                    contentDescription = label,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(17.dp)
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
            if (!enabled) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "soon",
                    color = GuardTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
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
        appName = appName,
        onSelected = onAccept,
        onBypass = onBypass,
        onMinimize = onMinimize
    )
}



@Composable
fun IntentionSelectionScreen(
    appName: String,
    onSelected: (String) -> Unit,
    onSkip: () -> Unit
) {
    val intentions = listOf(
        "💬  Read specific message",
        "✨  Post something creative",
        "⏳  Urgent task / Look up info",
        "🌪️  Doomscrolling / Habit / Bored"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Set Your Intention",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Why are you opening $appName right now?",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        intentions.forEach { intent ->
            Card(
                onClick = { onSelected(intent) },
                colors = CardDefaults.cardColors(
                    containerColor = GuardSurfaceItem,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = intent,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onSkip) {
            Text("Skip question", color = GuardTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun DurationSelectionScreen(
    isSarcasticMode: Boolean = false,
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
val SARCASTIC_FIRST_OPEN = listOf(
    "Oh, you're back? What a surprise.",
    "Do you really want to go there after spending so much time on this?",
    "Another hour of your life, gone. Ready for more?",
    "Ah yes, the pinnacle of human productivity.",
    "Sure, open it again. It's not like you had plans.",
    "Your future self is already disappointed in you.",
    "Because nothing says 'I'm thriving' like opening this app again.",
    "Oh look, the definition of insanity.",
    "I'm sure *this* time you'll just be a minute.",
    "Why be productive when you can do... whatever this is?",
    "Welcome back to the void.",
    "Haven't you had enough?",
    "Just admit you have no self-control.",
    "I was hoping you forgot about this app.",
    "Here we go again...",
    "Is this really the best use of your time?",
    "Oh joy. More scrolling.",
    "You again? Didn't you just leave?",
    "This is why you don't get things done.",
    "Prepare to achieve absolutely nothing.",
    "Are you procrastinating, or just giving up?",
    "Ah, your favorite time-waster.",
    "Let me guess: just checking 'one thing'?",
    "I bet you feel really productive right now.",
    "Don't you have something better to do?",
    "Welcome to the endless loop.",
    "I'm not judging. Okay, maybe a little.",
    "Are you sure about this?",
    "Let's waste some more time, shall we?",
    "You know this won't end well."
)

val SARCASTIC_EXPIRY = listOf(
    "Wow, time's up. Who could have seen that coming?",
    "Are you really going to extend this?",
    "Time flies when you're achieving nothing.",
    "You said just 5 minutes. We both knew it was a lie.",
    "Want more time? Really?",
    "Your 'quick check' is officially over.",
    "Shouldn't you be doing something else by now?",
    "The limit exists for a reason, you know.",
    "Extending? Color me shocked.",
    "I suppose you'll just hit extend again.",
    "Is this your life now?",
    "You're only lying to yourself.",
    "Go ahead, break your own rules.",
    "I'd tell you to stop, but you won't listen.",
    "Another extension? How original.",
    "Why do we even have a timer?",
    "Just close the app. You know you should.",
    "Do you really want to go ahead without a limit?",
    "Have some self-respect and close it.",
    "More time? Haven't you wasted enough?",
    "Sure, let's pretend this is necessary.",
    "I'm sure you 'need' to finish what you're doing.",
    "We both know you're just procrastinating.",
    "Extending the timer won't extend your potential.",
    "I'm tired of counting your wasted minutes.",
    "Do you even remember why you opened this?",
    "Just give up and close it.",
    "You're not fooling anyone.",
    "I guess self-control is hard.",
    "Go on, prove me right and extend it."
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
