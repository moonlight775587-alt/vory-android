package dev.vory.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One UI 8.5 design language for Vory for Android.
 *
 * - Dark = true black (#000000) for AMOLED; light = One UI greys (#F7F7F7, white cards).
 * - Accent: Samsung Blue #0381FE, user-pickable (falls back to the default).
 * - Typography: system default, One UI-like scale — large titles 32sp bold.
 * - Motion: cheap — no heavy blur/shadow overdraw; reduced motion is honoured
 *   by the components that animate.
 */
object OneUi {
    val SamsungBlue = Color(0xFF0381FE)
    val CardCorner = 24.dp
    val ScreenPadding = 20.dp
    val RowPadding = 16.dp
}

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    secondary = accent,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF6B6B),
    tertiary = Color(0xFF64D2FF),
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    secondary = accent,
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFECECEC),
    error = Color(0xFFD32F2F),
    tertiary = Color(0xFF007AFF),
)

private val VoryTypography = Typography(
    // One UI large title
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun VoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentArgb: Int? = null,
    content: @Composable () -> Unit,
) {
    val accent = accentArgb?.let { Color(it) } ?: OneUi.SamsungBlue
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme(accent) else lightScheme(accent),
        typography = VoryTypography,
        content = content,
    )
}
