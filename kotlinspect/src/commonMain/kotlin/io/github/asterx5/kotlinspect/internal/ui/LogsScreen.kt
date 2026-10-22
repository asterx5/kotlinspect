package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlinspect.internal.formatTime
import io.github.asterx5.kotlinspect.internal.uimodel.KsPalette
import io.github.asterx5.kotlinspect.internal.uimodel.NO_LAYER
import io.github.asterx5.kotlinspect.internal.uimodel.filterLogs
import io.github.asterx5.kotlinspect.internal.uimodel.levelLetter
import io.github.asterx5.kotlinspect.internal.uimodel.presentLevels
import io.github.asterx5.kotlinspect.internal.uimodel.presentValues
import kotlinx.coroutines.flow.flowOf

internal fun KsColors.levelColor(level: String): Color = Color(KsPalette.of(isDark).levelColor(level))

/**
 * The Logs tab: KtLog entries for one session, with search and multi-select filters. Filter
 * chips only list tags, layers and levels that actually appear in the session.
 */
@Suppress("DEPRECATION")
@Composable
internal fun LogsContent(
    host: KotlinspectHost,
    sessionId: String?,
    state: CallListState,
    scroll: LazyListState,
    onCopied: (String) -> Unit,
) {
    val logs by remember(sessionId) { sessionId?.let { host.logs(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val levels = remember(logs) { presentLevels(logs) }
    val tags = remember(logs) { presentValues(logs) { it.tag } }
    val layers = remember(logs) { presentValues(logs) { it.layer ?: NO_LAYER } }
    val filtered = remember(logs, state.logQuery, state.logTags, state.logLayers, state.logLevels) {
        filterLogs(logs, state.logQuery, state.logTags, state.logLayers, state.logLevels)
    }
    val clipboard = LocalClipboardManager.current
    val c = Ks.colors

    Column(Modifier.fillMaxSize()) {
        SearchField(state.logQuery, { state.logQuery = it }, "Search messages", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (levels.size > 1) {
            FilterRow {
                levels.forEach { (level, count) ->
                    Chip(levelLetter(level), level in state.logLevels, count, tint = c.levelColor(level)) {
                        state.logLevels = state.logLevels.toggle(level)
                    }
                }
            }
        }
        if (tags.size > 1) {
            FilterRow(label = "Tags", onClear = if (state.logTags.isEmpty()) null else ({ state.logTags = emptySet() })) {
                tags.forEach { (tag, count) ->
                    Chip(tag, tag in state.logTags, count) { state.logTags = state.logTags.toggle(tag) }
                }
            }
        }
        if (layers.size > 1) {
            FilterRow(label = "Layers", onClear = if (state.logLayers.isEmpty()) null else ({ state.logLayers = emptySet() })) {
                layers.forEach { (layer, count) ->
                    Chip(layer, layer in state.logLayers, count, tint = c.accent) { state.logLayers = state.logLayers.toggle(layer) }
                }
            }
        }
        Hairline()
        if (filtered.isEmpty()) {
            EmptyStateLogs(logs.isEmpty())
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 32.dp)) {
                items(filtered, key = { it.id }, contentType = { "log" }) { log ->
                    LogRow(
                        log = log,
                        expanded = log.id in state.expandedLogs,
                        onToggle = { state.expandedLogs = state.expandedLogs.toggle(log.id) },
                        onCopy = {
                            clipboard.setText(AnnotatedString(log.copyText()))
                            onCopied("Log")
                        },
                    )
                }
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun LogEntity.copyText(): String = buildString {
    append("${levelLetter(level)}/$tag")
    layer?.let { append(" [$it]") }
    append(": ").append(message)
    throwable?.let { append('\n').append(it) }
}

@Composable
private fun FilterRow(label: String? = null, onClear: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) KsText(label.uppercase(), Ks.type.label)
        content()
        if (onClear != null) Action("Clear", onClick = onClear)
    }
}

@Composable
private fun EmptyStateLogs(noLogs: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        KsText("≡", Ks.type.hero, color = Ks.colors.textFaint)
        Spacer(Modifier.height(12.dp))
        KsText(if (noLogs) "No logs yet" else "Nothing matches", Ks.type.title)
        Spacer(Modifier.height(4.dp))
        KsText(
            if (noLogs) "Entries logged with KtLog appear here, e.g. KtLog.d(TAG, \"message\", \"datasource\")." else "Try another filter or search.",
            Ks.type.caption,
        )
    }
}

@Composable
private fun LogRow(log: LogEntity, expanded: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val c = Ks.colors
    val color = c.levelColor(log.level)
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(levelLetter(log.level), color, minWidth = 22.dp)
            Spacer(Modifier.width(8.dp))
            KsText(log.tag, Ks.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            log.layer?.let { layer ->
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(c.surfaceAlt).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    KsText(layer, Ks.type.caption, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            KsText(formatTime(log.timestamp), Ks.type.caption, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        if (expanded) {
            SelectionContainer { KsText(log.message, Ks.type.monoSmall) }
            log.throwable?.let { trace ->
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.codeBg).padding(10.dp)) {
                    SelectionContainer { KsText(trace, Ks.type.monoSmall.copy(color = c.error)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                Action("Copy", onClick = onCopy)
            }
        } else {
            KsText(log.message, Ks.type.monoSmall, maxLines = 3)
            if (log.throwable != null) KsText("Has a stack trace · tap to expand", Ks.type.caption.copy(color = c.error), maxLines = 1)
        }
    }
    Hairline(Modifier.padding(start = 16.dp))
}
