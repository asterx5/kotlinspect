package io.github.asterx5.kotlinspect.internal.har

import io.github.asterx5.kotlinspect.CallState
import io.github.asterx5.kotlinspect.internal.Codecs
import io.github.asterx5.kotlinspect.internal.HeaderList
import io.github.asterx5.kotlinspect.internal.capture.REDACTED
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.ktor.http.decodeURLQueryComponent

/** Turns stored records into HAR 1.2 entries. Reads only what storage holds, already redacted. */
internal object HarEntries {

    fun write(json: HarJson, r: RecordEntity, includeBodies: Boolean) {
        val state = runCatching { CallState.valueOf(r.state) }.getOrDefault(CallState.Pending)
        val requestHeaders = Codecs.decodeHeaders(r.requestHeaders)
        val responseHeaders = Codecs.decodeHeaders(r.responseHeaders)
        val version = httpVersion(r.protocol)
        val t = timings(r)

        json.beginObject()
        json.field("pageref", r.sessionId)
        json.field("startedDateTime", isoUtc(r.startedAt))
        json.field("time", t.time)

        json.name("request").beginObject()
        json.field("method", r.method)
        json.field("url", r.url)
        json.field("httpVersion", version)
        json.name("cookies").beginArray()
        requestCookies(requestHeaders).forEach { (name, value) -> json.beginObject().field("name", name).field("value", value).endObject() }
        json.endArray()
        headers(json, requestHeaders)
        json.name("queryString").beginArray()
        queryString(r.url).forEach { (name, value) -> json.beginObject().field("name", name).field("value", value).endObject() }
        json.endArray()
        val hasRequestBody = r.requestBody != null || (r.requestBodySize ?: 0) > 0 || r.requestBodyNote != null
        if (includeBodies && hasRequestBody) {
            json.name("postData").beginObject()
            json.field("mimeType", r.requestContentType.orEmpty())
            json.field("text", r.requestBody.orEmpty())
            bodyComment(r.requestBody, r.requestBodyTruncated, r.requestBodyNote)?.let { json.field("comment", it) }
            json.endObject()
        }
        json.field("headersSize", -1)
        json.field("bodySize", if (hasRequestBody) r.requestBodySize ?: -1 else 0)
        json.endObject()

        val complete = state == CallState.Complete
        json.name("response").beginObject()
        json.field("status", if (complete) r.statusCode ?: 0 else 0)
        json.field("statusText", if (complete) r.statusText.orEmpty() else "")
        json.field("httpVersion", version)
        json.name("cookies").beginArray()
        responseCookies(responseHeaders).forEach { cookie(json, it) }
        json.endArray()
        headers(json, responseHeaders)
        json.name("content").beginObject()
        json.field("size", r.responseBodySize ?: r.responseBody?.encodeToByteArray()?.size?.toLong() ?: 0)
        json.field("mimeType", r.responseContentType ?: "x-unknown")
        if (includeBodies && r.responseBody != null) json.field("text", r.responseBody)
        if (includeBodies) bodyComment(r.responseBody, r.responseBodyTruncated, r.responseBodyNote)?.let { json.field("comment", it) }
        json.endObject()
        json.field("redirectURL", responseHeaders.firstOrNull { it.first.equals("Location", ignoreCase = true) }?.second.orEmpty())
        json.field("headersSize", -1)
        json.field("bodySize", r.responseBodySize ?: -1)
        json.endObject()

        json.name("cache").beginObject().endObject()
        json.name("timings").beginObject()
        json.field("blocked", t.blocked)
        json.field("dns", t.dns)
        json.field("connect", t.connect)
        json.field("ssl", t.ssl)
        json.field("send", t.send)
        json.field("wait", t.wait)
        json.field("receive", t.receive)
        json.endObject()
        r.serverIpAddress?.let { json.field("serverIPAddress", it) }
        error(state, r.error)?.let { json.field("_error", it) }
        json.endObject()
    }

    private fun headers(json: HarJson, headers: HeaderList) {
        json.name("headers").beginArray()
        headers.forEach { (name, value) -> json.beginObject().field("name", name).field("value", value).endObject() }
        json.endArray()
    }

    private fun cookie(json: HarJson, c: SetCookie) {
        json.beginObject()
        json.field("name", c.name)
        json.field("value", c.value)
        c.path?.let { json.field("path", it) }
        c.domain?.let { json.field("domain", it) }
        c.expires?.let { json.field("expires", it) }
        if (c.httpOnly) json.field("httpOnly", true)
        if (c.secure) json.field("secure", true)
        json.endObject()
    }

    /** The error Chrome shows for entries without a full response. */
    private fun error(state: CallState, error: String?): String? = when (state) {
        CallState.Failed -> error ?: "failed"
        CallState.Cancelled -> if (error?.startsWith("Interrupted") == true) "interrupted" else "cancelled"
        else -> null
    }

    private fun bodyComment(text: String?, truncated: Boolean, note: String?): String? = when {
        note == "Binary content not captured" -> "binary body not captured by Kotlinspect"
        note != null -> note
        truncated -> "Truncated by Kotlinspect"
        text == null -> null
        else -> null
    }

    /** "h2" and "HTTP/2.0" both become "HTTP/2.0"; anything unknown is reported as HTTP/1.1. */
    fun httpVersion(protocol: String?): String = when (protocol?.trim()?.lowercase()) {
        "http/1.0" -> "HTTP/1.0"
        "h2", "h2c", "h2_prior_knowledge", "http/2", "http/2.0" -> "HTTP/2.0"
        "h3", "quic", "http/3", "http/3.0" -> "HTTP/3.0"
        else -> "HTTP/1.1"
    }

