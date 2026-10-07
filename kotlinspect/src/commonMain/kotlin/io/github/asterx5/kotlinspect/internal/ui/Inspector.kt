package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.callState
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.formatBytes
import io.github.asterx5.kotlinspect.internal.formatDuration
import io.github.asterx5.kotlinspect.internal.formatTime
import io.github.asterx5.kotlinspect.internal.formatTimestamp
import io.github.asterx5.kotlinspect.internal.isError
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.math.ln

internal sealed interface InspectorRoute {
    data object Calls : InspectorRoute
    data class Detail(val recordId: String) : InspectorRoute
}

internal enum class StatusFilter(val label: String) {
    All("All"), Success("2xx"), Redirect("3xx"), ClientError("4xx"), ServerError("5xx"), Failed("Failed"), Pending("Live");

    fun matches(r: RecordSummary): Boolean {
        val code = r.statusCode ?: 0
        return when (this) {
            All -> true
            Success -> code in 200..299
            Redirect -> code in 300..399
            ClientError -> code in 400..499
            ServerError -> code >= 500
            Failed -> r.callState == CallState.Failed || r.callState == CallState.Cancelled
            Pending -> r.callState == CallState.Pending
        }
    }
}

/** List state hoisted above navigation so it survives opening a call. */
@Stable
internal class CallListState {
    var viewedSessionId: String? by mutableStateOf(null)
    var query: String by mutableStateOf("")
    var status: StatusFilter by mutableStateOf(StatusFilter.All)
    var method: String? by mutableStateOf(null)
    var sessionsOpen: Boolean by mutableStateOf(false)
}

/** Method filters shown in both inspectors, before any other methods seen in the session. */
internal val COMMON_METHODS: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

internal fun filterRecords(records: List<RecordSummary>, query: String, status: StatusFilter, method: String?): List<RecordSummary> {
    val q = query.trim()
    return records.filter { r ->
        status.matches(r) &&
            (method == null || r.method.equals(method, ignoreCase = true)) &&
            (q.isEmpty() || r.url.contains(q, ignoreCase = true) || r.method.contains(q, ignoreCase = true) ||
                r.statusCode?.toString()?.contains(q) == true)
    }
}

@Composable
internal fun InspectorApp(runtime: KotlinspectRuntime, onClose: () -> Unit) {
    KotlinspectTheme {
        val backStack = remember { mutableStateListOf<InspectorRoute>(InspectorRoute.Calls) }
        val listState = remember { CallListState() }
        val copied = remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize().background(Ks.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            NavDisplay(
                backStack = backStack,
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onClose() },
                entryProvider = entryProvider {
                    entry<InspectorRoute.Calls> {
                        CallListScreen(runtime, listState, onOpen = { backStack.add(InspectorRoute.Detail(it)) }, onClose = onClose)
                    }
                    entry<InspectorRoute.Detail> { key ->
                        CallDetailScreen(
                            runtime,
                            key.recordId,
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onCopied = { copied.value = it },
                        )
                    }
                },
            )
            CopiedPill(copied.value, Modifier.align(Alignment.BottomCenter)) { copied.value = null }
        }
    }
}

@Composable
private fun CopiedPill(label: String?, modifier: Modifier, onDone: () -> Unit) {
    LaunchedEffect(label) {
        if (label != null) {
            delay(1_400)
            onDone()
        }
    }
    AnimatedVisibility(label != null, modifier.padding(bottom = 24.dp), enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
        val c = Ks.colors
        Box(Modifier.clip(RoundedCornerShape(50)).background(c.text).padding(horizontal = 16.dp, vertical = 9.dp)) {
            KsText("${label ?: ""} copied", Ks.type.bodyStrong, color = c.bg)
        }
    }
}

// region Call list

