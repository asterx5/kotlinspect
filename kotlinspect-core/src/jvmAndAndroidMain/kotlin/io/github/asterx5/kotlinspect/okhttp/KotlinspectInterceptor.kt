@file:JvmName("KotlinspectOkHttp")
@file:OptIn(ExperimentalKotlinspectApi::class)

package io.github.asterx5.kotlinspect.okhttp

import io.github.asterx5.kotlinspect.CaptureHandle
import io.github.asterx5.kotlinspect.CaptureSource
import io.github.asterx5.kotlinspect.CapturedBody
import io.github.asterx5.kotlinspect.CapturedHeader
import io.github.asterx5.kotlinspect.CapturedRequest
import io.github.asterx5.kotlinspect.CapturedResponse
import io.github.asterx5.kotlinspect.CapturedTimings
import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.KotlinspectIngest
import io.github.asterx5.kotlog.Kotlog
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records every call made through an OkHttp client (and so Retrofit, Coil, and anything else on
 * that client) in Kotlinspect. Add it as an application interceptor:
 *
 * ```
 * val client = OkHttpClient.Builder()
 *     .kotlinspect()            // or .addInterceptor(KotlinspectInterceptor())
 *     .build()
 * ```
 *
 * It is free when Kotlinspect is disabled (release builds): the request goes straight through.
 * Response bodies are read ahead with `peekBody`, up to the configured body limit, before the
 * app reads them, as other inspectors do; the app still receives the whole, untouched response.
 *
 * @param sessionId pins every call through this client to a session (an id or a name); null
 * records into the current session. A request tagged with [kotlinspectSession] wins.
 */
public class KotlinspectInterceptor @JvmOverloads constructor(
    private val sessionId: String? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!KotlinspectIngest.isEnabled) return chain.proceed(request)
        val handle = safely { begin(chain, request) } ?: return chain.proceed(request)

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            safely { report(handle, chain, e) }
            throw e
        } catch (e: RuntimeException) {
            safely { report(handle, chain, e) }
            throw e
        }

        safely {
            handle.responseReceived(
                CapturedResponse(
                    statusCode = response.code,
                    statusText = response.message.ifBlank { null },
                    headers = response.headers.captured(),
                    httpVersion = response.protocol.toString(),
                ),
            )
            // Read ahead first: reading a small body to its end is itself a timing event.
            val body = ResponseBodies.capture(response)
            val measured = OkHttpTimings.of(chain.call())
            val timings = measured?.snapshot() ?: CapturedTimings(
                wait = (response.receivedResponseAtMillis - response.sentRequestAtMillis).takeIf { it >= 0 },
            )
            handle.completed(body, timings, measured?.serverIpAddress)
            measured?.completed(handle)
        }
        return response
    }

    private fun begin(chain: Interceptor.Chain, request: Request): CaptureHandle {
        if (chain.connection() != null) warnNetworkInterceptorOnce()
        val headers = request.headers.captured().toMutableList()
        val body = request.body
        // OkHttp adds these from the body after application interceptors run; show them anyway.
        body?.contentType()?.let { type ->
            if (headers.none { it.name.equals("Content-Type", ignoreCase = true) }) headers += CapturedHeader("Content-Type", type.toString())
        }
        body?.contentLength()?.takeIf { it >= 0 }?.let { length ->
            if (headers.none { it.name.equals("Content-Length", ignoreCase = true) }) headers += CapturedHeader("Content-Length", length.toString())
        }
        val session = request.tag(KotlinspectSessionTag::class.java)?.sessionId ?: sessionId
        return KotlinspectIngest.begin(
            CapturedRequest(
                method = request.method,
                url = request.url.toString(),
                headers = headers,
                body = RequestBodies.capture(body),
                startedAtEpochMillis = System.currentTimeMillis(),
            ),
            CaptureSource.OKHTTP,
            sessionId = session,
        )
    }

    private fun report(handle: CaptureHandle, chain: Interceptor.Chain, error: Exception) {
        val timings = OkHttpTimings.of(chain.call())?.snapshot()
        if (chain.call().isCanceled()) {
            handle.cancelled()
        } else {
            handle.failed(error.message?.let { "${error::class.java.simpleName}: $it" } ?: error::class.java.simpleName, timings)
        }
    }

    private fun warnNetworkInterceptorOnce() {
        if (networkWarning.compareAndSet(false, true)) {
            Kotlog.w(
                "Kotlinspect",
                "KotlinspectInterceptor was added with addNetworkInterceptor. It still records calls, but network " +
                    "interceptors see compressed bodies and one call per redirect; prefer addInterceptor.",
            )
        }
    }

    private companion object {
        val networkWarning = AtomicBoolean(false)
    }
}

/** Adds [KotlinspectInterceptor] as an application interceptor. */
@JvmOverloads
public fun OkHttpClient.Builder.kotlinspect(sessionId: String? = null): OkHttpClient.Builder =
    addInterceptor(KotlinspectInterceptor(sessionId))

/** Routes this request to a session (an id or a name); wins over the interceptor's session. */
public fun Request.Builder.kotlinspectSession(sessionId: String): Request.Builder =
    tag(KotlinspectSessionTag::class.java, KotlinspectSessionTag(sessionId))

/** The request tag set by [kotlinspectSession]. */
internal class KotlinspectSessionTag(val sessionId: String)

internal fun Headers.captured(): List<CapturedHeader> = List(size) { i -> CapturedHeader(name(i), value(i)) }

/** Capture code must never throw into the app's call. */
internal inline fun <T> safely(block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (problemLogged.compareAndSet(false, true)) {
        runCatching { Kotlog.w("Kotlinspect", "OkHttp capture failed: ${e.message ?: e::class.java.simpleName}") }
    }
    null
}

private val problemLogged = AtomicBoolean(false)

/** Shared empty array for bodies that are not captured. */
internal val NO_BYTES: ByteArray = ByteArray(0)

/** Turns a duration in nanoseconds into whole milliseconds. */
internal fun Long.nanosToMillis(): Long = this / 1_000_000
