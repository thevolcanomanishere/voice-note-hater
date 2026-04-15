package com.watranscribe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Grey90 = Color(0xFFE5E5E5)
private val Grey60 = Color(0xFF999999)
private val Grey40 = Color(0xFF666666)
private val Grey20 = Color(0xFF333333)
private val Grey10 = Color(0xFF1A1A1A)
private val Grey05 = Color(0xFF0D0D0D)

private val DarkColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = Grey60,
    onSecondary = Black,
    tertiary = Grey40,
    background = Black,
    onBackground = White,
    surface = Grey05,
    onSurface = White,
    surfaceVariant = Grey10,
    onSurfaceVariant = Grey90,
    outline = Grey20,
    outlineVariant = Grey10,
    error = Color(0xFFCF6679),
    onError = Black,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = White
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        color = White
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = White
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = White
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = Grey90
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = Grey90
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = Grey60
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = White
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = Grey60
    ),
)

@Composable
fun WaTranscribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
