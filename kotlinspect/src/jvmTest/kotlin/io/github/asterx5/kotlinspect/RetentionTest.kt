package io.github.asterx5.kotlinspect

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RetentionTest {

    @Test
    fun maxCountKeepsTheNewest() = runTest {
        val clock = TestClock()
        val rt = testRuntime(clock = clock) { retention = RetentionPolicy.MaxCount(3) }
        val client = mockClient(rt) { respond("ok") }
        repeat(5) {
            client.get("https://api.example.com/$it")
            clock.advance(1_000)
        }

        rt.awaitIdle()
        rt.applyRetention()

        assertEquals(listOf("/4", "/3", "/2"), rt.records().map { it.path })
    }

    @Test
    fun maxAgeDropsOldCalls() = runTest {
        val clock = TestClock()
        val rt = testRuntime(clock = clock) { retention = RetentionPolicy.MaxAge(1.hours) }
        val client = mockClient(rt) { respond("ok") }
        client.get("https://api.example.com/old")
        clock.advance(2.hours.inWholeMilliseconds)
        client.get("https://api.example.com/new")

        rt.awaitIdle()
        rt.applyRetention()

        assertEquals(listOf("/new"), rt.records().map { it.path })
    }

    @Test
    fun maxSizeKeepsNewestWithinBudget() = runTest {
        val clock = TestClock()
        val rt = testRuntime(clock = clock) { retention = RetentionPolicy.MaxSize(250) }
        val client = mockClient(rt) { respond("x".repeat(100)) }
        repeat(4) {
            client.get("https://api.example.com/$it")
            clock.advance(1_000)
        }

        rt.awaitIdle()
        rt.applyRetention()

        assertEquals(listOf("/3", "/2"), rt.records().map { it.path })
    }

    @Test
    fun perSessionOverrideBeatsGlobal() = runTest {
        val clock = TestClock()
        val rt = testRuntime(clock = clock) {
            retention = RetentionPolicy.MaxCount(1)
            retentionFor("keep", RetentionPolicy.Forever)
        }
        val client = mockClient(rt) { respond("ok") }
        repeat(3) {
            client.get("https://api.example.com/keep$it") { kotlinspectSession("keep") }
            client.get("https://api.example.com/drop$it")
            clock.advance(1_000)
        }

        rt.awaitIdle()
        rt.applyRetention()

        val keep = rt.database.sessions().byName("keep")!!
        assertEquals(3, rt.database.records().forSession(keep.id).size)
        assertEquals(listOf("/drop2"), rt.records().map { it.path })
    }

    @Test
    fun startSessionRetentionOverride() = runTest {
        val clock = TestClock()
        val rt = testRuntime(clock = clock) { retention = RetentionPolicy.Forever }
        val client = mockClient(rt) { respond("ok") }
        rt.startSession("short", emptySet(), emptyMap(), RetentionPolicy.MaxAge(10.minutes))
        client.get("https://api.example.com/a")
        clock.advance(1.hours.inWholeMilliseconds)
        client.get("https://api.example.com/b")

        rt.awaitIdle()
        rt.applyRetention()

        assertEquals(listOf("/b"), rt.records().map { it.path })
    }

    @Test
    fun inMemorySessionIsDiscardedOnNextLaunch() = runTest {
        val db = inMemoryDatabase()
        val first = testRuntime(db) { retentionFor("scratch", RetentionPolicy.InMemoryOnly) }
        val client = mockClient(first) { respond("ok") }
        client.get("https://api.example.com/a") { kotlinspectSession("scratch") }
        first.awaitIdle()
        assertTrue(db.sessions().byName("scratch") != null)
        first.close()

        val second = testRuntime(db) { retentionFor("scratch", RetentionPolicy.InMemoryOnly) }
        second.awaitIdle()

        assertNull(db.sessions().byName("scratch"))
    }

    @Test
    fun pendingCallsAreNeverRemoved() = runTest {
        val rt = testRuntime { retention = RetentionPolicy.MaxCount(0) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = mockClient(rt) {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val call = async { client.get("https://api.example.com/hang") }
        entered.await()

        rt.awaitIdle()
        rt.applyRetention()

        assertEquals(1, rt.records().size)
        call.cancel()
    }
}
