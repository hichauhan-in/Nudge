package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.*

@Composable
fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    BackHandler(onBack = onDecline)
    Scaffold(containerColor = GuardBlack, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().testTag("accessibility-disclosure")
                .padding(padding).padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Default.PrivacyTip, null, tint = GuardMintAccent, modifier = Modifier.size(36.dp))
                Text(
                    stringResource(R.string.accessibility_disclosure_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = GuardTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.accessibility_disclosure_access), color = GuardTextPrimary)
                Text(stringResource(R.string.accessibility_disclosure_storage), color = GuardTextPrimary)
                Text(stringResource(R.string.accessibility_disclosure_exclusions), color = GuardTextSecondary)
                Text(stringResource(R.string.accessibility_disclosure_choice), color = GuardTextSecondary)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GuardMintAccent, contentColor = GuardBlack)
            ) {
                Text(stringResource(R.string.accessibility_agree), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GuardTextPrimary)
            ) {
                Text(stringResource(R.string.accessibility_decline))
            }
        }
    }
}