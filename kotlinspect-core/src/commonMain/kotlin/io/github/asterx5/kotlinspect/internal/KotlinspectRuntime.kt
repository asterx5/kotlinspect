package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.BodyRedactor
import io.github.asterx5.kotlinspect.HeaderRedactor
import io.github.asterx5.kotlinspect.KotlinspectConfigBuilder
import io.github.asterx5.kotlinspect.RetentionPolicy
import io.github.asterx5.kotlinspect.SessionPolicy
import io.github.asterx5.kotlinspect.ToastFilter
import io.github.asterx5.kotlinspect.internal.capture.Redaction
import io.github.asterx5.kotlinspect.internal.db.KeyValueEntity
import io.github.asterx5.kotlinspect.internal.db.KotlinspectStore
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlog.KotlogEntry
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Immutable snapshot of [KotlinspectConfigBuilder]. */
internal data class ResolvedConfig(
    val enabled: Boolean,
    val allowInRelease: Boolean,
    val sessionPolicy: SessionPolicy,
    val retention: RetentionPolicy,
    val maxBodyBytes: Long,
    val desktopAppName: String?,
    val dedupeNestedCaptures: Boolean,
    val redaction: Redaction,
    val bodyRedactors: List<BodyRedactor>,
    val sessionRetention: Map<String, RetentionPolicy>,
    val bubbleEnabled: Boolean,
    val bubbleAutomatic: Boolean,
    val toastEnabled: Boolean,
    val toastFilter: ToastFilter,
    val toastDurationMillis: Long,
    val toastBatchWindowMillis: Long,
    val logsEnabled: Boolean,
    val maxLogs: Int,
    val showLogCount: Boolean,
) {
    companion object {
        fun from(b: KotlinspectConfigBuilder) = ResolvedConfig(
            enabled = b.enabled,
            allowInRelease = b.allowInRelease,
            sessionPolicy = b.sessionPolicy,
            retention = b.retention,
            maxBodyBytes = b.maxBodyBytes.coerceAtLeast(0),
            desktopAppName = b.desktopAppName,
            dedupeNestedCaptures = b.dedupeNestedCaptures,
            redaction = Redaction(b.redactedHeaders.toSet(), b.redactedQueryParameters.toSet(), b.headerRedactors.toList<HeaderRedactor>()),
            bodyRedactors = b.bodyRedactors.toList(),
            sessionRetention = b.sessionRetention.toMap(),
            bubbleEnabled = b.bubble.enabled,
            bubbleAutomatic = b.bubble.automatic,
            toastEnabled = b.toast.enabled,
            toastFilter = b.toast.filter,
            toastDurationMillis = b.toast.durationMillis,
            toastBatchWindowMillis = b.toast.batchWindowMillis,
            logsEnabled = b.logs.enabled,
            maxLogs = b.logs.maxCount.coerceAtLeast(0),
            showLogCount = b.bubble.showLogCount,
        )
    }
}

/** Values of [RecordEntity.source] and [RecordEntity.kind]. */
internal object RecordSources {
    /** The Ktor plugin. Stored as "http" before database version 3. */
    const val KTOR: String = "ktor"
    const val KIND_HTTP_CALL: String = "http.call"
}

/**
 * Where capture sources deliver records. Writes are applied in the order they are submitted, so
 * a source can submit a record and then any number of updates to it without awaiting.
 */
internal interface RecordSink {
    /** Stores a new record. [sessionName] picks a named session; null means the current one. */
    fun begin(record: RecordEntity, sessionName: String?)

    /** Replaces a record previously passed to [begin]. Its session is kept. */
    fun update(record: RecordEntity)

    /** Like [update], and announces the finished record to observers such as the toast. */
    fun finish(record: RecordEntity)
}

/** A producer of records, such as the Ktor plugin. Not public in v1. */
internal interface RecordSource {
    val id: String
}

