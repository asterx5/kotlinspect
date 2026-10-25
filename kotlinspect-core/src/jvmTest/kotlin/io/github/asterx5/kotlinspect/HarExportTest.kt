package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.BuildInfo
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.capture.REDACTED
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.SessionEntity
import io.github.asterx5.kotlinspect.internal.har.HarEntries
import io.github.asterx5.kotlinspect.internal.har.HarExporter
import io.github.asterx5.kotlinspect.internal.har.HarFiles
import io.github.asterx5.kotlinspect.internal.har.HarSelection
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalKotlinspectApi::class)
class HarExportTest {
    private val clock = TestClock(now = 1_761_400_931_120L) // 2025-10-25T14:02:11.120Z
    private val out = File.createTempFile("kotlinspect-test", ".har").apply { deleteOnExit() }

    @AfterTest
    fun reset() {
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure { enabled = true }
        out.delete()
    }

    // Waits for startup, which removes empty sessions, before the test writes its own.
    private fun runtime(): KotlinspectRuntime = testRuntime(clock = clock).also {
        Kotlinspect.installRuntime(it)
        runBlocking { it.awaitIdle() }
    }

    private fun session(rt: KotlinspectRuntime, id: String = "s1", name: String = "Checkout Flow") = runBlocking {
        rt.database.sessions().upsert(SessionEntity(id = id, name = name, createdAt = clock.now))
    }

    private fun rec(
        id: String,
        start: Long,
        state: CallState = CallState.Complete,
        url: String = "https://api.example.com/items",
        sessionId: String = "s1",
        block: RecordEntity.() -> RecordEntity = { this },
    ) = RecordEntity(
        id = id, sessionId = sessionId, source = "ktor", kind = "http.call", state = state.name,
        startedAt = clock.now + start, method = "GET", url = url, scheme = "https", host = "api.example.com",
        path = url.substringAfter("example.com").substringBefore('?').ifEmpty { "/" },
    ).block()

    private fun store(rt: KotlinspectRuntime, vararg records: RecordEntity) = runBlocking {
        records.forEach { rt.database.records().upsert(it) }
    }

    private fun export(rt: KotlinspectRuntime, options: HarExportOptions = HarExportOptions(), sessionId: String = "s1"): HarExportResult =
        runBlocking { HarExporter.export(rt, HarSelection.Session(sessionId), options, outputPath = out.absolutePath) }

    private fun json(): JsonObject = Json.parseToJsonElement(out.readText()).jsonObject["log"]!!.jsonObject

    /** Every kind of record the golden file covers. */
    private fun goldenRecords(): Array<RecordEntity> = arrayOf(
        rec("a-success", 0, url = "https://api.example.com/search?q=caf%C3%A9+cr%C3%A8me&tag=a&tag=b") {
            copy(
                method = "GET", statusCode = 200, statusText = "OK", protocol = "h2", durationMs = 120,
                requestSentAt = startedAt + 10, responseStartedAt = startedAt + 100, completedAt = startedAt + 120,
                requestHeaders = Codecs.encodeHeaders(listOf("Accept" to "application/json", "X-Trace" to "1", "X-Trace" to "2", "Cookie" to "theme=dark; lang=en")),
                responseHeaders = Codecs.encodeHeaders(
                    listOf(
                        "Content-Type" to "application/json",
                        "Set-Cookie" to "sid=abc123; Path=/; Domain=example.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; HttpOnly; Secure",
                        "Set-Cookie" to "pref=1",
                    ),
                ),
                responseContentType = "application/json", responseBody = """{"items":[1,2]}""", responseBodySize = 15,
                serverIpAddress = "203.0.113.5", timingDns = 3, timingConnect = 7, timingSsl = 4,
            )
        },
        rec("b-post", 200) {
            copy(
                method = "POST", statusCode = 201, statusText = "Created", protocol = "HTTP/1.1", durationMs = 50,
                requestHeaders = Codecs.encodeHeaders(listOf("Authorization" to REDACTED, "Cookie" to REDACTED, "Content-Type" to "application/json")),
                requestContentType = "application/json", requestBody = """{"note":"say \"hi\"\\n"}""", requestBodySize = 22,
                responseHeaders = Codecs.encodeHeaders(listOf("Location" to "https://api.example.com/items/9")),
                responseBodySize = 0,
            )
        },
        rec("c-failed", 400, CallState.Failed) { copy(error = "ConnectException: Connection refused", durationMs = 30) },
        rec("d-cancelled", 600, CallState.Cancelled) { copy(error = "Cancelled", durationMs = 5) },
        rec("e-interrupted", 700, CallState.Cancelled) { copy(error = "Interrupted: the app stopped before this call finished") },
        rec("f-truncated", 800) {
            copy(
                statusCode = 200, statusText = "OK", protocol = "weird/9", durationMs = 40, responseContentType = "text/plain",
                responseBody = "0123456789", responseBodySize = 5000, responseBodyTruncated = true, responseBodyNote = "Truncated to 10 of 5000 bytes",
            )
        },
        rec("g-binary", 900, url = "https://api.example.com/logo.png") {
            copy(statusCode = 200, statusText = "OK", durationMs = 10, responseContentType = "image/png", responseBodySize = 2048, responseBodyNote = "Binary content not captured")
        },
        rec("h-pending", 1_000, CallState.Pending),
    )

