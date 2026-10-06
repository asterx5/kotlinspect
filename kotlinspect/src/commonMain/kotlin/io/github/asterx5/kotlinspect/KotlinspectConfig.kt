package io.github.asterx5.kotlinspect

import kotlin.time.Duration

/**
 * How captured calls are grouped into sessions when no session is chosen explicitly.
 */
public sealed interface SessionPolicy {
    /** A new session is started every time the app launches. This is the default. */
    public data object NewPerLaunch : SessionPolicy

    /** Every call goes to the session with this [name], across launches. */
    public data class Fixed(public val name: String) : SessionPolicy

    /**
     * Calls go to whichever session was last started or switched to with [Kotlinspect],
     * across launches. A session named "Default" is created if none exists yet.
     */
    public data object Manual : SessionPolicy
}

/**
 * How long captured calls are kept. Set globally with [KotlinspectConfigBuilder.retention] and
 * per session with [KotlinspectConfigBuilder.retentionFor] or [Kotlinspect.startSession].
 */
public sealed interface RetentionPolicy {
    /** Keep everything until cleared. */
    public data object Forever : RetentionPolicy

    /** Delete calls older than [maxAge]. */
    public data class MaxAge(public val maxAge: Duration) : RetentionPolicy

    /** Keep only the newest [count] calls. */
    public data class MaxCount(public val count: Int) : RetentionPolicy

    /** Keep the newest calls whose stored bodies add up to at most [bytes]. */
    public data class MaxSize(public val bytes: Long) : RetentionPolicy

    /**
     * Never write to disk. Globally, this uses an in-memory database and no file is created.
     * For a single session, its calls are discarded on the next launch.
     */
    public data object InMemoryOnly : RetentionPolicy
}

/** Rewrites or removes header values before they are stored. */
public fun interface HeaderRedactor {
    /** Returns the value to store, or the original [value] to keep it unchanged. */
    public fun redact(name: String, value: String): String
}

/** Rewrites request and response bodies before they are stored. */
public fun interface BodyRedactor {
    /** Returns the body text to store. [isRequest] is false for response bodies. */
    public fun redact(contentType: String?, body: String, isRequest: Boolean): String
}

/** Which calls produce a toast. */
public fun interface ToastFilter {
    public fun shouldShow(call: KotlinspectCall): Boolean

    public companion object {
        public val All: ToastFilter = ToastFilter { true }
        public val ErrorsOnly: ToastFilter = ToastFilter { it.isError }
    }
}

public class BubbleOptions internal constructor() {
    /** Shows the floating bubble over the app. */
    public var enabled: Boolean = true
}

public class ToastOptions internal constructor() {
    /** Shows a slide-in summary of each completed call. */
    public var enabled: Boolean = true

    /** Which calls produce a toast. */
    public var filter: ToastFilter = ToastFilter.All

    /** How long a toast stays visible, in milliseconds. */
    public var durationMillis: Long = 2_500

    /** Calls completing within this window, in milliseconds, are batched into one toast. */
    public var batchWindowMillis: Long = 400
}

@DslMarker
internal annotation class KotlinspectDsl

/** Builder passed to [Kotlinspect.configure]. */
@KotlinspectDsl
public class KotlinspectConfigBuilder internal constructor() {
    /** Master switch. When false nothing is captured, stored or shown. */
    public var enabled: Boolean = true

    /**
     * Kotlinspect is off in release builds unless this is true. Release means a non-debuggable
     * APK on Android, a release framework on iOS, and a packaged app (jpackage) on desktop.
     */
    public var allowInRelease: Boolean = false

    public var sessionPolicy: SessionPolicy = SessionPolicy.NewPerLaunch

    /** Global retention. Sessions without an override follow this policy. */
    public var retention: RetentionPolicy = RetentionPolicy.MaxCount(1_000)

    /** Bodies larger than this are truncated before storage. */
    public var maxBodyBytes: Long = 256L * 1024

    /** Desktop only: folder name under `~/.kotlinspect` holding this app's database. */
    public var desktopAppName: String? = null

    internal val redactedHeaders: MutableSet<String> =
        mutableSetOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")
    internal val redactedQueryParameters: MutableSet<String> = mutableSetOf()
    internal val headerRedactors: MutableList<HeaderRedactor> = mutableListOf()
    internal val bodyRedactors: MutableList<BodyRedactor> = mutableListOf()
    internal val sessionRetention: MutableMap<String, RetentionPolicy> = mutableMapOf()

    public val bubble: BubbleOptions = BubbleOptions()
    public val toast: ToastOptions = ToastOptions()

    /** Replaces the values of these headers (case-insensitive) with a redaction marker. */
    public fun redactHeaders(vararg names: String) {
        redactedHeaders += names
    }

    /** Stops redacting headers that are redacted by default, such as Authorization. */
    public fun unredactHeaders(vararg names: String) {
        val lower = names.map { it.lowercase() }.toSet()
        redactedHeaders.removeAll { it.lowercase() in lower }
    }

    /** Replaces the values of these URL query parameters with a redaction marker. */
    public fun redactQueryParameters(vararg names: String) {
        redactedQueryParameters += names
    }

    public fun headerRedactor(redactor: HeaderRedactor) {
        headerRedactors += redactor
    }

    public fun bodyRedactor(redactor: BodyRedactor) {
        bodyRedactors += redactor
    }

    /** Overrides retention for the session named [sessionName]. */
    public fun retentionFor(sessionName: String, policy: RetentionPolicy) {
        sessionRetention[sessionName] = policy
    }

    public fun bubble(block: BubbleOptions.() -> Unit) {
        bubble.block()
    }

    public fun toast(block: ToastOptions.() -> Unit) {
        toast.block()
    }
}
