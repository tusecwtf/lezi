package com.lezi.babylog.feature.log

import com.lezi.babylog.designsystem.timestampOnLeziDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun timestampOnDate(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
): Long = timestampOnLeziDate(date, zone, now)

internal fun shiftStartPreservingDuration(
    oldStartMillis: Long,
    oldEndMillis: Long?,
    newStartMillis: Long,
): Long? {
    val duration = oldEndMillis?.minus(oldStartMillis)?.takeIf { it > 0L }
    return duration?.let(newStartMillis::plus)
}
