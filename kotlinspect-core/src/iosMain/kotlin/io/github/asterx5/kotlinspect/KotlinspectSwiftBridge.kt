@file:OptIn(ExperimentalObjCName::class, ExperimentalForeignApi::class, ExperimentalKotlinspectApi::class)

package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.NativeInspector
import io.github.asterx5.kotlinspect.internal.Platform
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.har.HarExporter
import io.github.asterx5.kotlinspect.internal.har.HarSelection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.UIKit.UIViewController
import platform.posix.memcpy
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Duration.Companion.milliseconds

/**
 * For the Kotlinspect Swift package only: a callback-based bridge to the engine, since Swift can't
 * consume Flow or suspend functions cleanly. It only forwards to existing internals.
 *
 * Every call obeys the release gate: when Kotlinspect is disabled, observers report their constant
 * value once, [begin] returns a no-op handle and UI calls do nothing. Callbacks run on the main
 * thread.
 */
@ExperimentalKotlinspectApi
@ObjCName("KSBridge", exact = true)
public object KotlinspectSwiftBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * The prebuilt framework can't tell whether the app is a debug build; the Swift package passes
     * its own `#if DEBUG` here before anything else runs.
     */
    public fun setHostBuildIsDebug(isDebug: Boolean) {
        Platform.hostBuildIsDebug = isDebug
    }

    /** Applies the configuration. Null fields keep Kotlinspect's defaults. */
    public fun start(config: KSConfig) {
        Kotlinspect.configure {
            config.enabled?.let { enabled = it }
            allowInRelease = config.allowInRelease
            when (config.sessionPolicy) {
                "newPerLaunch" -> sessionPolicy = SessionPolicy.NewPerLaunch
                "manual" -> sessionPolicy = SessionPolicy.Manual
                "fixed" -> config.fixedSessionName?.let { sessionPolicy = SessionPolicy.Fixed(it) }
            }
            when (config.retention) {
                "forever" -> retention = RetentionPolicy.Forever
                "inMemoryOnly" -> retention = RetentionPolicy.InMemoryOnly
                "duration" -> config.retentionValue?.let { retention = RetentionPolicy.MaxAge(it.milliseconds) }
                "maxCount" -> config.retentionValue?.let { retention = RetentionPolicy.MaxCount(it.toInt()) }
                "maxBytes" -> config.retentionValue?.let { retention = RetentionPolicy.MaxSize(it) }
            }
            config.maxBodyBytes?.let { maxBodyBytes = it }
            config.redactedHeaders?.let { redactHeaders(*it.toTypedArray()) }
            bubble { automatic = config.showOverlayAutomatically }
        }
        // Starts the engine (and the automatic overlay) now rather than on the first call.
        Kotlinspect.runtimeOrNull()
    }

    public val isEnabled: Boolean get() = Kotlinspect.isEnabled

    public val maxBodyBytes: Int get() = KotlinspectIngest.maxBodyBytes

    /** Starts and activates a session. Returns its id, or "" when disabled. */
    public fun startSession(name: String, tags: List<String>, metadata: Map<String, String>): String =
        Kotlinspect.startSession(name = name, tags = tags.toSet(), metadata = metadata) ?: ""

    /** The current session's id, or "" when disabled or not started yet. */
    public fun activeSessionId(): String = Kotlinspect.runtimeOrNull()?.currentSessionId?.value ?: ""

    public fun activateSession(id: String) {
        Kotlinspect.switchSession(id)
    }

    public fun clearSession(id: String) {
        Kotlinspect.clearSession(id)
    }

    public fun observeCallCount(sessionId: String?, onChange: (Int) -> Unit): KSCancellable =
        observe(countsFlow(sessionId).map { it.first }, 0, onChange)

    public fun observeInFlightCount(sessionId: String?, onChange: (Int) -> Unit): KSCancellable =
        observe(countsFlow(sessionId).map { it.second }, 0, onChange)

    public fun observeLatest(sessionId: String?, onChange: (KSCallSummary?) -> Unit): KSCancellable {
        val runtime = Kotlinspect.runtimeOrNull() ?: return constant(null, onChange)
        val flow = sessionFlow(sessionId).flatMapLatest { id ->
            if (id == null) flowOf(null) else runtime.database.records().observeLatestSummary(id).map { it?.toSwift() }
        }
        return observe(flow, null, onChange)
    }

    public fun observeSessions(onChange: (List<KSSession>) -> Unit): KSCancellable {
        val runtime = Kotlinspect.runtimeOrNull() ?: return constant(emptyList(), onChange)
        val flow = combine(runtime.database.sessions().observeAll(), runtime.database.records().observeSessionCounts()) { sessions, counts ->
            val byId = counts.associate { it.sessionId to it.calls }
            sessions.map { s ->
                KSSession(
                    id = s.id,
                    name = s.name,
                    tags = io.github.asterx5.kotlinspect.internal.Codecs.decodeTags(s.tags).toList(),
                    metadata = io.github.asterx5.kotlinspect.internal.Codecs.decodeMetadata(s.metadata),
                    createdAtEpochMillis = s.createdAt,
                    callCount = byId[s.id] ?: 0,
                )
            }
        }
        return observe(flow, emptyList(), onChange)
    }

    /**
     * Starts a record (see [KotlinspectIngest.begin]). [bodyTotalSize] is -1 when unknown;
     * [source] is a [CaptureSource] id such as "urlsession" or "alamofire".
     */
    public fun begin(
        method: String,
        url: String,
        headerNames: List<String>,
        headerValues: List<String>,
        body: NSData?,
        bodyTotalSize: Long,
        bodyContentType: String?,
        bodyNotCapturedReason: String?,
        startedAtEpochMillis: Long,
        source: String,
        sessionId: String?,
    ): KSCaptureHandle {
        if (!KotlinspectIngest.isEnabled) return KSCaptureHandle.Noop
        val captureSource = runCatching { CaptureSource.custom(source) }.getOrNull() ?: return KSCaptureHandle.Noop
        val handle = KotlinspectIngest.begin(
            CapturedRequest(
                method = method,
                url = url,
                headers = headers(headerNames, headerValues),
                body = body(body, bodyTotalSize, bodyContentType, bodyNotCapturedReason),
                startedAtEpochMillis = startedAtEpochMillis,
            ),
            captureSource,
            sessionId = sessionId?.takeIf { it.isNotEmpty() },
        )
        return KSCaptureHandle(handle)
    }

    public fun showOverlay() {
        Kotlinspect.setBubbleVisible(true)
    }

    public fun hideOverlay() {
        Kotlinspect.setBubbleVisible(false)
    }

    public fun openInspector(sessionId: String?) {
        Kotlinspect.openInspector(sessionId?.takeIf { it.isNotEmpty() })
    }

    /**
     * The native inspector as a view controller to embed (SwiftUI's KotlinspectInspectorView), or
     * null when disabled. Call [KSInspector.dispose] when it goes away.
     */
    public fun makeInspector(sessionId: String?, onClose: () -> Unit): KSInspector? {
        val runtime = Kotlinspect.runtimeOrNull() ?: return null
        if (!NativeInspector.isSupported()) return null
        return KSInspector(NativeInspector(runtime, sessionId?.takeIf { it.isNotEmpty() }, onClose))
    }

    /** Exports a session (the current one when null) as HAR; completion gets (path, null) or (null, error). */
    public fun exportHar(
        sessionId: String?,
        includeBodies: Boolean,
        includeFailed: Boolean,
        includePending: Boolean,
        completion: (String?, String?) -> Unit,
    ) {
        scope.launch {
            val runtime = Kotlinspect.runtimeOrNull()
            val id = sessionId?.takeIf { it.isNotEmpty() } ?: runtime?.currentSessionId?.value ?: ""
            val result = HarExporter.export(
                runtime,
                HarSelection.Session(id),
                HarExportOptions(includeBodies = includeBodies, includeFailed = includeFailed, includePending = includePending),
            )
            when (result) {
                is HarExportResult.Success -> completion(result.filePath, null)
                is HarExportResult.Failure -> completion(null, result.reason)
            }
        }
    }

    // region Helpers

    private fun sessionFlow(sessionId: String?): Flow<String?> {
        val runtime = Kotlinspect.runtimeOrNull() ?: return flowOf(null)
        val id = sessionId?.takeIf { it.isNotEmpty() }
        return if (id != null) flowOf(id) else runtime.currentSessionId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun countsFlow(sessionId: String?): Flow<Pair<Int, Int>> {
        val runtime = Kotlinspect.runtimeOrNull() ?: return flowOf(0 to 0)
        return sessionFlow(sessionId).flatMapLatest { id ->
            if (id == null) flowOf(0 to 0) else runtime.database.records().observeCounts(id).map { it.total to it.inFlight }
        }
    }

    private fun <T> observe(flow: Flow<T>, initial: T, onChange: (T) -> Unit): KSCancellable {
        if (!Kotlinspect.isEnabled) return constant(initial, onChange)
        val job = scope.launch { flow.distinctUntilChanged().collect { onChange(it) } }
        return KSCancellable(job)
    }

    private fun <T> constant(value: T, onChange: (T) -> Unit): KSCancellable {
        val job = scope.launch { onChange(value) }
        return KSCancellable(job)
    }

    internal fun headers(names: List<String>, values: List<String>): List<CapturedHeader> =
        names.indices.mapNotNull { i -> values.getOrNull(i)?.let { CapturedHeader(names[i], it) } }

    internal fun body(data: NSData?, totalSize: Long, contentType: String?, notCapturedReason: String?): CapturedBody? {
        if (data == null && notCapturedReason == null && totalSize <= 0) return null
        return CapturedBody(data?.toByteArray() ?: ByteArray(0), totalSize, contentType, notCapturedReason)
    }

    private fun RecordSummary.toSwift() = KSCallSummary(
        method = method,
        url = url,
        statusCode = statusCode,
        durationMilliseconds = durationMs,
        failed = state == CallState.Failed.name || state == CallState.Cancelled.name,
        source = source,
        operationName = operationName,
    )

    // endregion
}