    @Test
    fun goldenFile() {
        val rt = runtime()
        session(rt)
        store(rt, *goldenRecords())
        val result = export(rt)
        assertIs<HarExportResult.Success>(result)
        assertEquals(7, result.entryCount)
        assertEquals(out.length(), result.byteSize)

        val actual = out.readText().replace("\"version\":\"${BuildInfo.VERSION}\"", "\"version\":\"<version>\"")
        File("build/har-golden-actual.har").writeText(actual)
        val expected = File("src/jvmTest/resources/har/golden.har").readText().trimEnd('\n')
        assertEquals(expected, actual, "differs from the golden file; see build/har-golden-actual.har")
        assertFalse(out.readBytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "no BOM")
    }

    @Test
    fun everyRequiredHarFieldIsPresentWithTheRightType() {
        val rt = runtime()
        session(rt)
        store(rt, *goldenRecords())
        val result = export(rt)
        assertIs<HarExportResult.Success>(result, result.toString())
        val log = json()

        fun JsonObject.string(key: String) = assertIs<JsonPrimitive>(this[key], key).also { assertTrue(it.isString, key) }
        fun JsonObject.number(key: String) = assertIs<JsonPrimitive>(this[key], key).also { assertFalse(it.isString, key); it.long }
        fun JsonObject.array(key: String) = assertIs<JsonArray>(this[key], key)
        fun JsonObject.obj(key: String) = assertIs<JsonObject>(this[key], key)
        fun JsonArray.nameValues() = forEach { it.jsonObject.string("name"); it.jsonObject.string("value") }

        log.string("version")
        log.obj("creator").apply { string("name"); string("version") }
        log.array("pages").forEach { p ->
            p.jsonObject.apply { string("startedDateTime"); string("id"); string("title"); obj("pageTimings") }
        }
        val entries = log.array("entries")
        assertEquals(7, entries.size)
        entries.forEach { e ->
            e.jsonObject.apply {
                string("pageref"); string("startedDateTime"); number("time"); obj("cache")
                obj("request").apply {
                    string("method"); string("url"); string("httpVersion")
                    array("cookies").nameValues(); array("headers").nameValues(); array("queryString").nameValues()
                    number("headersSize"); number("bodySize")
                }
                obj("response").apply {
                    number("status"); string("statusText"); string("httpVersion")
                    array("cookies").nameValues(); array("headers").nameValues()
                    obj("content").apply { number("size"); string("mimeType") }
                    string("redirectURL"); number("headersSize"); number("bodySize")
                }
                obj("timings").apply { number("send"); number("wait"); number("receive") }
            }
        }
    }

