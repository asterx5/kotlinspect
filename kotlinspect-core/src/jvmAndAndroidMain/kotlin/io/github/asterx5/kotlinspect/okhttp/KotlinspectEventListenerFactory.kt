@file:OptIn(ExperimentalKotlinspectApi::class)

package io.github.asterx5.kotlinspect.okhttp

import io.github.asterx5.kotlinspect.CaptureHandle
import io.github.asterx5.kotlinspect.CapturedTimings
import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.KotlinspectIngest
import io.github.asterx5.kotlinspect.internal.capture.IngestCapture
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * Optional: measures DNS, connect, TLS, send, wait and receive for every call, so the inspector
 * and HAR exports show the full timing breakdown. Without it, calls still record the wait time.
 *
 * OkHttp allows one event listener factory per client; pass the app's own as [delegate] and every
 * callback is forwarded to it.
 *
 * ```
 * OkHttpClient.Builder()
 *     .kotlinspect()
 *     .eventListenerFactory(KotlinspectEventListenerFactory(appFactory))
 * ```
 *
 * Callbacks that OkHttp added after 4.9 (the oldest version Kotlinspect supports) are not
 * forwarded to [delegate].
 */
public class KotlinspectEventListenerFactory @JvmOverloads constructor(
    private val delegate: EventListener.Factory? = null,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val inner = delegate?.create(call) ?: EventListener.NONE
        // Disabled: hand back the app's own listener and measure nothing.
        if (!KotlinspectIngest.isEnabled) return inner
        return TimingListener(OkHttpTimings.start(call), inner)
    }
}

/** Phase timestamps for one call, in System.nanoTime. Written by the listener, read by the interceptor. */
internal class OkHttpTimings {
    @Volatile var callStart = 0L
    @Volatile var dnsStart = 0L
    @Volatile var dnsEnd = 0L
    @Volatile var connectStart = 0L
    @Volatile var connectEnd = 0L
    @Volatile var secureStart = 0L
    @Volatile var secureEnd = 0L
    @Volatile var requestStart = 0L
    @Volatile var requestEnd = 0L
    @Volatile var responseStart = 0L
    @Volatile var responseEnd = 0L
    @Volatile var serverIpAddress: String? = null

    private var handle: CaptureHandle? = null
    private var receiveSent = false

    fun snapshot(): CapturedTimings = CapturedTimings(
        blocked = span(callStart, listOf(dnsStart, connectStart, requestStart).filter { it > 0 }.minOrNull() ?: 0),
        dns = span(dnsStart, dnsEnd),
        connect = span(connectStart, connectEnd),
        ssl = span(secureStart, secureEnd),
        send = span(requestStart, requestEnd),
        wait = span(requestEnd, responseStart),
        receive = span(responseStart, responseEnd),
    )

    /** The interceptor finished the record; a receive time measured later is added to it. */
    @Synchronized
    fun completed(handle: CaptureHandle) {
        this.handle = handle
        receiveSent = responseEnd > 0
    }

    /** The app finished reading the body, usually after the interceptor returned. */
    @Synchronized
    fun responseBodyEnded(now: Long) {
        responseEnd = now
        val h = handle ?: return
        if (receiveSent) return
        receiveSent = true
        span(responseStart, responseEnd)?.let { receive ->
            (h as? IngestCapture)?.addLateTimings(CapturedTimings(receive = receive))
        }
    }

    private fun span(from: Long, to: Long): Long? = if (from > 0 && to >= from) (to - from).nanosToMillis() else null

    companion object {
        // Weak keys: a call's timings go away with the call.
        private val byCall: MutableMap<Call, OkHttpTimings> = Collections.synchronizedMap(WeakHashMap())

        fun start(call: Call): OkHttpTimings = OkHttpTimings().also { byCall[call] = it }

        fun of(call: Call): OkHttpTimings? = byCall[call]
    }
}

/** Records phase times and forwards every callback to the app's listener. */
private class TimingListener(private val t: OkHttpTimings, private val d: EventListener) : EventListener() {
    private fun now() = System.nanoTime()

    override fun callStart(call: Call) {
        t.callStart = now()
        d.callStart(call)
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) = d.proxySelectStart(call, url)
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<@JvmSuppressWildcards Proxy>) = d.proxySelectEnd(call, url, proxies)

    override fun dnsStart(call: Call, domainName: String) {
        t.dnsStart = now()
        d.dnsStart(call, domainName)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<@JvmSuppressWildcards InetAddress>) {
        t.dnsEnd = now()
        d.dnsEnd(call, domainName, inetAddressList)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        t.connectStart = now()
        d.connectStart(call, inetSocketAddress, proxy)
    }

    override fun secureConnectStart(call: Call) {
        t.secureStart = now()
        d.secureConnectStart(call)
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        t.secureEnd = now()
        d.secureConnectEnd(call, handshake)
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        t.connectEnd = now()
        d.connectEnd(call, inetSocketAddress, proxy, protocol)
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
        d.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)

    override fun connectionAcquired(call: Call, connection: Connection) {
        t.serverIpAddress = runCatching { connection.route().socketAddress.address?.hostAddress }.getOrNull()
        d.connectionAcquired(call, connection)
    }

    override fun connectionReleased(call: Call, connection: Connection) = d.connectionReleased(call, connection)

    override fun requestHeadersStart(call: Call) {
        t.requestStart = now()
        d.requestHeadersStart(call)
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        t.requestEnd = maxOf(t.requestEnd, now())
        d.requestHeadersEnd(call, request)
    }

    override fun requestBodyStart(call: Call) = d.requestBodyStart(call)

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        t.requestEnd = maxOf(t.requestEnd, now())
        d.requestBodyEnd(call, byteCount)
    }

    override fun requestFailed(call: Call, ioe: IOException) = d.requestFailed(call, ioe)

    override fun responseHeadersStart(call: Call) {
        t.responseStart = now()
        d.responseHeadersStart(call)
    }

    override fun responseHeadersEnd(call: Call, response: Response) = d.responseHeadersEnd(call, response)
    override fun responseBodyStart(call: Call) = d.responseBodyStart(call)

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        t.responseBodyEnded(now())
        d.responseBodyEnd(call, byteCount)
    }

    override fun responseFailed(call: Call, ioe: IOException) = d.responseFailed(call, ioe)
    override fun callEnd(call: Call) = d.callEnd(call)
    override fun callFailed(call: Call, ioe: IOException) = d.callFailed(call, ioe)
    override fun canceled(call: Call) = d.canceled(call)
    override fun satisfactionFailure(call: Call, response: Response) = d.satisfactionFailure(call, response)
    override fun cacheHit(call: Call, response: Response) = d.cacheHit(call, response)
    override fun cacheMiss(call: Call) = d.cacheMiss(call)
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) = d.cacheConditionalHit(call, cachedResponse)
}
