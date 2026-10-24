package com.dev.nudge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dev.nudge.CardBeige
import com.dev.nudge.data.AccentColor

/**
 * 🎨 Accent palette (spec §5 personalization): one accent hue layered over
 * the existing beige/blue theme. Light mode uses soft pastels (dark glyphs),
 * dark mode uses deeper tints that stay readable on #121212.
 */
fun accentPrimary(accent: AccentColor, dark: Boolean): Color = when (accent) {
    AccentColor.BLUE -> if (dark) Color(0xFF9DBBD0) else SoftBlue
    AccentColor.GREEN -> if (dark) Color(0xFF9CCFA0) else Color(0xFFCDE8CE)
    AccentColor.PURPLE -> if (dark) Color(0xFFB3A8DE) else Color(0xFFDCD3EF)
    AccentColor.ORANGE -> if (dark) Color(0xFFE5B98A) else Color(0xFFF7DFC2)
    AccentColor.ROSE -> if (dark) Color(0xFFD9A8B3) else Color(0xFFF5D4DB)
    AccentColor.MONO -> if (dark) Color(0xFFBFBFBF) else Color(0xFFE4E4E4)
}

/** Swatch shown in Settings — same hue in both modes for a stable picker. */
fun accentSwatch(accent: AccentColor): Color = accentPrimary(accent, dark = false)

@Composable
fun NudgeTheme(
    darkTheme: Boolean = false,
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit
) {
    val primary = accentPrimary(accent, darkTheme)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = primary,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = DarkText,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = primary,
            background = Color.White,
            surface = CardBeige,
            onPrimary = DarkText,
            onBackground = DarkText,
            onSurface = DarkText
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}