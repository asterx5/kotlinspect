package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.capture.IngestCapture

/**
 * Where a record was captured. The built-in sources are Kotlinspect's own adapters; [custom]
 * names any other one.
 */
@ExperimentalKotlinspectApi
public class CaptureSource private constructor(public val id: String) {
    override fun equals(other: Any?): Boolean = other is CaptureSource && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "CaptureSource($id)"

    public companion object {
        public val KTOR: CaptureSource = CaptureSource("ktor")
        public val OKHTTP: CaptureSource = CaptureSource("okhttp")
        public val URLSESSION: CaptureSource = CaptureSource("urlsession")
        public val ALAMOFIRE: CaptureSource = CaptureSource("alamofire")

        private val validId = Regex("^[a-z0-9-]{1,32}$")

        /** Lowercase letters, digits, '-' only; max 32 chars. Invalid ids throw at the call site. */
        public fun custom(id: String): CaptureSource {
            require(validId.matches(id)) { "CaptureSource id must be 1-32 lowercase letters, digits or '-': \"$id\"" }
            return CaptureSource(id)
        }
    }
}

/** One header as sent or received. Order and duplicates are kept. */
@ExperimentalKotlinspectApi
public class CapturedHeader(public val name: String, public val value: String)

/**
 * A request or response body as an adapter saw it.
 *
 * [bytes] holds at most [KotlinspectIngest.maxBodyBytes]; [totalSize] is the real size, or -1 when
 * unknown. When [notCapturedReason] is set (for example "streaming" or "one-shot"), [bytes] is
 * empty and the body is shown as not captured.
 */
@ExperimentalKotlinspectApi
public class CapturedBody(
    public val bytes: ByteArray,
    public val totalSize: Long,
    public val contentType: String?,
    public val notCapturedReason: String? = null,
)

@ExperimentalKotlinspectApi
public class CapturedRequest(
    public val method: String,
    public val url: String,
    public val headers: List<CapturedHeader>,
    public val body: CapturedBody?,
    public val startedAtEpochMillis: Long,
)

@ExperimentalKotlinspectApi
public class CapturedResponse(
    public val statusCode: Int,
    public val statusText: String?,
    public val headers: List<CapturedHeader>,
    /** The protocol as the client reports it, such as "h2" or "http/1.1". */
    public val httpVersion: String?,
)

/** Durations of each phase in milliseconds, following HAR. Unknown phases are null. */
@ExperimentalKotlinspectApi
public class CapturedTimings(
    public val blocked: Long? = null,
    public val dns: Long? = null,
    public val connect: Long? = null,
    public val ssl: Long? = null,
    public val send: Long? = null,
    public val wait: Long? = null,
    public val receive: Long? = null,
)

/**
 * The record of one exchange, returned by [KotlinspectIngest.begin]. Methods can be called from
 * any thread, never block and never throw. After the first of [completed], [failed] or
 * [cancelled], further calls are ignored.
 */
@ExperimentalKotlinspectApi
public interface CaptureHandle {
    public fun responseReceived(response: CapturedResponse)
    public fun completed(body: CapturedBody?, timings: CapturedTimings? = null, serverIpAddress: String? = null)
    public fun failed(error: String, timings: CapturedTimings? = null)
    public fun cancelled()
}

/**
 * Lets capture sources other than the Ktor plugin hand records to Kotlinspect. Records go through
 * the same pipeline as Ktor calls: redaction, binary detection, truncation, sessions, retention,
 * storage and the UI.
 *
 * ```
 * if (KotlinspectIngest.isEnabled) {
 *     val handle = KotlinspectIngest.begin(request, CaptureSource.custom("my-client"))
 *     handle.responseReceived(response)
 *     handle.completed(body)
 * }
 * ```
 */
@ExperimentalKotlinspectApi
public object KotlinspectIngest {
    /** False when Kotlinspect is disabled. Check it before copying bodies. */
    public val isEnabled: Boolean
        get() = Kotlinspect.isCaptureEnabled()

    /** The most body bytes worth passing in a [CapturedBody]; more are stored truncated anyway. */
    public val maxBodyBytes: Int
        get() = Kotlinspect.maxBodyBytesForIngest()

    /**
     * Starts a record. When Kotlinspect is disabled this returns a shared handle that does nothing.
     * [sessionId] is a session id (as returned by [Kotlinspect.startSession]) or a session name;
     * null records into the current session.
     */
    public fun begin(
        request: CapturedRequest,
        source: CaptureSource,
        sessionId: String? = null,
        operationName: String? = null,
    ): CaptureHandle {
        if (!isEnabled) return IngestCapture.Noop
        val runtime = Kotlinspect.runtimeOrNull() ?: return IngestCapture.Noop
        return IngestCapture.begin(runtime, request, source.id, sessionId, operationName)
    }
}