    @Test
    fun timeIsAlwaysTheSumOfThePhases() {
        val random = Random(7)
        repeat(2_000) { i ->
            fun maybe(): Long? = if (random.nextBoolean()) random.nextLong(0, 500) else null
            val start = random.nextLong(0, 10_000)
            val sent = if (random.nextBoolean()) start + random.nextLong(-5, 300) else null
            val response = if (sent != null && random.nextBoolean()) sent + random.nextLong(-5, 300) else null
            val done = if (response != null && random.nextBoolean()) response + random.nextLong(-5, 300) else null
            val r = rec("r$i", start) {
                copy(
                    durationMs = if (random.nextBoolean()) random.nextLong(-1, 2_000) else null,
                    requestSentAt = sent?.let { clock.now + it }, responseStartedAt = response?.let { clock.now + it },
                    completedAt = done?.let { clock.now + it },
                    timingBlocked = maybe(), timingDns = maybe(), timingConnect = maybe(), timingSsl = maybe(),
                    timingSend = maybe(), timingWait = maybe(), timingReceive = maybe(),
                )
            }
            val t = HarEntries.timings(r)
            assertTrue(t.send >= 0 && t.wait >= 0 && t.receive >= 0, "phases never negative")
            listOf(t.blocked, t.dns, t.connect, t.ssl).forEach { assertTrue(it >= -1) }
            assertEquals(listOf(t.blocked, t.dns, t.connect).filter { it >= 0 }.sum() + t.send + t.wait + t.receive, t.time)
        }
        // Nothing known: the duration is all wait.
        val bare = HarEntries.timings(rec("bare", 0) { copy(durationMs = 80) })
        assertEquals(listOf(0L, 80L, 0L), listOf(bare.send, bare.wait, bare.receive))
    }

