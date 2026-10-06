package io.github.asterx5.kotlinspect

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real [Kotlinspect] entry point in a simulated release build. */
class DisabledStateTest {
    private val appName = "kotlinspect-test-${System.nanoTime()}"
    private val dbDir = File(System.getProperty("user.home"), ".kotlinspect/$appName")

    @BeforeTest
    fun simulateRelease() {
        System.setProperty("kotlinspect.debug", "false")
        Kotlinspect.installRuntime(null)
        Kotlinspect.configure {
            enabled = true
            allowInRelease = false
            desktopAppName = appName
        }
    }

    @AfterTest
    fun reset() {
        System.clearProperty("kotlinspect.debug")
        Kotlinspect.installRuntime(null)
        dbDir.deleteRecursively()
    }

    @Test
    fun releaseBuildIsDisabledByDefault() {
        assertFalse(Kotlinspect.isEnabled)
    }

    @Test
    fun releaseBuildCanOptIn() {
        Kotlinspect.configure { allowInRelease = true }
        assertTrue(Kotlinspect.isEnabled)
        Kotlinspect.configure { allowInRelease = false }
    }

    @Test
    fun masterSwitchDisablesInDebug() {
        System.setProperty("kotlinspect.debug", "true")
        Kotlinspect.configure { enabled = false }
        assertFalse(Kotlinspect.isEnabled)
        Kotlinspect.configure { enabled = true }
    }

    @Test
    fun callsPassThroughAndNothingIsStored() = runTest {
        val client = HttpClient(MockEngine { respond("hello") }) { install(KotlinspectPlugin) }

        assertEquals("hello", client.get("https://api.example.com/").bodyAsText())

        assertNull(Kotlinspect.runtimeOrNull())
        assertFalse(dbDir.exists(), "no database directory may be created when disabled")
    }

    @Test
    fun apiIsSafeAndEmptyWhenDisabled() = runTest {
        assertNull(Kotlinspect.startSession("x"))
        Kotlinspect.switchSession("x")
        Kotlinspect.clearAll()
        Kotlinspect.addSessionTags("t")
        Kotlinspect.openInspector()

        assertEquals(0, Kotlinspect.callCount.first())
        assertEquals(0, Kotlinspect.inFlightCount.first())
        assertEquals(0, Kotlinspect.errorCount.first())
        assertNull(Kotlinspect.latestCall.first())
        assertEquals(emptyList(), Kotlinspect.calls.first())
        assertEquals(emptyList(), Kotlinspect.sessions.first())
        assertNull(Kotlinspect.currentSession.first())
        assertFalse(dbDir.exists())
    }
}
