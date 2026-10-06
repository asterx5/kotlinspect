package io.github.asterx5.kotlinspect.internal.ui

import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.db.RecordEntity

/** Builds a copy-pasteable cURL command. Redacted values stay redacted. */
internal fun RecordEntity.toCurl(): String {
    val parts = mutableListOf("curl")
    if (method != "GET" || requestBody != null) parts += "-X $method"
    parts += shellQuote(url)
    Codecs.decodeHeaders(requestHeaders)
        .filterNot { (name, _) -> name.equals("Content-Length", ignoreCase = true) }
        .forEach { (name, value) -> parts += "-H ${shellQuote("$name: $value")}" }
    requestBody?.let { parts += "--data-raw ${shellQuote(it)}" }
    if (requestBodyTruncated) parts += "# request body was truncated"
    return parts.joinToString(" \\\n  ")
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