@Composable
private fun CallListScreen(runtime: KotlinspectRuntime, state: CallListState, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val c = Ks.colors
    val sessions by remember { runtime.database.sessions().observeAll() }.collectAsState(emptyList())
    val currentId by runtime.currentSessionId.collectAsState()
    val sessionId = state.viewedSessionId?.takeIf { id -> sessions.isEmpty() || sessions.any { it.id == id } } ?: currentId
    val session = sessions.firstOrNull { it.id == sessionId }
    val records by remember(sessionId) {
        sessionId?.let { runtime.database.records().observeSummaries(it) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())

    val filtered = remember(records, state.query, state.status, state.method) {
        filterRecords(records, state.query, state.status, state.method)
    }
    val statusCounts = remember(records) { StatusFilter.entries.associateWith { f -> records.count(f::matches) } }
    val methodCounts = remember(records) { records.groupingBy { it.method.uppercase() }.eachCount() }
    val methods = remember(methodCounts) { COMMON_METHODS + (methodCounts.keys - COMMON_METHODS.toSet()).sorted() }
    val maxDuration = remember(records) { records.maxOfOrNull { it.durationMs ?: 0 } ?: 0 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header: session switcher and live stats
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Action("Close", onClick = onClose)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { state.sessionsOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sessionId == currentId) {
                            Dot(c.error, 7.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        KsText(session?.name ?: "Kotlinspect", Ks.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        KsText("  ▾", Ks.type.caption)
                    }
                    KsText(
                        if (sessionId == currentId) "Recording · tap to switch session" else "Past session · tap to switch",
                        Ks.type.caption,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(56.dp))
            }
            StatsStrip(records, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            SearchField(state.query, { state.query = it }, "Filter by URL, method or status", Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusFilter.entries.forEach { f ->
                    val count = statusCounts[f] ?: 0
                    if (f == StatusFilter.All || count > 0 || state.status == f) {
                        Chip(f.label, state.status == f, count, tint = filterTint(f, c)) { state.status = f }
                    }
                }
            }
            // Method filters are always shown so they are easy to find.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("Any method", state.method == null) { state.method = null }
                methods.forEach { m ->
                    Chip(m, state.method == m, methodCounts[m] ?: 0, tint = c.methodColor(m)) {
                        state.method = if (state.method == m) null else m
                    }
                }
            }
            Hairline()
            if (filtered.isEmpty()) {
                EmptyState(if (records.isEmpty()) "Waiting for traffic" else "Nothing matches", if (records.isEmpty()) "Calls made through KotlinspectPlugin appear here as they happen." else "Try another filter or search.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(filtered, key = { it.id }, contentType = { "call" }) { record ->
                        CallRow(record, maxDuration) { onOpen(record.id) }
                    }
                }
            }
        }
        SessionPanel(runtime, state, sessions, sessionId, currentId)
    }
}

private fun filterTint(f: StatusFilter, c: KsColors): Color = when (f) {
    StatusFilter.All -> c.accent
    StatusFilter.Success -> c.ok
    StatusFilter.Redirect -> c.redirect
    StatusFilter.ClientError -> c.warn
    StatusFilter.ServerError, StatusFilter.Failed -> c.error
    StatusFilter.Pending -> c.pending
}

