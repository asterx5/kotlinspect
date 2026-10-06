package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.Platform
import io.github.asterx5.kotlinspect.internal.ResolvedConfig
import io.github.asterx5.kotlinspect.internal.createDatabase
import io.github.asterx5.kotlinspect.internal.toCall
import io.github.asterx5.kotlinspect.internal.toSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Entry point for configuring Kotlinspect, managing sessions and observing captured calls.
 *
 * Nothing needs to be called for the defaults: install [KotlinspectPlugin] in your `HttpClient`
 * and calls appear in the bubble. Call [configure] early (for example in `Application.onCreate`,
 * `main`, or your iOS app init) to change the defaults.
 *
 * Every function is safe to call when Kotlinspect is disabled: mutations do nothing, flows emit
 * empty values, and no database file is created.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
public object Kotlinspect {
    private val builder = KotlinspectConfigBuilder()
    private val runtimeRef = AtomicReference<KotlinspectRuntime?>(null)

    /**
     * Changes the configuration. Session policy and the global in-memory choice take effect
     * only before the first call is captured; everything else applies immediately.
     */
    public fun configure(block: KotlinspectConfigBuilder.() -> Unit) {
        builder.block()
        val resolved = ResolvedConfig.from(builder)
        runtimeRef.load()?.let { runtime ->
            runtime.config = resolved
            runtime.bubbleVisible.value = resolved.bubbleEnabled && resolved.enabled
        }
    }

    /** True when calls are being captured: enabled in config, and a debug build or [KotlinspectConfigBuilder.allowInRelease]. */
    public val isEnabled: Boolean
        get() {
            val config = currentConfig()
            return config.enabled && Platform.isReady && (Platform.isDebugBuild || config.allowInRelease)
        }

    // region Sessions

    /** The session new calls are recorded in. */
    public val currentSession: Flow<KotlinspectSession?>
        get() = runtimeFlow { rt ->
            rt.currentSessionId.flatMapLatest { id ->
                if (id == null) flowOf(null) else rt.database.sessions().observe(id).map { it?.toSession() }
            }
        }.withDefault(null)

    /** All stored sessions, newest first. */
    public val sessions: Flow<List<KotlinspectSession>>
        get() = runtimeFlow { rt ->
            rt.database.sessions().observeAll().map { list -> list.map { it.toSession() } }
        }.withDefault(emptyList())

    /**
     * Starts a new session and makes it current. Returns its id, or null when disabled.
     * [retention] overrides the global retention for this session.
     */
    public fun startSession(
        name: String? = null,
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
        retention: RetentionPolicy? = null,
    ): String? = runtimeOrNull()?.startSession(name, tags, metadata, retention)

    /** Makes the session with [sessionId] current. */
    public fun switchSession(sessionId: String) {
        runtimeOrNull()?.switchSession(sessionId)
    }

    /** Deletes the calls in a session, keeping the session. */
    public fun clearSession(sessionId: String) {
        runtimeOrNull()?.clearSession(sessionId)
    }

    /** Deletes a session and its calls. */
    public fun deleteSession(sessionId: String) {
        runtimeOrNull()?.deleteSession(sessionId)
    }

    /** Deletes every call and every session except the current one. */
    public fun clearAll() {
        runtimeOrNull()?.clearAll()
    }

    /** Adds tags to the current session. */
    public fun addSessionTags(vararg tags: String) {
        runtimeOrNull()?.updateCurrentSession(addTags = tags.toSet())
    }

    /** Adds or replaces key-value metadata on the current session. */
    public fun putSessionMetadata(key: String, value: String) {
        runtimeOrNull()?.updateCurrentSession(putMetadata = mapOf(key to value))
    }

    // endregion

    // region Observation

    /** Number of calls in the current session. */
    public val callCount: Flow<Int>
        get() = currentSessionFlow { rt, id -> rt.database.records().observeCount(id) }.withDefault(0)

    /** Number of calls in the current session that have not finished. */
    public val inFlightCount: Flow<Int>
        get() = currentSessionFlow { rt, id -> rt.database.records().observeInFlight(id) }.withDefault(0)

    /** Number of failed calls and 4xx/5xx responses in the current session. */
    public val errorCount: Flow<Int>
        get() = currentSessionFlow { rt, id -> rt.database.records().observeErrorCount(id) }.withDefault(0)

    /** The most recently started call in the current session, updated as it progresses. */
    public val latestCall: Flow<KotlinspectCall?>
        get() = currentSessionFlow { rt, id -> rt.database.records().observeLatest(id).map { it?.toCall() } }
            .withDefault(null)

    /** Calls in the current session, newest first. */
    public val calls: Flow<List<KotlinspectCall>>
        get() = currentSessionFlow { rt, id ->
            rt.database.records().observeSession(id).map { list -> list.map { it.toCall() } }
        }.withDefault(emptyList())

    /** Calls in the session with [sessionId], newest first. */
    public fun calls(sessionId: String): Flow<List<KotlinspectCall>> =
        runtimeFlow { rt -> rt.database.records().observeSession(sessionId).map { list -> list.map { it.toCall() } } }
            .withDefault(emptyList())

    // endregion

    // region UI

    /** Opens the inspector in its own Activity, view controller or window. */
    public fun openInspector() {
        runtimeOrNull()?.let(Platform::openInspector)
    }

    /** Shows or hides the floating bubble at runtime. */
    public fun setBubbleVisible(visible: Boolean) {
        runtimeOrNull()?.bubbleVisible?.value = visible
    }

    // endregion

    // region Internals

    private fun currentConfig(): ResolvedConfig = runtimeRef.load()?.config ?: ResolvedConfig.from(builder)

    /** The running runtime, started on first use. Null when disabled. */
    internal fun runtimeOrNull(): KotlinspectRuntime? {
        runtimeRef.load()?.let { return if (it.config.enabled) it else null }
        if (!isEnabled) return null
        val config = ResolvedConfig.from(builder)
        val created = KotlinspectRuntime(config, databaseFactory = { Platform.createDatabase(config) })
        return if (runtimeRef.compareAndSet(null, created)) {
            created.start()
            Platform.onRuntimeStarted(created)
            created
        } else {
            created.close()
            runtimeRef.load()
        }
    }

    /** Test hook: replaces the runtime. */
    internal fun installRuntime(runtime: KotlinspectRuntime?) {
        runtimeRef.exchange(runtime)?.close()
    }

    private fun <T> runtimeFlow(block: (KotlinspectRuntime) -> Flow<T>): Flow<T?> = flow {
        val rt = runtimeOrNull()
        if (rt == null) emit(null) else emitAll(block(rt))
    }

    private fun <T> currentSessionFlow(block: (KotlinspectRuntime, String) -> Flow<T>): Flow<T?> = flow {
        val rt = runtimeOrNull()
        if (rt == null) {
            emit(null)
        } else {
            emitAll(rt.currentSessionId.flatMapLatest { id -> if (id == null) flowOf(null) else block(rt, id) })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Flow<T?>.withDefault(default: T): Flow<T> = map { it ?: default }.distinctUntilChanged() as Flow<T>

    // endregion
}
