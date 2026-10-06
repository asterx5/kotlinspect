package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.Codecs
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun newPerLaunchCreatesASessionPerRuntime() = runTest {
        val db = inMemoryDatabase()
        val first = testRuntime(db)
        first.awaitIdle()
        val firstId = first.currentSessionId.value
        first.close()

        val second = testRuntime(db)
        second.awaitIdle()

        assertNotEquals(firstId, second.currentSessionId.value)
        assertTrue(db.sessions().get(second.currentSessionId.value!!)!!.name.startsWith("Launch "))
    }

    @Test
    fun fixedPolicyReusesTheNamedSession() = runTest {
        val db = inMemoryDatabase()
        val first = testRuntime(db) { sessionPolicy = SessionPolicy.Fixed("qa") }
        first.awaitIdle()
        val id = first.currentSessionId.value
        first.close()

        val second = testRuntime(db) { sessionPolicy = SessionPolicy.Fixed("qa") }
        second.awaitIdle()

        assertEquals(id, second.currentSessionId.value)
        assertEquals("qa", db.sessions().get(id!!)!!.name)
    }

    @Test
    fun manualPolicyRestoresTheLastActiveSession() = runTest {
        val db = inMemoryDatabase()
        val first = testRuntime(db) { sessionPolicy = SessionPolicy.Manual }
        val started = first.startSession("bug-123", setOf("repro"), mapOf("ticket" to "123"), null)
        first.awaitIdle()
        first.close()

        val second = testRuntime(db) { sessionPolicy = SessionPolicy.Manual }
        second.awaitIdle()

        assertEquals(started, second.currentSessionId.value)
        val session = db.sessions().get(started)!!
        assertEquals(setOf("repro"), Codecs.decodeTags(session.tags))
        assertEquals(mapOf("ticket" to "123"), Codecs.decodeMetadata(session.metadata))
    }

    @Test
    fun perRequestSessionTag() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("ok") }

        client.get("https://api.example.com/a") { kotlinspectSession("checkout") }
        client.get("https://api.example.com/b")

        rt.awaitIdle()
        val checkout = rt.database.sessions().byName("checkout")!!
        assertEquals(listOf("/a"), rt.database.records().forSession(checkout.id).map { it.path })
        assertEquals(listOf("/b"), rt.records().map { it.path })
    }

    @Test
    fun coroutineScopedSession() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("ok") }

        withKotlinspectSession("onboarding") {
            client.get("https://api.example.com/1")
            client.get("https://api.example.com/2")
        }
        withContext(KotlinspectSessionContext("other")) { client.get("https://api.example.com/3") }

        rt.awaitIdle()
        val onboarding = rt.database.sessions().byName("onboarding")!!
        assertEquals(2, rt.database.records().forSession(onboarding.id).size)
        val other = rt.database.sessions().byName("other")!!
        assertEquals(1, rt.database.records().forSession(other.id).size)
        assertTrue(rt.records().isEmpty())
    }

    @Test
    fun clientWideSession() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt, pluginConfig = { session = "payments-sdk" }) { respond("ok") }

        client.get("https://api.example.com/charge")

        rt.awaitIdle()
        val s = rt.database.sessions().byName("payments-sdk")!!
        assertEquals(1, rt.database.records().forSession(s.id).size)
    }

    @Test
    fun startAndSwitchSessions() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("ok") }
        rt.awaitIdle()
        val launch = rt.currentSessionId.value!!

        val second = rt.startSession("second", emptySet(), emptyMap(), null)
        client.get("https://api.example.com/in-second")
        rt.switchSession(launch)
        client.get("https://api.example.com/in-launch")

        rt.awaitIdle()
        assertEquals(listOf("/in-second"), rt.database.records().forSession(second).map { it.path })
        assertEquals(listOf("/in-launch"), rt.database.records().forSession(launch).map { it.path })
    }

    @Test
    fun clearAndDeleteSessions() = runTest {
        val rt = testRuntime()
        val client = mockClient(rt) { respond("ok") }
        client.get("https://api.example.com/x") { kotlinspectSession("temp") }
        client.get("https://api.example.com/y")
        rt.awaitIdle()
        val temp = rt.database.sessions().byName("temp")!!.id

        rt.clearSession(rt.currentSessionId.value!!)
        rt.deleteSession(temp)

        rt.awaitIdle()
        assertTrue(rt.records().isEmpty())
        assertEquals(null, rt.database.sessions().get(temp))
    }

    @Test
    fun pendingCallsFromAPreviousLaunchAreMarkedInterrupted() = runTest {
        val db = inMemoryDatabase()
        val first = testRuntime(db) { sessionPolicy = SessionPolicy.Fixed("s") }
        val entered = CompletableDeferred<Unit>()
        val client = mockClient(first) {
            entered.complete(Unit)
            awaitCancellation()
        }
        val call = async { client.get("https://api.example.com/hang") }
        entered.await()
        first.awaitIdle()
        first.close() // simulates the app being killed: the record is still Pending
        call.cancel()

        val second = testRuntime(db) { sessionPolicy = SessionPolicy.Fixed("s") }
        val r = second.single()
        assertEquals(CallState.Cancelled.name, r.state)
        assertTrue(r.error!!.startsWith("Interrupted"))
    }
}
