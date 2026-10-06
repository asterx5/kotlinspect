package io.github.asterx5.kotlinspect.internal

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private fun Int.pad(n: Int = 2) = toString().padStart(n, '0')

/** "2026-10-06 19:20:05" in the device time zone. */
internal fun formatTimestamp(millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.year}-${t.month.ordinal.plus(1).pad()}-${t.day.pad()} ${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"
}

/** "19:20:05.123" in the device time zone. */
internal fun formatTime(millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}.${(t.nanosecond / 1_000_000).pad(3)}"
}

internal fun formatDuration(ms: Long?): String = when {
    ms == null -> "…"
    ms < 1_000 -> "${ms}ms"
    else -> "${ms / 1000}.${((ms % 1000) / 100)}s"
}

internal fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024}.${(bytes % 1024) * 10 / 1024} KB"
    else -> "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)} MB"
}
