package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An outer source can report a request just after the inner one started it (Alamofire's event
 * monitor is asynchronous, so the automatic URLSession capture may begin first). The late outer
 * record takes the inner one over, so the request is still stored once.
 */
@OptIn(ExperimentalKotlinspectApi::class)
class LateOuterCaptureTest {
    private val clock = TestClock()

    @AfterTest
    fun reset() {
        Kotlinspect.installRuntime(null)
    }

    private fun runtime(configure: KotlinspectConfigBuilder.() -> Unit = {}): KotlinspectRuntime =
        testRuntime(clock = clock, configure = configure).also { Kotlinspect.installRuntime(it) }

    private fun begin(source: CaptureSource, url: String, startedAt: Long = clock.now) =
        KotlinspectIngest.begin(CapturedRequest("GET", url, emptyList(), null, startedAt), source)

    @Test
    fun lateOuterTakesTheInnerRecordOver() = runTest {
        val rt = runtime()
        val inner = begin(CaptureSource.URLSESSION, "https://api.example.com/late")
        rt.awaitIdle()
        assertEquals(listOf("urlsession"), rt.allRecords().map { it.source }, "stored while it is the only one")

        clock.advance(40)
        val outer = begin(CaptureSource.ALAMOFIRE, "https://api.example.com/late")
        inner.responseReceived(CapturedResponse(200, "OK", emptyList(), "h2"))
        inner.completed(null, CapturedTimings(dns = 4, connect = 11), serverIpAddress = "192.0.2.10")
        outer.responseReceived(CapturedResponse(200, "OK", emptyList(), null))
        outer.completed(null)

        val r = rt.allRecords().single()
        assertEquals("alamofire", r.source)
        assertEquals("192.0.2.10", r.serverIpAddress)
        assertEquals(11L, r.timingConnect)
        assertEquals("h2", r.protocol)
    }

    @Test
    fun anInnerThatEndedBeforeTheOuterBeganIsKept() = runTest {
        val rt = runtime()
        begin(CaptureSource.URLSESSION, "https://api.example.com/done").completed(null)
        rt.awaitIdle()
        begin(CaptureSource.ALAMOFIRE, "https://api.example.com/done").completed(null)

        assertEquals(setOf("urlsession", "alamofire"), rt.allRecords().map { it.source }.toSet())
    }

    @Test
    fun anInnerFromLongBeforeIsNotTakenOver() = runTest {
        val rt = runtime()
        val inner = begin(CaptureSource.URLSESSION, "https://api.example.com/old")
        rt.awaitIdle()
        clock.advance(5_000)
        val outer = begin(CaptureSource.ALAMOFIRE, "https://api.example.com/old")
        inner.completed(null)
        outer.completed(null)

        assertEquals(2, rt.allRecords().size)
    }

    @Test
    fun turnedOffWithTheFlag() = runTest {
        val rt = runtime { dedupeNestedCaptures = false }
        val inner = begin(CaptureSource.URLSESSION, "https://api.example.com/flag")
        rt.awaitIdle()
        val outer = begin(CaptureSource.ALAMOFIRE, "https://api.example.com/flag")
        inner.completed(null)
        outer.completed(null)

        assertEquals(2, rt.allRecords().size)
    }
}
