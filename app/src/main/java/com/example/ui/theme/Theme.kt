package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean? = null,
  // Disable dynamic system color by default to preserve the premium custom branding
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val prefs = remember(context) { context.getSharedPreferences("focus_time_prefs", android.content.Context.MODE_PRIVATE) }
  var mode by remember(prefs) { mutableStateOf(prefs.getString("theme_mode", "dark")) }
  DisposableEffect(prefs) {
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == "theme_mode") mode = prefs.getString("theme_mode", "dark")
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
  }
  val useDark = darkTheme ?: when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
  val palette = if (useDark) DarkGuardPalette else LightGuardPalette
  val base = if (useDark) darkColorScheme() else lightColorScheme()
  val scheme = base.copy(primary = palette.accent, onPrimary = palette.background,
    primaryContainer = palette.accent.copy(alpha = 0.18f), onPrimaryContainer = palette.text,
    secondary = palette.secondary, secondaryContainer = palette.accent.copy(alpha = 0.22f), onSecondaryContainer = palette.text,
    background = palette.background, surface = palette.surface,
    onBackground = palette.text, onSurface = palette.text, onSurfaceVariant = palette.secondary,
    surfaceVariant = palette.item)
  CompositionLocalProvider(LocalGuardPalette provides palette) {
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
  }
}
