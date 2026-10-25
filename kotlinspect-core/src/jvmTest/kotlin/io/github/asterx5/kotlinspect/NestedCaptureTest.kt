package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two capture sources seeing the same request store it once: Ktor running on an OkHttp engine
 * whose client also has the Kotlinspect interceptor. The inner capture is simulated from inside
 * the mock engine, where the OkHttp interceptor would run.
 */
@OptIn(ExperimentalKotlinspectApi::class)
class NestedCaptureTest {
    private val clock = TestClock()

    @AfterTest
    fun reset() {
        Kotlinspect.installRuntime(null)
    }

    private fun runtime(configure: KotlinspectConfigBuilder.() -> Unit = {}): KotlinspectRuntime =
        testRuntime(clock = clock, configure = configure).also { Kotlinspect.installRuntime(it) }

    private fun innerBegin(url: String): CaptureHandle =
        KotlinspectIngest.begin(CapturedRequest("GET", url, emptyList(), null, clock.now), CaptureSource.OKHTTP)

    private fun CaptureHandle.finishOk() {
        responseReceived(CapturedResponse(200, "OK", emptyList(), "h2"))
        completed(null, CapturedTimings(dns = 2, connect = 9, ssl = 5, wait = 20), serverIpAddress = "198.51.100.7")
    }

    @Test
    fun nestedPairStoresOneRecordWithMergedDetails() = runTest {
        val rt = runtime()
        val client = mockClient(rt) { request ->
            innerBegin(request.url.toString()).finishOk()
            respond("ok")
        }
        client.get("https://api.example.com/items?page=1").bodyAsText()

        val r = rt.allRecords().single()
        assertEquals("ktor", r.source)
        assertEquals(200, r.statusCode)
        assertEquals("198.51.100.7", r.serverIpAddress)
        assertEquals(2L, r.timingDns)
        assertEquals(9L, r.timingConnect)
        assertEquals(20L, r.timingWait)
        // Ktor reports its own protocol, which wins over the inner capture's.
        assertEquals("HTTP/1.1", r.protocol)
    }

    @Test
    fun parallelIdenticalRequestsStayTwoRecords() = runTest {
        val rt = runtime()
        val client = mockClient(rt) { request ->
            innerBegin(request.url.toString()).finishOk()
            respond("ok")
        }
        listOf(
            async { client.get("https://api.example.com/same").bodyAsText() },
            async { client.get("https://api.example.com/same").bodyAsText() },
        ).awaitAll()

        val records = rt.allRecords()
        assertEquals(2, records.size)
        records.forEach {
            assertEquals("ktor", it.source)
            assertEquals("198.51.100.7", it.serverIpAddress)
        }
    }

    @Test
    fun innerCaptureWithoutAnOuterOneIsStoredNormally() = runTest {
        val rt = runtime()
        innerBegin("https://api.example.com/alone").finishOk()

        val r = rt.allRecords().single()
        assertEquals("okhttp", r.source)
        assertEquals("h2", r.protocol)
    }

    @Test
    fun outerFinishingFirstStillGetsTheInnerDetails() = runTest {
        val rt = runtime()
        val inner = CompletableDeferred<CaptureHandle>()
        val client = mockClient(rt) { request ->
            inner.complete(innerBegin(request.url.toString()))
            respond("ok")
        }
        client.get("https://api.example.com/late").bodyAsText()
        rt.awaitIdle()
        assertNull(rt.allRecords().single().serverIpAddress)

        inner.await().finishOk()

        val r = rt.allRecords().single()
        assertEquals("ktor", r.source)
        assertEquals("198.51.100.7", r.serverIpAddress)
        assertEquals(9L, r.timingConnect)
    }

    @Test
    fun turningDedupeOffStoresBoth() = runTest {
        val rt = runtime { dedupeNestedCaptures = false }
        val client = mockClient(rt) { request ->
            innerBegin(request.url.toString()).finishOk()
            respond("ok")
        }
        client.get("https://api.example.com/twice").bodyAsText()

        assertEquals(setOf("ktor", "okhttp"), rt.allRecords().map { it.source }.toSet())
    }

    @Test
    fun innerStartedBeforeTheOuterIsNotMatched() = runTest {
        val rt = runtime()
        val early = innerBegin("https://api.example.com/early")
        clock.advance(5)
        val client = mockClient(rt) { respond("ok") }
        client.get("https://api.example.com/early").bodyAsText()
        early.finishOk()

        assertEquals(setOf("ktor", "okhttp"), rt.allRecords().map { it.source }.toSet())
    }
}
