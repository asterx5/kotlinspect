package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun capturesSuccessfulCall() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("""{"id":1}""", HttpStatusCode.OK, json) }

        client.get("https://api.example.com/users/42?expand=true").bodyAsText()

        val r = rt.single()
        assertEquals(CallState.Complete.name, r.state)
        assertEquals("GET", r.method)
        assertEquals("api.example.com", r.host)
        assertEquals("/users/42", r.path)
        assertEquals(200, r.statusCode)
        assertEquals("""{"id":1}""", r.responseBody)
        assertEquals(8L, r.responseBodySize)
        assertEquals("GET api.example.com/users/{id}", r.endpointKey)
        assertNotNull(r.fingerprint)
        assertNotNull(r.durationMs)
        assertNotNull(r.completedAt)
    }

    @Test
    fun capturesRequestBodyAndHeaders() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("ok") }

        client.post("https://api.example.com/login") {
            setBody(TextContent("""{"user":"a"}""", ContentType.Application.Json))
        }

        val r = rt.single()
        assertEquals("""{"user":"a"}""", r.requestBody)
        assertEquals(12L, r.requestBodySize)
        val headers = Codecs.decodeHeaders(r.requestHeaders).toMap()
        assertEquals("application/json", headers[HttpHeaders.ContentType])
    }

    @Test
    fun networkFailureIsRecordedAsFailed() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { throw IOException("Connection reset") }

        assertFailsWith<IOException> { client.get("https://api.example.com/") }

        val r = rt.single()
        assertEquals(CallState.Failed.name, r.state)
        assertNull(r.statusCode)
        assertTrue(r.error!!.contains("Connection reset"))
    }

    @Test
    fun errorStatusIsCompleteNotFailed() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt, clientConfig = { expectSuccess = true }) {
            respond("missing", HttpStatusCode.NotFound)
        }

        assertFailsWith<ClientRequestException> { client.get("https://api.example.com/x") }

        val r = rt.single()
        assertEquals(CallState.Complete.name, r.state)
        assertEquals(404, r.statusCode)
        assertEquals("missing", r.responseBody)
    }

    @Test
    fun inFlightCallIsPendingThenCancelled() = runTest {
        val rt = testRuntime()
        val entered = CompletableDeferred<Unit>()
        val client = mockClient(rt) {
            entered.complete(Unit)
            awaitCancellation()
        }

        val call = async { client.get("https://api.example.com/slow") }
        entered.await()
        assertEquals(CallState.Pending.name, rt.single().state)

        call.cancel()
        runCatching { call.await() }

        val r = rt.single()
        assertEquals(CallState.Cancelled.name, r.state)
        assertNotNull(r.completedAt)
    }

    @Test
    fun streamingResponseIsTeedWithoutChangingTheBody() = runTest {
        val rt = testRuntime()
        val payload = (1..50).joinToString("\n") { "line $it" }
        val client = mockClient(rt) { respond(ByteReadChannel(payload), HttpStatusCode.OK) }

        val received = client.prepareGet("https://api.example.com/stream").execute { it.bodyAsText() }
        assertEquals(payload, received)

        rt.awaitFinished()
        val r = rt.single()
        assertEquals(CallState.Complete.name, r.state)
        assertEquals(payload, r.responseBody)
        assertEquals(payload.length.toLong(), r.responseBodySize)
    }

    @Test
    fun abandonedStreamIsCompleteWithNote() = runTest {
        val rt = testRuntime()
        val payload = "x".repeat(200_000)
        val client = mockClient(rt) { respond(ByteReadChannel(payload), HttpStatusCode.OK) }

        client.prepareGet("https://api.example.com/stream").execute { it.bodyAsChannel().readAvailable(ByteArray(10)) }

        rt.awaitFinished()
        assertTrue(rt.single().state in setOf(CallState.Complete.name, CallState.Cancelled.name))
    }

    @Test
    fun binaryResponseIsNotStored() = runTest {
        val rt = testRuntime()
        val bytes = ByteArray(4096) { it.toByte() }
        val client = mockClient(rt) {
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }

        client.get("https://api.example.com/a.png")

        val r = rt.single()
        assertNull(r.responseBody)
        assertEquals(4096L, r.responseBodySize)
        assertTrue(r.responseBodyNote!!.contains("Binary"))
    }

    @Test
    fun disabledPluginInstanceSkipsCapture() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt, pluginConfig = { enabled = false }) { respond("ok") }

        client.get("https://api.example.com/")

        assertTrue(rt.records().isEmpty())
    }
}
