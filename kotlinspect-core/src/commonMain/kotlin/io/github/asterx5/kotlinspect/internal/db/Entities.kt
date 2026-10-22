package io.github.asterx5.kotlinspect.internal.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured event. The generic columns describe any [source]; the HTTP columns are null for
 * other kinds of records. Timing columns follow HAR: send = requestSentAt - startedAt,
 * wait = responseStartedAt - requestSentAt, receive = completedAt - responseStartedAt.
 */
@Entity(
    tableName = "records",
    indices = [Index("sessionId"), Index("startedAt"), Index("endpointKey")],
)
@io.github.asterx5.kotlinspect.InternalKotlinspectApi
public data class RecordEntity(
    @PrimaryKey public val id: String,
    public val sessionId: String,
    public val source: String,
    public val kind: String,
    public val state: String,
    public val startedAt: Long,
    public val requestSentAt: Long? = null,
    public val responseStartedAt: Long? = null,
    public val completedAt: Long? = null,
    public val durationMs: Long? = null,
    public val endpointKey: String? = null,
    public val fingerprint: String? = null,
    public val error: String? = null,
    // HTTP
    public val method: String = "",
    public val url: String = "",
    public val scheme: String = "",
    public val host: String = "",
    public val path: String = "",
    public val requestHeaders: String = "[]",
    public val requestContentType: String? = null,
    public val requestBody: String? = null,
    public val requestBodySize: Long? = null,
    public val requestBodyTruncated: Boolean = false,
    public val requestBodyNote: String? = null,
    public val statusCode: Int? = null,
    public val statusText: String? = null,
    public val protocol: String? = null,
    public val responseHeaders: String = "[]",
    public val responseContentType: String? = null,
    public val responseBody: String? = null,
    public val responseBodySize: Long? = null,
    public val responseBodyTruncated: Boolean = false,
    public val responseBodyNote: String? = null,
    /** Bytes of body text stored for this record, used by size-based retention. */
    public val storedBytes: Long = 0,
)

@Entity(tableName = "sessions", indices = [Index("name")])
@io.github.asterx5.kotlinspect.InternalKotlinspectApi
public data class SessionEntity(
    @PrimaryKey public val id: String,
    public val name: String,
    public val createdAt: Long,
    /** JSON array of strings. */
    public val tags: String = "[]",
    /** JSON object of string values. */
    public val metadata: String = "{}",
    /** Encoded [io.github.asterx5.kotlinspect.RetentionPolicy] override, or null for the global one. */
    public val retention: String? = null,
)

@Entity(tableName = "kv")
internal data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
)

internal data class RecordSize(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "storedBytes") val storedBytes: Long,
)

/** The columns the call list needs: everything except headers and bodies. */
@io.github.asterx5.kotlinspect.InternalKotlinspectApi
public data class RecordSummary(
    public val id: String,
    public val sessionId: String,
    public val state: String,
    public val startedAt: Long,
    public val durationMs: Long?,
    public val method: String,
    public val url: String,
    public val host: String,
    public val path: String,
    public val statusCode: Int?,
    public val requestBodySize: Long?,
    public val responseBodySize: Long?,
    public val error: String?,
)

/** Aggregates for one session, computed in a single query. */
@io.github.asterx5.kotlinspect.InternalKotlinspectApi
public data class CallCounts(
    public val total: Int = 0,
    public val inFlight: Int = 0,
    public val errors: Int = 0,
    public val bytes: Long = 0,
    public val avgMs: Long? = null,
)

@io.github.asterx5.kotlinspect.InternalKotlinspectApi
public data class SessionCount(
    @ColumnInfo(name = "sessionId") public val sessionId: String,
    @ColumnInfo(name = "calls") public val calls: Int,
)

/** One KtLog entry, stored in the session that was current when it was logged. */
@io.github.asterx5.kotlinspect.InternalKotlinspectApi
@Entity(tableName = "logs", indices = [Index("sessionId"), Index("timestamp")])
public data class LogEntity(
    @PrimaryKey(autoGenerate = true) public val id: Long = 0,
    public val sessionId: String,
    public val timestamp: Long,
    /** A [io.github.asterx5.kotlog.LogLevel] name. */
    public val level: String,
    public val tag: String,
    public val layer: String?,
    public val message: String,
    public val throwable: String?,
)
