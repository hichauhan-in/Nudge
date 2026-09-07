package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.GuardSurface

@Composable
internal fun EditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).heightIn(max = maxHeight),
            color = GuardSurface, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) { text() }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}