internal class KotlinspectRuntime(
    config: ResolvedConfig,
    private val databaseFactory: () -> KotlinspectStore,
    val clock: () -> Long = ::getTimeMillis,
    context: CoroutineContext = Dispatchers.Default,
    private val retentionDelayMillis: Long = 1_500,
) : RecordSink {
    @Volatile
    var config: ResolvedConfig = config

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + context)
    val database: KotlinspectStore by lazy(databaseFactory)

    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _completed = MutableSharedFlow<RecordEntity>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val completed: SharedFlow<RecordEntity> = _completed.asSharedFlow()

    val bubbleVisible: MutableStateFlow<Boolean> = MutableStateFlow(config.bubbleEnabled)

    // Owned by the op loop.
    private val sessionIdsByName = mutableMapOf<String, String>()
    private val recordSessions = mutableMapOf<String, String>()
    private var retentionJob: Job? = null

    fun start() {
        scope.launch {
            for (op in ops) {
                try {
                    op()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Inspector failures must never affect the host app.
                }
            }
        }
        enqueue { initialize() }
        scope.launch { writeLogs() }
    }

    // region Logs

    private val logQueue = Channel<KotlogEntry>(Channel.UNLIMITED)

    /** Queues a Kotlog entry. Never blocks the caller; entries are written in batches. */
    fun log(entry: KotlogEntry) {
        val c = config
        if (c.enabled && c.logsEnabled) logQueue.trySend(entry)
    }

    private suspend fun writeLogs() {
        while (true) {
            val first = logQueue.receive()
            // Logs often come in bursts; gather them so they are written in one transaction.
            delay(LOG_BATCH_DELAY_MILLIS)
            val batch = mutableListOf(first)
            while (batch.size < MAX_LOG_BATCH) batch += logQueue.tryReceive().getOrNull() ?: break
            enqueue {
                val sessionId = currentOrDefault()
                val logs = database.logs()
                logs.insertAll(batch.map { it.toEntity(sessionId) })
                val max = config.maxLogs
                if (logs.countAll() > max) logs.trimTo(max)
            }
        }
    }

    private fun KotlogEntry.toEntity(sessionId: String) = LogEntity(
        sessionId = sessionId,
        timestamp = timestampMillis,
        level = level.name,
        tag = tag,
        layer = layer?.takeIf { it.isNotBlank() },
        message = if (message.length > MAX_LOG_MESSAGE) message.take(MAX_LOG_MESSAGE) + "…" else message,
        throwable = throwable,
    )

    // endregion

    fun close() {
        ops.close()
        scope.cancel()
    }

    fun enqueue(op: suspend () -> Unit) {
        ops.trySend(op)
    }

    /** Suspends until every operation submitted so far has run. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        enqueue { done.complete(Unit) }
        done.await()
    }

    // region Sink

    override fun begin(record: RecordEntity, sessionName: String?) = enqueue {
        val sessionId = if (sessionName != null) findOrCreateSession(sessionName) else currentOrDefault()
        store(record, sessionId)
    }

    /**
     * Like [begin], but [sessionRef] may be a session id or a session name. Must run on the op
     * loop (inside [enqueue]).
     */
    suspend fun beginInLoop(record: RecordEntity, sessionRef: String?) {
        val sessionId = when {
            sessionRef == null -> currentOrDefault()
            database.sessions().get(sessionRef) != null -> sessionRef
            else -> findOrCreateSession(sessionRef)
        }
        store(record, sessionId)
    }

    private suspend fun store(record: RecordEntity, sessionId: String) {
        recordSessions[record.id] = sessionId
        val stored = record.copy(sessionId = sessionId)
        database.records().upsert(stored)
        trackOuter(stored)
    }

    override fun update(record: RecordEntity) = enqueue { write(record) }

    override fun finish(record: RecordEntity) = enqueue { finishInLoop(record) }

    /** [finish] for callers already on the op loop. */
    suspend fun finishInLoop(record: RecordEntity) {
        val stored = write(record)
        recordSessions.remove(record.id)
        untrackOuter(record.id)
        _completed.tryEmit(stored)
        scheduleRetention()
    }

    /** [update] for callers already on the op loop. */
    suspend fun updateInLoop(record: RecordEntity) {
        write(record)
    }

    private suspend fun write(record: RecordEntity): RecordEntity {
        val sessionId = recordSessions[record.id] ?: record.sessionId
        val merged = mergedExtras[record.id]?.let { record.fillFrom(it) } ?: record
        val stored = merged.copy(sessionId = sessionId)
        database.records().upsert(stored)
        return stored
    }

    // endregion

    // region Nested captures

    // Two capture sources can see the same request (Ktor on an OkHttp client with the Kotlinspect
    // interceptor; Alamofire on a URLSession with the automatic capture). The outer source keeps
    // the record; the inner one becomes a shadow whose extra details are merged into it.

    private class InFlightOuter(val id: String, val source: String, val startedAt: Long) {
        var claimed = false
    }

    // Owned by the op loop.
    private val inFlightOuter = mutableMapOf<String, MutableList<InFlightOuter>>()
    private val outerKeys = mutableMapOf<String, String>()
    private val mergedExtras = mutableMapOf<String, CaptureExtras>()

    private fun trackOuter(record: RecordEntity) {
        if (record.source !in OUTER_SOURCES) return
        val key = nestedCaptureKey(record.method, record.url) ?: return
        inFlightOuter.getOrPut(key) { mutableListOf() } += InFlightOuter(record.id, record.source, record.startedAt)
        outerKeys[record.id] = key
    }

    private fun untrackOuter(recordId: String) {
        mergedExtras.remove(recordId)
        val key = outerKeys.remove(recordId) ?: return
        val list = inFlightOuter[key] ?: return
        list.removeAll { it.id == recordId }
        if (list.isEmpty()) inFlightOuter.remove(key)
    }

    /**
     * Pairs an inner capture with the oldest unclaimed in-flight outer record for the same request
     * that started no later than it. Returns that record's id, or null when the inner capture
     * should be stored on its own. Must run on the op loop.
     */
    fun claimOuter(innerSource: String, method: String, url: String, startedAt: Long): String? {
        if (!config.dedupeNestedCaptures) return null
        val outerSource = INNER_TO_OUTER[innerSource] ?: return null
        val key = nestedCaptureKey(method, url) ?: return null
        val outer = inFlightOuter[key]
            ?.filter { !it.claimed && it.source == outerSource && it.startedAt <= startedAt }
            ?.minByOrNull { it.startedAt }
            ?: return null
        outer.claimed = true
        return outer.id
    }

    /**
     * Fills a record's empty fields from [extras] without overwriting anything it already has:
     * a shadow capture's details for its outer record, or timings measured after a record ended.
     * Must run on the op loop.
     */
    suspend fun fillRecord(recordId: String, extras: CaptureExtras) {
        if (recordId in outerKeys) {
            // Still in flight: keep the details so the outer source's later snapshots retain them.
            mergedExtras[recordId] = mergedExtras[recordId]?.fillFrom(extras) ?: extras
        }
        val records = database.records()
        val current = records.get(recordId) ?: return
        records.upsert(current.fillFrom(extras))
    }

    // endregion

    // region Sessions

    private suspend fun initialize() {
        val records = database.records()
        val sessions = database.sessions()
        records.interruptPending("Interrupted: the app stopped before this call finished")
        sessions.all()
            .filter { effectiveRetention(it) == RetentionPolicy.InMemoryOnly }
            .forEach {
                records.deleteSession(it.id)
                database.logs().deleteSession(it.id)
                sessions.delete(it.id)
            }
        val id = when (val policy = config.sessionPolicy) {
            SessionPolicy.NewPerLaunch -> createSession(newSessionId(), "Launch ${formatTimestamp(clock())}")
            is SessionPolicy.Fixed -> findOrCreateSession(policy.name)
            SessionPolicy.Manual -> sessions.getValue(KEY_ACTIVE_SESSION)?.takeIf { sessions.get(it) != null }
                ?: findOrCreateSession(DEFAULT_SESSION)
        }
        setCurrent(id)
        applyRetention()
    }

    private suspend fun currentOrDefault(): String =
        _currentSessionId.value ?: findOrCreateSession(DEFAULT_SESSION).also { setCurrent(it) }

    suspend fun findOrCreateSession(name: String): String {
        sessionIdsByName[name]?.let { return it }
        val id = database.sessions().byName(name)?.id ?: createSession(newSessionId(), name)
        sessionIdsByName[name] = id
        return id
    }

    private suspend fun createSession(
        id: String,
        name: String,
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
        retention: RetentionPolicy? = null,
    ): String {
        database.sessions().upsert(
            SessionEntity(
                id = id,
                name = name,
                createdAt = clock(),
                tags = Codecs.encodeTags(tags),
                metadata = Codecs.encodeMetadata(metadata),
                retention = retention?.let(Codecs::encodeRetention),
            ),
        )
        sessionIdsByName[name] = id
        return id
    }

    private suspend fun setCurrent(id: String) {
        _currentSessionId.value = id
        database.sessions().putValue(KeyValueEntity(KEY_ACTIVE_SESSION, id))
    }

    /** Creates a session and makes it current. Returns its id immediately. */
    fun startSession(
        name: String?,
        tags: Set<String>,
        metadata: Map<String, String>,
        retention: RetentionPolicy?,
    ): String {
        val id = newSessionId()
        enqueue {
            createSession(id, name ?: "Session ${formatTimestamp(clock())}", tags, metadata, retention)
            setCurrent(id)
        }
        return id
    }

    fun switchSession(id: String) = enqueue {
        if (database.sessions().get(id) != null) setCurrent(id)
    }

    fun clearSession(id: String) = enqueue {
        database.records().deleteSession(id)
        database.logs().deleteSession(id)
    }

    fun deleteSession(id: String) = enqueue {
        database.records().deleteSession(id)
        database.logs().deleteSession(id)
        database.sessions().delete(id)
        sessionIdsByName.entries.removeAll { it.value == id }
        if (_currentSessionId.value == id) {
            val next = database.sessions().all().firstOrNull()?.id ?: createSession(newSessionId(), DEFAULT_SESSION)
            setCurrent(next)
        }
    }

    fun clearAll() = enqueue {
        val current = currentOrDefault()
        database.records().deleteAll()
        database.logs().deleteAll()
        database.sessions().deleteAllExcept(current)
        sessionIdsByName.entries.removeAll { it.value != current }
    }

    fun updateCurrentSession(addTags: Set<String> = emptySet(), putMetadata: Map<String, String> = emptyMap()) = enqueue {
        val id = currentOrDefault()
        val session = database.sessions().get(id) ?: return@enqueue
        database.sessions().upsert(
            session.copy(
                tags = Codecs.encodeTags(Codecs.decodeTags(session.tags) + addTags),
                metadata = Codecs.encodeMetadata(Codecs.decodeMetadata(session.metadata) + putMetadata),
            ),
        )
    }

    // endregion

    // region Retention

    private fun effectiveRetention(session: SessionEntity): RetentionPolicy? =
        Codecs.decodeRetention(session.retention) ?: config.sessionRetention[session.name]

    private fun scheduleRetention() {
        if (retentionJob?.isActive == true) return
        retentionJob = scope.launch {
            delay(retentionDelayMillis)
            enqueue { applyRetention() }
        }
    }

    suspend fun applyRetention() {
        val records = database.records()
        val sessions = database.sessions().all()
        val overrides = sessions.mapNotNull { s -> effectiveRetention(s)?.let { s.id to it } }.toMap()
        val now = clock()
        for ((sessionId, policy) in overrides) {
            when (policy) {
                RetentionPolicy.Forever, RetentionPolicy.InMemoryOnly -> Unit
                is RetentionPolicy.MaxAge -> records.deleteOlderThanScoped(sessionId, now - policy.maxAge.inWholeMilliseconds)
                is RetentionPolicy.MaxCount -> deleteBeyondCount(records.sizesScoped(sessionId), policy.count)
                is RetentionPolicy.MaxSize -> deleteBeyondSize(records.sizesScoped(sessionId), policy.bytes)
            }
        }
        val excluded = overrides.keys.toList()
        when (val policy = config.retention) {
            RetentionPolicy.Forever, RetentionPolicy.InMemoryOnly -> Unit
            is RetentionPolicy.MaxAge -> records.deleteOlderThanGlobal(excluded, now - policy.maxAge.inWholeMilliseconds)
            is RetentionPolicy.MaxCount -> deleteBeyondCount(records.sizesGlobal(excluded), policy.count)
            is RetentionPolicy.MaxSize -> deleteBeyondSize(records.sizesGlobal(excluded), policy.bytes)
        }
        if (config.sessionPolicy == SessionPolicy.NewPerLaunch) {
            _currentSessionId.value?.let { database.sessions().deleteEmptyExcept(it) }
        }
    }

    private suspend fun deleteBeyondCount(newestFirst: List<io.github.asterx5.kotlinspect.internal.db.RecordSize>, count: Int) {
        if (newestFirst.size <= count) return
        deleteIds(newestFirst.drop(count.coerceAtLeast(0)).map { it.id })
    }

    private suspend fun deleteBeyondSize(newestFirst: List<io.github.asterx5.kotlinspect.internal.db.RecordSize>, bytes: Long) {
        var total = 0L
        val doomed = newestFirst.filter { total += it.storedBytes; total > bytes }.map { it.id }
        deleteIds(doomed)
    }

    private suspend fun deleteIds(ids: List<String>) {
        ids.chunked(500).forEach { database.records().deleteIds(it) }
    }

    // endregion

    companion object {
        /** Sources that wrap another capture source keep the record when both see a request. */
        private val INNER_TO_OUTER = mapOf("okhttp" to RecordSources.KTOR, "urlsession" to "alamofire")
        private val OUTER_SOURCES = INNER_TO_OUTER.values.toSet()

        private const val LOG_BATCH_DELAY_MILLIS = 50L
        private const val MAX_LOG_BATCH = 500
        private const val MAX_LOG_MESSAGE = 32_000
        const val DEFAULT_SESSION: String = "Default"
        private const val KEY_ACTIVE_SESSION = "activeSession"

        fun newSessionId(): String = Uuid.random().toString()
        fun newRecordId(): String = Uuid.random().toString()
    }
}

