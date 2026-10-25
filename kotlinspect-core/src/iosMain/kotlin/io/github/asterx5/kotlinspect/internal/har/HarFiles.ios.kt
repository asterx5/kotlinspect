@file:OptIn(ExperimentalForeignApi::class)

package io.github.asterx5.kotlinspect.internal.har

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.timeIntervalSince1970
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal actual object HarFiles {
    /** NSTemporaryDirectory()/kotlinspect/exports. */
    actual fun exportsDirectory(): String? {
        val dir = NSTemporaryDirectory().trimEnd('/') + "/kotlinspect/exports"
        val ok = NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return if (ok) dir else null
    }

    actual fun openSink(path: String): HarSink {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: error("Cannot open $path")
        return object : HarSink {
            override fun write(text: String) {
                val bytes = text.encodeToByteArray()
                if (bytes.isEmpty()) return
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
                check(written == bytes.size.toULong()) { "Could not write $path" }
            }

            override fun close() {
                fclose(file)
            }
        }
    }

    actual fun size(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
    }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual fun deleteOlderThan(directory: String, cutoffMillis: Long) {
        val manager = NSFileManager.defaultManager
        val names = manager.contentsOfDirectoryAtPath(directory, error = null) ?: return
        for (name in names) {
            val file = "$directory/${name as? String ?: continue}"
            if (!file.endsWith(".har")) continue
            val modified = manager.attributesOfItemAtPath(file, error = null)?.get(NSFileModificationDate) as? NSDate ?: continue
            if ((modified.timeIntervalSince1970 * 1000).toLong() < cutoffMillis) manager.removeItemAtPath(file, error = null)
        }
    }
}
