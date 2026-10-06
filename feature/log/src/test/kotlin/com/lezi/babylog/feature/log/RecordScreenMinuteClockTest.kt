package com.lezi.babylog.feature.log
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.relativeTimeLabel
import com.lezi.babylog.domain.timeline.TimelineWindowRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class RecordScreenMinuteClockTest {
    @Test
    fun activeClockEmitsImmediatelyThenAlignsToTheNextMinuteBoundary() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val beforeBoundary = RecordScreenTimeSnapshot(
            instant = Instant.parse("2026-07-30T04:34:59.750Z"),
            zoneId = zone,
        )
        val onBoundary = beforeBoundary.copy(
            instant = Instant.parse("2026-07-30T04:35:00Z"),
        )
        val clock = FakeRecordScreenClock(beforeBoundary)
        val waits = mutableListOf<Long>()

        val snapshots = recordScreenMinuteTicks(
            clock = clock,
            awaitBoundary = { waitMillis ->
                waits += waitMillis
                clock.current = onBoundary
            },
        ).take(2).toList()

        assertEquals(listOf(beforeBoundary, onBoundary), snapshots)
        assertEquals(listOf(250L), waits)
    }

    @Test
    fun minuteTickCrossesMidnightUsingTheSnapshotZone() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val beforeMidnight = RecordScreenTimeSnapshot(
            instant = Instant.parse("2026-07-30T15:59:59.999Z"),
            zoneId = zone,
        )
        val midnight = beforeMidnight.copy(
            instant = Instant.parse("2026-07-30T16:00:00Z"),
        )
        val clock = FakeRecordScreenClock(beforeMidnight)

        val snapshots = recordScreenMinuteTicks(
            clock = clock,
            awaitBoundary = { clock.current = midnight },
        ).take(2).toList()

        assertEquals(
            listOf(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 31)),
            snapshots.map(RecordScreenTimeSnapshot::localDate),
        )
    }

    @Test
    fun resumedCollectionImmediatelyRefreshesAChangedClockAndZone() = runBlocking {
        val instant = Instant.parse("2026-07-30T16:05:00Z")
        val clock = FakeRecordScreenClock(
            RecordScreenTimeSnapshot(instant, ZoneId.of("Asia/Shanghai")),
        )
        val ticks = recordScreenMinuteTicks(clock)

        val beforePause = ticks.take(1).toList().single()
        clock.current = RecordScreenTimeSnapshot(
            instant = instant.minusSeconds(120),
            zoneId = ZoneId.of("America/Los_Angeles"),
        )
        val afterResume = ticks.take(1).toList().single()

        assertEquals(LocalDate.of(2026, 7, 31), beforePause.localDate)
        assertEquals(LocalDate.of(2026, 7, 30), afterResume.localDate)
        assertEquals(ZoneId.of("America/Los_Angeles"), afterResume.zoneId)
        assertEquals(instant.minusSeconds(120), afterResume.instant)
    }

    @Test
    fun cancellingCollectionCancelsThePendingBoundaryWait() = runBlocking {
        val waitStarted = CompletableDeferred<Unit>()
        val waitCancelled = CompletableDeferred<Unit>()
        val clock = FakeRecordScreenClock(
            RecordScreenTimeSnapshot(
                instant = Instant.parse("2026-07-30T04:34:30Z"),
                zoneId = ZoneId.of("Asia/Shanghai"),
            ),
        )
        val collection = launch {
            recordScreenMinuteTicks(
                clock = clock,
                awaitBoundary = {
                    waitStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        waitCancelled.complete(Unit)
                    }
                },
            ).collect { }
        }

        waitStarted.await()
        collection.cancelAndJoin()

        assertTrue(waitCancelled.isCompleted)
    }

    @Test
    fun clockRollbackDoesNotProduceANegativeRelativeTimeLabel() {
        val rolledBack = RecordScreenTimeSnapshot(
            instant = Instant.parse("2026-07-30T04:33:00Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )
        val recordTimestamp = Instant.parse("2026-07-30T04:34:00Z").toEpochMilli()

        assertEquals("刚刚", relativeTimeLabel(recordTimestamp, rolledBack.epochMillis))
    }

    @Test
    fun nextMinuteSnapshotTurnsADuePlanIntoMissed() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val beforeDue = RecordScreenTimeSnapshot(
            instant = Instant.parse("2026-07-30T04:34:59.750Z"),
            zoneId = zone,
        )
        val afterDue = beforeDue.copy(instant = Instant.parse("2026-07-30T04:35:00Z"))
        val clock = FakeRecordScreenClock(beforeDue)
        val plan = CarePlan(
            clientUuid = "plan-clock-boundary",
            babyId = 1L,
            type = RecordType.BATH,
            scheduledAt = Instant.parse("2026-07-30T04:34:59.900Z").toEpochMilli(),
            scheduledZoneId = zone.id,
            updatedAt = beforeDue.epochMillis,
        )

        val statuses = recordScreenMinuteTicks(
            clock = clock,
            awaitBoundary = { clock.current = afterDue },
        ).take(2).toList().map { snapshot ->
            plan.effectiveStatus(snapshot.epochMillis)
        }

        assertEquals(listOf(CarePlanStatus.PENDING, CarePlanStatus.MISSED), statuses)
    }

    @Test
    fun sameDayMinuteTicksShareTimelineLoadKeyAndNextDayDoesNot() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 7, 30)
        val beforeDue = TimelineWindowRequest(
            babyId = 1L,
            selectedDay = day,
            zoneId = zone,
            nowMillis = Instant.parse("2026-07-30T04:34:59.750Z").toEpochMilli(),
        )
        val afterDue = beforeDue.copy(
            nowMillis = Instant.parse("2026-07-30T04:35:00Z").toEpochMilli(),
        )
        val nextDay = beforeDue.copy(
            selectedDay = day.plusDays(1),
            nowMillis = Instant.parse("2026-07-30T16:00:00Z").toEpochMilli(),
        )

        assertEquals(beforeDue.loadKey, afterDue.loadKey)
        assertEquals(beforeDue.overdueHorizonMillis, afterDue.overdueHorizonMillis)
        assertTrue(beforeDue.loadKey != nextDay.loadKey)
    }

    private class FakeRecordScreenClock(
        var current: RecordScreenTimeSnapshot,
    ) : RecordScreenClock {
        override fun snapshot(): RecordScreenTimeSnapshot = current
    }
}
