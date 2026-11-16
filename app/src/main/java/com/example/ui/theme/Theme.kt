package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DripDarkColorScheme =
  darkColorScheme(
    primary = DripElectricBlue,
    onPrimary = DripGraphiteBg,
    primaryContainer = DripElectricBlueDeep,
    onPrimaryContainer = DripTextPrimary,
    secondary = DripGoldAccent,
    onSecondary = DripGraphiteBg,
    tertiary = DripMetallicSilver,
    background = DripGraphiteBg,
    onBackground = DripTextPrimary,
    surface = DripSurfaceDark,
    onSurface = DripTextPrimary,
    surfaceVariant = DripSurfaceElevated,
    onSurfaceVariant = DripTextMuted,
    error = DripAlertRed,
    onError = DripTextPrimary,
  )

@Composable
fun DripRaceTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = DripDarkColorScheme,
    typography = DripTypography,
    content = content,
  )
}
