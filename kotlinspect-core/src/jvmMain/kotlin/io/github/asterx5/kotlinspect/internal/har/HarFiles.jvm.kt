package io.github.asterx5.kotlinspect.internal.har

import java.io.File

internal actual object HarFiles {
    /** The system temp folder; the desktop UI writes straight to the path the user picks instead. */
    actual fun exportsDirectory(): String? = JvmHarFiles.exportsDirectory(System.getProperty("java.io.tmpdir")?.let(::File))
    actual fun openSink(path: String): HarSink = JvmHarFiles.openSink(path)
    actual fun size(path: String): Long = JvmHarFiles.size(path)
    actual fun delete(path: String) = JvmHarFiles.delete(path)
    actual fun deleteOlderThan(directory: String, cutoffMillis: Long) = JvmHarFiles.deleteOlderThan(directory, cutoffMillis)
}
