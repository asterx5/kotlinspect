package io.github.asterx5.kotlinspect.internal.har

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** File access shared by the JVM and Android; each platform picks the export folder. */
internal object JvmHarFiles {
    fun exportsDirectory(base: File?): String? {
        val dir = File(base ?: return null, "kotlinspect/exports")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir.absolutePath
    }

    fun openSink(path: String): HarSink {
        File(path).absoluteFile.parentFile?.mkdirs()
        val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(path), Charsets.UTF_8), 64 * 1024)
        return object : HarSink {
            override fun write(text: String) = writer.write(text)
            override fun close() = writer.close()
        }
    }

    fun size(path: String): Long = File(path).length()

    fun delete(path: String) {
        File(path).delete()
    }

    fun deleteOlderThan(directory: String, cutoffMillis: Long) {
        File(directory).listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".har") && file.lastModified() < cutoffMillis) file.delete()
        }
    }
}
