package io.github.asterx5.kotlog

import android.util.Log
import java.util.ServiceLoader

internal actual fun discoverSinks(): List<KotlogSink> =
    ServiceLoader.load(KotlogSink::class.java, KotlogSink::class.java.classLoader).toList()

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?) {
    val priority = when (level) {
        LogLevel.Verbose -> Log.VERBOSE
        LogLevel.Debug -> Log.DEBUG
        LogLevel.Info -> Log.INFO
        LogLevel.Warn -> Log.WARN
        LogLevel.Error -> Log.ERROR
        LogLevel.Assert -> Log.ASSERT
    }
    val text = consoleText(message, layer) + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: "")
    Log.println(priority, tag, text)
}
