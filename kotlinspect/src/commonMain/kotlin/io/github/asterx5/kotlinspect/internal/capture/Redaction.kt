package io.github.asterx5.kotlinspect.internal.capture

import io.github.asterx5.kotlinspect.HeaderRedactor
import io.github.asterx5.kotlinspect.internal.HeaderList
import io.ktor.http.URLBuilder
import io.ktor.http.Url

internal const val REDACTED: String = "██ redacted"

internal class Redaction(
    redactedHeaders: Set<String>,
    redactedQueryParameters: Set<String>,
    private val headerRedactors: List<HeaderRedactor>,
) {
    private val headerNames = redactedHeaders.map { it.lowercase() }.toSet()
    private val queryNames = redactedQueryParameters.map { it.lowercase() }.toSet()

    fun headers(headers: HeaderList): HeaderList = headers.map { (name, value) ->
        if (name.lowercase() in headerNames) {
            name to REDACTED
        } else {
            name to headerRedactors.fold(value) { acc, r -> runCatching { r.redact(name, acc) }.getOrDefault(acc) }
        }
    }

    fun url(url: Url): Url {
        if (queryNames.isEmpty() || url.parameters.isEmpty()) return url
        val builder = URLBuilder(url)
        val names = builder.parameters.names().filter { it.lowercase() in queryNames }
        if (names.isEmpty()) return url
        names.forEach { name ->
            val count = builder.parameters.getAll(name)?.size ?: 0
            builder.parameters.remove(name)
            repeat(count) { builder.parameters.append(name, REDACTED) }
        }
        return builder.build()
    }
}

/** Keys that let later versions group, deduplicate and diff calls. */
internal object EndpointKeys {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val number = Regex("^\\d+$")
    private val hex = Regex("^[0-9a-fA-F]{16,}$")

    /** "GET api.example.com/users/{id}/posts": ids in the path are normalized. */
    fun endpointKey(method: String, url: Url): String {
        val path = url.segments.filter { it.isNotEmpty() }.joinToString("/") { segment ->
            if (uuid.matches(segment) || number.matches(segment) || hex.matches(segment)) "{id}" else segment
        }
        return "${method.uppercase()} ${url.host}/$path"
    }

    /** Stable hash of method, full URL with sorted query, and request body. */
    fun fingerprint(method: String, url: Url, body: String?): String {
        val query = url.parameters.entries()
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${v.sorted().joinToString(",")}" }
        val input = "${method.uppercase()} ${url.protocol.name}://${url.host}:${url.port}${url.encodedPath}?$query\n${body.orEmpty()}"
        return fnv1a64(input)
    }

    private fun fnv1a64(input: String): String {
        var hash = 0xcbf29ce484222325uL
        for (b in input.encodeToByteArray()) {
            hash = hash xor (b.toUByte().toULong())
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