@Composable
private fun StatsStrip(records: List<RecordSummary>, modifier: Modifier) {
    val c = Ks.colors
    val stats = remember(records) {
        val done = records.mapNotNull { it.durationMs }
        listOf(
            "Calls" to records.size.toString(),
            "Errors" to records.count { it.isError }.toString(),
            "Data" to formatBytes(records.sumOf { it.responseBodySize ?: 0 }),
            "Avg" to if (done.isEmpty()) "—" else formatDuration(done.sum() / done.size),
        )
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(vertical = 10.dp)) {
        stats.forEachIndexed { i, (label, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                KsText(value, Ks.type.monoStrong, color = if (label == "Errors" && value != "0") c.error else c.text, maxLines = 1)
                KsText(label, Ks.type.caption, maxLines = 1)
            }
            if (i < stats.lastIndex) Box(Modifier.width(1.dp).height(30.dp).background(c.line).align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun EmptyState(title: String, hint: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        KsText("◌", Ks.type.hero, color = Ks.colors.textFaint)
        Spacer(Modifier.height(12.dp))
        KsText(title, Ks.type.title)
        Spacer(Modifier.height(4.dp))
        KsText(hint, Ks.type.caption)
    }
}

@Composable
private fun CallRow(record: RecordSummary, maxDuration: Long, onClick: () -> Unit) {
    val c = Ks.colors
    val state = record.callState
    val color = c.statusColor(state, record.statusCode)
    val subtitle = remember(record.host, record.startedAt, record.responseBodySize) {
        listOfNotNull(record.host, formatTime(record.startedAt), record.responseBodySize?.let(::formatBytes)).joinToString("  ·  ")
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(record.method)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                KsText(record.path, Ks.type.mono, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                KsText(subtitle, Ks.type.caption, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (state == CallState.Pending) {
                    Spinner(color, 16.dp)
                } else {
                    KsText(statusLabel(record), Ks.type.monoStrong, color = color, maxLines = 1)
                }
                KsText(formatDuration(record.durationMs), Ks.type.caption, maxLines = 1)
            }
        }
        Spacer(Modifier.height(9.dp))
        // Duration relative to the slowest call, on a log scale so fast calls stay visible.
        val fraction = if (maxDuration <= 0 || record.durationMs == null) 0.02f
        else (ln(1.0 + record.durationMs) / ln(1.0 + maxDuration)).toFloat().coerceIn(0.02f, 1f)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceAlt)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color.copy(alpha = 0.8f)))
        }
    }
    Hairline(Modifier.padding(start = 16.dp))
}

private fun statusLabel(r: RecordSummary): String = when (r.callState) {
    CallState.Pending -> r.statusCode?.toString() ?: "…"
    CallState.Failed -> "ERR"
    CallState.Cancelled -> "CXL"
    CallState.Complete -> r.statusCode?.toString() ?: "—"
}

@Composable
private fun SessionPanel(
    runtime: KotlinspectRuntime,
    state: CallListState,
    sessions: List<SessionEntity>,
    viewedId: String?,
    currentId: String?,
) {
    val c = Ks.colors
    AnimatedVisibility(state.sessionsOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { state.sessionsOpen = false },
        )
    }
    AnimatedVisibility(state.sessionsOpen, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        val counts by remember { runtime.database.records().observeSessionCounts() }.collectAsState(emptyList())
        var confirmClearAll by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .background(c.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KsText("Sessions", Ks.type.title, modifier = Modifier.weight(1f))
                Action("Done") { state.sessionsOpen = false }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(sessions, key = { it.id }) { s ->
                    val selected = s.id == viewedId
                    val calls = counts.firstOrNull { it.sessionId == s.id }?.calls ?: 0
                    val tags = remember(s.tags) { Codecs.decodeTags(s.tags) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                state.viewedSessionId = s.id
                                state.sessionsOpen = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (s.id == currentId) {
                                    Dot(c.error, 7.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                KsText(s.name, Ks.type.bodyStrong, maxLines = 1)
                            }
                            KsText(
                                formatTimestamp(s.createdAt) + if (tags.isEmpty()) "" else "  ·  " + tags.joinToString(" ") { "#$it" },
                                Ks.type.caption,
                                maxLines = 1,
                            )
                        }
                        KsText(calls.toString(), Ks.type.monoStrong, color = if (selected) c.accent else c.textMuted)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("+ New", false, tint = c.accent) {
                    state.viewedSessionId = runtime.startSession(null, emptySet(), emptyMap(), null)
                    state.sessionsOpen = false
                }
                if (viewedId != null && viewedId != currentId) {
                    Chip("Record here", false) { runtime.switchSession(viewedId) }
                }
                if (viewedId != null) {
                    Chip("Clear calls", false) { runtime.clearSession(viewedId) }
                    Chip("Delete", false) {
                        runtime.deleteSession(viewedId)
                        state.viewedSessionId = null
                    }
                }
                Chip(if (confirmClearAll) "Tap again to clear all" else "Clear all", confirmClearAll, tint = c.error) {
                    if (confirmClearAll) {
                        runtime.clearAll()
                        state.viewedSessionId = null
                        state.sessionsOpen = false
                    }
                    confirmClearAll = !confirmClearAll
                }
            }
        }
    }
}

