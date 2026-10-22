package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.InternalKotlinspectApi
import io.github.asterx5.kotlinspect.internal.db.CallCounts
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.db.SessionCount
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.uimodel.BubbleCounts
import io.github.asterx5.kotlinspect.internal.uimodel.ToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * What Kotlinspect's UI modules need from the runtime, and nothing more: observation of sessions
 * and calls, and the session actions the inspector offers.
 */
@InternalKotlinspectApi
@OptIn(ExperimentalCoroutinesApi::class)
public class KotlinspectHost internal constructor(internal val runtime: KotlinspectRuntime) {
    public val scope: CoroutineScope get() = runtime.scope
    public val currentSessionId: StateFlow<String?> get() = runtime.currentSessionId
    public val bubbleVisible: StateFlow<Boolean> get() = runtime.bubbleVisible
    public val isActive: Boolean get() = runtime.config.enabled

    /** Whether the automatic overlay should show right now (it hides while an embedded one is placed). */
    public val showAutomaticOverlay: Boolean
        get() = runtime.config.enabled && runtime.config.bubbleEnabled && runtime.config.bubbleAutomatic &&
            runtime.bubbleVisible.value && embeddedOverlays.value == 0

    private val _embeddedOverlays = MutableStateFlow(0)

    /** Number of overlays placed in the app's own UI. While above zero, the automatic overlay hides. */
    public val embeddedOverlays: StateFlow<Int> = _embeddedOverlays.asStateFlow()

    /** Registers an overlay placed in the app's UI. Call the returned function when it goes away. */
    public fun attachEmbeddedOverlay(): () -> Unit {
        _embeddedOverlays.update { it + 1 }
        var detached = false
        return {
            if (!detached) {
                detached = true
                _embeddedOverlays.update { it - 1 }
            }
        }
    }

    public fun sessions(): Flow<List<SessionEntity>> = runtime.database.sessions().observeAll()
    public fun sessionCounts(): Flow<List<SessionCount>> = runtime.database.records().observeSessionCounts()
    public fun summaries(sessionId: String): Flow<List<RecordSummary>> = runtime.database.records().observeSummaries(sessionId)
    public fun record(recordId: String): Flow<RecordEntity?> = runtime.database.records().observe(recordId)
    public fun logs(sessionId: String): Flow<List<LogEntity>> = runtime.database.logs().observeSession(sessionId)
    public fun logCount(sessionId: String): Flow<Int> = runtime.database.logs().observeCount(sessionId)

    /** Whether the bubble shows the log count badge. */
    public val showLogCount: Boolean get() = runtime.config.showLogCount

    /** Counts for [sessionId], or for whichever session is current when null. */
    public fun bubbleCounts(sessionId: String? = null): Flow<BubbleCounts> =
        (if (sessionId != null) flowOf(sessionId) else runtime.currentSessionId).flatMapLatest { id ->
            if (id == null) {
                flowOf(BubbleCounts())
            } else {
                combine(runtime.database.records().observeCounts(id), runtime.database.logs().observeCount(id)) { c: CallCounts, logs ->
                    BubbleCounts(c.total, c.inFlight, c.errors, logs)
                }
            }
        }.distinctUntilChanged().conflate()

    /** A toast stream for one overlay. Close it when the overlay goes away. */
    public fun toasts(sessionId: String? = null): ToastController = ToastController(runtime, sessionId)

    public fun startSession(): String = runtime.startSession(null, emptySet(), emptyMap(), null)
    public fun switchSession(sessionId: String) { runtime.switchSession(sessionId) }
    public fun clearSession(sessionId: String) { runtime.clearSession(sessionId) }
    public fun deleteSession(sessionId: String) { runtime.deleteSession(sessionId) }
    public fun clearAll() { runtime.clearAll() }
}

/**
 * A UI implementation discovered at runtime, so adding the Compose artifact is enough to get its
 * bubble and inspector. On Android and desktop it is found with java.util.ServiceLoader.
 */
@InternalKotlinspectApi
public interface KotlinspectUiProvider {
    /** Called once the platform is ready (on Android, when the application context is known). */
    public fun install() {}

    /** Called once, when capture starts. Shows the automatic bubble and toast. */
    public fun onStarted(host: KotlinspectHost)

    /** Opens the inspector, showing [sessionId] or the current session. */
    public fun openInspector(host: KotlinspectHost, sessionId: String?)
}
