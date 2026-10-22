package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behaviour behind KotlinspectOverlay and the native overlay view: session scoping and suppression. */
class OverlayModelTest {

    @Test
    fun bubbleCountsCanBeScopedToOneSession() = runTest {
        val rt = testRuntime()
        val host = KotlinspectHost(rt)
        val client = mockClient(rt) { request ->
            if (request.url.encodedPath == "/fail") respond("no", HttpStatusCode.InternalServerError) else respond("ok")
        }
        client.get("https://api.example.com/a") { kotlinspectSession("checkout") }
        client.get("https://api.example.com/fail") { kotlinspectSession("checkout") }
        client.get("https://api.example.com/b")
        rt.awaitIdle()
        val checkout = rt.database.sessions().byName("checkout")!!.id

        val scoped = host.bubbleCounts(checkout).first { it.total == 2 }
        assertEquals(1, scoped.errors)
        val current = host.bubbleCounts(null).first { it.total == 1 }
        assertEquals(0, current.errors)
    }

    @Test
    fun scopedToastsOnlyShowThatSession() = runTest {
        val rt = testRuntime { toast { batchWindowMillis = 10 } }
        val host = KotlinspectHost(rt)
        rt.awaitIdle()
        val checkout = rt.startSession("checkout", emptySet(), emptyMap(), null)
        rt.awaitIdle()
        val scoped = host.toasts(checkout)
        val client = mockClient(rt) { respond("ok") }

        rt.switchSession(rt.currentSessionId.value!!) // stay on checkout as current
        client.get("https://api.example.com/other") { kotlinspectSession("elsewhere") }
        client.get("https://api.example.com/mine") { kotlinspectSession("checkout") }

        val toast = withContext(Dispatchers.Default) {
            withTimeout(5_000) { scoped.current.first { it != null } }
        }!!
        assertTrue(toast.title.contains("/mine"), toast.title)
        scoped.close()
    }

    @Test
    fun embeddedOverlayHidesTheAutomaticOne() = runTest {
        val rt = testRuntime()
        val host = KotlinspectHost(rt)
        assertTrue(host.showAutomaticOverlay)

        val detachFirst = host.attachEmbeddedOverlay()
        val detachSecond = host.attachEmbeddedOverlay()
        assertFalse(host.showAutomaticOverlay)
        detachFirst()
        detachFirst() // detaching twice must not count twice
        assertFalse(host.showAutomaticOverlay)
        detachSecond()
        assertTrue(host.showAutomaticOverlay)
    }

    @Test
    fun automaticOverlayCanBeTurnedOff() = runTest {
        val rt = testRuntime { bubble { automatic = false } }
        assertFalse(KotlinspectHost(rt).showAutomaticOverlay)
    }
}
