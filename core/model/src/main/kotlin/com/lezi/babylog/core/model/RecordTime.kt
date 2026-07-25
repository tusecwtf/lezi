package com.lezi.babylog.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The in-process interface that owns record-date, wall-clock and interval
 * decisions. Callers render decisions; they do not reconstruct DST/future
 * policy from boolean flags.
 */
object RecordTime {
    fun currentTimeMillis(clock: Clock = Clock.systemUTC()): Long = clock.millis()

    fun snap(hour: Int, minute: Int, step: Int): RecordTimeTick {
        val normalized = normalizedMinuteStep(step)
        val total = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        val rounded = (((total + normalized / 2) / normalized) * normalized)
            .coerceAtMost(MINUTES_PER_DAY - normalized)
        return RecordTimeTick(rounded / 60, rounded % 60)
    }

    fun resolve(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId,
        preferredOffset: ZoneOffset? = null,
    ): RecordTimeDecision {
        val local = LocalDateTime.of(date, time)
        val validOffsets = zone.rules.getValidOffsets(local)
        if (validOffsets.isEmpty()) return RecordTimeDecision.RejectedGap
        val offset = preferredOffset?.takeIf(validOffsets::contains) ?: validOffsets.first()
        return RecordTimeDecision.Accepted(ZonedDateTime.ofLocal(local, zone, offset))
    }

    fun merge(
        value: ZonedDateTime,
        date: LocalDate,
        hour: Int,
        minute: Int,
        step: Int,
    ): RecordTimeDecision {
        val tick = snap(hour, minute, step)
        return resolve(
            date = date,
            time = LocalTime.of(tick.hour, tick.minute),
            zone = value.zone,
            preferredOffset = value.offset,
        )
    }

    /**
     * A new draft must always have a usable time. Future dates clamp to today;
     * the rare DST gap advances using platform resolution.
     */
    fun newDraftTimestamp(
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = ZonedDateTime.now(zone),
    ): Long {
        val safeDate = minOf(selectedDate, now.toLocalDate())
        val time = now.toLocalTime().withSecond(0).withNano(0)
        val resolved = resolve(safeDate, time, zone, now.offset)
        val value = (resolved as? RecordTimeDecision.Accepted)?.value
            ?: LocalDateTime.of(safeDate, time).atZone(zone)
        return value.toInstant().toEpochMilli()
    }

    fun shiftStartPreservingDuration(
        oldStartMillis: Long,
        oldEndMillis: Long?,
        newStartMillis: Long,
    ): Long? {
        val duration = oldEndMillis?.minus(oldStartMillis)?.takeIf { it > 0L }
        return duration?.let(newStartMillis::plus)
    }

    fun pointError(timestamp: Long, now: Long): String? =
        if (timestamp > now) "不能选未来时刻" else null

    fun intervalError(start: Long, end: Long?, now: Long): String? = when {
        start > now -> "不能选未来时刻"
        end == null -> null
        end <= start -> "醒来须晚于睡下"
        end > now -> "不能选未来时刻"
        else -> null
    }

    fun defaultFutureEventTimestamp(
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = ZonedDateTime.now(zone),
    ): Long {
        val decision = resolve(
            date = selectedDate,
            time = LocalTime.of(now.hour, now.minute),
            zone = zone,
            preferredOffset = now.offset,
        )
        val preferred = (decision as? RecordTimeDecision.Accepted)?.value
            ?: LocalDateTime.of(selectedDate, LocalTime.of(now.hour, now.minute)).atZone(zone)
        val future = preferred.takeIf { it.isAfter(now) }
            ?: now.plusDays(1).withSecond(0).withNano(0)
        return future.withSecond(0).withNano(0).toInstant().toEpochMilli()
    }

    fun futureEventError(
        eventAt: Long,
        remindAt: Long?,
        now: Long,
    ): FutureEventError? = when {
        eventAt <= now -> FutureEventError.EventNotFuture
        remindAt != null && remindAt <= now -> FutureEventError.ReminderNotFuture
        remindAt != null && remindAt >= eventAt -> FutureEventError.ReminderNotBeforeEvent
        else -> null
    }

    fun reminderAfterEventChange(
        priorEventAt: Long,
        newEventAt: Long,
        priorReminderAt: Long,
    ): Long {
        if (priorReminderAt >= newEventAt) return newEventAt - DEFAULT_REMINDER_LEAD_MILLIS
        val priorLeadTime = (priorEventAt - priorReminderAt).coerceAtLeast(MIN_REMINDER_LEAD_MILLIS)
        return newEventAt - priorLeadTime
    }

    fun normalizedMinuteStep(step: Int): Int = if (step == 5) 5 else 1

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MIN_REMINDER_LEAD_MILLIS = 60_000L
    private const val DEFAULT_REMINDER_LEAD_MILLIS = 60 * MIN_REMINDER_LEAD_MILLIS
}

data class RecordTimeTick(val hour: Int, val minute: Int)

sealed interface RecordTimeDecision {
    data class Accepted(val value: ZonedDateTime) : RecordTimeDecision
    data object RejectedGap : RecordTimeDecision
}

enum class FutureEventError {
    EventNotFuture,
    ReminderNotFuture,
    ReminderNotBeforeEvent,
}
