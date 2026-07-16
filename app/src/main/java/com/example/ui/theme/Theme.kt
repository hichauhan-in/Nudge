package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = GuardMintAccent,
    secondary = GuardTextSecondary,
    tertiary = GuardSurfaceItem,
    background = GuardBlack,
    surface = GuardSurface,
    onBackground = GuardTextPrimary,
    onSurface = GuardTextPrimary
  )

private val LightColorScheme = DarkColorScheme // Elegant unified dark branding

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force sophisticated dark mode globally
  // Disable dynamic system color by default to preserve the premium custom branding
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      else -> DarkColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