// endregion

// region Detail

private enum class DetailTab(val label: String) { Overview("Overview"), Request("Request"), Response("Response") }

@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
private fun CallDetailScreen(runtime: KotlinspectRuntime, recordId: String, onBack: () -> Unit, onCopied: (String) -> Unit) {
    val c = Ks.colors
    val record by remember(recordId) { runtime.database.records().observe(recordId) }.collectAsState(null)
    var tab by remember { mutableStateOf(DetailTab.Overview) }
    val clipboard = LocalClipboardManager.current
    val copy: (String, String) -> Unit = { label, text ->
        clipboard.setText(AnnotatedString(text))
        onCopied(label)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Action("‹ Calls", onClick = onBack)
            Spacer(Modifier.weight(1f))
            record?.let { r -> Action("Copy cURL", emphasized = true) { copy("cURL", r.toCurl()) } }
        }
        val r = record
        if (r == null) {
            EmptyState("Call not found", "It may have been cleared.")
            return
        }
        val requestHeaders = remember(r.requestHeaders) { Codecs.decodeHeaders(r.requestHeaders) }
        val responseHeaders = remember(r.responseHeaders) { Codecs.decodeHeaders(r.responseHeaders) }
        val requestBody = rememberBody(r.requestBody, active = tab == DetailTab.Request)
        val responseBody = rememberBody(r.responseBody, active = tab == DetailTab.Response)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            item(key = "hero") { Hero(r) }
            item(key = "url") { UrlBlock(r, copy) }
            stickyHeader(key = "tabs") {
                Box(Modifier.fillMaxWidth().background(c.bg).padding(vertical = 10.dp)) {
                    Segmented(DetailTab.entries.map { it.label }, tab.ordinal, { tab = DetailTab.entries[it] })
                }
            }
            when (tab) {
                DetailTab.Overview -> {
                    item(key = "timing") { TimingCard(r) }
                    item(key = "facts") { Facts(r) }
                }
                DetailTab.Request -> exchange(
                    headers = requestHeaders,
                    body = requestBody,
                    contentType = r.requestContentType,
                    note = r.requestBodyNote,
                    size = r.requestBodySize,
                    copy = copy,
                    keyPrefix = "req",
                )
                DetailTab.Response -> if (r.statusCode == null && r.callState != CallState.Complete) {
                    item(key = "noresponse") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            if (r.callState == CallState.Pending) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spinner(c.pending, 16.dp)
                                    Spacer(Modifier.width(10.dp))
                                    KsText("Waiting for the response…", Ks.type.body, color = c.textMuted)
                                }
                            } else {
                                KsText(r.error ?: "No response", Ks.type.body, color = c.error)
                            }
                        }
                    }
                } else {
                    exchange(
                        headers = responseHeaders,
                        body = responseBody,
                        contentType = r.responseContentType,
                        note = r.responseBodyNote,
                        size = r.responseBodySize,
                        copy = copy,
                        keyPrefix = "res",
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(r: RecordEntity) {
    val c = Ks.colors
    val state = r.callState
    val color = c.statusColor(state, r.statusCode)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MethodBadge(r.method)
            Spacer(Modifier.width(8.dp))
            KsText(r.host, Ks.type.caption, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            KsText(
                when (state) {
                    CallState.Pending -> r.statusCode?.toString() ?: "…"
                    CallState.Failed -> "ERR"
                    CallState.Cancelled -> "CXL"
                    CallState.Complete -> r.statusCode?.toString() ?: "—"
                },
                Ks.type.hero,
                color = color,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                KsText(
                    when (state) {
                        CallState.Pending -> "In flight"
                        CallState.Failed -> "Failed"
                        CallState.Cancelled -> "Cancelled"
                        CallState.Complete -> r.statusText?.takeIf { it.isNotBlank() } ?: "Complete"
                    },
                    Ks.type.bodyStrong,
                    color = color,
                    maxLines = 1,
                )
                KsText(r.protocol ?: "", Ks.type.caption, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                KsText(formatDuration(r.durationMs), Ks.type.monoStrong)
                KsText(formatBytes(r.responseBodySize), Ks.type.caption)
            }
        }
        r.error?.let {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.error.copy(alpha = 0.12f)).padding(12.dp)) {
                KsText(it, Ks.type.monoSmall, color = c.error)
            }
        }
    }
}