    class Timings(
        val blocked: Long,
        val dns: Long,
        val connect: Long,
        val ssl: Long,
        val send: Long,
        val wait: Long,
        val receive: Long,
    ) {
        /** HAR's total: every phase that is known; TLS is part of connect, so it is not added. */
        val time: Long get() = listOf(blocked, dns, connect).filter { it >= 0 }.sum() + send + wait + receive
    }

    /**
     * HAR timings. Setup phases are -1 when unknown; send, wait and receive are never negative:
     * measured or timestamp-derived values when known, otherwise the call's duration counts as
     * wait so the phases still add up to it.
     */
    fun timings(r: RecordEntity): Timings {
        val blocked = r.timingBlocked ?: -1
        val dns = r.timingDns ?: -1
        val connect = r.timingConnect ?: -1
        val ssl = r.timingSsl ?: -1
        val setup = listOf(blocked, dns, connect).filter { it >= 0 }.sum()
        val sentAt = r.requestSentAt
        val responseAt = r.responseStartedAt
        val completedAt = r.completedAt
        val send = r.timingSend ?: sentAt?.let { (it - r.startedAt - setup).coerceAtLeast(0) }
        val wait = r.timingWait ?: if (sentAt != null && responseAt != null) (responseAt - sentAt).coerceAtLeast(0) else null
        val receive = r.timingReceive ?: if (responseAt != null && completedAt != null) (completedAt - responseAt).coerceAtLeast(0) else null
        val duration = (r.durationMs ?: 0).coerceAtLeast(0)
        if (send == null && wait == null && receive == null) {
            return Timings(blocked, dns, connect, ssl, send = 0, wait = duration, receive = 0)
        }
        val s = send ?: 0
        val rc = receive ?: 0
        val w = wait ?: (duration - setup - s - rc).coerceAtLeast(0)
        return Timings(blocked, dns, connect, ssl, send = s, wait = w, receive = rc)
    }

    /** "a=1; b=2" from every Cookie header; a redacted header gives nothing. */
    fun requestCookies(headers: HeaderList): List<Pair<String, String>> =
        headers.filter { it.first.equals("Cookie", ignoreCase = true) && it.second != REDACTED }
            .flatMap { (_, value) -> value.split(';') }
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val eq = trimmed.indexOf('=')
                if (eq <= 0) null else trimmed.substring(0, eq).trim() to trimmed.substring(eq + 1).trim()
            }

    class SetCookie(
        val name: String,
        val value: String,
        val path: String?,
        val domain: String?,
        val expires: String?,
        val httpOnly: Boolean,
        val secure: Boolean,
    )

    /** Best effort, one per Set-Cookie header; redacted headers are skipped. */
    fun responseCookies(headers: HeaderList): List<SetCookie> =
        headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) && it.second != REDACTED }.mapNotNull { (_, header) ->
            val parts = header.split(';').map { it.trim() }
            val first = parts.firstOrNull() ?: return@mapNotNull null
            val eq = first.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            var path: String? = null
            var domain: String? = null
            var expires: String? = null
            var httpOnly = false
            var secure = false
            parts.drop(1).forEach { attribute ->
                val key = attribute.substringBefore('=').trim().lowercase()
                val value = attribute.substringAfter('=', "").trim()
                when (key) {
                    "path" -> path = value
                    "domain" -> domain = value
                    "expires" -> expires = parseHttpDate(value)?.let(::isoUtc)
                    "httponly" -> httpOnly = true
                    "secure" -> secure = true
                }
            }
            SetCookie(first.substring(0, eq).trim(), first.substring(eq + 1).trim(), path, domain, expires, httpOnly, secure)
        }

    /** Query parameters in order, duplicates kept, percent-decoded. */
    fun queryString(url: String): List<Pair<String, String>> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(name) to decode(value)
        }
    }

    private fun decode(s: String): String = runCatching { s.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(s)

    /** "2026-10-25T14:02:11.120Z" */
    fun isoUtc(epochMillis: Long): String {
        val days = floorDiv(epochMillis, MILLIS_PER_DAY)
        val ms = epochMillis - days * MILLIS_PER_DAY
        // Days to civil date (Howard Hinnant's algorithm), valid for the proleptic Gregorian calendar.
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val hour = ms / 3_600_000
        val minute = ms / 60_000 % 60
        val second = ms / 1_000 % 60
        val milli = ms % 1_000
        return "${pad(year, 4)}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}:${pad(second)}.${pad(milli, 3)}Z"
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Parses "Wed, 21 Oct 2015 07:28:00 GMT" (and the dashed cookie variant); null if unreadable. */
    fun parseHttpDate(text: String): Long? {
        val parts = text.substringAfter(',').trim().replace('-', ' ').split(' ').filter { it.isNotEmpty() }
        if (parts.size < 4) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = months.indexOf(parts[1].take(3).lowercase()).takeIf { it >= 0 }?.plus(1) ?: return null
        var year = parts[2].toIntOrNull() ?: return null
        if (year < 100) year += if (year < 70) 2000 else 1900
        val time = parts[3].split(':').map { it.toIntOrNull() ?: return null }
        if (time.size != 3) return null
        return (daysFromCivil(year, month, day) * MILLIS_PER_DAY) + time[0] * 3_600_000L + time[1] * 60_000L + time[2] * 1_000L
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    private fun pad(n: Long, width: Int = 2): String = n.toString().padStart(width, '0')

    private const val MILLIS_PER_DAY = 86_400_000L
}
