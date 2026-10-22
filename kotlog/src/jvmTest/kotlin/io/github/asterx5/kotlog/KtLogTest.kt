package io.github.asterx5.kotlog

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtLogTest {
    private val received = mutableListOf<KtLogEntry>()

    init {
        KtLogSinks.register { received += it }
    }

    @Test
    fun entriesReachSinksWithAllFields() {
        KtLog.d("Repo", "Loaded 3 items", LogLayer.Repository.value)
        val entry = received.last()
        assertEquals(LogLevel.Debug, entry.level)
        assertEquals("Repo", entry.tag)
        assertEquals("Loaded 3 items", entry.message)
        assertEquals("repository", entry.layer)
        assertNull(entry.throwable)
        assertTrue(entry.timestampMillis > 0)
    }

    @Test
    fun anyStringWorksAsLayerAndLayerIsOptional() {
        KtLog.i("Sync", "custom", "background-sync")
        assertEquals("background-sync", received.last().layer)
        KtLog.v("Sync", "no layer")
        assertNull(received.last().layer)
    }

    @Test
    fun throwableIsKeptAsStackTrace() {
        KtLog.e("Api", "Failed", "datasource", IllegalStateException("boom"))
        val trace = assertNotNull(received.last().throwable)
        assertTrue(trace.contains("IllegalStateException: boom"))
    }

    @Test
    fun genericLogTakesTheLevel() {
        LogLevel.entries.forEach { KtLog.log(it, "T", it.name) }
        assertEquals(LogLevel.entries, received.takeLast(LogLevel.entries.size).map { it.level })
    }

    @Test
    fun printsThePlatformWayUnlessDisabled() {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            KtLog.d("Ui", "Shown", "ui")
            KtLog.consoleEnabled = false
            KtLog.d("Ui", "Hidden", "ui")
        } finally {
            KtLog.consoleEnabled = true
            System.setOut(original)
        }
        val printed = out.toString()
        assertTrue(printed.contains("D/Ui: [ui] Shown"), printed)
        assertTrue(!printed.contains("Hidden"), printed)
        assertEquals("Hidden", received.last().message, "disabling the console must not stop sinks")
    }

    @Test
    fun aFailingSinkNeverReachesTheCaller() {
        KtLogSinks.register { error("sink failure") }
        KtLog.w("Safe", "still fine")
        assertEquals("still fine", received.last().message)
    }
}
