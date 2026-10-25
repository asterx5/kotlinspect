package io.github.asterx5.kotlinspect.internal

import java.io.File
import java.util.ServiceLoader

internal actual object Platform {
    actual val supportsStorage: Boolean = true

    actual val isReady: Boolean = true

    /**
     * Desktop has no build type, so a packaged app (jpackage, as produced by Compose's
     * packageDistribution tasks) counts as release. `-Dkotlinspect.debug=true|false` overrides.
     */
    actual val isDebugBuild: Boolean
        get() = System.getProperty("kotlinspect.debug")?.toBooleanStrictOrNull()
            ?: (System.getProperty("jpackage.app-path") == null)

    /** UI modules on the classpath, such as the Compose artifact. Empty for core alone. */
    private val providers: List<KotlinspectUiProvider> by lazy {
        runCatching {
            ServiceLoader.load(KotlinspectUiProvider::class.java, KotlinspectUiProvider::class.java.classLoader).toList()
        }.getOrDefault(emptyList())
    }

    actual fun onRuntimeStarted(host: KotlinspectHost) {
        providers.forEach { runCatching { it.install(); it.onStarted(host) } }
    }

    // The Compose overlay reads the config live; nothing to install or remove.
    actual fun onConfigChanged(host: KotlinspectHost) = Unit

    actual fun openInspector(host: KotlinspectHost, sessionId: String?) {
        providers.firstOrNull()?.let { runCatching { it.openInspector(host, sessionId) } }
    }

    // Desktop has no share sheet: the inspector saves exports where the user chooses.
    actual fun shareFile(path: String) = Unit
}
