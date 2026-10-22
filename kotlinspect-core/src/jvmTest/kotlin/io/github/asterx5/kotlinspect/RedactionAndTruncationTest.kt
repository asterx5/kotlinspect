package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.capture.REDACTED
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionAndTruncationTest {

    @Test
    fun defaultHeadersAreRedactedInRequestAndResponse() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) {
            respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "session=abc"))
        }

        client.get("https://api.example.com/") {
            header(HttpHeaders.Authorization, "Bearer secret")
            header(HttpHeaders.Cookie, "a=b")
            header("X-Visible", "yes")
        }

        val r = rt.single()
        val request = Codecs.decodeHeaders(r.requestHeaders).toMap()
        assertEquals(REDACTED, request[HttpHeaders.Authorization])
        assertEquals(REDACTED, request[HttpHeaders.Cookie])
        assertEquals("yes", request["X-Visible"])
        assertEquals(REDACTED, Codecs.decodeHeaders(r.responseHeaders).toMap()[HttpHeaders.SetCookie])
    }

    @Test
    fun customRedactionRules() = runTest {
        val rt = testRuntime {
            redactHeaders("X-Api-Key")
            unredactHeaders("Authorization")
            redactQueryParameters("token")
            headerRedactor { name, value -> if (name == "X-User") value.take(2) + "***" else value }
            bodyRedactor { _, body, _ -> body.replace(Regex("\"password\":\"[^\"]*\""), "\"password\":\"***\"") }
        }
        val client = mockClient(rt) { respond("""{"password":"server"}""") }

        client.post("https://api.example.com/login?token=t0p&page=2") {
            header("X-Api-Key", "k")
            header("X-User", "ahmed")
            header(HttpHeaders.Authorization, "Bearer visible")
            setBody(TextContent("""{"password":"hunter2"}""", ContentType.Application.Json))
        }

        val r = rt.single()
        val headers = Codecs.decodeHeaders(r.requestHeaders).toMap()
        assertEquals(REDACTED, headers["X-Api-Key"])
        assertEquals("ah***", headers["X-User"])
        assertEquals("Bearer visible", headers[HttpHeaders.Authorization])
        assertFalse(r.url.contains("t0p"))
        assertTrue(r.url.contains("page=2"))
        assertEquals("""{"password":"***"}""", r.requestBody)
        assertEquals("""{"password":"***"}""", r.responseBody)
    }

    @Test
    fun largeBodiesAreTruncatedButSizeIsKept() = runTest {
        val rt = testRuntime { maxBodyBytes = 10 }
        val body = "a".repeat(100)
        val client = mockClient(rt) { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }

        client.post("https://api.example.com/") { setBody(TextContent(body, ContentType.Text.Plain)) }

        val r = rt.single()
        assertEquals("a".repeat(10), r.responseBody)
        assertTrue(r.responseBodyTruncated)
        assertEquals(100L, r.responseBodySize)
        assertEquals("a".repeat(10), r.requestBody)
        assertTrue(r.requestBodyTruncated)
        assertEquals(100L, r.requestBodySize)
    }

    @Test
    fun truncationDoesNotLeaveBrokenMultibyteCharacters() = runTest {
        val rt = testRuntime { maxBodyBytes = 5 }
        val client = mockClient(rt) { respond("ééééé", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }

        client.get("https://api.example.com/")

        // 5 bytes = two 2-byte chars plus half of a third, which is dropped.
        assertEquals("éé", rt.single().responseBody)
    }

    @Test
    fun unlabeledBinaryIsDetectedBySniffing() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01), HttpStatusCode.OK) }

        client.get("https://api.example.com/blob")

        val r = rt.single()
        assertEquals(null, r.responseBody)
        assertEquals(6L, r.responseBodySize)
    }
}
