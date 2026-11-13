package com.igi.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val IgiBlack = Color(0xFF030503)
val IgiSurface = Color(0xFF0A120B)
val IgiSurface2 = Color(0xFF101B11)
val IgiGreen = Color(0xFF6EFF6E)
val IgiGreenDim = Color(0xFF2E7D3A)
val IgiGold = Color(0xFFE8B84A)
val IgiText = Color(0xFFC8D2B4)
val IgiDim = Color(0xFF6F7A5E)
val IgiRed = Color(0xFFFF6262)

private val IgiTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 3.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 1.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.sp
    )
)

@Composable
fun IgiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = IgiGreen,
            onPrimary = Color.Black,
            primaryContainer = IgiGreenDim,
            onPrimaryContainer = Color.White,
            secondary = IgiGold,
            onSecondary = Color.Black,
            background = IgiBlack,
            onBackground = IgiText,
            surface = IgiSurface,
            onSurface = IgiText,
            surfaceVariant = IgiSurface2,
            onSurfaceVariant = IgiDim,
            outline = IgiGreen.copy(alpha = 0.35f),
            error = IgiRed,
            onError = Color.Black
        ),
        typography = IgiTypography,
        content = content
    )
}
