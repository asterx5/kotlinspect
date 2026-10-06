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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.callState
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.formatBytes
import io.github.asterx5.kotlinspect.internal.formatDuration
import io.github.asterx5.kotlinspect.internal.formatTime
import io.github.asterx5.kotlinspect.internal.formatTimestamp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal sealed interface InspectorRoute {
    data object Calls : InspectorRoute
    data class Detail(val recordId: String) : InspectorRoute
}

internal enum class StatusFilter(val label: String) {
    All("All"), Success("2xx"), Redirect("3xx"), ClientError("4xx"), ServerError("5xx"), Failed("Failed"), Pending("Pending");

    fun matches(r: RecordEntity): Boolean {
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
}

internal fun filterRecords(records: List<RecordEntity>, query: String, status: StatusFilter, method: String?): List<RecordEntity> {
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
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onClose() },
            entryProvider = entryProvider {
                entry<InspectorRoute.Calls> {
                    CallListScreen(runtime, listState, onOpen = { backStack.add(InspectorRoute.Detail(it)) }, onClose = onClose)
                }
                entry<InspectorRoute.Detail> { key ->
                    CallDetailScreen(runtime, key.recordId, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
            },
        )
    }
}

// region Call list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallListScreen(runtime: KotlinspectRuntime, state: CallListState, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val sessions by remember { runtime.database.sessions().observeAll() }.collectAsState(emptyList())
    val currentId by runtime.currentSessionId.collectAsState()
    val sessionId = state.viewedSessionId?.takeIf { id -> sessions.isEmpty() || sessions.any { it.id == id } } ?: currentId
    val records by remember(sessionId) {
        sessionId?.let { runtime.database.records().observeSession(it) } ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val filtered = remember(records, state.query, state.status, state.method) {
        filterRecords(records, state.query, state.status, state.method)
    }
    val methods = remember(records) { records.map { it.method }.distinct().sorted() }
    var confirmClearAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    SessionPicker(
                        sessions = sessions,
                        selectedId = sessionId,
                        currentId = currentId,
                        onSelect = { state.viewedSessionId = it },
                    )
                },
                navigationIcon = { TextButton(onClick = onClose) { Text("✕", fontSize = 18.sp) } },
                actions = {
                    ListMenu(
                        isCurrent = sessionId == currentId,
                        onMakeCurrent = { sessionId?.let(runtime::switchSession) },
                        onNewSession = {
                            state.viewedSessionId = runtime.startSession(null, emptySet(), emptyMap(), null)
                        },
                        onClearSession = { sessionId?.let(runtime::clearSession) },
                        onDeleteSession = {
                            sessionId?.let(runtime::deleteSession)
                            state.viewedSessionId = null
                        },
                        onClearAll = { confirmClearAll = true },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = { Text("Search URL, method or status") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ChipRow {
                StatusFilter.entries.forEach { f ->
                    FilterChip(selected = state.status == f, onClick = { state.status = f }, label = { Text(f.label) })
                }
            }
            if (methods.size > 1) {
                ChipRow {
                    FilterChip(selected = state.method == null, onClick = { state.method = null }, label = { Text("Any method") })
                    methods.forEach { m ->
                        FilterChip(
                            selected = state.method == m,
                            onClick = { state.method = if (state.method == m) null else m },
                            label = { Text(m) },
                        )
                    }
                }
            }
            HorizontalDivider()
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (records.isEmpty()) "No calls in this session yet" else "No calls match the filters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.id }) { record ->
                        CallRow(record, onClick = { onOpen(record.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear everything?") },
            text = { Text("Deletes every captured call and every session except the current one.") },
            confirmButton = {
                TextButton(onClick = {
                    runtime.clearAll()
                    state.viewedSessionId = null
                    confirmClearAll = false
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun SessionPicker(sessions: List<SessionEntity>, selectedId: String?, currentId: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    Box {
        Column(Modifier.clickable { open = true }.padding(vertical = 4.dp)) {
            Text(
                (selected?.name ?: "Kotlinspect") + "  ▾",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (selectedId == currentId) "Recording" else "Viewing an older session",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sessions.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.name + if (s.id == currentId) "  ●" else "", fontWeight = if (s.id == selectedId) FontWeight.Bold else null)
                            val tags = Codecs.decodeTags(s.tags)
                            Text(
                                formatTimestamp(s.createdAt) + if (tags.isNotEmpty()) "  ·  " + tags.joinToString(" ") { "#$it" } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(s.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ListMenu(
    isCurrent: Boolean,
    onMakeCurrent: () -> Unit,
    onNewSession: () -> Unit,
    onClearSession: () -> Unit,
    onDeleteSession: () -> Unit,
    onClearAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", fontSize = 20.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = buildList {
                if (!isCurrent) add("Record new calls here" to onMakeCurrent)
                add("New session" to onNewSession)
                add("Clear calls in session" to onClearSession)
                add("Delete session" to onDeleteSession)
                add("Clear everything…" to onClearAll)
            }
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { action(); open = false })
            }
        }
    }
}

internal fun statusColor(r: RecordEntity): Color {
    val code = r.statusCode
    return when {
        r.callState == CallState.Pending -> StatusColors.pending
        r.callState == CallState.Failed || r.callState == CallState.Cancelled -> StatusColors.serverError
        code == null -> StatusColors.pending
        code >= 500 -> StatusColors.serverError
        code >= 400 -> StatusColors.clientError
        code >= 300 -> StatusColors.redirect
        else -> StatusColors.success
    }
}

private fun statusLabel(r: RecordEntity): String = when (r.callState) {
    CallState.Pending -> r.statusCode?.toString() ?: "…"
    CallState.Failed -> "ERR"
    CallState.Cancelled -> "CXL"
    CallState.Complete -> r.statusCode?.toString() ?: "—"
}

@Composable
private fun CallRow(record: RecordEntity, onClick: () -> Unit) {
    val color = statusColor(record)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(38.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.method, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    record.path,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOf(
                    record.host,
                    formatTime(record.startedAt),
                    formatDuration(record.durationMs),
                    record.responseBodySize?.let(::formatBytes),
                ).filterNotNull().joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(statusLabel(record), color = color, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

// endregion

// region Detail

private enum class DetailTab { Overview, Request, Response }

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
private fun CallDetailScreen(runtime: KotlinspectRuntime, recordId: String, onBack: () -> Unit) {
    val record by remember(recordId) { runtime.database.records().observe(recordId) }.collectAsState(null)
    var tab by remember { mutableStateOf(DetailTab.Overview) }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("$label copied") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        record?.let { "${it.method} ${it.path}" } ?: "",
                        fontFamily = Mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } },
                actions = {
                    record?.let { r -> TextButton(onClick = { copy("cURL", r.toCurl()) }) { Text("Copy cURL") } }
                },
            )
        },
    ) { padding ->
        val r = record
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DetailTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.name) })
                }
            }
            if (r == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Call not found") }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    when (tab) {
                        DetailTab.Overview -> Overview(r, onCopy = ::copy)
                        DetailTab.Request -> Exchange(
                            headers = Codecs.decodeHeaders(r.requestHeaders),
                            body = r.requestBody,
                            note = r.requestBodyNote,
                            size = r.requestBodySize,
                            onCopy = ::copy,
                        )
                        DetailTab.Response -> if (r.statusCode == null && r.callState != CallState.Complete) {
                            Text(
                                if (r.callState == CallState.Pending) "Waiting for the response…" else (r.error ?: "No response"),
                                color = if (r.callState == CallState.Pending) Color.Unspecified else MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Exchange(
                                headers = Codecs.decodeHeaders(r.responseHeaders),
                                body = r.responseBody,
                                note = r.responseBodyNote,
                                size = r.responseBodySize,
                                onCopy = ::copy,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(r: RecordEntity, onCopy: (String, String) -> Unit) {
    val color = statusColor(r)
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when (r.callState) {
                    CallState.Pending -> "In flight"
                    CallState.Complete -> "${r.statusCode ?: ""} ${r.statusText.orEmpty()}".trim()
                    CallState.Failed -> "Failed"
                    CallState.Cancelled -> "Cancelled"
                },
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionTitle("URL") { onCopy("URL", r.url) }
    SelectionContainer { Text(r.url, fontFamily = Mono, fontSize = 13.sp) }
    Spacer(Modifier.height(12.dp))
    val sendMs = r.requestSentAt?.let { it - r.startedAt }?.coerceAtLeast(0)
    val waitMs = if (r.requestSentAt != null && r.responseStartedAt != null) (r.responseStartedAt - r.requestSentAt).coerceAtLeast(0) else null
    val receiveMs = if (r.responseStartedAt != null && r.completedAt != null) (r.completedAt - r.responseStartedAt).coerceAtLeast(0) else null
    KeyValues(
        listOfNotNull(
            "Method" to r.method,
            "Protocol" to (r.protocol ?: "—"),
            "Started" to formatTimestamp(r.startedAt),
            "Duration" to formatDuration(r.durationMs),
            sendMs?.let { "  Send" to formatDuration(it) },
            waitMs?.let { "  Wait" to formatDuration(it) },
            receiveMs?.let { "  Receive" to formatDuration(it) },
            "Request size" to formatBytes(r.requestBodySize),
            "Response size" to formatBytes(r.responseBodySize),
            "Endpoint" to (r.endpointKey ?: "—"),
        ),
    )
}

@Composable
private fun Exchange(
    headers: List<Pair<String, String>>,
    body: String?,
    note: String?,
    size: Long?,
    onCopy: (String, String) -> Unit,
) {
    SectionTitle("Headers (${headers.size})") {
        onCopy("Headers", headers.joinToString("\n") { "${it.first}: ${it.second}" })
    }
    if (headers.isEmpty()) {
        Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        KeyValues(headers)
    }
    Spacer(Modifier.height(16.dp))
    SectionTitle("Body · ${formatBytes(size)}", onCopy = body?.let { { onCopy("Body", it) } })
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
    }
    when {
        body == null && note == null -> Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
        body != null -> BodyText(body)
    }
}

@Composable
private fun BodyText(body: String) {
    val pretty = remember(body) { Codecs.prettyJsonOrNull(body) }
    var showPretty by remember { mutableStateOf(true) }
    var showAll by remember { mutableStateOf(false) }
    val text = if (pretty != null && showPretty) pretty else body
    val limit = 40_000
    if (pretty != null) {
        Row {
            FilterChip(selected = showPretty, onClick = { showPretty = true }, label = { Text("Pretty") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = !showPretty, onClick = { showPretty = false }, label = { Text("Raw") })
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                if (showAll || text.length <= limit) text else text.take(limit),
                fontFamily = Mono,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
    if (!showAll && text.length > limit) {
        TextButton(onClick = { showAll = true }) { Text("Show all ${text.length} characters") }
    }
}

@Composable
private fun SectionTitle(text: String, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (onCopy != null) TextButton(onClick = onCopy) { Text("Copy") }
    }
}

@Composable
private fun KeyValues(pairs: List<Pair<String, String>>) {
    SelectionContainer {
        Column {
            pairs.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        k,
                        modifier = Modifier.widthIn(min = 110.dp, max = 180.dp),
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(v, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// endregion
