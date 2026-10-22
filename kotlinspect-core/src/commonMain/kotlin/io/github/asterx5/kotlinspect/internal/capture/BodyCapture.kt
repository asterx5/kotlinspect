package io.github.asterx5.kotlinspect.internal.capture

import io.github.asterx5.kotlinspect.BodyRedactor

/** What gets stored for a request or response body. */
internal data class CapturedBody(
    val text: String?,
    /** Full size in bytes when known, even if [text] is truncated or omitted. */
    val size: Long?,
    val truncated: Boolean = false,
    /** Why the body is missing or partial, shown in the inspector. */
    val note: String? = null,
) {
    companion object {
        val Empty = CapturedBody(text = null, size = 0)
        fun omitted(note: String, size: Long? = null) = CapturedBody(text = null, size = size, note = note)
    }
}

internal object BodyCapture {
    private val binaryTypePrefixes = listOf("image/", "audio/", "video/", "font/")
    private val binaryTypes = setOf(
        "application/octet-stream",
        "application/pdf",
        "application/zip",
        "application/gzip",
        "application/x-protobuf",
        "application/protobuf",
        "application/grpc",
        "application/wasm",
        "application/vnd.android.package-archive",
    )

    /** True for content types known to be binary, false for known text, null when unknown. */
    fun isBinaryContentType(contentType: String?): Boolean? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (type.isEmpty()) return null
        if (binaryTypePrefixes.any { type.startsWith(it) } || type in binaryTypes) return true
        if (type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml") ||
            type.contains("+json") || type.contains("+xml") ||
            type == "application/x-www-form-urlencoded" || type == "application/javascript" ||
            type == "application/graphql"
        ) {
            return false
        }
        return null
    }

    /** Heuristic for bodies without a usable content type: NUL bytes or many control characters. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val sample = minOf(length, 512)
        if (sample == 0) return false
        var suspicious = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) suspicious++
        }
        return suspicious * 10 > sample
    }

    /**
     * Builds the stored body from the first [length] bytes of [bytes]. [totalSize] is the full
     * body size, which may be larger than what was buffered.
     */
    fun fromBytes(
        bytes: ByteArray,
        length: Int = bytes.size,
        totalSize: Long = length.toLong(),
        contentType: String?,
        maxBytes: Long,
        redactors: List<BodyRedactor>,
        isRequest: Boolean,
    ): CapturedBody {
        if (totalSize == 0L) return CapturedBody.Empty
        val binary = isBinaryContentType(contentType) ?: looksBinary(bytes, length)
        if (binary) return CapturedBody.omitted("Binary content not captured", totalSize)

        val keep = minOf(length.toLong(), maxBytes).toInt()
        val truncated = totalSize > keep
        var text = bytes.decodeToString(0, keep)
        // A cut inside a multi-byte character decodes to a trailing replacement char; drop it.
        if (truncated && text.endsWith('�')) text = text.dropLast(1)
        text = redact(text, contentType, redactors, isRequest)
        return CapturedBody(
            text = text,
            size = totalSize,
            truncated = truncated,
            note = if (truncated) "Truncated to $keep of $totalSize bytes" else null,
        )
    }

    fun fromText(
        text: String,
        contentType: String?,
        maxBytes: Long,
        redactors: List<BodyRedactor>,
        isRequest: Boolean,
    ): CapturedBody {
        val bytes = text.encodeToByteArray()
        return fromBytes(bytes, bytes.size, bytes.size.toLong(), contentType ?: "text/plain", maxBytes, redactors, isRequest)
    }

    private fun redact(text: String, contentType: String?, redactors: List<BodyRedactor>, isRequest: Boolean): String =
        redactors.fold(text) { acc, r -> runCatching { r.redact(contentType, acc, isRequest) }.getOrDefault(acc) }
}

/** Accumulates the first [limit] bytes of a stream while counting all of them. */
internal class BoundedBuffer(private val limit: Int) {
    private var buffer = ByteArray(minOf(limit, 8 * 1024))
    var buffered: Int = 0
        private set
    var total: Long = 0
        private set

    fun append(source: ByteArray, count: Int) {
        total += count
        val room = limit - buffered
        if (room <= 0) return
        val take = minOf(room, count)
        if (buffered + take > buffer.size) {
            buffer = buffer.copyOf(minOf(limit, maxOf(buffer.size * 2, buffered + take)))
        }
        source.copyInto(buffer, buffered, 0, take)
        buffered += take
    }

    fun bytes(): ByteArray = buffer
}
