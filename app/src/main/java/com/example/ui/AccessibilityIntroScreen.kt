package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.*

@Composable
fun AccessibilityIntroScreen(onContinue: () -> Unit, onNotNow: () -> Unit) {
    BackHandler(onBack = onNotNow)
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val compact = LocalConfiguration.current.screenHeightDp < 600 || largeText
    Scaffold(containerColor = GuardBlack, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            Modifier.fillMaxSize().testTag("accessibility-intro")
                .padding(padding).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!compact) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        Modifier.size(112.dp)
                            .background(GuardMintAccent.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                            .border(BorderStroke(1.5.dp, GuardMintAccent), RoundedCornerShape(32.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null,
                            tint = GuardMintAccent, modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.app_name), color = GuardMintAccent,
                        style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }
                Text(stringResource(R.string.accessibility_intro_title), color = GuardTextPrimary,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    style = if (largeText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.accessibility_intro_message), color = GuardTextSecondary,
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.accessibility_intro_no_consent), color = GuardTextSecondary,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack)
            ) {
                Text(stringResource(R.string.accessibility_intro_continue), fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GuardTextPrimary)
            ) {
                Text(stringResource(R.string.accessibility_intro_not_now), textAlign = TextAlign.Center)
            }
        }
    }
}