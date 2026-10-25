package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.db.RecordSummary
import io.github.asterx5.kotlinspect.internal.uimodel.TimingPhaseKind
import io.github.asterx5.kotlinspect.internal.uimodel.hasMixedSources
import io.github.asterx5.kotlinspect.internal.uimodel.sourceLabel
import io.github.asterx5.kotlinspect.internal.uimodel.timingPhases
import io.github.asterx5.kotlinspect.internal.uimodel.title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalKotlinspectApi::class)
class SourcesAndTimingsTest {
    private fun record(
        requestSentAt: Long? = null,
        responseStartedAt: Long? = null,
        completedAt: Long? = null,
        blocked: Long? = null,
        dns: Long? = null,
        connect: Long? = null,
        ssl: Long? = null,
        send: Long? = null,
        wait: Long? = null,
        receive: Long? = null,
    ) = RecordEntity(
        id = "r", sessionId = "s", source = "ktor", kind = "http.call", state = "Complete", startedAt = 1_000,
        requestSentAt = requestSentAt, responseStartedAt = responseStartedAt, completedAt = completedAt,
        timingBlocked = blocked, timingDns = dns, timingConnect = connect, timingSsl = ssl,
        timingSend = send, timingWait = wait, timingReceive = receive,
    )

    private fun List<io.github.asterx5.kotlinspect.internal.uimodel.TimingPhase>.pairs() = map { it.kind to it.millis }

    @Test
    fun ktorRecordsKeepTheirTimestampPhases() {
        val phases = timingPhases(record(requestSentAt = 1_010, responseStartedAt = 1_060, completedAt = 1_065))
        assertEquals(listOf(TimingPhaseKind.Send to 10L, TimingPhaseKind.Wait to 50L, TimingPhaseKind.Receive to 5L), phases.pairs())
        assertEquals(listOf(TimingPhaseKind.Send to 10L), timingPhases(record(requestSentAt = 1_010)).pairs())
        assertTrue(timingPhases(record()).isEmpty())
    }

    @Test
    fun measuredPhasesWinAndTlsIsNotCountedTwice() {
        val phases = timingPhases(record(blocked = 1, dns = 2, connect = 9, ssl = 4, send = 1, wait = 30, receive = 3))
        assertEquals(
            listOf(
                TimingPhaseKind.Blocked to 1L, TimingPhaseKind.Dns to 2L, TimingPhaseKind.Connect to 5L, TimingPhaseKind.Ssl to 4L,
                TimingPhaseKind.Send to 1L, TimingPhaseKind.Wait to 30L, TimingPhaseKind.Receive to 3L,
            ),
            phases.pairs(),
        )
    }

    @Test
    fun mergedRecordsSubtractMeasuredSetupFromTheTimestampSend() {
        // Ktor's send runs from start to request sent; OkHttp measured 12ms of it as DNS and connect.
        val phases = timingPhases(record(requestSentAt = 1_020, responseStartedAt = 1_070, completedAt = 1_080, dns = 3, connect = 9))
        assertEquals(
            listOf(
                TimingPhaseKind.Dns to 3L, TimingPhaseKind.Connect to 9L,
                TimingPhaseKind.Send to 8L, TimingPhaseKind.Wait to 50L, TimingPhaseKind.Receive to 10L,
            ),
            phases.pairs(),
        )
    }

    @Test
    fun sourceLabelsAndBadges() {
        assertEquals("Ktor", sourceLabel("ktor"))
        assertEquals("OkHttp", sourceLabel("okhttp"))
        assertEquals("URLSession", sourceLabel("urlsession"))
        assertEquals("Alamofire", sourceLabel("alamofire"))
        assertEquals("my-client", sourceLabel("my-client"))

        fun summary(source: String, op: String? = null) = RecordSummary(
            "id", "s", "Complete", 0, null, "GET", "https://a/x", "a", "/x", 200, null, null, null, source, op,
        )
        assertFalse(hasMixedSources(listOf(summary("ktor"), summary("ktor"))))
        assertTrue(hasMixedSources(listOf(summary("ktor"), summary("okhttp"))))
        assertFalse(hasMixedSources(emptyList()))
        assertEquals("/x", summary("okhttp").title)
        assertEquals("UserApi.getUser", summary("okhttp", "UserApi.getUser").title)
    }
}
