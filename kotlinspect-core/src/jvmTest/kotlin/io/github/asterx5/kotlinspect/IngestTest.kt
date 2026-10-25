package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.capture.IngestCapture
import io.github.asterx5.kotlinspect.internal.capture.REDACTED
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The ingestion API that capture sources other than Ktor use (OkHttp, URLSession, custom). */
@OptIn(ExperimentalKotlinspectApi::class)
class IngestTest {
    private val clock = TestClock()

    @AfterTest
    fun reset() {
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure {
            enabled = true
            maxBodyBytes = 256L * 1024
        }
    }

    private fun runtime(configure: KotlinspectConfigBuilder.() -> Unit = {}): KotlinspectRuntime =
        testRuntime(clock = clock, configure = configure).also { Kotlinspect.installRuntime(it) }

    private fun request(
        url: String = "https://api.example.com/users/42",
        method: String = "GET",
        headers: List<CapturedHeader> = emptyList(),
        body: CapturedBody? = null,
    ) = CapturedRequest(method, url, headers, body, startedAtEpochMillis = clock.now)

    private fun text(s: String, type: String? = "application/json", total: Long? = null) =
        s.encodeToByteArray().let { CapturedBody(it, total ?: it.size.toLong(), type) }

    @Test
    fun recordsTheWholeLifecycle() = runTest {
        val rt = runtime()
        val handle = KotlinspectIngest.begin(
            request(method = "post", url = "https://api.example.com/orders", body = text("""{"item":1}""")),
            CaptureSource.OKHTTP,
            operationName = "OrderApi.create",
        )
        rt.awaitIdle()
        assertEquals(CallState.Pending.name, rt.single().state)

        clock.advance(40)
        handle.responseReceived(
            CapturedResponse(201, "Created", listOf(CapturedHeader("Content-Type", "application/json")), "h2"),
        )
        clock.advance(10)
        handle.completed(
            text("""{"id":7}"""),
            CapturedTimings(dns = 3, connect = 12, ssl = 8, send = 1, wait = 30, receive = 4),
            serverIpAddress = "203.0.113.5",
        )

        val r = rt.single()
        assertEquals("okhttp", r.source)
        assertEquals("POST", r.method)
        assertEquals("/orders", r.path)
        assertEquals("OrderApi.create", r.operationName)
        assertEquals(CallState.Complete.name, r.state)
        assertEquals(201, r.statusCode)
        assertEquals("Created", r.statusText)
        assertEquals("h2", r.protocol)
        assertEquals("""{"item":1}""", r.requestBody)
        assertEquals("""{"id":7}""", r.responseBody)
        assertEquals("application/json", r.responseContentType)
        assertEquals(50L, r.durationMs)
        assertEquals("203.0.113.5", r.serverIpAddress)
        assertEquals(3L, r.timingDns)
        assertEquals(12L, r.timingConnect)
        assertEquals(8L, r.timingSsl)
        assertEquals(30L, r.timingWait)
        assertEquals("POST api.example.com/orders", r.endpointKey)
    }

    @Test
    fun disabledReturnsTheSharedNoOpHandle() = runTest {
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure { enabled = false }
        assertFalse(KotlinspectIngest.isEnabled)
        val handle = KotlinspectIngest.begin(request(), CaptureSource.OKHTTP)
        assertSame(IngestCapture.Noop, handle)
        handle.responseReceived(CapturedResponse(200, null, emptyList(), null))
        handle.completed(null)
        assertNull(Kotlinspect.runtimeOrNull(), "a disabled ingestion must not start the runtime")
    }

    @Test
    fun onlyTheFirstTerminalCallCounts() = runTest {
        val rt = runtime()
        val handle = KotlinspectIngest.begin(request(), CaptureSource.custom("acme-client"))
        handle.responseReceived(CapturedResponse(200, "OK", emptyList(), "http/1.1"))
        handle.completed(text("first"))
        handle.completed(text("second"))
        handle.failed("late failure")
        handle.cancelled()
        handle.responseReceived(CapturedResponse(500, "Late", emptyList(), null))

        val r = rt.single()
        assertEquals("acme-client", r.source)
        assertEquals(CallState.Complete.name, r.state)
        assertEquals("first", r.responseBody)
        assertEquals(200, r.statusCode)
        assertNull(r.error)
    }

    @Test
    fun malformedInputIsDroppedWithoutThrowing() = runTest {
        val rt = runtime()
        val bad = KotlinspectIngest.begin(request(url = "not a url"), CaptureSource.OKHTTP)
        bad.responseReceived(CapturedResponse(200, null, emptyList(), null))
        bad.completed(text("x"))
        val badMethod = KotlinspectIngest.begin(request(method = "GE T"), CaptureSource.OKHTTP)
        badMethod.failed("x")
        assertTrue(rt.records().isEmpty())

        assertFailsWith<IllegalArgumentException> { CaptureSource.custom("Not Valid") }
        assertFailsWith<IllegalArgumentException> { CaptureSource.custom("a".repeat(33)) }
        assertEquals(CaptureSource.custom("okhttp"), CaptureSource.OKHTTP)
    }

