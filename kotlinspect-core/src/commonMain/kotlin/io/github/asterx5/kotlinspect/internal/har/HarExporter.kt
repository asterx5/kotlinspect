@file:OptIn(ExperimentalKotlinspectApi::class)

package io.github.asterx5.kotlinspect.internal.har

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.HarExportOptions
import io.github.asterx5.kotlinspect.HarExportResult
import io.github.asterx5.kotlinspect.internal.BuildInfo
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.localTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** Which calls an export contains. */
internal sealed interface HarSelection {
    class Session(val sessionId: String) : HarSelection
    class Records(val recordIds: List<String>) : HarSelection
}

/**
 * Writes sessions or single calls as HAR 1.2 files. Records are read from storage in pages and
 * written one by one, so memory use does not grow with the session. Every problem becomes a
 * [HarExportResult.Failure]; nothing is thrown to the caller.
 */
internal object HarExporter {
    const val PAGE_SIZE = 200
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    /** Pages read by the last export, for tests that check memory stays bounded. */
    @Volatile
    var pagesRead: Int = 0
        private set

    /**
     * Exports [selection]. [outputPath] writes to that file (the desktop save dialog's choice)
     * instead of the export folder. [onProgress] gets (written, total) as entries are written.
     */
    suspend fun export(
        runtime: KotlinspectRuntime?,
        selection: HarSelection,
        options: HarExportOptions,
        outputPath: String? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): HarExportResult {
        if (runtime == null) return HarExportResult.Failure("Kotlinspect is disabled")
        return withContext(Dispatchers.IO) {
            try {
                exportOrFail(runtime, selection, options, outputPath, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                HarExportResult.Failure("Export failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private suspend fun exportOrFail(
        runtime: KotlinspectRuntime,
        selection: HarSelection,
        options: HarExportOptions,
        outputPath: String?,
        onProgress: ((Int, Int) -> Unit)?,
    ): HarExportResult {
        val db = runtime.database
        val states = buildList {
            add(CallState.Complete.name)
            if (options.includeFailed) {
                add(CallState.Failed.name)
                add(CallState.Cancelled.name)
            }
            if (options.includePending) add(CallState.Pending.name)
        }

        val exportsDir = HarFiles.exportsDirectory()
        exportsDir?.let { HarFiles.deleteOlderThan(it, runtime.clock() - MAX_AGE_MILLIS) }

        // What to write, and the file name.
        val slug: String
        val total: Int
        var preloaded: List<RecordEntity>? = null
        when (selection) {
            is HarSelection.Session -> {
                val session = db.sessions().get(selection.sessionId)
                    ?: return HarExportResult.Failure("No session with id ${selection.sessionId}")
                slug = slug(session.name, "session")
                total = db.records().countForHar(selection.sessionId, states)
            }
            is HarSelection.Records -> {
                val records = selection.recordIds.distinct().chunked(500)
                    .flatMap { db.records().byIds(it) }
                    .filter { it.state in states }
                    .sortedWith(compareBy<RecordEntity> { it.startedAt }.thenBy { it.id })
                if (records.isEmpty()) return HarExportResult.Failure("No calls to export")
                slug = if (records.size == 1) slug(records[0].endpointKey ?: records[0].path, "call") else "calls"
                total = records.size
                preloaded = records
            }
        }

        val path = outputPath ?: run {
            val dir = exportsDir ?: return HarExportResult.Failure("No folder to export to on this platform")
            "$dir/kotlinspect-$slug-${fileStamp(runtime.clock())}.har"
        }

        val sink = HarFiles.openSink(path)
        var written = 0
        try {
            val json = HarJson(sink)
            val earliest = linkedMapOf<String, Long>()
            json.beginObject().name("log").beginObject()
            json.field("version", "1.2")
            json.name("creator").beginObject().field("name", "Kotlinspect").field("version", BuildInfo.VERSION).endObject()
            json.name("entries").beginArray()

            fun write(r: RecordEntity) {
                HarEntries.write(json, r, options.includeBodies)
                earliest[r.sessionId] = minOf(earliest[r.sessionId] ?: Long.MAX_VALUE, r.startedAt)
                written++
                onProgress?.invoke(written, total)
            }

            pagesRead = 0
            when (selection) {
                is HarSelection.Session -> {
                    var afterStart = Long.MIN_VALUE
                    var afterId = ""
                    while (true) {
                        val page = db.records().harPage(selection.sessionId, states, afterStart, afterId, PAGE_SIZE)
                        pagesRead++
                        if (page.isEmpty()) break
                        page.forEach(::write)
                        afterStart = page.last().startedAt
                        afterId = page.last().id
                        if (page.size < PAGE_SIZE) break
                    }
                }
                is HarSelection.Records -> preloaded.orEmpty().forEach(::write)
            }
            json.endArray()

            // Pages last: each one starts at its session's earliest entry, known only now.
            json.name("pages").beginArray()
            if (selection is HarSelection.Session && earliest.isEmpty()) earliest[selection.sessionId] = Long.MAX_VALUE
            for ((sessionId, start) in earliest) {
                val session = db.sessions().get(sessionId)
                json.beginObject()
                json.field("id", sessionId)
                json.field("title", session?.name ?: sessionId)
                json.field("startedDateTime", HarEntries.isoUtc(if (start == Long.MAX_VALUE) session?.createdAt ?: 0 else start))
                json.name("pageTimings").beginObject().field("onContentLoad", -1).field("onLoad", -1).endObject()
                json.endObject()
            }
            json.endArray()
            json.endObject().endObject()
            json.flush()
        } catch (e: Throwable) {
            runCatching { sink.close() }
            HarFiles.delete(path)
            throw e
        }
        sink.close()
        return HarExportResult.Success(path, written, HarFiles.size(path))
    }

    /** "kotlinspect-<slug>-<yyyyMMdd-HHmmss>.har" for a session name or endpoint. */
    fun fileName(name: String, nowMillis: Long): String = "kotlinspect-${slug(name, "export")}-${fileStamp(nowMillis)}.har"

    /** Lowercase letters, digits and '-' only, at most 40 characters. */
    fun slug(text: String, fallback: String): String {
        val s = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
        return s.ifEmpty { fallback }
    }

    private fun fileStamp(millis: Long): String {
        val t = localTime(millis)
        fun Int.p(n: Int = 2) = toString().padStart(n, '0')
        return "${t.year.p(4)}${t.month.p()}${t.day.p()}-${t.hour.p()}${t.minute.p()}${t.second.p()}"
    }
}

/** File access for exports; platforms without files (web) have no export folder. */
internal expect object HarFiles {
    /** Where exports are written by default, created if needed; null when unavailable. */
    fun exportsDirectory(): String?

    fun openSink(path: String): HarSink

    fun size(path: String): Long

    fun delete(path: String)

    /** Removes exports last modified before [cutoffMillis]. */
    fun deleteOlderThan(directory: String, cutoffMillis: Long)
}
