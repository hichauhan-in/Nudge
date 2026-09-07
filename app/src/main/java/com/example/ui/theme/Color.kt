package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

data class GuardPalette(val background: Color, val surface: Color, val item: Color, val accent: Color, val text: Color, val secondary: Color)

val DarkGuardPalette = GuardPalette(Color(0xFF0A0A0A), Color(0xFF161616), Color(0xFF121212), Color(0xFFA5D6A7), Color.White, Color(0xFF9E9E9E))
val LightGuardPalette = GuardPalette(Color(0xFFF5F7F6), Color.White, Color(0xFFEBF0ED), Color(0xFF286B48), Color(0xFF16251C), Color(0xFF526458))
val LocalGuardPalette = staticCompositionLocalOf { DarkGuardPalette }

val GuardBlack: Color @Composable get() = LocalGuardPalette.current.background
val GuardSurface: Color @Composable get() = LocalGuardPalette.current.surface
val GuardSurfaceItem: Color @Composable get() = LocalGuardPalette.current.item
val GuardMintAccent: Color @Composable get() = LocalGuardPalette.current.accent
val GuardTextPrimary: Color @Composable get() = LocalGuardPalette.current.text
val GuardTextSecondary: Color @Composable get() = LocalGuardPalette.current.secondary

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
