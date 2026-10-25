package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
 * The Logs tab: Kotlog entries for one session, with search and multi-select filters. Level, tag
 * and layer each get a dropdown, like the native iOS inspector, listing only the values that
 * actually appear in the session, with their counts.
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
        // A filter with only one value has nothing to choose between, so it is left out.
        if (levels.size > 1 || tags.size > 1 || layers.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (levels.size > 1) {
                    FilterMenu(
                        title = "Level",
                        values = levels,
                        selected = state.logLevels,
                        onChange = { state.logLevels = it },
                        label = { "${levelLetter(it)}  $it" },
                        dot = { c.levelColor(it) },
                    )
                }
                if (tags.size > 1) FilterMenu("Tags", tags, state.logTags, onChange = { state.logTags = it })
                if (layers.size > 1) FilterMenu("Layers", layers, state.logLayers, onChange = { state.logLayers = it })
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

/**
 * One multi-select filter as a dropdown: a pill that reads "Tags ▾", or "Tags · 2 ▾" once
 * something is picked, opening a menu of [values] with their counts and a Clear entry.
 *
 * The menu stays open while values are toggled, so several can be picked in one go; a tap
 * outside, or back, closes it.
 */
@Composable
private fun FilterMenu(
    title: String,
    values: List<Pair<String, Int>>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    label: (String) -> String = { it },
    dot: ((String) -> Color)? = null,
) {
    val c = Ks.colors
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box {
        Row(
            Modifier
                .height(PILL_HEIGHT)
                .clip(RoundedCornerShape(50))
                .background(if (selected.isEmpty()) c.surfaceAlt else c.accent.copy(alpha = 0.18f))
                .clickable { open = !open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KsText(
                if (selected.isEmpty()) "$title ▾" else "$title · ${selected.size} ▾",
                Ks.type.bodyStrong,
                color = if (selected.isEmpty()) c.text else c.accent,
                maxLines = 1,
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { (PILL_HEIGHT + 6.dp).roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                FilterMenuPanel(
                    title = title,
                    values = values,
                    selected = selected,
                    onChange = onChange,
                    onClear = {
                        onChange(emptySet())
                        open = false
                    },
                    label = label,
                    dot = dot,
                )
            }
        }
    }
}

/** The open menu: [values] with their counts and check marks, then Clear once anything is picked. */
@Composable
private fun FilterMenuPanel(
    title: String,
    values: List<Pair<String, Int>>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onClear: () -> Unit,
    label: (String) -> String = { it },
    dot: ((String) -> Color)? = null,
) {
    val c = Ks.colors
    Column(
        Modifier
            // As wide as its longest row, within bounds, rather than always the maximum.
            .width(IntrinsicSize.Max)
            .widthIn(min = 200.dp, max = 320.dp)
            .heightIn(max = 380.dp)
            .shadow(12.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        KsText(title.uppercase(), Ks.type.label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        values.forEach { (value, count) ->
            val on = value in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChange(if (on) selected - value else selected + value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KsText(if (on) "✓" else "", Ks.type.bodyStrong, color = c.accent, modifier = Modifier.width(20.dp))
                if (dot != null) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(dot(value)))
                    Spacer(Modifier.width(8.dp))
                }
                KsText(label(value), Ks.type.body, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                KsText(count.toString(), Ks.type.monoSmall, color = c.textMuted, maxLines = 1)
            }
        }
        if (selected.isNotEmpty()) {
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClear)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KsText("✕", Ks.type.bodyStrong, color = c.accent, modifier = Modifier.width(20.dp))
                KsText("Clear", Ks.type.bodyStrong, color = c.accent, maxLines = 1)
            }
        }
    }
}

private val PILL_HEIGHT = 32.dp

@Composable
private fun EmptyStateLogs(noLogs: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        KsText("≡", Ks.type.hero, color = Ks.colors.textFaint)
        Spacer(Modifier.height(12.dp))
        KsText(if (noLogs) "No logs yet" else "Nothing matches", Ks.type.title)
        Spacer(Modifier.height(4.dp))
        KsText(
            if (noLogs) "Entries logged with Kotlog appear here, e.g. Kotlog.d(TAG, \"message\", \"datasource\")." else "Try another filter or search.",
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
            // Tag and layer take the free space, so the time always sits at the end.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                KsText(log.tag, Ks.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                log.layer?.let { layer ->
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(c.surfaceAlt).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        KsText(layer, Ks.type.caption, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
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
