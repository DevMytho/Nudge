package com.dev.nudge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.dev.nudge.CardBeige

private val LightColorScheme = lightColorScheme(
    primary = SoftBlue,
    background = Beige,
    surface = Beige,

    onPrimary = DarkText,
    onBackground = DarkText,
    onSurface = DarkText
)

@Composable
fun NudgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = SoftBlue,
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            onPrimary = DarkText,
            onBackground = androidx.compose.ui.graphics.Color.White,
            onSurface = androidx.compose.ui.graphics.Color.White
        )
    } else {
        lightColorScheme(
            primary = SoftBlue,
            background = androidx.compose.ui.graphics.Color.White,
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