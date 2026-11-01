package com.narada.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NaradaColors {
    val Ink = Color(0xFF191A1C)
    val Muted = Color(0xFF60646A)
    val Background = Color(0xFFFAFAF8)
    val Soft = Color(0xFFF1F2F0)
    val Border = Color(0xFFE2E3E0)
    val OnDarkMuted = Color(0xFFC6C9CD)
}
private val scheme = lightColorScheme(
    primary = NaradaColors.Ink, onPrimary = Color.White, primaryContainer = NaradaColors.Soft, onPrimaryContainer = NaradaColors.Ink,
    secondary = NaradaColors.Muted, onSecondary = Color.White, secondaryContainer = NaradaColors.Soft, onSecondaryContainer = NaradaColors.Ink,
    background = NaradaColors.Background, onBackground = NaradaColors.Ink,
    surface = Color.White, onSurface = NaradaColors.Ink, surfaceVariant = NaradaColors.Soft, onSurfaceVariant = NaradaColors.Muted,
    outline = NaradaColors.Muted, outlineVariant = NaradaColors.Border,
    error = Color(0xFF8D2424), onError = Color.White,
    surfaceContainer = Color.White, surfaceContainerLow = NaradaColors.Background, surfaceContainerHigh = NaradaColors.Soft,
)
@Composable
fun NaradaTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-0.7).sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 23.sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            labelMedium = base.labelMedium.copy(fontSize = 12.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}
