package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.ResolvedConfig
import io.github.asterx5.kotlinspect.internal.capture.REDACTED
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.okhttp.KotlinspectEventListenerFactory
import io.github.asterx5.kotlinspect.okhttp.KotlinspectInterceptor
import io.github.asterx5.kotlinspect.okhttp.kotlinspect
import io.github.asterx5.kotlinspect.okhttp.kotlinspectSession
import io.github.asterx5.kotlog.KotlogSinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The OkHttp interceptor against a real local server. Run against each supported OkHttp line with
 * `-PokhttpTestVersion=4.9.3`, `4.12.1` or `5.5.1`.
 */
class OkHttpInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var rt: KotlinspectRuntime

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        rt = realTimeRuntime()
    }

    @AfterTest
    fun tearDown() {
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure { enabled = true }
        runCatching { server.shutdown() }
    }

    private fun realTimeRuntime(configure: KotlinspectConfigBuilder.() -> Unit = {}): KotlinspectRuntime {
        val config = ResolvedConfig.from(KotlinspectConfigBuilder().apply(configure))
        return KotlinspectRuntime(config, databaseFactory = { inMemoryDatabase() }, clock = System::currentTimeMillis)
            .also { it.start() }
            .also { Kotlinspect.installRuntime(it) }
    }

    private fun client(build: OkHttpClient.Builder.() -> Unit = { kotlinspect() }): OkHttpClient =
        OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).apply(build).build()

    // A timeout turns a stuck write queue into a failure instead of a hung build.
    private fun records(): List<RecordEntity> = runBlocking { withTimeout(10_000) { rt.allRecords() } }

    private fun single(): RecordEntity = records().single()

    private fun OkHttpClient.get(path: String, configure: Request.Builder.() -> Unit = {}): String =
        newCall(Request.Builder().url(server.url(path)).apply(configure).build()).execute().use { it.body!!.string() }

    @Test
    fun recordsAGetWithItsJsonBody() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":42}"""))
        assertEquals("""{"id":42}""", client().get("/users/42"))

        val r = single()
        assertEquals("okhttp", r.source)
        assertEquals("GET", r.method)
        assertEquals("/users/42", r.path)
        assertEquals(CallState.Complete.name, r.state)
        assertEquals(200, r.statusCode)
        assertEquals("""{"id":42}""", r.responseBody)
        assertEquals("http/1.1", r.protocol)
        assertNotNull(r.timingWait)
    }

    @Test
    fun recordsAPostBody() {
        server.enqueue(MockResponse().setBody("ok"))
        client().newCall(
            Request.Builder().url(server.url("/orders"))
                .post("""{"item":1}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()

        val r = single()
        assertEquals("POST", r.method)
        assertEquals("""{"item":1}""", r.requestBody)
        assertEquals("""{"item":1}""".length.toLong(), r.requestBodySize)
        assertEquals("application/json; charset=utf-8", r.requestContentType)
    }

    /** A body that counts how often it is written, for one-shot and duplex checks. */
    private class CountingBody(private val oneShot: Boolean = false, private val duplex: Boolean = false) : RequestBody() {
        val writes = AtomicInteger()
        override fun contentType() = "text/plain".toMediaType()
        override fun isOneShot() = oneShot
        override fun isDuplex() = duplex
        override fun writeTo(sink: BufferedSink) {
            writes.incrementAndGet()
            sink.writeUtf8("payload")
        }
    }

    /** Answers in place of the network, so nothing but Kotlinspect could have written the body. */
    private val fakeNetwork = Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("done".toResponseBody("text/plain".toMediaType())).build()
    }

    @Test
    fun oneShotAndDuplexBodiesAreNeverRead() {
        val oneShot = CountingBody(oneShot = true)
        val duplex = CountingBody(duplex = true)
        val c = client { kotlinspect(); addInterceptor(fakeNetwork) }
        c.newCall(Request.Builder().url(server.url("/one")).post(oneShot).build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/duplex")).post(duplex).build()).execute().close()

        assertEquals(0, oneShot.writes.get())
        assertEquals(0, duplex.writes.get())
        val byPath = records().associateBy { it.path }
        assertEquals("Not captured: one-shot", byPath.getValue("/one").requestBodyNote)
        assertEquals("Not captured: duplex", byPath.getValue("/duplex").requestBodyNote)
    }

    @Test
    fun largeUploadsAreCappedNotBuffered() {
        rt = realTimeRuntime { maxBodyBytes = 1_024 }
        val size = 8L * 1024 * 1024
        val upload = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                var left = size
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    sink.write(chunk, 0, n)
                    left -= n
                }
            }
        }
        client { kotlinspect(); addInterceptor(fakeNetwork) }
            .newCall(Request.Builder().url(server.url("/upload")).post(upload).build()).execute().close()

        val r = single()
        assertEquals(1_024, r.requestBody!!.length)
        assertEquals(size, r.requestBodySize)
        assertTrue(r.requestBodyTruncated)
    }

    @Test
    fun largeResponsesAreTruncatedButTheAppGetsEverything() {
        rt = realTimeRuntime { maxBodyBytes = 100 }
        val body = "x".repeat(10_000)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(body))
        assertEquals(body, client().get("/big"))

        val r = single()
        assertEquals(100, r.responseBody!!.length)
        assertTrue(r.responseBodyTruncated)
        assertEquals(10_000L, r.responseBodySize)
    }

    private fun gzip(text: String): Buffer {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out
    }

    @Test
    fun gzipTheAppAskedForIsDecodedForTheInspector() {
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setHeader("Content-Type", "text/plain").setBody(gzip("hello gzip")))
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setHeader("Content-Type", "text/plain").setBody("not gzip at all"))
        server.enqueue(MockResponse().setHeader("Content-Encoding", "br").setHeader("Content-Type", "text/plain").setBody("brotli"))
        val c = client()
        c.newCall(Request.Builder().url(server.url("/gz")).header("Accept-Encoding", "gzip").build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/broken")).header("Accept-Encoding", "gzip").build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/br")).header("Accept-Encoding", "br").build()).execute().close()

        val byPath = records().associateBy { it.path }
        assertEquals("hello gzip", byPath.getValue("/gz").responseBody)
        assertEquals("Not captured: gzip", byPath.getValue("/broken").responseBodyNote)
        assertEquals("Not captured: encoding br", byPath.getValue("/br").responseBodyNote)
    }

    @Test
    fun eventStreamsAreNeverReadAhead() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody("data: 1\n\ndata: 2\n\n", 4))
        assertEquals("data: 1\n\ndata: 2\n\n", client().get("/events"))

        val r = single()
        assertNull(r.responseBody)
        assertEquals("Not captured: streaming", r.responseBodyNote)
    }

    @Test
    fun failuresAndCancellationsReachTheCallerUnchanged() {
        val port = server.port
        server.shutdown()
        val failing = client().newCall(Request.Builder().url("http://127.0.0.1:$port/down").build())
        assertFailsWith<IOException> { failing.execute() }

        val cancelled = client().newCall(Request.Builder().url("http://127.0.0.1:$port/cancel").build())
        cancelled.cancel()
        assertFailsWith<IOException> { cancelled.execute() }

        val byPath = records().associateBy { it.path }
        assertEquals(CallState.Failed.name, byPath.getValue("/down").state)
        assertEquals(CallState.Cancelled.name, byPath.getValue("/cancel").state)
    }

    @Test
    fun disabledAddsNothing() {
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure { enabled = false }
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", client().get("/off"))
        // Nothing started: no runtime, so nothing could have been stored.
        assertNull(Kotlinspect.runtimeOrNull())
    }

    @Test
    fun sensitiveHeadersAreRedacted() {
        server.enqueue(MockResponse().setBody("ok"))
        client().get("/me") { header("Authorization", "Bearer secret-token") }

        val headers = Codecs.decodeHeaders(single().requestHeaders).toMap()
        assertEquals(REDACTED, headers["Authorization"])
    }

    @Test
    fun requestTagBeatsTheInterceptorSessionWhichBeatsTheCurrentOne() {
        repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
        client { kotlinspect("payments") }.get("/client")
        client { kotlinspect("payments") }.get("/tagged") { kotlinspectSession("checkout") }
        client().get("/plain")

        val byPath = records().associateBy { it.path }
        val sessions = runBlocking { rt.database.sessions().all() }.associate { it.id to it.name }
        assertEquals("payments", sessions[byPath.getValue("/client").sessionId])
        assertEquals("checkout", sessions[byPath.getValue("/tagged").sessionId])
        assertEquals(rt.currentSessionId.value, byPath.getValue("/plain").sessionId)
    }

    @Test
    fun networkInterceptorModeCapturesAndWarnsOnce() {
        val warnings = AtomicInteger()
        KotlogSinks.register { if (it.tag == "Kotlinspect" && "addNetworkInterceptor" in it.message) warnings.incrementAndGet() }
        repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
        val c = client { addNetworkInterceptor(KotlinspectInterceptor()) }
        c.get("/n1")
        c.get("/n2")

        assertEquals(2, records().size)
        assertEquals(1, warnings.get())
    }

    @Test
    fun eventListenerAddsPhasesAndForwardsToTheAppsListener() {
        val forwarded = AtomicInteger()
        val appFactory = EventListener.Factory {
            object : EventListener() {
                override fun callStart(call: Call) {
                    forwarded.incrementAndGet()
                }

                override fun callEnd(call: Call) {
                    forwarded.incrementAndGet()
                }
            }
        }
        server.enqueue(MockResponse().setBody("timed"))
        client { kotlinspect(); eventListenerFactory(KotlinspectEventListenerFactory(appFactory)) }.get("/timed")

        val r = single()
        assertNotNull(r.timingConnect)
        assertNotNull(r.timingSend)
        assertNotNull(r.timingWait)
        assertNotNull(r.timingReceive, "measured once the app has read the body")
        assertNotNull(r.serverIpAddress)
        assertEquals(2, forwarded.get())
    }

    @Test
    fun ktorOnAnOkHttpClientWithTheInterceptorStoresOneRecord() = runBlocking {
        server.enqueue(MockResponse().setBody("from ktor"))
        val okHttp = client()
        val ktor = HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(KotlinspectPlugin) { runtimeProvider = { rt } }
        }
        assertEquals("from ktor", ktor.get(server.url("/nested").toString()).bodyAsText())

        val r = records().single()
        assertEquals("ktor", r.source)
        assertEquals(200, r.statusCode)
        assertNotNull(r.timingWait, "the OkHttp capture's details are merged into the Ktor record")
        ktor.close()
    }

    @Test
    fun javaStyleConstructorsWork() {
        assertSame(KotlinspectInterceptor::class.java, KotlinspectInterceptor().javaClass)
        assertNotNull(KotlinspectInterceptor::class.java.getConstructor())
        assertNotNull(KotlinspectInterceptor::class.java.getConstructor(String::class.java))
        assertNotNull(KotlinspectEventListenerFactory::class.java.getConstructor())
    }
}
