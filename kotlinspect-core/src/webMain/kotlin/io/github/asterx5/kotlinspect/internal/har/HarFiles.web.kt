package io.github.asterx5.kotlinspect.internal.har

/** No files on web; exports fail before reaching these. */
internal actual object HarFiles {
    actual fun exportsDirectory(): String? = null
    actual fun openSink(path: String): HarSink = throw UnsupportedOperationException("No files on web")
    actual fun size(path: String): Long = 0
    actual fun delete(path: String) = Unit
    actual fun deleteOlderThan(directory: String, cutoffMillis: Long) = Unit
}
