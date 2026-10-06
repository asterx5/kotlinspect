package io.github.asterx5.kotlinspect.internal.capture

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.HeaderList
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.RecordSources
import io.github.asterx5.kotlinspect.internal.ResolvedConfig
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lifecycle of one HTTP exchange: created when the request enters the send pipeline, updated
 * when the response arrives, finished when the body is fully read, the call fails, or it is
 * cancelled. Every state change is pushed to the runtime's sink as a full record snapshot.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HttpCapture private constructor(
    private val runtime: KotlinspectRuntime,
    private val config: ResolvedConfig,
    initial: RecordEntity,
) {
    @Volatile
    private var record: RecordEntity = initial

    @Volatile
    private var streaming = false
    private val finished = AtomicBoolean(false)

    /** Called when the send pipeline returned normally. Streaming bodies finish later. */
    fun onExchangeReturned() {
        if (!streaming) finish(CallState.Complete)
    }

    suspend fun onFailure(cause: Throwable) {
        // Response validation (expectSuccess) throws before our receive hook runs; the exception
        // still carries the response, so record it rather than reporting a network failure.
        if (cause is ResponseException && record.statusCode == null) {
            runCatching { onResponse(cause.response) }
        }
        when {
            cause is CancellationException -> finish(CallState.Cancelled, error = "Cancelled")
            // The response arrived but a later step, such as response validation, threw.
            record.statusCode != null -> finish(CallState.Complete, error = cause.describe())
            else -> finish(CallState.Failed, error = cause.describe())
        }
    }

    /**
     * Records the response. Returns a replacement response whose body is teed into the capture
     * when the body is streamed, or null when the body was already saved and has been read here.
     */
    @OptIn(InternalAPI::class)
    suspend fun onResponse(response: HttpResponse): HttpResponse? {
        val headers: HeaderList = response.headers.entries().flatMap { (k, v) -> v.map { k to it } }
        val contentType = response.headers[HttpHeaders.ContentType]
        record = record.copy(
            statusCode = response.status.value,
            statusText = response.status.description,
            protocol = response.version.toString(),
            responseHeaders = Codecs.encodeHeaders(config.redaction.headers(headers)),
            responseContentType = contentType,
            requestSentAt = response.requestTime.timestamp,
            responseStartedAt = response.responseTime.timestamp,
        )
        val binary = BodyCapture.isBinaryContentType(contentType) == true
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()

        if (response.isSaved) {
            val buffer = BoundedBuffer(if (binary) 0 else bufferLimit())
            drain(response.rawContent, buffer)
            setResponseBody(buffer, contentType)
            return null
        }

        streaming = true
        runtime.update(record.copy(responseBodySize = declaredLength))
        val tee = tee(response, contentType, binary)
        return response.call.replaceResponse { tee }.response
    }

    private fun tee(response: HttpResponse, contentType: String?, binary: Boolean): ByteReadChannel {
        @OptIn(InternalAPI::class)
        val source = response.rawContent
        val out = ByteChannel()
        val buffer = BoundedBuffer(if (binary) 0 else bufferLimit())
        CoroutineScope(response.coroutineContext).launch {
            val chunk = ByteArray(8 * 1024)
            var readFailure: Throwable? = null
            var abandoned = false
            try {
                while (true) {
                    val n = try {
                        source.readAvailable(chunk)
                    } catch (e: Throwable) {
                        readFailure = e
                        throw e
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    buffer.append(chunk, n)
                    try {
                        out.writeFully(chunk, 0, n)
                        out.flush()
                    } catch (e: Throwable) {
                        abandoned = true
                        throw e
                    }
                }
                out.flushAndClose()
            } catch (e: Throwable) {
                out.cancel(e)
                source.cancel(e)
            } finally {
                setResponseBody(buffer, contentType)
                val failure = readFailure
                when {
                    failure is CancellationException -> finish(CallState.Cancelled, error = "Cancelled while reading the body")
                    failure != null -> finish(CallState.Failed, error = failure.describe())
                    abandoned -> {
                        record = record.copy(responseBodyNote = "The app stopped reading after ${buffer.total} bytes")
                        finish(CallState.Complete)
                    }
                    else -> finish(CallState.Complete)
                }
            }
        }
        return out
    }

    private fun setResponseBody(buffer: BoundedBuffer, contentType: String?) {
        val body = if (buffer.total == 0L) {
            CapturedBody.Empty
        } else if (BodyCapture.isBinaryContentType(contentType) == true) {
            CapturedBody.omitted("Binary content not captured", buffer.total)
        } else {
            BodyCapture.fromBytes(
                bytes = buffer.bytes(),
                length = buffer.buffered,
                totalSize = buffer.total,
                contentType = contentType,
                maxBytes = config.maxBodyBytes,
                redactors = config.bodyRedactors,
                isRequest = false,
            )
        }
        record = record.copy(
            responseBody = body.text,
            responseBodySize = body.size,
            responseBodyTruncated = body.truncated,
            responseBodyNote = body.note,
            storedBytes = (record.requestBody?.length ?: 0).toLong() + (body.text?.length ?: 0),
        )
    }

    private fun bufferLimit(): Int = config.maxBodyBytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private fun finish(state: CallState, error: String? = null) {
        if (!finished.compareAndSet(expectedValue = false, newValue = true)) return
        val now = runtime.clock()
        record = record.copy(
            state = state.name,
            completedAt = now,
            durationMs = now - record.startedAt,
            error = error ?: record.error,
        )
        runtime.finish(record)
    }

    companion object {
        fun begin(
            runtime: KotlinspectRuntime,
            request: HttpRequestBuilder,
            content: OutgoingContent?,
            sessionName: String?,
        ): HttpCapture {
            val config = runtime.config
            val method = request.method.value
            val url = config.redaction.url(request.url.build())

            val headers = buildList {
                content?.contentType?.let { add(HttpHeaders.ContentType to it.toString()) }
                content?.contentLength?.let { add(HttpHeaders.ContentLength to it.toString()) }
                content?.headers?.entries()?.forEach { (k, v) -> v.forEach { add(k to it) } }
                request.headers.entries().forEach { (k, v) -> v.forEach { add(k to it) } }
            }.distinct()
            val contentType = content?.contentType?.toString() ?: request.headers[HttpHeaders.ContentType]
            val body = requestBody(content, contentType, config)

            val record = RecordEntity(
                id = KotlinspectRuntime.newRecordId(),
                sessionId = "",
                source = RecordSources.HTTP,
                kind = RecordSources.KIND_HTTP_CALL,
                state = CallState.Pending.name,
                startedAt = runtime.clock(),
                endpointKey = EndpointKeys.endpointKey(method, url),
                fingerprint = EndpointKeys.fingerprint(method, url, body.text),
                method = method,
                url = url.toString(),
                scheme = url.protocol.name,
                host = url.host,
                path = url.encodedPath.ifEmpty { "/" },
                requestHeaders = Codecs.encodeHeaders(config.redaction.headers(headers)),
                requestContentType = contentType,
                requestBody = body.text,
                requestBodySize = body.size,
                requestBodyTruncated = body.truncated,
                requestBodyNote = body.note,
                storedBytes = (body.text?.length ?: 0).toLong(),
            )
            runtime.begin(record, sessionName)
            return HttpCapture(runtime, config, record)
        }

        private fun requestBody(content: OutgoingContent?, contentType: String?, config: ResolvedConfig): CapturedBody =
            when (content) {
                null, is OutgoingContent.NoContent -> CapturedBody.Empty
                is OutgoingContent.ByteArrayContent -> {
                    val bytes = content.bytes()
                    BodyCapture.fromBytes(
                        bytes = bytes,
                        contentType = contentType,
                        maxBytes = config.maxBodyBytes,
                        redactors = config.bodyRedactors,
                        isRequest = true,
                    )
                }
                is OutgoingContent.ContentWrapper -> requestBody(content.delegate(), contentType, config)
                is MultiPartFormDataContent -> CapturedBody.omitted("Multipart body not captured", content.contentLength)
                is OutgoingContent.ProtocolUpgrade -> CapturedBody.omitted("Protocol upgrade")
                is OutgoingContent.ReadChannelContent,
                is OutgoingContent.WriteChannelContent,
                -> CapturedBody.omitted("Streaming body not captured", content.contentLength)
            }

        private suspend fun drain(channel: ByteReadChannel, into: BoundedBuffer) {
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val n = channel.readAvailable(chunk)
                if (n < 0) break
                into.append(chunk, n)
            }
        }

        private fun Throwable.describe(): String {
            val type = this::class.simpleName ?: "Error"
            return message?.let { "$type: $it" } ?: type
        }
    }
}

/**
 * Rules run around every call, in order, before it reaches the network. v1 ships no rules; the
 * chain exists so mocking and error injection can be added without restructuring the plugin.
 */
internal fun interface CallRule {
    suspend fun intercept(request: HttpRequestBuilder, chain: RuleChain): HttpClientCall
}

internal class RuleChain(
    private val rules: List<CallRule>,
    private val index: Int = 0,
    private val terminal: suspend (HttpRequestBuilder) -> HttpClientCall,
) {
    suspend fun proceed(request: HttpRequestBuilder): HttpClientCall =
        if (index < rules.size) {
            rules[index].intercept(request, RuleChain(rules, index + 1, terminal))
        } else {
            terminal(request)
        }
}
