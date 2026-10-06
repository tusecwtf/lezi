package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class RecordTimeDecisionTest {
    @Test
    fun explicitDstGapRejectsAndOverlapPreservesPreferredOffset() {
        val zone = ZoneId.of("America/New_York")
        val gap = RecordTime.resolve(
            date = LocalDate.of(2026, 3, 8),
            time = LocalTime.of(2, 30),
            zone = zone,
        )
        val overlap = RecordTime.resolve(
            date = LocalDate.of(2026, 11, 1),
            time = LocalTime.of(1, 30),
            zone = zone,
            preferredOffset = ZoneOffset.ofHours(-5),
        )

        assertThat(gap).isEqualTo(RecordTimeDecision.RejectedGap)
        assertThat((overlap as RecordTimeDecision.Accepted).value.offset)
            .isEqualTo(ZoneOffset.ofHours(-5))
    }

    @Test
    fun newDraftClampsFutureDateAndIntervalShiftKeepsDuration() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(9, 5),
            zone,
        )

        val timestamp = RecordTime.newDraftTimestamp(
            selectedDate = LocalDate.of(2026, 7, 30),
            zone = zone,
            now = now,
        )
        val shiftedEnd = RecordTime.shiftStartPreservingDuration(
            oldStartMillis = 1_000L,
            oldEndMillis = 61_000L,
            newStartMillis = 10_000L,
        )

        assertThat(timestamp).isEqualTo(now.toInstant().toEpochMilli())
        assertThat(shiftedEnd).isEqualTo(70_000L)
        assertThat(RecordTime.snap(0, 0, 1)).isEqualTo(RecordTimeTick(0, 0))
        assertThat(RecordTime.snap(12, 0, 1)).isEqualTo(RecordTimeTick(12, 0))
        assertThat(RecordTime.snap(23, 59, 1)).isEqualTo(RecordTimeTick(23, 59))
        assertThat(RecordTime.snap(23, 58, 5)).isEqualTo(RecordTimeTick(23, 55))
    }

    @Test
    fun recordDateDecisionOwnsFutureDayClamping() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(23, 30),
            zone,
        ).toInstant().toEpochMilli()

        assertThat(
            RecordTime.selectDate(
                selectedDate = LocalDate.of(2026, 7, 22),
                zone = zone,
                nowMillis = now,
            ),
        ).isEqualTo(RecordDateDecision.Accepted(LocalDate.of(2026, 7, 22)))
        assertThat(
            RecordTime.selectDate(
                selectedDate = LocalDate.of(2026, 7, 24),
                zone = zone,
                nowMillis = now,
            ),
        ).isEqualTo(RecordDateDecision.ClampedToToday(LocalDate.of(2026, 7, 23)))
        assertThat(RecordTime.today(zone, now)).isEqualTo(LocalDate.of(2026, 7, 23))
    }

    @Test
    fun factWritesUseZeroSkewAndFulfillmentAllowsFiveMinuteBoundary() {
        val now = 1_000_000L
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        assertThat(fiveMin).isEqualTo(5 * 60_000L)

        // Create / update (default 0 skew): any future fails.
        assertThat(RecordTime.pointError(now, now)).isNull()
        assertThat(RecordTime.pointError(now + 1L, now)).isEqualTo("不能选未来时刻")
        assertThat(RecordTime.intervalError(now - 1L, now + 1L, now))
            .isEqualTo("不能选未来时刻")
        assertThat(RecordTime.intervalError(now - 10L, now, now)).isNull()

        // Fulfill: exactly +5 minutes passes; +1 ms over fails (start and end).
        assertThat(RecordTime.pointError(now + fiveMin, now, fiveMin)).isNull()
        assertThat(RecordTime.pointError(now + fiveMin + 1L, now, fiveMin))
            .isEqualTo("不能选未来时刻")
        assertThat(
            RecordTime.intervalError(
                start = now - 60_000L,
                end = now + fiveMin,
                now = now,
                maxFutureSkewMillis = fiveMin,
            ),
        ).isNull()
        assertThat(
            RecordTime.intervalError(
                start = now - 60_000L,
                end = now + fiveMin + 1L,
                now = now,
                maxFutureSkewMillis = fiveMin,
            ),
        ).isEqualTo("不能选未来时刻")
        assertThat(
            RecordTime.intervalError(
                start = now + fiveMin + 1L,
                end = now + fiveMin + 2L,
                now = now,
                maxFutureSkewMillis = fiveMin,
            ),
        ).isEqualTo("不能选未来时刻")
    }

    @Test
    fun futureEventAndReminderUseTheSameOrderingDecision() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(
            LocalDate.of(2026, 7, 23),
            LocalTime.of(10, 30),
            zone,
        )
        val defaultEvent = RecordTime.defaultFutureEventTimestamp(
            selectedDate = LocalDate.of(2026, 7, 1),
            zone = zone,
            now = now,
        )
        val event = now.plusHours(2).toInstant().toEpochMilli()

        assertThat(defaultEvent)
            .isEqualTo(now.plusDays(1).toInstant().toEpochMilli())
        assertThat(RecordTime.futureEventError(now.toInstant().toEpochMilli(), null, now.toInstant().toEpochMilli()))
            .isEqualTo(FutureEventError.EventNotFuture)
        assertThat(RecordTime.futureEventError(event, event, now.toInstant().toEpochMilli()))
            .isEqualTo(FutureEventError.ReminderNotBeforeEvent)
        assertThat(
            RecordTime.reminderAfterEventChange(
                priorEventAt = event,
                newEventAt = event + 24 * 60 * 60_000L,
                priorReminderAt = event - 2 * 60 * 60_000L,
            ),
        ).isEqualTo(event + 22 * 60 * 60_000L)
    }
}
