package com.voleyclips.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VcInk = Color(0xFF0A111C)
val VcPanel = Color(0xFF121C2B)
val VcPanel2 = Color(0xFF1B2840)
val VcBlue = Color(0xFF7A9CFF)
val VcYellow = Color(0xFFF5C518)
val VcYellowInk = Color(0xFF1C1500)
val VcText = Color(0xFFE8EEF7)
val VcMuted = Color(0xFF9DAEC4)
val VcRed = Color(0xFFE5484D)

@Composable
fun VoleyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VcBlue,
            onPrimary = VcInk,
            secondary = VcYellow,
            onSecondary = VcYellowInk,
            background = VcInk,
            onBackground = VcText,
            surface = VcPanel,
            onSurface = VcText,
            surfaceVariant = VcPanel2,
            onSurfaceVariant = VcMuted,
            error = VcRed,
        ),
        content = content,
    )
}