/** Details an inner capture contributes to the outer record of the same request. */
internal data class CaptureExtras(
    val protocol: String? = null,
    val serverIpAddress: String? = null,
    val operationName: String? = null,
    val timingBlocked: Long? = null,
    val timingDns: Long? = null,
    val timingConnect: Long? = null,
    val timingSsl: Long? = null,
    val timingSend: Long? = null,
    val timingWait: Long? = null,
    val timingReceive: Long? = null,
) {
    /** Keeps this object's values and takes [other]'s only where this one has none. */
    fun fillFrom(other: CaptureExtras): CaptureExtras = CaptureExtras(
        protocol = protocol ?: other.protocol,
        serverIpAddress = serverIpAddress ?: other.serverIpAddress,
        operationName = operationName ?: other.operationName,
        timingBlocked = timingBlocked ?: other.timingBlocked,
        timingDns = timingDns ?: other.timingDns,
        timingConnect = timingConnect ?: other.timingConnect,
        timingSsl = timingSsl ?: other.timingSsl,
        timingSend = timingSend ?: other.timingSend,
        timingWait = timingWait ?: other.timingWait,
        timingReceive = timingReceive ?: other.timingReceive,
    )
}

/** Fills this record's empty fields from [extras]; values already present win. */
internal fun RecordEntity.fillFrom(extras: CaptureExtras): RecordEntity = copy(
    protocol = protocol ?: extras.protocol,
    serverIpAddress = serverIpAddress ?: extras.serverIpAddress,
    operationName = operationName ?: extras.operationName,
    timingBlocked = timingBlocked ?: extras.timingBlocked,
    timingDns = timingDns ?: extras.timingDns,
    timingConnect = timingConnect ?: extras.timingConnect,
    timingSsl = timingSsl ?: extras.timingSsl,
    timingSend = timingSend ?: extras.timingSend,
    timingWait = timingWait ?: extras.timingWait,
    timingReceive = timingReceive ?: extras.timingReceive,
)

/**
 * "GET https://host:443/path?query": the same request as seen by two capture sources maps to the
 * same key whatever each client's URL formatting (default ports, an empty path).
 */
internal fun nestedCaptureKey(method: String, url: String): String? {
    val parsed = runCatching { io.ktor.http.Url(url) }.getOrNull() ?: return null
    val query = parsed.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
    val path = parsed.encodedPath.ifEmpty { "/" }
    return "${method.uppercase()} ${parsed.protocol.name}://${parsed.host.lowercase()}:${parsed.port}$path$query"
}
