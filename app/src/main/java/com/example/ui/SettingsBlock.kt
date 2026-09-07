package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.*

@Composable
internal fun SettingsBlock(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = GuardMintAccent
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GuardSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GuardTextPrimary.copy(alpha = 0.05f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 72.dp).padding(16.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).background(accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = GuardTextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GuardTextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = GuardTextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun <Value> SettingsChoiceBlock(
    title: String,
    icon: ImageVector,
    selected: Value,
    options: List<Pair<Value, String>>,
    modifier: Modifier = Modifier,
    onSelect: (Value) -> Unit
) {
    var showChoices by remember { mutableStateOf(false) }
    SettingsBlock(title, options.firstOrNull { it.first == selected }?.second ?: selected.toString(), icon,
        onClick = { showChoices = true }, modifier = modifier)
    if (showChoices) {
        SettingsChoiceDialog(title, selected, options, onDismiss = { showChoices = false }) {
            showChoices = false
            onSelect(it)
        }
    }
}

@Composable
internal fun <Value> SettingsChoiceDialog(
    title: String,
    selected: Value,
    options: List<Pair<Value, String>>,
    onDismiss: () -> Unit,
    onSelect: (Value) -> Unit
) {
    EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = GuardTextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected == value, role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, modifier = Modifier.weight(1f), color = GuardTextPrimary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } }
    )
}