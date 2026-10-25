package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.internal.db.KotlinspectStore

/** Per-platform integration: build type, storage location and UI hosting. */
internal expect object Platform {
    /** False until the platform has what it needs, such as the Android application context. */
    val isReady: Boolean

    /** Debuggable APK on Android, debug framework on iOS, not a packaged app on desktop. */
    val isDebugBuild: Boolean

    /** Shows the automatic bubble and toast. Called once, when capture starts. */
    fun onRuntimeStarted(host: KotlinspectHost)

    /** Called when [io.github.asterx5.kotlinspect.Kotlinspect.configure] changes a running config. */
    fun onConfigChanged(host: KotlinspectHost)

    /** Opens the inspector on [sessionId], or on the current session when null. */
    fun openInspector(host: KotlinspectHost, sessionId: String?)

    /** Offers an exported file through the system share sheet, where there is one. */
    fun shareFile(path: String)
}

/** Opens Kotlinspect's storage. Only called once capture is enabled. */
internal expect fun createStore(config: ResolvedConfig): KotlinspectStore
