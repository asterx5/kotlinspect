package io.github.asterx5.kotlog

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Log levels, the same as Android's. */
public enum class LogLevel(public val letter: String) {
    Verbose("V"),
    Debug("D"),
    Info("I"),
    Warn("W"),
    Error("E"),
    Assert("A"),
}

/**
 * Common architecture layers, for filtering logs in the inspector. Pass [value]; any other string
 * works as a layer too.
 */
public enum class LogLayer(public val value: String) {
    Datasource("datasource"),
    Repository("repository"),
    Usecase("usecase"),
    ViewModel("viewmodel"),
    UI("ui"),
}

/**
 * Logs with a tag, level, message and optional layer. Every entry is printed the platform way
 * (Android Log, NSLog on iOS, the console on desktop) and, when Kotlinspect is in the app,
 * also shown in its inspector. Only what goes through KtLog is captured, not the rest of the console.
 *
 * ```
 * KtLog.d(TAG, "Loaded ${items.size} items", LogLayer.Datasource.value)
 * KtLog.e(TAG, "Request failed", "repository", error)
 * ```
 *
 * From Swift: `KtLog.shared.d(tag: tag, message: "Loaded", layer: "datasource")`.
 */
public object KtLog {
    /** Prints entries to the platform log. Turn off to only send them to Kotlinspect. */
    @Volatile
    public var consoleEnabled: Boolean = true

    public fun v(tag: String, message: String, layer: String? = null) {
        log(LogLevel.Verbose, tag, message, layer, null)
    }

    public fun d(tag: String, message: String, layer: String? = null) {
        log(LogLevel.Debug, tag, message, layer, null)
    }

    public fun i(tag: String, message: String, layer: String? = null) {
        log(LogLevel.Info, tag, message, layer, null)
    }

    public fun w(tag: String, message: String, layer: String? = null, throwable: Throwable? = null) {
        log(LogLevel.Warn, tag, message, layer, throwable)
    }

    public fun e(tag: String, message: String, layer: String? = null, throwable: Throwable? = null) {
        log(LogLevel.Error, tag, message, layer, throwable)
    }

    /** "What a terrible failure": Android's Assert level. */
    public fun wtf(tag: String, message: String, layer: String? = null, throwable: Throwable? = null) {
        log(LogLevel.Assert, tag, message, layer, throwable)
    }

    /** Logs at [level]. The other functions are shortcuts for this one. */
    public fun log(level: LogLevel, tag: String, message: String, layer: String? = null, throwable: Throwable? = null) {
        if (consoleEnabled) {
            runCatching { printToPlatform(level, tag, message, layer, throwable) }
        }
        val sinks = KtLogSinks.all
        if (sinks.isEmpty()) return
        val entry = KtLogEntry(
            timestampMillis = currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            layer = layer,
            throwable = throwable?.stackTraceToString(),
        )
        // A failing sink must never affect the caller.
        sinks.forEach { runCatching { it.record(entry) } }
    }
}

/** One log entry as handed to sinks. */
@InternalKotlogApi
public class KtLogEntry(
    public val timestampMillis: Long,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val layer: String?,
    /** Stack trace of the attached throwable, if any. */
    public val throwable: String?,
)

/** Receives every entry. Kotlinspect provides one to show logs in its inspector. */
@InternalKotlogApi
public fun interface KtLogSink {
    public fun record(entry: KtLogEntry)
}

/**
 * Where sinks come from: found with java.util.ServiceLoader on Android and the JVM, and registered
 * at startup on iOS (Kotlin/Native has no ServiceLoader).
 */
@InternalKotlogApi
@OptIn(ExperimentalAtomicApi::class)
public object KtLogSinks {
    private val registered = AtomicReference<List<KtLogSink>>(emptyList())
    private val discovered: List<KtLogSink> by lazy { runCatching { discoverSinks() }.getOrDefault(emptyList()) }

    public fun register(sink: KtLogSink) {
        while (true) {
            val current = registered.load()
            if (registered.compareAndSet(current, current + sink)) return
        }
    }

    internal val all: List<KtLogSink>
        get() {
            val extra = registered.load()
            return if (extra.isEmpty()) discovered else discovered + extra
        }
}

internal expect fun discoverSinks(): List<KtLogSink>

internal expect fun currentTimeMillis(): Long

internal expect fun printToPlatform(level: LogLevel, tag: String, message: String, layer: String?, throwable: Throwable?)

/** "[datasource] message", the text printed for an entry. */
internal fun consoleText(message: String, layer: String?): String = if (layer.isNullOrEmpty()) message else "[$layer] $message"
