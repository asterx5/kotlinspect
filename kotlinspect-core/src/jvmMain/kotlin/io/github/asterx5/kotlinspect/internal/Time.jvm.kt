package io.github.asterx5.kotlinspect.internal

import java.util.Calendar

// java.util.Calendar rather than java.time, which needs Android API 26.
internal actual fun localTime(epochMillis: Long): LocalTime {
    val c = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return LocalTime(
        year = c.get(Calendar.YEAR),
        month = c.get(Calendar.MONTH) + 1,
        day = c.get(Calendar.DAY_OF_MONTH),
        hour = c.get(Calendar.HOUR_OF_DAY),
        minute = c.get(Calendar.MINUTE),
        second = c.get(Calendar.SECOND),
        millisecond = c.get(Calendar.MILLISECOND),
    )
}
