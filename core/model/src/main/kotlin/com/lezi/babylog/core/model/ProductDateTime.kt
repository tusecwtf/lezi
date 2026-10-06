package com.lezi.babylog.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared product Chinese clocks. Export ISO and member MM-dd stay local. */
object ProductDateTime {
    private val locale = Locale.SIMPLIFIED_CHINESE
    private val monthDayTime = DateTimeFormatter.ofPattern("M月d日 HH:mm", locale)
    private val yearMonthDayTime = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", locale)
    private val yearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日", locale)

    fun monthDayTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(monthDayTime)

    fun yearMonthDayTime(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(yearMonthDayTime)

    fun yearMonthDay(date: LocalDate): String = date.format(yearMonthDay)
}
