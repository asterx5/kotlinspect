package io.github.asterx5.kotlinspect.internal

/** Web never formats times (nothing is recorded); UTC fields keep the function total. */
internal actual fun localTime(epochMillis: Long): LocalTime {
    val days = epochMillis.floorDiv(86_400_000L)
    val ms = epochMillis - days * 86_400_000L
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return LocalTime(
        year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt(),
        month = month,
        day = (doy - (153 * mp + 2) / 5 + 1).toInt(),
        hour = (ms / 3_600_000).toInt(),
        minute = (ms / 60_000 % 60).toInt(),
        second = (ms / 1_000 % 60).toInt(),
        millisecond = (ms % 1_000).toInt(),
    )
}
