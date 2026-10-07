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
internal data class RecordEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val source: String,
    val kind: String,
    val state: String,
    val startedAt: Long,
    val requestSentAt: Long? = null,
    val responseStartedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    val endpointKey: String? = null,
    val fingerprint: String? = null,
    val error: String? = null,
    // HTTP
    val method: String = "",
    val url: String = "",
    val scheme: String = "",
    val host: String = "",
    val path: String = "",
    val requestHeaders: String = "[]",
    val requestContentType: String? = null,
    val requestBody: String? = null,
    val requestBodySize: Long? = null,
    val requestBodyTruncated: Boolean = false,
    val requestBodyNote: String? = null,
    val statusCode: Int? = null,
    val statusText: String? = null,
    val protocol: String? = null,
    val responseHeaders: String = "[]",
    val responseContentType: String? = null,
    val responseBody: String? = null,
    val responseBodySize: Long? = null,
    val responseBodyTruncated: Boolean = false,
    val responseBodyNote: String? = null,
    /** Bytes of body text stored for this record, used by size-based retention. */
    val storedBytes: Long = 0,
)

@Entity(tableName = "sessions", indices = [Index("name")])
internal data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    /** JSON array of strings. */
    val tags: String = "[]",
    /** JSON object of string values. */
    val metadata: String = "{}",
    /** Encoded [io.github.asterx5.kotlinspect.RetentionPolicy] override, or null for the global one. */
    val retention: String? = null,
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
internal data class RecordSummary(
    val id: String,
    val sessionId: String,
    val state: String,
    val startedAt: Long,
    val durationMs: Long?,
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val statusCode: Int?,
    val requestBodySize: Long?,
    val responseBodySize: Long?,
    val error: String?,
)

/** Aggregates for one session, computed in a single query. */
internal data class CallCounts(
    val total: Int = 0,
    val inFlight: Int = 0,
    val errors: Int = 0,
    val bytes: Long = 0,
    val avgMs: Long? = null,
)

internal data class SessionCount(
    @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "calls") val calls: Int,
)
