package app.sicumi.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Токены направления C «Смелые формы». Источник правды: shared/design/tokens.json */
object SicumiColors {
    val Violet = Color(0xFF4B2FD6)
    val Violet2 = Color(0xFF5D44E6)
    val Lilac = Color(0xFFE0DAFF)
    val LilacText = Color(0xFFE4DEFF)
    val Background = Color(0xFFF1EEFF)
    val White = Color(0xFFFFFFFF)
    val Tangerine = Color(0xFFFF8A3D)
    val Peach = Color(0xFFFFE3CF)
    val PeachText = Color(0xFF8A3E0B)
    val Ink = Color(0xFF17123A)
    val Muted = Color(0xFF5B5680)
    val Success = Color(0xFF1D6B43)
    val SuccessBg = Color(0xFFD8F5E4)
}

internal val SicumiColorScheme = lightColorScheme(
    primary = SicumiColors.Violet,
    onPrimary = SicumiColors.White,
    primaryContainer = SicumiColors.Lilac,
    onPrimaryContainer = SicumiColors.Ink,
    secondary = SicumiColors.Tangerine,
    onSecondary = SicumiColors.Ink,
    secondaryContainer = SicumiColors.Peach,
    onSecondaryContainer = SicumiColors.PeachText,
    tertiary = SicumiColors.Success,
    tertiaryContainer = SicumiColors.SuccessBg,
    background = SicumiColors.Background,
    onBackground = SicumiColors.Ink,
    surface = SicumiColors.White,
    onSurface = SicumiColors.Ink,
    surfaceVariant = SicumiColors.Lilac,
    onSurfaceVariant = SicumiColors.Muted,
)
