package io.github.asterx5.kotlinspect.internal.uimodel

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.InternalKotlinspectApi

/**
 * Kotlinspect's palette as ARGB values, shared by the Compose and native UIs: ink-dark or
 * paper-light surfaces with a violet accent.
 */
@InternalKotlinspectApi
public class KsPalette(
    public val isDark: Boolean,
    public val bg: Long,
    public val surface: Long,
    public val surfaceAlt: Long,
    public val line: Long,
    public val text: Long,
    public val textMuted: Long,
    public val textFaint: Long,
    public val accent: Long,
    public val onAccent: Long,
    public val ok: Long,
    public val redirect: Long,
    public val warn: Long,
    public val error: Long,
    public val pending: Long,
    public val codeBg: Long,
    public val codeKey: Long,
    public val codeString: Long,
    public val codeNumber: Long,
    public val codeLiteral: Long,
    public val codePunct: Long,
) {
    public companion object {
        /** The bubble and toast use these in both themes so they read clearly over any app. */
        public const val INK: Long = 0xFF15171D
        public const val INK_TEXT: Long = 0xFFF2F3F7

        public val Dark: KsPalette = KsPalette(
            isDark = true,
            bg = 0xFF0D0E12, surface = 0xFF15171D, surfaceAlt = 0xFF1C1F27, line = 0xFF252933,
            text = 0xFFE9EBF1, textMuted = 0xFF9299A8, textFaint = 0xFF5C6270,
            accent = 0xFF8C7BFF, onAccent = 0xFF0D0E12,
            ok = 0xFF3DD68C, redirect = 0xFF5AB0FF, warn = 0xFFFFB547, error = 0xFFFF5C6C, pending = 0xFF8A90A0,
            codeBg = 0xFF111318, codeKey = 0xFFA8A0FF, codeString = 0xFF7EE2A8, codeNumber = 0xFFFFB86B,
            codeLiteral = 0xFFFF7AB6, codePunct = 0xFF6B7280,
        )

        public val Light: KsPalette = KsPalette(
            isDark = false,
            bg = 0xFFF5F6F9, surface = 0xFFFFFFFF, surfaceAlt = 0xFFEDEFF4, line = 0xFFE2E5EB,
            text = 0xFF14161B, textMuted = 0xFF5D6472, textFaint = 0xFF9AA0AB,
            accent = 0xFF5B48E0, onAccent = 0xFFFFFFFF,
            ok = 0xFF0F9D58, redirect = 0xFF1F78E0, warn = 0xFFC77700, error = 0xFFD92D3F, pending = 0xFF7A808C,
            codeBg = 0xFFF6F7FA, codeKey = 0xFF4B3BD1, codeString = 0xFF0E7A45, codeNumber = 0xFFB45600,
            codeLiteral = 0xFFC01F76, codePunct = 0xFF8A8F99,
        )

        public fun of(dark: Boolean): KsPalette = if (dark) Dark else Light
    }

    public fun methodColor(method: String): Long = when (method.uppercase()) {
        "GET" -> redirect
        "POST" -> ok
        "PUT" -> warn
        "PATCH" -> accent
        "DELETE" -> error
        else -> textMuted
    }

    public fun statusColor(state: CallState, code: Int?): Long = when {
        state == CallState.Pending -> pending
        state == CallState.Failed || state == CallState.Cancelled -> error
        code == null -> pending
        code >= 500 -> error
        code >= 400 -> warn
        code >= 300 -> redirect
        else -> ok
    }

    public fun tokenColor(kind: JsonTokenKind): Long = when (kind) {
        JsonTokenKind.Key -> codeKey
        JsonTokenKind.StringValue -> codeString
        JsonTokenKind.Number -> codeNumber
        JsonTokenKind.Literal -> codeLiteral
        JsonTokenKind.Punctuation -> codePunct
    }
}
