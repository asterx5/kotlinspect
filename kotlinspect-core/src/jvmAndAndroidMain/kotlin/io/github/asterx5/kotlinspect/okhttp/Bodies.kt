@file:OptIn(ExperimentalKotlinspectApi::class)

package io.github.asterx5.kotlinspect.okhttp

import io.github.asterx5.kotlinspect.CapturedBody
import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.KotlinspectIngest
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSource
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException

internal object RequestBodies {
    /**
     * Copies at most the body limit from a request body. Duplex and one-shot bodies are never
     * written: writing them here would consume what the network needs.
     */
    fun capture(body: RequestBody?): CapturedBody? {
        body ?: return null
        val contentType = body.contentType()?.toString()
        val declared = body.contentLength()
        if (body.isDuplex()) return CapturedBody(NO_BYTES, declared, contentType, "duplex")
        if (body.isOneShot()) return CapturedBody(NO_BYTES, declared, contentType, "one-shot")
        val sink = CappedSink(KotlinspectIngest.maxBodyBytes)
        return try {
            val buffered = sink.buffer()
            body.writeTo(buffered)
            buffered.flush()
            CapturedBody(sink.bytes(), if (declared >= 0) declared else sink.total, contentType)
        } catch (e: IOException) {
            CapturedBody(NO_BYTES, declared, contentType, "unreadable")
        }
    }
}

/**
 * Keeps the first [limit] bytes written to it and only counts the rest, so a large upload is
 * never held in memory.
 */
internal class CappedSink(private val limit: Int) : Sink {
    private val kept = Buffer()
    var total: Long = 0
        private set

    override fun write(source: Buffer, byteCount: Long) {
        val room = (limit - kept.size).coerceAtLeast(0)
        val take = minOf(room, byteCount)
        if (take > 0) source.copyTo(kept, 0, take)
        source.skip(byteCount)
        total += byteCount
    }

    fun bytes(): ByteArray = kept.readByteArray()

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

internal object ResponseBodies {
    private val streamingTypes = listOf("text/event-stream", "application/x-ndjson")

    fun capture(response: Response): CapturedBody? {
        val body = response.body ?: return null
        val contentType = body.contentType()?.toString() ?: response.header("Content-Type")
        val declared = body.contentLength()
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        // Reading ahead would hold back events the app is waiting for.
        if (type != null && type in streamingTypes) return CapturedBody(NO_BYTES, declared, contentType, "streaming")

        val max = KotlinspectIngest.maxBodyBytes
        return when (val encoding = response.header("Content-Encoding")?.trim()?.lowercase()) {
            null, "", "identity" -> {
                val peeked = response.peekBody(max.toLong()).bytes()
                val total = when {
                    declared >= 0 -> declared
                    peeked.size < max -> peeked.size.toLong() // read to the end
                    else -> -1L
                }
                CapturedBody(peeked, total, contentType)
            }
            // The app asked for gzip itself, so OkHttp passed the compressed body through.
            "gzip" -> {
                val compressed = response.peekBody(max.toLong()).bytes()
                val complete = declared in 0..compressed.size.toLong() || compressed.size < max
                when (val result = gunzip(compressed, max, complete)) {
                    null -> CapturedBody(NO_BYTES, -1, contentType, "gzip")
                    else -> CapturedBody(result.bytes, if (complete && !result.capped) result.bytes.size.toLong() else -1L, contentType)
                }
            }
            else -> CapturedBody(NO_BYTES, declared, contentType, "encoding $encoding")
        }
    }

    class Gunzipped(val bytes: ByteArray, val capped: Boolean)

    /**
     * Decompresses at most [max] bytes. A body cut short by the read-ahead limit ends early, which
     * is expected; a complete body that fails to decompress returns null.
     */
    fun gunzip(compressed: ByteArray, max: Int, complete: Boolean): Gunzipped? {
        val out = Buffer()
        return try {
            GzipSource(Buffer().write(compressed)).use { source ->
                while (out.size < max) {
                    if (source.read(out, minOf(8_192L, max - out.size)) == -1L) break
                }
            }
            val capped = out.size >= max
            Gunzipped(out.readByteArray(minOf(out.size, max.toLong())), capped)
        } catch (e: IOException) {
            if (complete || out.size == 0L) null else Gunzipped(out.readByteArray(), capped = true)
        }
    }
}