    @Test
    fun failuresAndCancellationsMapToTheirStates() = runTest {
        val rt = runtime()
        KotlinspectIngest.begin(request(url = "https://a.example.com/fail"), CaptureSource.OKHTTP).failed("timeout")
        KotlinspectIngest.begin(request(url = "https://a.example.com/cancel"), CaptureSource.OKHTTP).cancelled()

        val byPath = rt.records().associateBy { it.path }
        assertEquals(CallState.Failed.name, byPath.getValue("/fail").state)
        assertEquals("timeout", byPath.getValue("/fail").error)
        assertEquals(CallState.Cancelled.name, byPath.getValue("/cancel").state)
    }

    @Test
    fun redactionAppliesToIngestedRecords() = runTest {
        val rt = runtime {
            redactQueryParameters("token")
            bodyRedactor { _, body, _ -> body.replace("hunter2", "***") }
        }
        val handle = KotlinspectIngest.begin(
            request(
                url = "https://api.example.com/login?token=abc&page=2",
                method = "POST",
                headers = listOf(CapturedHeader("Authorization", "Bearer secret"), CapturedHeader("Accept", "*/*")),
                body = text("""{"password":"hunter2"}"""),
            ),
            CaptureSource.OKHTTP,
        )
        handle.responseReceived(CapturedResponse(200, "OK", listOf(CapturedHeader("Set-Cookie", "sid=1")), null))
        handle.completed(null)

        val r = rt.single()
        val requestHeaders = Codecs.decodeHeaders(r.requestHeaders).toMap()
        assertEquals(REDACTED, requestHeaders["Authorization"])
        assertEquals("*/*", requestHeaders["Accept"])
        assertEquals(REDACTED, Codecs.decodeHeaders(r.responseHeaders).toMap()["Set-Cookie"])
        assertTrue("abc" !in r.url, r.url)
        assertTrue("page=2" in r.url, r.url)
        assertEquals("""{"password":"***"}""", r.requestBody)
    }

    @Test
    fun bodiesFollowTheSameRulesAsKtorRecords() = runTest {
        val rt = runtime { maxBodyBytes = 10 }
        val big = "0123456789abcdefghij"
        KotlinspectIngest.begin(request(url = "https://b.example.com/truncated"), CaptureSource.OKHTTP)
            .completed(CapturedBody(big.encodeToByteArray().copyOf(10), totalSize = 20, contentType = "text/plain"))
        KotlinspectIngest.begin(request(url = "https://b.example.com/unknown"), CaptureSource.OKHTTP)
            .completed(CapturedBody(big.encodeToByteArray().copyOf(10), totalSize = -1, contentType = "text/plain"))
        KotlinspectIngest.begin(request(url = "https://b.example.com/image"), CaptureSource.OKHTTP)
            .completed(CapturedBody(byteArrayOf(1, 2, 3), totalSize = 3, contentType = "image/png"))
        KotlinspectIngest.begin(request(url = "https://b.example.com/stream"), CaptureSource.OKHTTP)
            .completed(CapturedBody(ByteArray(0), totalSize = -1, contentType = "text/event-stream", notCapturedReason = "streaming"))

        val byPath = rt.records().associateBy { it.path }
        byPath.getValue("/truncated").let {
            assertEquals("0123456789", it.responseBody)
            assertTrue(it.responseBodyTruncated)
            assertEquals(20L, it.responseBodySize)
        }
        byPath.getValue("/unknown").let {
            assertTrue(it.responseBodyTruncated)
            assertNull(it.responseBodySize)
        }
        byPath.getValue("/image").let {
            assertNull(it.responseBody)
            assertEquals("Binary content not captured", it.responseBodyNote)
        }
        byPath.getValue("/stream").let {
            assertNull(it.responseBody)
            assertEquals("Not captured: streaming", it.responseBodyNote)
        }
    }

    @Test
    fun sessionResolvesByIdThenNameThenCurrent() = runTest {
        val rt = runtime()
        val explicit = rt.startSession("Explicit", emptySet(), emptyMap(), null)
        KotlinspectIngest.begin(request(url = "https://c.example.com/by-id"), CaptureSource.OKHTTP, sessionId = explicit).cancelled()
        KotlinspectIngest.begin(request(url = "https://c.example.com/by-name"), CaptureSource.OKHTTP, sessionId = "checkout").cancelled()
        KotlinspectIngest.begin(request(url = "https://c.example.com/current"), CaptureSource.OKHTTP).cancelled()

        val all = rt.allRecords().associateBy { it.path }
        assertEquals(explicit, all.getValue("/by-id").sessionId)
        val checkout = rt.database.sessions().byName("checkout")
        assertNotNull(checkout)
        assertEquals(checkout.id, all.getValue("/by-name").sessionId)
        assertEquals(rt.currentSessionId.value, all.getValue("/current").sessionId)
    }
}
