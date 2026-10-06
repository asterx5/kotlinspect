package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF1A0E6B),
    secondaryContainer = Color(0xFFE7E5F2),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE2E2),
    surface = Color(0xFFFCFBFF),
    surfaceVariant = Color(0xFFEDEBF4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1C9A),
    primaryContainer = Color(0xFF4335B8),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondaryContainer = Color(0xFF34323F),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C1A1A),
    surface = Color(0xFF15141B),
    surfaceVariant = Color(0xFF2A2933),
)

internal object StatusColors {
    val success = Color(0xFF2E7D32)
    val redirect = Color(0xFF1565C0)
    val clientError = Color(0xFFEF6C00)
    val serverError = Color(0xFFC62828)
    val pending = Color(0xFF757575)
}

internal val Mono: FontFamily = FontFamily.Monospace

@Composable
internal fun KotlinspectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
