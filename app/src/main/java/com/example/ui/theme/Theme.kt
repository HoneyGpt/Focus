package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = GeoPrimaryPurple,
    onPrimary = Color.White,
    primaryContainer = GeoAccentContainer,
    onPrimaryContainer = GeoPrimaryText,
    secondary = GeoHighlightPill,
    background = GeoBackground,
    onBackground = GeoPrimaryText,
    surface = GeoCardBackground,
    onSurface = GeoPrimaryText,
    surfaceVariant = GeoCardBackground,
    onSurfaceVariant = GeoSecondaryText,
    outline = GeoDivider,
    error = GeoWarningRed
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false,
  // Disable dynamic colors by default so that our theme is consistent
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  // Always use LightColorScheme to guarantee pristine layout, perfect high contrast text,
  // and complete immunity to dynamic device forcing glitches on Vivo/Redmi/Oppo/Samsung etc.
  val colorScheme = LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
