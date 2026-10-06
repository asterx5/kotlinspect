package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.BodyRedactor
import io.github.asterx5.kotlinspect.HeaderRedactor
import io.github.asterx5.kotlinspect.KotlinspectConfigBuilder
import io.github.asterx5.kotlinspect.RetentionPolicy
import io.github.asterx5.kotlinspect.SessionPolicy
import io.github.asterx5.kotlinspect.ToastFilter
import io.github.asterx5.kotlinspect.internal.capture.Redaction
import io.github.asterx5.kotlinspect.internal.db.KeyValueEntity
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
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
    val redaction: Redaction,
    val bodyRedactors: List<BodyRedactor>,
    val sessionRetention: Map<String, RetentionPolicy>,
    val bubbleEnabled: Boolean,
    val toastEnabled: Boolean,
    val toastFilter: ToastFilter,
    val toastDurationMillis: Long,
    val toastBatchWindowMillis: Long,
) {
    companion object {
        fun from(b: KotlinspectConfigBuilder) = ResolvedConfig(
            enabled = b.enabled,
            allowInRelease = b.allowInRelease,
            sessionPolicy = b.sessionPolicy,
            retention = b.retention,
            maxBodyBytes = b.maxBodyBytes.coerceAtLeast(0),
            desktopAppName = b.desktopAppName,
            redaction = Redaction(b.redactedHeaders.toSet(), b.redactedQueryParameters.toSet(), b.headerRedactors.toList<HeaderRedactor>()),
            bodyRedactors = b.bodyRedactors.toList(),
            sessionRetention = b.sessionRetention.toMap(),
            bubbleEnabled = b.bubble.enabled,
            toastEnabled = b.toast.enabled,
            toastFilter = b.toast.filter,
            toastDurationMillis = b.toast.durationMillis,
            toastBatchWindowMillis = b.toast.batchWindowMillis,
        )
    }
}

/** Kinds of [RecordEntity.source]. Future sources (WebSocket, GraphQL, custom) plug in here. */
internal object RecordSources {
    const val HTTP: String = "http"
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
    private val databaseFactory: () -> KotlinspectDatabase,
    val clock: () -> Long = ::getTimeMillis,
    context: CoroutineContext = Dispatchers.Default,
    private val retentionDelayMillis: Long = 1_500,
) : RecordSink {
    @Volatile
    var config: ResolvedConfig = config

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + context)
    val database: KotlinspectDatabase by lazy(databaseFactory)

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
    }

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
        recordSessions[record.id] = sessionId
        database.records().upsert(record.copy(sessionId = sessionId))
    }

    override fun update(record: RecordEntity) = enqueue { write(record) }

    override fun finish(record: RecordEntity) = enqueue {
        val stored = write(record)
        recordSessions.remove(record.id)
        _completed.tryEmit(stored)
        scheduleRetention()
    }

    private suspend fun write(record: RecordEntity): RecordEntity {
        val sessionId = recordSessions[record.id] ?: record.sessionId
        val stored = record.copy(sessionId = sessionId)
        database.records().upsert(stored)
        return stored
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

    fun clearSession(id: String) = enqueue { database.records().deleteSession(id) }

    fun deleteSession(id: String) = enqueue {
        database.records().deleteSession(id)
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
        const val DEFAULT_SESSION: String = "Default"
        private const val KEY_ACTIVE_SESSION = "activeSession"

        fun newSessionId(): String = Uuid.random().toString()
        fun newRecordId(): String = Uuid.random().toString()
    }
}