/** An embedded native inspector. */
@ExperimentalKotlinspectApi
@ObjCName("KSInspector", exact = true)
public class KSInspector internal constructor(private val inspector: NativeInspector) {
    public val viewController: UIViewController get() = inspector.root

    public fun dispose() {
        inspector.dispose()
    }
}

/** Flat, Objective-C friendly configuration for [KotlinspectSwiftBridge.start]. */
@ExperimentalKotlinspectApi
@ObjCName("KSConfig", exact = true)
public class KSConfig(
    public val enabled: Boolean?,
    public val allowInRelease: Boolean,
    /** "newPerLaunch", "fixed" (with [fixedSessionName]), "manual", or null for the default. */
    public val sessionPolicy: String?,
    public val fixedSessionName: String?,
    /** "forever", "duration" (ms), "maxCount", "maxBytes", "inMemoryOnly", or null for the default. */
    public val retention: String?,
    public val retentionValue: Long?,
    public val maxBodyBytes: Long?,
    public val redactedHeaders: List<String>?,
    public val showOverlayAutomatically: Boolean,
)

/** Stops an observer started through [KotlinspectSwiftBridge]. */
@ExperimentalKotlinspectApi
@ObjCName("KSCancellable", exact = true)
public class KSCancellable internal constructor(private val job: Job) {
    public fun cancel() {
        job.cancel()
    }
}

