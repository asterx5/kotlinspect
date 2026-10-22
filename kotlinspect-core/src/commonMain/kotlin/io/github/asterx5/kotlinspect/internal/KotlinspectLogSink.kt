package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.Kotlinspect
import io.github.asterx5.kotlog.KtLogEntry
import io.github.asterx5.kotlog.KtLogSink

/**
 * Receives every KtLog entry and stores it for the inspector. Found by Kotlog through ServiceLoader
 * on Android and the JVM, and registered at startup on iOS. Does nothing when Kotlinspect is off.
 */
internal class KotlinspectLogSink : KtLogSink {
    override fun record(entry: KtLogEntry) {
        Kotlinspect.runtimeOrNull()?.log(entry)
    }
}
