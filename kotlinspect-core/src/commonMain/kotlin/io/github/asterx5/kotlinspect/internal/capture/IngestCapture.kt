package io.github.asterx5.kotlinspect.internal.capture

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.CaptureHandle
import io.github.asterx5.kotlinspect.CapturedRequest
import io.github.asterx5.kotlinspect.CapturedResponse
import io.github.asterx5.kotlinspect.CapturedTimings
import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.internal.CaptureExtras
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.RecordSources
import io.github.asterx5.kotlinspect.internal.ResolvedConfig
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.fillFrom
import io.github.asterx5.kotlog.Kotlog
import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.github.asterx5.kotlinspect.CapturedBody as IngestedBody

/**
 * One exchange reported through [io.github.asterx5.kotlinspect.KotlinspectIngest]. Handle calls
 * only copy their arguments and queue the work on the runtime's op loop, so they never block the
 * caller and apply in order. All record state below is owned by the op loop.
 *
 * When another capture source already records the same request (see
 * [KotlinspectRuntime.claimOuter]), this capture becomes a shadow: it stores nothing and only adds
 * its extra details to that record when it ends.
 */
@OptIn(ExperimentalKotlinspectApi::class, ExperimentalAtomicApi::class)
internal class IngestCapture private constructor(
    private val runtime: KotlinspectRuntime,
    private val source: String,
) : CaptureHandle {
    private val ended = AtomicBoolean(false)

    // Op loop state.
    private var record: RecordEntity? = null
    private var shadowOf: String? = null
    private var extras = CaptureExtras()

    override fun responseReceived(response: CapturedResponse) {
        if (ended.load()) return
        guard {
            val headers = response.headers.map { it.name to it.value }
            val at = runtime.clock()
            runtime.enqueue {
                extras = extras.copy(protocol = extras.protocol ?: response.httpVersion)
                val current = record ?: return@enqueue
                val config = runtime.config
                val updated = current.copy(
                    statusCode = response.statusCode,
                    statusText = response.statusText,
                    protocol = response.httpVersion ?: current.protocol,
                    responseHeaders = Codecs.encodeHeaders(config.redaction.headers(headers)),
                    responseContentType = headers.contentType(),
                    responseStartedAt = at,
                )
                record = updated
                runtime.updateInLoop(updated)
            }
        }
    }

    override fun completed(body: IngestedBody?, timings: CapturedTimings?, serverIpAddress: String?) {
        if (!ended.compareAndSet(expectedValue = false, newValue = true)) return
        guard {
            val copy = body?.copied()
            val at = runtime.clock()
            runtime.enqueue {
                addExtras(timings, serverIpAddress)
                val current = record ?: return@enqueue mergeShadow()
                val stored = storedBody(copy, current.responseContentType, runtime.config, isRequest = false)
                finish(
                    current.copy(
                        responseBody = stored.text,
                        responseBodySize = stored.size,
                        responseBodyTruncated = stored.truncated,
                        responseBodyNote = stored.note,
                        storedBytes = (current.requestBody?.length ?: 0).toLong() + (stored.text?.length ?: 0),
                    ),
                    CallState.Complete,
                    error = null,
                    at = at,
                )
            }
        }
    }

    override fun failed(error: String, timings: CapturedTimings?) {
        if (!ended.compareAndSet(expectedValue = false, newValue = true)) return
        guard {
            val at = runtime.clock()
            runtime.enqueue {
                addExtras(timings, null)
                val current = record ?: return@enqueue mergeShadow()
                finish(current, CallState.Failed, error.ifBlank { "Failed" }, at)
            }
        }
    }

    override fun cancelled() {
        if (!ended.compareAndSet(expectedValue = false, newValue = true)) return
        guard {
            val at = runtime.clock()
            runtime.enqueue {
                val current = record ?: return@enqueue mergeShadow()
                finish(current, CallState.Cancelled, "Cancelled", at)
            }
        }
    }

    private suspend fun finish(current: RecordEntity, state: CallState, error: String?, at: Long) {
        val done = current.fillFrom(extras).copy(
            state = state.name,
            completedAt = at,
            durationMs = (at - current.startedAt).coerceAtLeast(0),
            error = error ?: current.error,
        )
        record = done
        runtime.finishInLoop(done)
    }

    private suspend fun mergeShadow() {
        val outer = shadowOf ?: return
        runtime.fillRecord(outer, extras)
    }

    /**
     * Adds timings that became known after the record ended, such as OkHttp's receive time once
     * the app has read the body. Only empty timing fields are filled.
     */
    fun addLateTimings(timings: CapturedTimings) {
        guard {
            runtime.enqueue {
                val target = record?.id ?: shadowOf ?: return@enqueue
                val late = CaptureExtras(
                    timingBlocked = timings.blocked?.nonNegative(),
                    timingDns = timings.dns?.nonNegative(),
                    timingConnect = timings.connect?.nonNegative(),
                    timingSsl = timings.ssl?.nonNegative(),
                    timingSend = timings.send?.nonNegative(),
                    timingWait = timings.wait?.nonNegative(),
                    timingReceive = timings.receive?.nonNegative(),
                )
                runtime.fillRecord(target, late)
            }
        }
    }

    private fun addExtras(timings: CapturedTimings?, serverIpAddress: String?) {
        extras = extras.copy(
            serverIpAddress = extras.serverIpAddress ?: serverIpAddress,
            timingBlocked = timings?.blocked?.nonNegative() ?: extras.timingBlocked,
            timingDns = timings?.dns?.nonNegative() ?: extras.timingDns,
            timingConnect = timings?.connect?.nonNegative() ?: extras.timingConnect,
            timingSsl = timings?.ssl?.nonNegative() ?: extras.timingSsl,
            timingSend = timings?.send?.nonNegative() ?: extras.timingSend,
            timingWait = timings?.wait?.nonNegative() ?: extras.timingWait,
            timingReceive = timings?.receive?.nonNegative() ?: extras.timingReceive,
        )
    }

    /** Never lets a failure reach the host app. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            warnOnce("A capture callback failed: ${e.message ?: e::class.simpleName}")
        }
    }

    companion object {
        /** Returned while Kotlinspect is disabled: shared, allocation-free and inert. */
        val Noop: CaptureHandle = object : CaptureHandle {
            override fun responseReceived(response: CapturedResponse) = Unit
            override fun completed(body: IngestedBody?, timings: CapturedTimings?, serverIpAddress: String?) = Unit
            override fun failed(error: String, timings: CapturedTimings?) = Unit
            override fun cancelled() = Unit
        }

        private val warned = AtomicBoolean(false)

        /** Logs the first problem only, so a misbehaving adapter can't flood the log. */
        fun warnOnce(message: String) {
            if (warned.compareAndSet(expectedValue = false, newValue = true)) {
                runCatching { Kotlog.w("Kotlinspect", message) }
            }
        }

        fun begin(
            runtime: KotlinspectRuntime,
            request: CapturedRequest,
            source: String,
            sessionRef: String?,
            operationName: String?,
        ): CaptureHandle = try {
            val capture = IngestCapture(runtime, source)
            val method = request.method.trim().uppercase()
            val url = request.url
            val headers = request.headers.map { it.name to it.value }
            val body = request.body?.copied()
            val startedAt = request.startedAtEpochMillis
            runtime.enqueue {
                val outer = runtime.claimOuter(source, method, url, startedAt)
                if (outer != null) {
                    capture.shadowOf = outer
                    capture.extras = CaptureExtras(operationName = operationName)
                    return@enqueue
                }
                val record = buildRecord(runtime.config, source, method, url, headers, body, startedAt, operationName)
                if (record == null) {
                    warnOnce("Ignored a captured request with an invalid method or URL: $method $url")
                    return@enqueue
                }
                capture.record = record
                runtime.beginInLoop(record, sessionRef)
            }
            capture
        } catch (e: Throwable) {
            warnOnce("Could not start a capture: ${e.message ?: e::class.simpleName}")
            Noop
        }

        private fun buildRecord(
            config: ResolvedConfig,
            source: String,
            method: String,
            rawUrl: String,
            headers: List<Pair<String, String>>,
            body: IngestedBody?,
            startedAt: Long,
            operationName: String?,
        ): RecordEntity? {
            if (method.isEmpty() || method.any { it.isWhitespace() }) return null
            if (!rawUrl.contains("://")) return null
            val parsed = runCatching { Url(rawUrl) }.getOrNull() ?: return null
            if (parsed.host.isEmpty()) return null
            val url = config.redaction.url(parsed)
            val contentType = body?.contentType ?: headers.contentType()
            val stored = storedBody(body, contentType, config, isRequest = true)
            return RecordEntity(
                id = KotlinspectRuntime.newRecordId(),
                sessionId = "",
                source = source,
                kind = RecordSources.KIND_HTTP_CALL,
                state = CallState.Pending.name,
                startedAt = startedAt,
                endpointKey = EndpointKeys.endpointKey(method, url),
                fingerprint = EndpointKeys.fingerprint(method, url, stored.text),
                method = method,
                url = url.toString(),
                scheme = url.protocol.name,
                host = url.host,
                path = url.encodedPath.ifEmpty { "/" },
                requestHeaders = Codecs.encodeHeaders(config.redaction.headers(headers)),
                requestContentType = contentType,
                requestBody = stored.text,
                requestBodySize = stored.size,
                requestBodyTruncated = stored.truncated,
                requestBodyNote = stored.note,
                storedBytes = (stored.text?.length ?: 0).toLong(),
                operationName = operationName?.takeIf { it.isNotBlank() },
            )
        }

        /** Applies the same body rules as Ktor records: binary detection, truncation, redaction. */
        private fun storedBody(body: IngestedBody?, fallbackType: String?, config: ResolvedConfig, isRequest: Boolean): CapturedBody {
            if (body == null) return CapturedBody.Empty
            val known = body.totalSize.takeIf { it >= 0 }
            body.notCapturedReason?.let { reason -> return CapturedBody.omitted("Not captured: $reason", known) }
            val bytes = body.bytes
            if (bytes.isEmpty() && (known == null || known == 0L)) return CapturedBody.Empty
            val contentType = body.contentType ?: fallbackType
            val total = known?.coerceAtLeast(bytes.size.toLong()) ?: bytes.size.toLong()
            val stored = BodyCapture.fromBytes(
                bytes = bytes,
                length = bytes.size,
                totalSize = total,
                contentType = contentType,
                maxBytes = config.maxBodyBytes,
                redactors = config.bodyRedactors,
                isRequest = isRequest,
            )
            // The adapter stopped at the limit without knowing the real size.
            return if (known == null && stored.text != null && bytes.size >= config.maxBodyBytes) {
                stored.copy(size = null, truncated = true, note = "First ${stored.text.length} characters; total size unknown")
            } else {
                stored
            }
        }

        private fun IngestedBody.copied(): IngestedBody =
            IngestedBody(bytes.copyOf(), totalSize, contentType, notCapturedReason)

        private fun List<Pair<String, String>>.contentType(): String? =
            firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second

        private fun Long.nonNegative(): Long? = takeIf { it >= 0 }
    }
}
