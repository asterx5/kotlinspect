package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.Kotlinspect
import io.github.asterx5.kotlog.KotlogEntry
import io.github.asterx5.kotlog.KotlogSink

/**
 * Receives every Kotlog entry and stores it for the inspector. Found by Kotlog through ServiceLoader
 * on Android and the JVM, and registered at startup on iOS. Does nothing when Kotlinspect is off.
 */
internal class KotlinspectLogSink : KotlogSink {
    override fun record(entry: KotlogEntry) {
        Kotlinspect.runtimeOrNull()?.log(entry)
    }
}
