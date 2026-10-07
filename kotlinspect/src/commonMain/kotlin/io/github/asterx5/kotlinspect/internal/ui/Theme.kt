package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.asterx5.kotlinspect.CallState

/** Kotlinspect's own palette: ink-dark or paper-light surfaces with a violet accent. */
@Immutable
internal class KsColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val redirect: Color,
    val warn: Color,
    val error: Color,
    val pending: Color,
    val codeBg: Color,
    val codeKey: Color,
    val codeString: Color,
    val codeNumber: Color,
    val codeLiteral: Color,
    val codePunct: Color,
)

private val DarkColors = KsColors(
    isDark = true,
    bg = Color(0xFF0D0E12),
    surface = Color(0xFF15171D),
    surfaceAlt = Color(0xFF1C1F27),
    line = Color(0xFF252933),
    text = Color(0xFFE9EBF1),
    textMuted = Color(0xFF9299A8),
    textFaint = Color(0xFF5C6270),
    accent = Color(0xFF8C7BFF),
    onAccent = Color(0xFF0D0E12),
    ok = Color(0xFF3DD68C),
    redirect = Color(0xFF5AB0FF),
    warn = Color(0xFFFFB547),
    error = Color(0xFFFF5C6C),
    pending = Color(0xFF8A90A0),
    codeBg = Color(0xFF111318),
    codeKey = Color(0xFFA8A0FF),
    codeString = Color(0xFF7EE2A8),
    codeNumber = Color(0xFFFFB86B),
    codeLiteral = Color(0xFFFF7AB6),
    codePunct = Color(0xFF6B7280),
)

private val LightColors = KsColors(
    isDark = false,
    bg = Color(0xFFF5F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEDEFF4),
    line = Color(0xFFE2E5EB),
    text = Color(0xFF14161B),
    textMuted = Color(0xFF5D6472),
    textFaint = Color(0xFF9AA0AB),
    accent = Color(0xFF5B48E0),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF0F9D58),
    redirect = Color(0xFF1F78E0),
    warn = Color(0xFFC77700),
    error = Color(0xFFD92D3F),
    pending = Color(0xFF7A808C),
    codeBg = Color(0xFFF6F7FA),
    codeKey = Color(0xFF4B3BD1),
    codeString = Color(0xFF0E7A45),
    codeNumber = Color(0xFFB45600),
    codeLiteral = Color(0xFFC01F76),
    codePunct = Color(0xFF8A8F99),
)

internal val Mono: FontFamily = FontFamily.Monospace

/** The palette outside Compose, e.g. for the native iOS inspector. */
internal fun ksColors(dark: Boolean): KsColors = if (dark) DarkColors else LightColors

@Immutable
internal class KsType(colors: KsColors) {
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 14.sp, color = colors.text, lineHeight = 19.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.sp, color = colors.textMuted, lineHeight = 16.sp)
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textMuted, letterSpacing = 0.8.sp)
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = colors.text, lineHeight = 18.sp)
    val monoSmall = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = colors.text, lineHeight = 17.sp)
    val monoStrong = mono.copy(fontWeight = FontWeight.Bold)
    val hero = TextStyle(fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colors.text, lineHeight = 38.sp)
}

private val LocalKsColors = staticCompositionLocalOf { DarkColors }
private val LocalKsType = staticCompositionLocalOf { KsType(DarkColors) }

private val DarkType = KsType(DarkColors)
private val LightType = KsType(LightColors)

internal object Ks {
    val colors: KsColors
        @Composable get() = LocalKsColors.current
    val type: KsType
        @Composable get() = LocalKsType.current
}

@Composable
internal fun KotlinspectTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalKsColors provides if (dark) DarkColors else LightColors,
        LocalKsType provides if (dark) DarkType else LightType,
        content = content,
    )
}

internal fun KsColors.methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> redirect
    "POST" -> ok
    "PUT" -> warn
    "PATCH" -> accent
    "DELETE" -> error
    else -> textMuted
}

internal fun KsColors.statusColor(state: CallState, code: Int?): Color = when {
    state == CallState.Pending -> pending
    state == CallState.Failed || state == CallState.Cancelled -> error
    code == null -> pending
    code >= 500 -> error
    code >= 400 -> warn
    code >= 300 -> redirect
    else -> ok
}
