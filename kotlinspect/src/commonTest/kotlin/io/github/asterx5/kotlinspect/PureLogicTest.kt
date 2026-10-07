package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.capture.BodyCapture
import io.github.asterx5.kotlinspect.internal.capture.BoundedBuffer
import io.github.asterx5.kotlinspect.internal.capture.EndpointKeys
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.ui.StatusFilter
import io.github.asterx5.kotlinspect.internal.ui.buildToast
import io.github.asterx5.kotlinspect.internal.ui.filterRecords
import io.github.asterx5.kotlinspect.internal.ui.toCurl
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class PureLogicTest {
    private fun record(
        path: String = "/x",
        method: String = "GET",
        status: Int? = 200,
        state: CallState = CallState.Complete,
    ) = RecordEntity(
        id = path + method,
        sessionId = "s",
        source = "http",
        kind = "http.call",
        state = state.name,
        startedAt = 0,
        durationMs = 120,
        method = method,
        url = "https://api.example.com$path",
        host = "api.example.com",
        path = path,
        statusCode = status,
    )

    @Test
    fun endpointKeyNormalizesIds() {
        assertEquals(
            "GET api.example.com/users/{id}/posts/{id}",
            EndpointKeys.endpointKey("get", Url("https://api.example.com/users/42/posts/3f2b1c9e-0000-4000-8000-123456789abc")),
        )
        assertEquals("POST api.example.com/login", EndpointKeys.endpointKey("POST", Url("https://api.example.com/login")))
    }

    @Test
    fun fingerprintIgnoresQueryOrderButNotBody() {
        val a = EndpointKeys.fingerprint("GET", Url("https://a.com/x?b=2&a=1"), null)
        val b = EndpointKeys.fingerprint("GET", Url("https://a.com/x?a=1&b=2"), null)
        assertEquals(a, b)
        assertNotEquals(a, EndpointKeys.fingerprint("GET", Url("https://a.com/x?a=1&b=2"), "body"))
    }

    @Test
    fun contentTypeClassification() {
        assertEquals(true, BodyCapture.isBinaryContentType("image/png"))
        assertEquals(false, BodyCapture.isBinaryContentType("application/json; charset=utf-8"))
        assertEquals(false, BodyCapture.isBinaryContentType("application/problem+json"))
        assertNull(BodyCapture.isBinaryContentType(null))
        assertTrue(BodyCapture.looksBinary(byteArrayOf(1, 0, 2)))
        assertFalse(BodyCapture.looksBinary("plain text\n".encodeToByteArray()))
    }

    @Test
    fun boundedBufferCountsEverythingKeepsPrefix() {
        val buffer = BoundedBuffer(4)
        buffer.append("hello".encodeToByteArray(), 5)
        buffer.append("world".encodeToByteArray(), 5)
        assertEquals(10, buffer.total)
        assertEquals("hell", buffer.bytes().decodeToString(0, buffer.buffered))
    }

    @Test
    fun retentionRoundTrips() {
        listOf(
            RetentionPolicy.Forever,
            RetentionPolicy.InMemoryOnly,
            RetentionPolicy.MaxAge(3.days),
            RetentionPolicy.MaxCount(50),
            RetentionPolicy.MaxSize(1024),
        ).forEach { assertEquals(it, Codecs.decodeRetention(Codecs.encodeRetention(it))) }
    }

    @Test
    fun prettyJsonOnlyForJson() {
        assertEquals("{\n    \"a\": 1\n}", Codecs.prettyJsonOrNull("""{"a":1}"""))
        assertNull(Codecs.prettyJsonOrNull("not json"))
    }

    @Test
    fun curlEscapesQuotes() {
        val r = record(method = "POST").copy(
            requestHeaders = Codecs.encodeHeaders(listOf("Content-Type" to "application/json")),
            requestBody = """{"name":"O'Brien"}""",
        )
        assertEquals(
            "curl \\\n  -X POST \\\n  'https://api.example.com/x' \\\n  -H 'Content-Type: application/json' \\\n" +
                "  --data-raw '{\"name\":\"O'\\''Brien\"}'",
            r.toCurl(),
        )
    }

    @Test
    fun toastSingleAndBatched() {
        val single = buildToast(1, listOf(record(path = "/login", method = "POST")))!!
        assertEquals("POST /login · 200 · 120ms", single.title)
        assertNull(single.subtitle)

        val batch = buildToast(2, listOf(record(path = "/a"), record(path = "/b", status = 500)))!!
        assertEquals("2 calls · 1 failed", batch.title)
        assertTrue(batch.isError)
    }

    private fun RecordEntity.summary() = io.github.asterx5.kotlinspect.internal.db.RecordSummary(
        id, sessionId, state, startedAt, durationMs, method, url, host, path, statusCode, requestBodySize, responseBodySize, error,
    )

    @Test
    fun listFilters() {
        val records = listOf(
            record(path = "/ok"),
            record(path = "/missing", status = 404),
            record(path = "/post", method = "POST", status = 201),
            record(path = "/boom", status = null, state = CallState.Failed),
            record(path = "/wait", status = null, state = CallState.Pending),
        ).map { it.summary() }
        assertEquals(listOf("/missing"), filterRecords(records, "", StatusFilter.ClientError, null).map { it.path })
        assertEquals(listOf("/boom"), filterRecords(records, "", StatusFilter.Failed, null).map { it.path })
        assertEquals(listOf("/wait"), filterRecords(records, "", StatusFilter.Pending, null).map { it.path })
        assertEquals(listOf("/post"), filterRecords(records, "", StatusFilter.All, "POST").map { it.path })
        assertEquals(listOf("/missing"), filterRecords(records, "404", StatusFilter.All, null).map { it.path })
    }
}
