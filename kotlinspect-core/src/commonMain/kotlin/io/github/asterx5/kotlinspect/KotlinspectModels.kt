package io.github.asterx5.kotlinspect

/** Lifecycle of a captured call. */
public enum class CallState {
    /** Sent, or receiving its body. */
    Pending,

    /** Finished with a response, whatever its status code. */
    Complete,

    /** Failed before a full response was received. */
    Failed,

    /** Cancelled by the caller, or interrupted by the app being killed. */
    Cancelled,
}

/** A captured HTTP call, as exposed through the public observation API. */
public class KotlinspectCall internal constructor(
    public val id: String,
    public val sessionId: String,
    public val state: CallState,
    public val method: String,
    public val url: String,
    public val host: String,
    public val path: String,
    /** Null until response headers arrive, and for calls that failed before that. */
    public val statusCode: Int?,
    public val startedAtMillis: Long,
    /** Null while pending. */
    public val durationMillis: Long?,
    public val requestBodySize: Long?,
    public val responseBodySize: Long?,
    public val errorMessage: String?,
) {
    /** True for failed calls and responses with a 4xx or 5xx status. */
    public val isError: Boolean
        get() = state == CallState.Failed || (statusCode ?: 0) >= 400

    override fun toString(): String =
        "KotlinspectCall($method $url, state=$state, status=$statusCode, duration=${durationMillis}ms)"
}

/** A group of captured calls. */
public class KotlinspectSession internal constructor(
    public val id: String,
    public val name: String,
    public val createdAtMillis: Long,
    public val tags: Set<String>,
    public val metadata: Map<String, String>,
) {
    override fun toString(): String = "KotlinspectSession($name, id=$id)"
}
