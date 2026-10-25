package io.github.asterx5.kotlog

import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

// Kotlin/Native has no ServiceLoader; Kotlinspect registers its sink at startup instead.
internal actual fun discoverSinks(): List<KotlogSink> = emptyList()

internal actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** NSLog, so entries show in Xcode and in the device's Console app. */
internal actual fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?) {
    val text = "${level.letter}/$tag: ${consoleText(message, layer)}" + (throwable?.let { "\n" + it.stackTraceToString() } ?: "")
    // The text goes in as the format itself, with % escaped. Kotlin/Native bridges a String to
    // NSString only for the fixed format parameter: passed as a C vararg for "%@" it is not an
    // object, and NSLog crashes reading it.
    NSLog(text.replace("%", "%%"))
}
