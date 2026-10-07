package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Per-line highlighted body text, built once off the main thread and rendered lazily. */
internal class HighlightedBody(val lines: List<AnnotatedString>, val isJson: Boolean)

/** True for newline-delimited JSON (one object or array per line), which is highlighted line by line. */
internal fun looksLikeJsonLines(text: String): Boolean {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(50).toList()
    return lines.isNotEmpty() && lines.all { (it.startsWith("{") && it.endsWith("}")) || (it.startsWith("[") && it.endsWith("]")) }
}

/**
 * Splits [text] into lines, highlighting JSON tokens when [json] is true. Pretty-printed JSON keeps
 * every string on one line, so tokenizing line by line is exact.
 */
internal fun highlight(text: String, json: Boolean, colors: KsColors): HighlightedBody {
    val raw = text.split('\n')
    if (!json) return HighlightedBody(raw.map { AnnotatedString(it) }, isJson = false)
    val key = SpanStyle(color = colors.codeKey)
    val string = SpanStyle(color = colors.codeString)
    val number = SpanStyle(color = colors.codeNumber)
    val literal = SpanStyle(color = colors.codeLiteral)
    val punct = SpanStyle(color = colors.codePunct)
    return HighlightedBody(raw.map { line -> highlightJsonLine(line, key, string, number, literal, punct) }, isJson = true)
}

private fun highlightJsonLine(
    line: String,
    key: SpanStyle,
    string: SpanStyle,
    number: SpanStyle,
    literal: SpanStyle,
    punct: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' -> {
                var j = i + 1
                while (j < line.length && line[j] != '"') j += if (line[j] == '\\') 2 else 1
                val end = minOf(j + 1, line.length)
                var k = end
                while (k < line.length && line[k] == ' ') k++
                val isKey = k < line.length && line[k] == ':'
                withStyle(if (isKey) key else string) { append(line, i, end) }
                i = end
            }
            ch == '-' || ch.isDigit() -> {
                var j = i + 1
                while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++
                withStyle(number) { append(line, i, j) }
                i = j
            }
            line.startsWith("true", i) || line.startsWith("null", i) -> {
                withStyle(literal) { append(line, i, i + 4) }
                i += 4
            }
            line.startsWith("false", i) -> {
                withStyle(literal) { append(line, i, i + 5) }
                i += 5
            }
            ch in "{}[],:" -> {
                withStyle(punct) { append(ch) }
                i++
            }
            else -> {
                append(ch)
                i++
            }
        }
    }
}
