package com.lezi.babylog.domain

import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.RecordType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DayBucket(
    val date: LocalDate,
    val feedMl: Int = 0,
    val nursingMin: Long = 0,
    val sleepMin: Long = 0,
    val pee: Int = 0,
    val poop: Int = 0,
    val temps: List<Double> = emptyList(),
)

data class WeekSummary(
    val weekStart: LocalDate,
    val days: List<DayBucket>, // size 7
) {
    val totalFeedMl: Int get() = days.sumOf { it.feedMl }
    val totalNursingMin: Long get() = days.sumOf { it.nursingMin }
    val totalSleepMin: Long get() = days.sumOf { it.sleepMin }
    val totalPee: Int get() = days.sumOf { it.pee }
    val totalPoop: Int get() = days.sumOf { it.poop }
    val avgSleepMin: Long
        get() {
            val withSleep = days.count { it.sleepMin > 0 }
            return if (withSleep == 0) 0 else totalSleepMin / withSleep
        }
}

/** weekStartSetting: 1 = Monday, 7 = Sunday (ISO). */
fun weekStartFor(day: LocalDate, weekStartSetting: Int): LocalDate {
    val dow = if (weekStartSetting == 7) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
    return day.with(TemporalAdjusters.previousOrSame(dow))
}

fun aggregateWeek(
    records: List<RecordEntity>,
    weekStart: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): WeekSummary {
    val days = (0..6).map { offset ->
        val date = weekStart.plusDays(offset.toLong())
        DayBucket(date = date)
    }.toMutableList()
    val index = days.associateBy { it.date }

    for (r in records) {
        if (r.deletedAt != null) continue
        val date = Instant.ofEpochMilli(r.timestamp).atZone(zone).toLocalDate()
        val bucket = index[date] ?: continue
        val i = days.indexOfFirst { it.date == date }
        if (i < 0) continue
        var b = days[i]
        when (RecordType.fromKey(r.type)) {
            RecordType.FORMULA, RecordType.PUMPED_FEED -> {
                val ml = payloadInt(r.payloadJson, "amount_ml")
                b = b.copy(feedMl = b.feedMl + ml)
            }
            RecordType.NURSING -> {
                val left = payloadInt(r.payloadJson, "left_min")
                val right = payloadInt(r.payloadJson, "right_min")
                b = b.copy(
                    nursingMin = b.nursingMin + left + right,
                    feedMl = b.feedMl + payloadInt(r.payloadJson, "amount_ml"),
                )
            }
            RecordType.SLEEP -> {
                val end = r.endTimestamp
                if (end != null && end >= r.timestamp) {
                    // Count full duration on start day (product choice).
                    b = b.copy(sleepMin = b.sleepMin + (end - r.timestamp) / 60_000L)
                }
            }
            RecordType.PEE -> b = b.copy(pee = b.pee + 1)
            RecordType.POOP -> b = b.copy(poop = b.poop + 1)
            RecordType.BOTH_DIAPER -> b = b.copy(pee = b.pee + 1, poop = b.poop + 1)
            RecordType.TEMPERATURE -> {
                val c = payloadDouble(r.payloadJson, "celsius")
                if (c != null) b = b.copy(temps = b.temps + c)
            }
            else -> Unit
        }
        days[i] = b
    }
    return WeekSummary(weekStart = weekStart, days = days)
}

data class WidgetSummaryDto(
    val babyName: String,
    val feedMl: Int,
    val sleepMin: Long,
    val pee: Int,
    val poop: Int,
    val lastLabel: String?,
)