    @Test
    fun textIsEscapedAndRoundTrips() {
        val tricky = "quote \" backslash \\ tab \t newline \n nul \u0000 bell \u0007 emoji 😀🚀 RTL مرحبا بالعالم עברית"
        val rt = runtime()
        session(rt, name = tricky)
        store(rt, rec("x", 0) { copy(statusCode = 200, responseBody = tricky, responseContentType = "text/plain", responseBodySize = 99) })
        export(rt)
        val raw = out.readText()
        assertTrue("\\u0000" in raw && "\\u0007" in raw && "\\u0009" in raw && "\\u000A" in raw, "control characters as \\u00XX")
        assertTrue("😀" in raw && "مرحبا" in raw, "other characters written as UTF-8")
        val log = json()
        assertEquals(tricky, log["pages"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(tricky, log["entries"]!!.jsonArray[0].jsonObject["response"]!!.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun JsonElement.keys(): Set<String> = when (this) {
        is JsonObject -> keys + values.flatMap { it.keys() }
        is JsonArray -> flatMap { it.keys() }.toSet()
        else -> emptySet()
    }

    @Test
    fun optionsControlBodiesFailuresAndPending() {
        val rt = runtime()
        session(rt)
        store(rt, *goldenRecords())

        export(rt, HarExportOptions(includeBodies = false))
        val noBodies = json()["entries"]!!
        assertFalse("text" in noBodies.keys(), "no body text anywhere")
        assertFalse("postData" in noBodies.keys())
        assertEquals(7, noBodies.jsonArray.size, "pending excluded by default")

        export(rt, HarExportOptions(includeFailed = false))
        assertEquals(listOf("a", "b", "f", "g").size, json()["entries"]!!.jsonArray.size)

        export(rt, HarExportOptions(includePending = true))
        assertEquals(8, json()["entries"]!!.jsonArray.size)
    }

    @Test
    fun redactedValuesNeverReachTheFile() = runBlocking {
        val rt = runtime()
        val client = mockClient(rt) { respond("ok", headers = headersOf(HttpHeaders.SetCookie, "session=topsecret-cookie")) }
        client.get("https://api.example.com/me") {
            header(HttpHeaders.Authorization, "Bearer topsecret-token")
            header(HttpHeaders.Cookie, "auth=topsecret-cookie")
        }.bodyAsText()
        rt.awaitIdle()
        val sessionId = rt.currentSessionId.value!!
        assertIs<HarExportResult.Success>(export(rt, sessionId = sessionId))

        val raw = out.readText()
        assertFalse("topsecret" in raw, raw)
        val entry = json()["entries"]!!.jsonArray.single().jsonObject
        assertEquals(0, entry["request"]!!.jsonObject["cookies"]!!.jsonArray.size, "a redacted Cookie header gives no cookies")
    }

    @Test
    fun largeSessionsAreReadInPages() {
        val rt = runtime()
        session(rt)
        runBlocking {
            val records = rt.database.records()
            repeat(10_000) { i -> records.upsert(rec("r${i.toString().padStart(5, '0')}", (i / 3).toLong()) { copy(statusCode = 200, durationMs = 1) }) }
        }
        val result = export(rt)
        assertIs<HarExportResult.Success>(result)
        assertEquals(10_000, result.entryCount)
        assertEquals(10_000 / HarExporter.PAGE_SIZE + 1, HarExporter.pagesRead, "200 records per read, never the whole session")
        val ids = json()["entries"]!!.jsonArray.map { it.jsonObject["startedDateTime"]!!.jsonPrimitive.content }
        assertEquals(ids.sorted(), ids, "entries in start order")
    }

    @Test
    fun exportsLandInTheExportFolderAndOldOnesAreCleanedUp() {
        val rt = runtime()
        session(rt, name = "My Session: Ünïcode & spaces")
        store(rt, rec("r1", 0) { copy(statusCode = 200) })
        val dir = File(HarFiles.exportsDirectory()!!)
        val old = File(dir, "kotlinspect-old-20200101-000000.har").apply { writeText("{}"); setLastModified(clock.now - 25L * 3600 * 1000) }
        val recent = File(dir, "kotlinspect-recent-20200101-000000.har").apply { writeText("{}"); setLastModified(clock.now - 3600 * 1000) }

        val result = runBlocking { Kotlinspect.exportHar("s1") }
        assertIs<HarExportResult.Success>(result)
        val file = File(result.filePath)
        assertEquals(dir, file.parentFile)
        assertTrue(Regex("kotlinspect-my-session-n-code-spaces-\\d{8}-\\d{6}\\.har").matches(file.name), file.name)
        assertFalse(old.exists(), "exports older than 24 hours are deleted")
        assertTrue(recent.exists())
        file.delete()
        recent.delete()
    }

    @Test
    fun singleCallsExportWithTheirEndpointAsName() {
        val rt = runtime()
        session(rt)
        store(rt, rec("one", 0) { copy(statusCode = 200, endpointKey = "GET api.example.com/items/{id}") }, rec("two", 10) { copy(statusCode = 200) })
        val result = runBlocking { Kotlinspect.exportHar(listOf("one")) }
        assertIs<HarExportResult.Success>(result)
        assertEquals(1, result.entryCount)
        assertTrue(File(result.filePath).name.startsWith("kotlinspect-get-api-example-com-items-id-"), result.filePath)
        File(result.filePath).delete()
    }

    @Test
    fun disabledAndUnknownSessionsFailWithAReason() {
        val rt = runtime()
        session(rt)
        assertIs<HarExportResult.Failure>(runBlocking { Kotlinspect.exportHar("no-such-session") })
        assertIs<HarExportResult.Failure>(runBlocking { Kotlinspect.exportHar(listOf("no-such-call")) })
        rt.close()
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure { enabled = false }
        val disabled = runBlocking { Kotlinspect.exportHar("s1") }
        assertIs<HarExportResult.Failure>(disabled)
        assertEquals("Kotlinspect is disabled", disabled.reason)
    }
}
