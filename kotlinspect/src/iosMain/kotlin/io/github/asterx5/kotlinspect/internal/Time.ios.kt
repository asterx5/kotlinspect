package io.github.asterx5.kotlinspect.internal

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitNanosecond
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun localTime(epochMillis: Long): LocalTime {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or
        NSCalendarUnitMinute or NSCalendarUnitSecond or NSCalendarUnitNanosecond
    val c = NSCalendar.currentCalendar.components(units, fromDate = date)
    return LocalTime(
        year = c.year.toInt(),
        month = c.month.toInt(),
        day = c.day.toInt(),
        hour = c.hour.toInt(),
        minute = c.minute.toInt(),
        second = c.second.toInt(),
        millisecond = (epochMillis % 1000).toInt().let { if (it < 0) it + 1000 else it },
    )
}
