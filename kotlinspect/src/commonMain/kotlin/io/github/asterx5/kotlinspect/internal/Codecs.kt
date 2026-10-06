package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.KotlinspectCall
import io.github.asterx5.kotlinspect.KotlinspectSession
import io.github.asterx5.kotlinspect.RetentionPolicy
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

internal typealias HeaderList = List<Pair<String, String>>

internal object Codecs {
    private val prettyJson = Json { prettyPrint = true }

    fun encodeHeaders(headers: HeaderList): String =
        JsonArray(headers.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }).toString()

    fun decodeHeaders(text: String): HeaderList = runCatching {
        Json.parseToJsonElement(text).jsonArray.map {
            val pair = it.jsonArray
            pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
        }
    }.getOrDefault(emptyList())

    fun encodeTags(tags: Set<String>): String = JsonArray(tags.map(::JsonPrimitive)).toString()

    fun decodeTags(text: String): Set<String> = runCatching {
        Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }.toSet()
    }.getOrDefault(emptySet())

    fun encodeMetadata(metadata: Map<String, String>): String =
        JsonObject(metadata.mapValues { JsonPrimitive(it.value) }).toString()

    fun decodeMetadata(text: String): Map<String, String> = runCatching {
        (Json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    fun encodeRetention(policy: RetentionPolicy): String = when (policy) {
        RetentionPolicy.Forever -> "forever"
        RetentionPolicy.InMemoryOnly -> "memory"
        is RetentionPolicy.MaxAge -> "age:${policy.maxAge.inWholeMilliseconds}"
        is RetentionPolicy.MaxCount -> "count:${policy.count}"
        is RetentionPolicy.MaxSize -> "size:${policy.bytes}"
    }

    fun decodeRetention(text: String?): RetentionPolicy? {
        if (text == null) return null
        val value = text.substringAfter(':', "").toLongOrNull()
        return when {
            text == "forever" -> RetentionPolicy.Forever
            text == "memory" -> RetentionPolicy.InMemoryOnly
            text.startsWith("age:") && value != null -> RetentionPolicy.MaxAge(value.milliseconds)
            text.startsWith("count:") && value != null -> RetentionPolicy.MaxCount(value.toInt())
            text.startsWith("size:") && value != null -> RetentionPolicy.MaxSize(value)
            else -> null
        }
    }

    /** Pretty-prints [text] if it is JSON, otherwise returns null. */
    fun prettyJsonOrNull(text: String): String? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return runCatching {
            prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
        }.getOrNull()
    }
}

internal fun RecordEntity.toCall(): KotlinspectCall = KotlinspectCall(
    id = id,
    sessionId = sessionId,
    state = callState,
    method = method,
    url = url,
    host = host,
    path = path,
    statusCode = statusCode,
    startedAtMillis = startedAt,
    durationMillis = durationMs,
    requestBodySize = requestBodySize,
    responseBodySize = responseBodySize,
    errorMessage = error,
)

internal val RecordEntity.callState: CallState
    get() = CallState.entries.firstOrNull { it.name == state } ?: CallState.Complete

internal val RecordEntity.isError: Boolean
    get() = callState == CallState.Failed || (statusCode ?: 0) >= 400

internal fun SessionEntity.toSession(): KotlinspectSession = KotlinspectSession(
    id = id,
    name = name,
    createdAtMillis = createdAt,
    tags = Codecs.decodeTags(tags),
    metadata = Codecs.decodeMetadata(metadata),
)
