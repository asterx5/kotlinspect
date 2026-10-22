package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.asterx5.kotlinspect.internal.uimodel.JsonTokenKind
import io.github.asterx5.kotlinspect.internal.uimodel.jsonTokens

/** Per-line highlighted body text, built once off the main thread and rendered lazily. */
internal class HighlightedBody(val lines: List<AnnotatedString>, val isJson: Boolean)

/** Splits [text] into lines, highlighting JSON tokens (from the shared tokenizer) when [json] is true. */
internal fun highlight(text: String, json: Boolean, colors: KsColors): HighlightedBody {
    val raw = text.split('\n')
    if (!json) return HighlightedBody(raw.map { AnnotatedString(it) }, isJson = false)
    val styles = mapOf(
        JsonTokenKind.Key to SpanStyle(color = colors.codeKey),
        JsonTokenKind.StringValue to SpanStyle(color = colors.codeString),
        JsonTokenKind.Number to SpanStyle(color = colors.codeNumber),
        JsonTokenKind.Literal to SpanStyle(color = colors.codeLiteral),
        JsonTokenKind.Punctuation to SpanStyle(color = colors.codePunct),
    )
    val lines = raw.map { line ->
        buildAnnotatedString {
            var cursor = 0
            jsonTokens(line).forEach { token ->
                if (token.start > cursor) append(line, cursor, token.start)
                withStyle(styles.getValue(token.kind)) { append(line, token.start, token.end) }
                cursor = token.end
            }
            if (cursor < line.length) append(line, cursor, line.length)
        }
    }
    return HighlightedBody(lines, isJson = true)
}
