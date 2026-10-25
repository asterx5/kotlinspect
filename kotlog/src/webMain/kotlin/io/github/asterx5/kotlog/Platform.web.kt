package io.github.asterx5.kotlog

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal actual fun discoverSinks(): List<KotlogSink> = emptyList()

@OptIn(ExperimentalTime::class)
internal actual fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** The browser or Node console. */
internal actual fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?) {
    println("${level.letter}/$tag: ${consoleText(message, layer)}" + (throwable?.let { "\n" + it.stackTraceToString() } ?: ""))
}
