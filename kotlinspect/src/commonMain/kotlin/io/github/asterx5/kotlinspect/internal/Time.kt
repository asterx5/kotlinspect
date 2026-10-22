package io.github.asterx5.kotlinspect.internal

/** Wall-clock fields in the device time zone. */
internal class LocalTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)

/** Uses the platform's calendar, so Kotlinspect needs no date-time library. */
internal expect fun localTime(epochMillis: Long): LocalTime

private fun Int.pad(n: Int = 2) = toString().padStart(n, '0')

/** "2026-10-06 19:20:05" in the device time zone. */
internal fun formatTimestamp(millis: Long): String {
    val t = localTime(millis)
    return "${t.year}-${t.month.pad()}-${t.day.pad()} ${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}"
}

/** "19:20:05.123" in the device time zone. */
internal fun formatTime(millis: Long): String {
    val t = localTime(millis)
    return "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}.${t.millisecond.pad(3)}"
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
