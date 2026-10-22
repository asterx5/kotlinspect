package io.github.asterx5.kotlinspect

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.ResolvedConfig
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers

internal fun inMemoryDatabase(): KotlinspectDatabase =
    Room.inMemoryDatabaseBuilder<KotlinspectDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** Mutable clock so retention tests can move time. */
internal class TestClock(var now: Long = 1_700_000_000_000L) {
    fun advance(ms: Long) {
        now += ms
    }
}

internal fun testRuntime(
    database: KotlinspectDatabase = inMemoryDatabase(),
    clock: TestClock = TestClock(),
    configure: KotlinspectConfigBuilder.() -> Unit = {},
): KotlinspectRuntime {
    val config = ResolvedConfig.from(KotlinspectConfigBuilder().apply(configure))
    return KotlinspectRuntime(
        config = config,
        databaseFactory = { database },
        clock = { clock.now },
        retentionDelayMillis = Long.MAX_VALUE / 4, // retention is triggered explicitly in tests
    ).also { it.start() }
}

internal fun mockClient(
    runtime: KotlinspectRuntime?,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
    pluginConfig: KotlinspectPluginConfig.() -> Unit = {},
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpClient = HttpClient(MockEngine(handler)) {
    install(KotlinspectPlugin) {
        runtimeProvider = { runtime }
        pluginConfig()
    }
    clientConfig()
}

internal suspend fun KotlinspectRuntime.records(): List<RecordEntity> {
    awaitIdle()
    val session = currentSessionId.value ?: return emptyList()
    return database.records().forSession(session)
}

internal suspend fun KotlinspectRuntime.single(): RecordEntity = records().single()

internal suspend fun KotlinspectRuntime.allRecords(): List<RecordEntity> {
    awaitIdle()
    return database.sessions().all().flatMap { database.records().forSession(it.id) }
}

/** Waits in real time (not the test's virtual time) until no call is pending. */
internal suspend fun KotlinspectRuntime.awaitFinished() = kotlinx.coroutines.withContext(Dispatchers.Default) {
    kotlinx.coroutines.withTimeout(5_000) {
        while (records().any { it.state == CallState.Pending.name }) kotlinx.coroutines.delay(10)
    }
}
