package pl.lokalizator.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF0E7C86)
val Hot = Color(0xFFE4572E)
val Cold = Color(0xFF3A86FF)
val Ok = Color(0xFF2A9D8F)

@Composable
fun LokTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFF5CC8D0), secondary = Color(0xFFFFB27A),
        ) else lightColorScheme(
            primary = Teal, secondary = Color(0xFFB85C1E),
        ),
        content = content,
    )
}
