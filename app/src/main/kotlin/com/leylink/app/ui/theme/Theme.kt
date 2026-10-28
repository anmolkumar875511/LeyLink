package com.leylink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Night-sky palette: ink background, antique gold for the "ley" light, teal for living nodes, ember for SOS. */
object LeyColors {
    val Night = Color(0xFF0B0D14)
    val NightLift = Color(0xFF141826)
    val Surface = Color(0xFF12151F)
    val Line = Color(0xFF2B3045)
    val Gold = Color(0xFFD8B26A)
    val Moon = Color(0xFFE8E4D8)
    val Mist = Color(0xFF8E93A6)
    val Aurora = Color(0xFF6FD0C4)
    val Ember = Color(0xFFE0675A)
    val Parchment = Color(0xFFF4EEE0)
}

private val NightScheme = darkColorScheme(
    primary = LeyColors.Gold,
    onPrimary = LeyColors.Night,
    secondary = LeyColors.Mist,
    tertiary = LeyColors.Aurora,
    background = LeyColors.Night,
    onBackground = LeyColors.Moon,
    surface = LeyColors.Surface,
    onSurface = LeyColors.Moon,
    surfaceVariant = LeyColors.NightLift,
    onSurfaceVariant = LeyColors.Mist,
    outline = LeyColors.Line,
    error = LeyColors.Ember,
    onError = LeyColors.Night,
    errorContainer = Color(0xFF2A1618),
    onErrorContainer = Color(0xFFF2C9C3),
)

private val DawnScheme = lightColorScheme(
    primary = Color(0xFF7A5A1E),
    onPrimary = LeyColors.Parchment,
    secondary = Color(0xFF6B6F7E),
    tertiary = Color(0xFF1E7C72),
    background = LeyColors.Parchment,
    onBackground = Color(0xFF1B1D26),
    surface = Color(0xFFFAF6EA),
    onSurface = Color(0xFF1B1D26),
    surfaceVariant = Color(0xFFEAE3D0),
    onSurfaceVariant = Color(0xFF6B6F7E),
    outline = Color(0xFFCFC6AE),
    error = Color(0xFFB3382C),
    onError = Color.White,
    errorContainer = Color(0xFFF6DDD8),
    onErrorContainer = Color(0xFF5A1912),
)

private val Serif = FontFamily.Serif

private val LeyTypography = Typography(
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 20.sp, letterSpacing = 4.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = Serif, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 2.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.5.sp),
)

private val LeyShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

/** Night by default (it is a blackout app); pass `darkTheme = false` for the parchment "dawn" variant. */
@Composable
fun LeyLinkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightScheme else DawnScheme,
        typography = LeyTypography,
        shapes = LeyShapes,
        content = content,
    )
}
