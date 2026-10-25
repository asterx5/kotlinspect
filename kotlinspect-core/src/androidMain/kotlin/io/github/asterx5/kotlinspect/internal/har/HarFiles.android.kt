package io.github.asterx5.kotlinspect.internal.har

import io.github.asterx5.kotlinspect.internal.Platform

internal actual object HarFiles {
    /** cacheDir/kotlinspect/exports, the only folder KotlinspectFileProvider serves. */
    actual fun exportsDirectory(): String? = JvmHarFiles.exportsDirectory(Platform.context?.cacheDir)
    actual fun openSink(path: String): HarSink = JvmHarFiles.openSink(path)
    actual fun size(path: String): Long = JvmHarFiles.size(path)
    actual fun delete(path: String) = JvmHarFiles.delete(path)
    actual fun deleteOlderThan(directory: String, cutoffMillis: Long) = JvmHarFiles.deleteOlderThan(directory, cutoffMillis)
}