@Composable
private fun UrlBlock(r: RecordEntity, copy: (String, String) -> Unit) {
    val c = Ks.colors
    val styled = remember(r.url, c) {
        val url = runCatching { Url(r.url) }.getOrNull()
        buildAnnotatedString {
            if (url == null) {
                append(r.url)
                return@buildAnnotatedString
            }
            withStyle(SpanStyle(color = c.textFaint)) { append("${url.protocol.name}://") }
            withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(url.host) }
            withStyle(SpanStyle(color = c.text)) { append(url.encodedPath) }
            if (url.encodedQuery.isNotEmpty()) withStyle(SpanStyle(color = c.textMuted)) { append("?${url.encodedQuery}") }
        }
    }
    val params = remember(r.url) { runCatching { Url(r.url).parameters.entries().flatMap { (k, v) -> v.map { k to it } } }.getOrDefault(emptyList()) }
    SectionLabel("URL", trailing = { Action("Copy") { copy("URL", r.url) } })
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp)) {
        KsText(styled, Ks.type.monoSmall)
    }
    if (params.isNotEmpty()) {
        SectionLabel("Query · ${params.size}")
        KeyValueTable(params)
    }
}

@Composable
private fun TimingCard(r: RecordEntity) {
    val c = Ks.colors
    val send = r.requestSentAt?.let { it - r.startedAt }?.coerceAtLeast(0)
    val wait = if (r.requestSentAt != null && r.responseStartedAt != null) (r.responseStartedAt - r.requestSentAt).coerceAtLeast(0) else null
    val receive = if (r.responseStartedAt != null && r.completedAt != null) (r.completedAt - r.responseStartedAt).coerceAtLeast(0) else null
    val phases = listOfNotNull(
        send?.let { Triple("Send", it, c.redirect) },
        wait?.let { Triple("Wait", it, c.warn) },
        receive?.let { Triple("Receive", it, c.ok) },
    )
    SectionLabel("Timing · ${formatDuration(r.durationMs)}")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp)) {
        val total = phases.sumOf { it.second }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surfaceAlt)) {
            phases.forEach { (_, ms, color) ->
                if (ms > 0) Box(Modifier.weight(ms.toFloat() / total).fillMaxHeight().background(color))
            }
        }
        if (phases.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            KsText(if (r.callState == CallState.Pending) "Still running" else "No timing for this call", Ks.type.caption)
        }
        phases.forEach { (label, ms, color) ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Spacer(Modifier.width(8.dp))
                KsText(label, Ks.type.body, modifier = Modifier.weight(1f))
                KsText(formatDuration(ms), Ks.type.mono)
            }
        }
    }
}

@Composable
private fun Facts(r: RecordEntity) {
    SectionLabel("Details")
    KeyValueTable(
        listOf(
            "Started" to formatTimestamp(r.startedAt),
            "Protocol" to (r.protocol ?: "—"),
            "Request" to formatBytes(r.requestBodySize),
            "Response" to formatBytes(r.responseBodySize),
            "Endpoint" to (r.endpointKey ?: "—"),
        ),
    )
}

@Composable
private fun KeyValueTable(pairs: List<Pair<String, String>>, stacked: Boolean = false) {
    val c = Ks.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)) {
        pairs.forEachIndexed { i, (k, v) ->
            if (stacked) {
                // Header names can be long; give name and value each the full width.
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    KsText(k, Ks.type.caption, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    KsText(v, Ks.type.monoSmall)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    KsText(k, Ks.type.monoSmall, color = c.textMuted, modifier = Modifier.weight(0.38f))
                    Spacer(Modifier.width(10.dp))
                    KsText(v, Ks.type.monoSmall, modifier = Modifier.weight(0.62f))
                }
            }
            if (i < pairs.lastIndex) Hairline(Modifier.padding(start = 12.dp))
        }
    }
}

