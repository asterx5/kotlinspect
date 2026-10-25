package io.github.asterx5.kotlinspect.internal.uimodel

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.InternalKotlinspectApi
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.callState
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.formatDuration
import io.github.asterx5.kotlinspect.internal.isError
import io.github.asterx5.kotlinspect.internal.toCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// UI-agnostic pieces shared by the Compose UI and the native iOS UI.

@InternalKotlinspectApi
public enum class StatusFilter(public val label: String) {
    All("All"), Success("2xx"), Redirect("3xx"), ClientError("4xx"), ServerError("5xx"), Failed("Failed"), Pending("Live");

    public fun matches(r: RecordSummary): Boolean {
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

@InternalKotlinspectApi
public fun filterRecords(records: List<RecordSummary>, query: String, status: StatusFilter, method: String?): List<RecordSummary> {
    val q = query.trim()
    return records.filter { r ->
        status.matches(r) &&
            (method == null || r.method.equals(method, ignoreCase = true)) &&
            (q.isEmpty() || r.url.contains(q, ignoreCase = true) || r.method.contains(q, ignoreCase = true) ||
                r.statusCode?.toString()?.contains(q) == true)
    }
}

/** Order for method filters: the common ones first, then any others alphabetically. */
@InternalKotlinspectApi
public val COMMON_METHODS: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

/** The methods present in a session, in filter order. */
@InternalKotlinspectApi
public fun orderMethods(present: Collection<String>): List<String> {
    val upper = present.map { it.uppercase() }.toSet()
    return COMMON_METHODS.filter { it in upper } + (upper - COMMON_METHODS.toSet()).sorted()
}

@InternalKotlinspectApi
public data class BubbleCounts(
    public val total: Int = 0,
    public val inFlight: Int = 0,
    public val errors: Int = 0,
    public val logs: Int = 0,
)

@InternalKotlinspectApi
public data class ToastMessage(
    public val id: Long,
    public val title: String,
    public val subtitle: String?,
    public val isError: Boolean,
    public val method: String? = null,
    public val status: String? = null,
)

@InternalKotlinspectApi
public fun buildToast(id: Long, records: List<RecordEntity>): ToastMessage? {
    if (records.isEmpty()) return null
    val last = records.last()
    fun line(r: RecordEntity): String {
        val status = r.statusCode?.toString() ?: r.state.uppercase()
        return "${r.method} ${r.path} · $status · ${formatDuration(r.durationMs)}"
    }
    if (records.size == 1) {
        return ToastMessage(
            id = id,
            title = line(last),
            subtitle = null,
            isError = last.isError,
            method = last.method,
            status = last.statusCode?.toString() ?: last.state.uppercase(),
        )
    }
    val errors = records.count { it.isError }
    val title = buildString {
        append("${records.size} calls")
        if (errors > 0) append(" · $errors failed")
    }
    return ToastMessage(id, title, "Latest: ${line(last)}", errors > 0)
}

/**
 * Turns finished calls into toasts. Calls finishing within the batch window are combined into
 * one toast, and a new toast replaces the one on screen. With [sessionId], only calls recorded in
 * that session produce toasts.
 */
@InternalKotlinspectApi
public class ToastController internal constructor(private val runtime: KotlinspectRuntime, private val sessionId: String?) {
    // Single-threaded so the batch list needs no locking.
    private val scope = CoroutineScope(runtime.scope.coroutineContext + Job(runtime.scope.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1))

    private val _current = MutableStateFlow<ToastMessage?>(null)
    public val current: StateFlow<ToastMessage?> = _current.asStateFlow()

    private val pending = mutableListOf<RecordEntity>()
    private var batchJob: Job? = null
    private var hideJob: Job? = null
    private var nextId = 0L

    init {
        scope.launch {
            runtime.completed.collect { record ->
                val config = runtime.config
                if (!config.toastEnabled || !config.enabled) return@collect
                if (sessionId != null && record.sessionId != sessionId) return@collect
                if (!runCatching { config.toastFilter.shouldShow(record.toCall()) }.getOrDefault(false)) return@collect
                pending += record
                if (batchJob?.isActive != true) {
                    batchJob = scope.launch {
                        delay(config.toastBatchWindowMillis)
                        val batch = pending.toList()
                        pending.clear()
                        show(buildToast(nextId++, batch))
                    }
                }
            }
        }
    }

    private fun show(message: ToastMessage?) {
        message ?: return
        _current.value = message
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(runtime.config.toastDurationMillis)
            _current.value = null
        }
    }

    /** Stops listening. Call when the view showing these toasts goes away. */
    public fun close() {
        scope.cancel()
    }
}

// region Sources and timings

/** Display name of a record source: "Ktor", "OkHttp", "URLSession", "Alamofire", or a custom id. */
@InternalKotlinspectApi
public fun sourceLabel(source: String): String = when (source) {
    "ktor", "http" -> "Ktor"
    "okhttp" -> "OkHttp"
    "urlsession" -> "URLSession"
    "alamofire" -> "Alamofire"
    else -> source
}

/** True when a list mixes capture sources, which is when rows show a source badge. */
@InternalKotlinspectApi
public fun hasMixedSources(records: List<RecordSummary>): Boolean {
    val first = records.firstOrNull()?.source ?: return false
    return records.any { it.source != first }
}

/** The row title: the operation name when a source provides one, else the path. */
@InternalKotlinspectApi
public val RecordSummary.title: String get() = operationName?.takeIf { it.isNotBlank() } ?: path

@InternalKotlinspectApi
public enum class TimingPhaseKind(public val label: String) {
    Blocked("Blocked"), Dns("DNS"), Connect("Connect"), Ssl("TLS"), Send("Send"), Wait("Wait"), Receive("Receive"),
}

@InternalKotlinspectApi
public class TimingPhase(public val kind: TimingPhaseKind, public val millis: Long)

/**
 * The phases of a call, in order, for the timing bar. Measured phases (sources that report them)
 * win; send, wait and receive otherwise come from the record's timestamps, as for Ktor calls. TLS
 * is part of connect, so connect is shown without it and the phases add up without overlap.
 */
@InternalKotlinspectApi
public fun timingPhases(r: RecordEntity): List<TimingPhase> {
    val sentAt = r.requestSentAt
    val responseAt = r.responseStartedAt
    val completedAt = r.completedAt
    val before = listOfNotNull(r.timingBlocked, r.timingDns, r.timingConnect).sum()
    val derivedSend = sentAt?.let { (it - r.startedAt - before).coerceAtLeast(0) }
    val derivedWait = if (sentAt != null && responseAt != null) (responseAt - sentAt).coerceAtLeast(0) else null
    val derivedReceive = if (responseAt != null && completedAt != null) (completedAt - responseAt).coerceAtLeast(0) else null
    val ssl = r.timingSsl
    val connect = r.timingConnect?.let { if (ssl != null) (it - ssl).coerceAtLeast(0) else it }
    return listOfNotNull(
        r.timingBlocked?.let { TimingPhase(TimingPhaseKind.Blocked, it) },
        r.timingDns?.let { TimingPhase(TimingPhaseKind.Dns, it) },
        connect?.let { TimingPhase(TimingPhaseKind.Connect, it) },
        ssl?.let { TimingPhase(TimingPhaseKind.Ssl, it) },
        (r.timingSend ?: derivedSend)?.let { TimingPhase(TimingPhaseKind.Send, it) },
        (r.timingWait ?: derivedWait)?.let { TimingPhase(TimingPhaseKind.Wait, it) },
        (r.timingReceive ?: derivedReceive)?.let { TimingPhase(TimingPhaseKind.Receive, it) },
    )
}

// endregion

// region Logs

/**
 * Filters logs: [query] matches the message (and tag), and each set keeps only entries whose value
 * is in it. An empty set means no filter for that field.
 */
@InternalKotlinspectApi
public fun filterLogs(
    logs: List<LogEntity>,
    query: String,
    tags: Set<String>,
    layers: Set<String>,
    levels: Set<String>,
): List<LogEntity> {
    val q = query.trim()
    return logs.filter { log ->
        (tags.isEmpty() || log.tag in tags) &&
            (layers.isEmpty() || (log.layer ?: NO_LAYER) in layers) &&
            (levels.isEmpty() || log.level in levels) &&
            (q.isEmpty() || log.message.contains(q, ignoreCase = true) || log.tag.contains(q, ignoreCase = true))
    }
}

/** Filter value for entries logged without a layer. */
@InternalKotlinspectApi
public const val NO_LAYER: String = "(none)"

/** Distinct values with their counts, most frequent first: the only filter options worth showing. */
@InternalKotlinspectApi
public fun <T> presentValues(logs: List<LogEntity>, of: (LogEntity) -> T): List<Pair<T, Int>> =
    logs.groupingBy(of).eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

/** Levels present in [logs], in severity order (V, D, I, W, E, A). */
@InternalKotlinspectApi
public fun presentLevels(logs: List<LogEntity>): List<Pair<String, Int>> {
    val counts = logs.groupingBy { it.level }.eachCount()
    return io.github.asterx5.kotlog.LogLevel.entries.mapNotNull { level -> counts[level.name]?.let { level.name to it } }
}

/** "D" for "Debug": the one-letter form shown in lists. */
@InternalKotlinspectApi
public fun levelLetter(level: String): String =
    io.github.asterx5.kotlog.LogLevel.entries.firstOrNull { it.name == level }?.letter ?: level.take(1)

// endregion

// region JSON tokens

@InternalKotlinspectApi
public enum class JsonTokenKind { Key, StringValue, Number, Literal, Punctuation }

@InternalKotlinspectApi
public class JsonToken(public val start: Int, public val end: Int, public val kind: JsonTokenKind)

/** True for newline-delimited JSON (one object or array per line), which is highlighted line by line. */
@InternalKotlinspectApi
public fun looksLikeJsonLines(text: String): Boolean {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(50).toList()
    return lines.isNotEmpty() && lines.all { (it.startsWith("{") && it.endsWith("}")) || (it.startsWith("[") && it.endsWith("]")) }
}

/**
 * Tokens of one line of JSON. Pretty-printed JSON keeps every string on one line, so tokenizing
 * line by line is exact. Text between tokens (spaces) is left unstyled.
 */
@InternalKotlinspectApi
public fun jsonTokens(line: String): List<JsonToken> {
    val out = ArrayList<JsonToken>()
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
                out += JsonToken(i, end, if (isKey) JsonTokenKind.Key else JsonTokenKind.StringValue)
                i = end
            }
            ch == '-' || ch.isDigit() -> {
                var j = i + 1
                while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++
                out += JsonToken(i, j, JsonTokenKind.Number)
                i = j
            }
            line.startsWith("true", i) || line.startsWith("null", i) -> {
                out += JsonToken(i, i + 4, JsonTokenKind.Literal)
                i += 4
            }
            line.startsWith("false", i) -> {
                out += JsonToken(i, i + 5, JsonTokenKind.Literal)
                i += 5
            }
            ch in "{}[],:" -> {
                out += JsonToken(i, i + 1, JsonTokenKind.Punctuation)
                i++
            }
            else -> i++
        }
    }
    return out
}

// endregion
