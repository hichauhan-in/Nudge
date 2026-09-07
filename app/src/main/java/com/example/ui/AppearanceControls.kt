package com.example.ui

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppearanceControls() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefs = remember(context) { context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme_mode", "dark").orEmpty()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_theme), theme, listOf("dark" to androidx.compose.ui.res.stringResource(com.example.R.string.ui_dark), "light" to androidx.compose.ui.res.stringResource(com.example.R.string.ui_light), "system" to androidx.compose.ui.res.stringResource(com.example.R.string.ui_system))) {
            theme = it
            prefs.edit().putString("theme_mode", it).apply()
        }
        RuleMenu(androidx.compose.ui.res.stringResource(com.example.R.string.ui_language), AppLanguage.selected(context), listOf("" to androidx.compose.ui.res.stringResource(com.example.R.string.ui_system), "en" to "English", "hi" to "Hindi (core screens)")) {
            if (activity != null) AppLanguage.select(activity, it)
        }
    }
}