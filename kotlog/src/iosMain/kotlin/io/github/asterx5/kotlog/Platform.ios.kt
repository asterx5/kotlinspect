package io.github.asterx5.kotlog

import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

// Kotlin/Native has no ServiceLoader; Kotlinspect registers its sink at startup instead.
internal actual fun discoverSinks(): List<KtLogSink> = emptyList()

internal actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** NSLog, so entries show in Xcode and in the device's Console app. */
internal actual fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?) {
    val text = "${level.letter}/$tag: ${consoleText(message, layer)}" + (throwable?.let { "\n" + it.stackTraceToString() } ?: "")
    NSLog("%@", text)
}
