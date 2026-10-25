package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.internal.db.KotlinspectStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Web is a no-op in this version: Kotlinspect is never enabled, so nothing is captured, stored or
 * shown, and every API returns its disabled value. The Ktor plugin passes calls straight through.
 */
internal actual object Platform {
    actual val supportsStorage: Boolean = false

    private var noticeShown = false

    /** Never ready, so never enabled. The first check logs one console notice. */
    actual val isReady: Boolean
        get() {
            if (!noticeShown) {
                noticeShown = true
                webConsoleInfo("Kotlinspect: web targets are a no-op in this version.")
            }
            return false
        }

    actual val isDebugBuild: Boolean = false

    actual fun onRuntimeStarted(host: KotlinspectHost) = Unit
    actual fun onConfigChanged(host: KotlinspectHost) = Unit
    actual fun openInspector(host: KotlinspectHost, sessionId: String?) = Unit
    actual fun shareFile(path: String) = Unit
}

// Never called: the runtime only starts once Kotlinspect is enabled, which it never is on web.
internal actual fun createStore(config: ResolvedConfig): KotlinspectStore =
    throw UnsupportedOperationException("Kotlinspect has no storage on web")

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

/** console.info, which Kotlin's println (console.log) does not reach. */
internal expect fun webConsoleInfo(message: String)
