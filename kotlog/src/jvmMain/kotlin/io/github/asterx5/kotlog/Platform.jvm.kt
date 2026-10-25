package io.github.asterx5.kotlog

import java.util.ServiceLoader

internal actual fun discoverSinks(): List<KotlogSink> =
    ServiceLoader.load(KotlogSink::class.java, KotlogSink::class.java.classLoader).toList()

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** "D/Tag: [layer] message", warnings and errors to stderr, like a desktop console logger. */
internal actual fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?) {
    val stream = if (level >= LogLevel.Warn) System.err else System.out
    stream.println("${level.letter}/$tag: ${consoleText(message, layer)}")
    throwable?.printStackTrace(stream)
}
