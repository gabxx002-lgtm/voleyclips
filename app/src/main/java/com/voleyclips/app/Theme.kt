package com.voleyclips.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Carmesí y blanco sobre fondo oscuro neutro (con un leve tono cálido).
val VcInk = Color(0xFF110C0D)
val VcPanel = Color(0xFF1B1315)
val VcPanel2 = Color(0xFF271B1E)
val VcCrimson = Color(0xFFDC143C)
val VcAccent = Color(0xFFFFFFFF)
val VcOnAccent = Color(0xFF2A0A10)
val VcText = Color(0xFFF4EEEF)
val VcMuted = Color(0xFFBDB0B3)
val VcRed = VcCrimson

@Composable
fun VoleyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VcCrimson,
            onPrimary = Color.White,
            secondary = VcAccent,
            onSecondary = VcOnAccent,
            background = VcInk,
            onBackground = VcText,
            surface = VcPanel,
            onSurface = VcText,
            surfaceVariant = VcPanel2,
            onSurfaceVariant = VcMuted,
            error = Color(0xFFFF6B7F),
        ),
        content = content,
    )
}
