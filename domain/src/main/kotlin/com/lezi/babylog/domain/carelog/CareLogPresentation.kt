package com.lezi.babylog.domain.carelog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure display formatting kept outside the CareLog persistence facade. */
object CareLogPresentation {
    fun babyAgeLabel(
        birthdayEpochDay: Long,
        today: LocalDate = LocalDate.now(),
    ): String {
        val birth = LocalDate.ofEpochDay(birthdayEpochDay)
        if (birth.isAfter(today)) return "未出生"

        if (today.isBefore(birth.plusYears(1))) {
            val months = completedCalendarMonthsBetween(birth, today)
            val days = ChronoUnit.DAYS.between(birth.plusMonths(months.toLong()), today).toInt()
            return "${months}个月${days}天"
        }

        var years = today.year - birth.year
        var yearAnchor = birth.plusYears(years.toLong())
        if (today.isBefore(yearAnchor)) {
            years -= 1
            yearAnchor = birth.plusYears(years.toLong())
        }
        val months = completedCalendarMonthsBetween(yearAnchor, today)
        return "${years}岁${months}个月"
    }

    fun relativeTimeLabel(
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val delta = (now - timestamp).coerceAtLeast(0L)
        val min = delta / 60_000L
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "${min} 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / (60 * 24)} 天前"
        }
    }

    fun formatClock(
        timestamp: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }

    fun amountCandidates(step: Int, lastMl: Int?): List<Int> {
        val normalizedStep = step.coerceAtLeast(1)
        val candidates = (30..300 step normalizedStep).toMutableList()
        if (lastMl != null && lastMl > 0 && lastMl !in candidates) {
            candidates.add(lastMl)
            candidates.sort()
        }
        return candidates
    }

    fun amountCenterIndex(candidates: List<Int>, lastMl: Int?): Int {
        if (candidates.isEmpty()) return 0
        if (lastMl == null) {
            return candidates.indexOf(120).takeIf { it >= 0 }
                ?: candidates.indexOfFirst { it >= 120 }.coerceAtLeast(0)
        }
        val exact = candidates.indexOf(lastMl)
        if (exact >= 0) return exact
        return candidates.indices.minByOrNull {
            kotlin.math.abs(candidates[it] - lastMl)
        } ?: 0
    }

    private fun completedCalendarMonthsBetween(start: LocalDate, end: LocalDate): Int {
        var months = (end.year - start.year) * 12 + end.monthValue - start.monthValue
        if (end.isBefore(start.plusMonths(months.toLong()))) months -= 1
        return months
    }
}

fun babyAgeLabel(
    birthdayEpochDay: Long,
    today: LocalDate = LocalDate.now(),
): String = CareLogPresentation.babyAgeLabel(birthdayEpochDay, today)

fun relativeTimeLabel(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String = CareLogPresentation.relativeTimeLabel(timestamp, now)

fun formatClock(
    timestamp: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = CareLogPresentation.formatClock(timestamp, zone)

fun amountCandidates(step: Int, lastMl: Int?): List<Int> =
    CareLogPresentation.amountCandidates(step, lastMl)

fun amountCenterIndex(candidates: List<Int>, lastMl: Int?): Int =
    CareLogPresentation.amountCenterIndex(candidates, lastMl)