/** Pretty/raw choice and the highlighted lines for one body, computed off the main thread. */
@Stable
private class BodyState(val body: String?) {
    var pretty: Boolean by mutableStateOf(true)
    var prettyText: String? by mutableStateOf(null)
    var highlighted: HighlightedBody? by mutableStateOf(null)
    val canPrettify: Boolean get() = prettyText != null
}

@Composable
private fun rememberBody(body: String?, active: Boolean): BodyState {
    val c = Ks.colors
    val state = remember(body) { BodyState(body) }
    LaunchedEffect(state, active, state.pretty, c) {
        if (!active || body == null) return@LaunchedEffect
        val known = state.prettyText
        val wantPretty = state.pretty
        val (pretty, lines) = withContext(Dispatchers.Default) {
            val pretty = known ?: Codecs.prettyJsonOrNull(body)
            val usePretty = wantPretty && pretty != null
            pretty to highlight(if (usePretty) pretty!! else body, json = usePretty || looksLikeJsonLines(body), colors = c)
        }
        // Back on the composition's dispatcher here.
        state.prettyText = pretty
        state.highlighted = lines
    }
    return state
}

/** Headers and body as lazy items: every body line is its own item, laid out only when visible. */
private fun androidx.compose.foundation.lazy.LazyListScope.exchange(
    headers: List<Pair<String, String>>,
    body: BodyState,
    contentType: String?,
    note: String?,
    size: Long?,
    copy: (String, String) -> Unit,
    keyPrefix: String,
) {
    item(key = "$keyPrefix-headers") {
        SectionLabel("Headers · ${headers.size}", trailing = {
            if (headers.isNotEmpty()) Action("Copy") { copy("Headers", headers.joinToString("\n") { "${it.first}: ${it.second}" }) }
        })
        if (headers.isEmpty()) KsText("None", Ks.type.caption) else KeyValueTable(headers, stacked = true)
    }
    item(key = "$keyPrefix-body-header") {
        SectionLabel(
            "Body · ${formatBytes(size)}" + (contentType?.substringBefore(';')?.let { " · $it" } ?: ""),
            trailing = { body.body?.let { text -> Action("Copy") { copy("Body", text) } } },
        )
        note?.let { KsText(it, Ks.type.caption, modifier = Modifier.padding(bottom = 6.dp)) }
        when {
            body.body == null -> if (note == null) KsText("Empty", Ks.type.caption)
            body.canPrettify -> Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Pretty", body.pretty) { body.pretty = true }
                Chip("Raw", !body.pretty) { body.pretty = false }
            }
        }
    }
    if (body.body == null) return
    val lines = body.highlighted?.lines
    if (lines == null) {
        item(key = "$keyPrefix-formatting") { CodeEdge(top = true); CodeEdge(top = false, text = "Formatting…") }
        return
    }
    val gutter = lines.size.toString().length
    item(key = "$keyPrefix-code-top") { CodeEdge(top = true) }
    items(lines.size, key = { "$keyPrefix-line-$it" }, contentType = { "code-line" }) { index ->
        CodeLine(index + 1, gutter, lines[index])
    }
    item(key = "$keyPrefix-code-bottom") { CodeEdge(top = false) }
}

@Composable
private fun CodeEdge(top: Boolean, text: String? = null) {
    val c = Ks.colors
    val shape = if (top) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.codeBg).padding(horizontal = 12.dp, vertical = 5.dp)) {
        if (text != null) KsText(text, Ks.type.caption)
    }
}

@Composable
private fun CodeLine(number: Int, gutter: Int, line: AnnotatedString) {
    val c = Ks.colors
    Row(Modifier.fillMaxWidth().background(c.codeBg).padding(end = 12.dp)) {
        KsText(number.toString().padStart(gutter), Ks.type.monoSmall, color = c.textFaint, modifier = Modifier.padding(start = 10.dp, end = 10.dp))
        KsText(line, Ks.type.monoSmall)
    }
}

// endregion
