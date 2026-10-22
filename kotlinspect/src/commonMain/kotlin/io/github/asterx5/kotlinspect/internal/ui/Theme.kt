package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.uimodel.KsPalette

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

/** Compose colors for the shared core palette, so the Compose and native UIs always match. */
private fun KsPalette.toColors() = KsColors(
    isDark = isDark,
    bg = Color(bg), surface = Color(surface), surfaceAlt = Color(surfaceAlt), line = Color(line),
    text = Color(text), textMuted = Color(textMuted), textFaint = Color(textFaint),
    accent = Color(accent), onAccent = Color(onAccent),
    ok = Color(ok), redirect = Color(redirect), warn = Color(warn), error = Color(error), pending = Color(pending),
    codeBg = Color(codeBg), codeKey = Color(codeKey), codeString = Color(codeString), codeNumber = Color(codeNumber),
    codeLiteral = Color(codeLiteral), codePunct = Color(codePunct),
)

private val DarkColors = KsPalette.Dark.toColors()
private val LightColors = KsPalette.Light.toColors()

internal val Mono: FontFamily = FontFamily.Monospace

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

private val DarkType = KsType(DarkColors)
private val LightType = KsType(LightColors)

/**
 * Colors and type follow the system theme directly rather than through composition locals.
 * Providing locals passes ProvidedValue to a Compose runtime function, and code compiled against
 * the Compose 1.7 floor then reads a stability field that newer runtimes made private, which
 * fails to link on iOS (IrLinkageError).
 */
internal object Ks {
    val colors: KsColors
        @Composable get() = if (isSystemInDarkTheme()) DarkColors else LightColors
    val type: KsType
        @Composable get() = if (isSystemInDarkTheme()) DarkType else LightType
}

@Composable
internal fun KotlinspectTheme(content: @Composable () -> Unit) {
    content()
}

internal fun KsColors.methodColor(method: String): Color = Color(KsPalette.of(isDark).methodColor(method))

internal fun KsColors.statusColor(state: CallState, code: Int?): Color = Color(KsPalette.of(isDark).statusColor(state, code))