@ExperimentalKotlinspectApi
@ObjCName("KSCallSummary", exact = true)
public class KSCallSummary(
    public val method: String,
    public val url: String,
    public val statusCode: Int?,
    public val durationMilliseconds: Long?,
    public val failed: Boolean,
    public val source: String,
    public val operationName: String?,
)

@ExperimentalKotlinspectApi
@ObjCName("KSSession", exact = true)
public class KSSession(
    public val id: String,
    public val name: String,
    public val tags: List<String>,
    public val metadata: Map<String, String>,
    public val createdAtEpochMillis: Long,
    public val callCount: Int,
)

/** [CaptureHandle] with Objective-C friendly parameters. Timings are milliseconds, null when unknown. */
@ExperimentalKotlinspectApi
@ObjCName("KSCaptureHandle", exact = true)
public class KSCaptureHandle internal constructor(private val handle: CaptureHandle?) {
    public fun responseReceived(statusCode: Int, statusText: String?, headerNames: List<String>, headerValues: List<String>, httpVersion: String?) {
        handle?.responseReceived(CapturedResponse(statusCode, statusText, KotlinspectSwiftBridge.headers(headerNames, headerValues), httpVersion))
    }

    public fun completed(
        body: NSData?,
        bodyTotalSize: Long,
        bodyContentType: String?,
        bodyNotCapturedReason: String?,
        blocked: Long?,
        dns: Long?,
        connect: Long?,
        ssl: Long?,
        send: Long?,
        wait: Long?,
        receive: Long?,
        serverIpAddress: String?,
    ) {
        handle?.completed(
            KotlinspectSwiftBridge.body(body, bodyTotalSize, bodyContentType, bodyNotCapturedReason),
            CapturedTimings(blocked, dns, connect, ssl, send, wait, receive),
            serverIpAddress,
        )
    }

    public fun failed(error: String, blocked: Long?, dns: Long?, connect: Long?, ssl: Long?, send: Long?, wait: Long?, receive: Long?) {
        handle?.failed(error, CapturedTimings(blocked, dns, connect, ssl, send, wait, receive))
    }

    public fun cancelled() {
        handle?.cancelled()
    }

    internal companion object {
        val Noop = KSCaptureHandle(null)
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
