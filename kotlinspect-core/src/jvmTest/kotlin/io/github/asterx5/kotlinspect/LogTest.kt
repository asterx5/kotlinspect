package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.db.LogEntity
import io.github.asterx5.kotlog.KtLog
import io.github.asterx5.kotlog.KtLogEntry
import io.github.asterx5.kotlog.LogLayer
import io.github.asterx5.kotlog.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogTest {
    @AfterTest
    fun reset() {
        Kotlinspect.installRuntime(null)
    }

    private fun entry(message: String, level: LogLevel = LogLevel.Debug, tag: String = "Repo", layer: String? = "repository") =
        KtLogEntry(System.currentTimeMillis(), level, tag, message, layer, null)

    /** Waits in real time until [count] logs are stored in the current session. */
    private suspend fun KotlinspectRuntime.awaitLogs(count: Int): List<LogEntity> = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (true) {
                awaitIdle()
                val id = currentSessionId.value
                val logs = if (id == null) emptyList() else database.logs().observeSession(id).first()
                if (logs.size >= count) return@withTimeout logs
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }
    }

    @Test
    fun logsAreStoredInTheCurrentSessionNewestFirst() = runTest {
        val rt = testRuntime()
        rt.log(entry("first"))
        rt.log(entry("second", LogLevel.Error, tag = "Api", layer = "datasource"))

        val logs = rt.awaitLogs(2)
        assertEquals(listOf("second", "first"), logs.map { it.message })
        assertEquals("Error", logs.first().level)
        assertEquals("Api", logs.first().tag)
        assertEquals("datasource", logs.first().layer)
        assertEquals(rt.currentSessionId.value, logs.first().sessionId)
    }

    @Test
    fun ktLogReachesTheInspectorWithNoSetup() = runTest {
        val rt = testRuntime()
        Kotlinspect.installRuntime(rt)
        KtLog.consoleEnabled = false
        try {
            KtLog.d("Feed", "Loaded 3 items", LogLayer.Datasource.value)
        } finally {
            KtLog.consoleEnabled = true
        }

        val log = rt.awaitLogs(1).single()
        assertEquals("Feed", log.tag)
        assertEquals("datasource", log.layer)
        assertEquals("Loaded 3 items", log.message)
    }

    @Test
    fun clearingAndDeletingSessionsRemovesTheirLogs() = runTest {
        val rt = testRuntime()
        rt.log(entry("a"))
        rt.awaitLogs(1)
        val session = rt.currentSessionId.value!!

        rt.clearSession(session)
        rt.awaitIdle()
        assertEquals(0, rt.database.logs().countAll())

        rt.log(entry("b"))
        rt.awaitLogs(1)
        rt.clearAll()
        rt.awaitIdle()
        assertEquals(0, rt.database.logs().countAll())
    }

    @Test
    fun oldestLogsAreTrimmedPastTheCap() = runTest {
        val rt = testRuntime { logs { maxCount = 3 } }
        repeat(6) { rt.log(entry("m$it")) }

        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (rt.database.logs().countAll() != 3 ||
                    rt.database.logs().observeSession(rt.currentSessionId.value!!).first().first().message != "m5"
                ) {
                    rt.awaitIdle()
                    delay(20)
                }
            }
        }
        val kept = rt.database.logs().observeSession(rt.currentSessionId.value!!).first().map { it.message }
        assertEquals(listOf("m5", "m4", "m3"), kept)
    }

    @Test
    fun logsCanBeTurnedOff() = runTest {
        val rt = testRuntime { logs { enabled = false } }
        rt.log(entry("ignored"))
        delay(200)
        rt.awaitIdle()
        assertEquals(0, rt.database.logs().countAll())
    }

    @Test
    fun bubbleCountsIncludeLogs() = runTest {
        val rt = testRuntime()
        rt.log(entry("x"))
        rt.log(entry("y"))
        rt.awaitLogs(2)

        val counts = KotlinspectHost(rt).bubbleCounts().first { it.logs == 2 }
        assertEquals(0, counts.total)
        assertTrue(KotlinspectHost(rt).showLogCount)
    }
}
