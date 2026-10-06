package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTimelineLanesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 7, 22)
    private val clipStart = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val clipEnd = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun springForwardRecordsAndSleepUseElapsedInstants() {
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 3, 8)
        val clipStartMs = dstDay.minusDays(1).atStartOfDay(dstZone).toInstant().toEpochMilli()
        val clipEndMs = dstDay.plusDays(2).atStartOfDay(dstZone).toInstant().toEpochMilli()
        val sleepStart = dstDay.atTime(1, 30).atZone(dstZone).toInstant().toEpochMilli()
        val sleepEnd = dstDay.atTime(3, 30).atZone(dstZone).toInstant().toEpochMilli()
        val nextMidnight = dstDay.plusDays(1).atStartOfDay(dstZone).toInstant().toEpochMilli()

        val lanes = buildTimelineLanes(
            records = listOf(
                record(100, RecordType.SLEEP, sleepStart, endTimestamp = sleepEnd),
                record(101, RecordType.PEE, nextMidnight),
            ),
            clipStartMs = clipStartMs,
            clipEndExclusiveMs = clipEndMs,
            zoneId = dstZone,
        )

        val sleep = lanes.sleep.single()
        assertEquals(hours(1), sleep.endMs - sleep.startMs)
        assertEquals(nextMidnight, lanes.care.single().startMs)
    }

    @Test
    fun overnightSleepIsOneContinuousSegmentAcrossMidnight() {
        val sleepStart = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val sleepEnd = day.plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(
            record(
                id = 1,
                type = RecordType.SLEEP,
                timestamp = sleepStart,
                endTimestamp = sleepEnd,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            ),
        )

        assertEquals(1, lanes.sleep.size)
        val seg = lanes.sleep.single()
        assertEquals(sleepStart, seg.startMs)
        assertEquals(sleepEnd, seg.endMs)
        assertEquals(DayChartCategory.SLEEP.name, seg.dayChartCategoryKey)
        assertTrue(seg.startMs < midnight)
        assertTrue(seg.endMs > midnight)
        assertTrue(seg.endMs - seg.startMs > hours(2))
    }

    @Test
    fun sleepSpanningClipStartIsClippedOnlyAtRangeEdge() {
        val sleepStart = clipStart - hours(2)
        val sleepEnd = day.minusDays(1).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(
            record(
                id = 2,
                type = RecordType.SLEEP,
                timestamp = sleepStart,
                endTimestamp = sleepEnd,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            ),
        )

        val seg = lanes.sleep.single()
        assertEquals(clipStart, seg.startMs)
        assertEquals(sleepEnd, seg.endMs)
    }

    @Test
    fun eventsFromAllThreeDaysKeepAbsoluteInstants() {
        val dMinus = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dNoon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dPlus = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(
            record(10, RecordType.PEE, dMinus),
            record(11, RecordType.FORMULA, dNoon, payloadJson = """{"amount_ml":120}"""),
            record(12, RecordType.POOP, dPlus),
        )

        assertEquals(dMinus, lanes.care[0].startMs)
        assertEquals(dNoon, lanes.feed.single().startMs)
        assertEquals(dPlus, lanes.care[1].startMs)
    }

    @Test
    fun sameCategoryAcrossThreeDaysSharesKey_for72hFilterHighlight() {
        val dMinus = day.minusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val dNoon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dPlus = day.plusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val primaryStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val primaryEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(
            record(20, RecordType.PEE, dMinus),
            record(21, RecordType.PEE, dNoon),
            record(22, RecordType.PEE, dPlus),
            record(23, RecordType.FORMULA, dNoon, payloadJson = """{"amount_ml":90}"""),
        )

        assertEquals(3, lanes.care.size)
        assertTrue(lanes.care.all { it.dayChartCategoryKey == DayChartCategory.PEE.name })
        assertTrue(lanes.care.any { it.startMs < primaryStart })
        assertTrue(lanes.care.any { it.startMs in primaryStart until primaryEnd })
        assertTrue(lanes.care.any { it.startMs >= primaryEnd })
        assertEquals(DayChartCategory.MILK.name, lanes.feed.single().dayChartCategoryKey)
    }

    @Test
    fun eventsOutsideClipAreIgnored() {
        val outside = day.minusDays(2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(record(99, RecordType.PEE, outside))
        assertTrue(lanes.care.isEmpty())
        assertTrue(lanes.feed.isEmpty())
        assertTrue(lanes.sleep.isEmpty())
    }

    @Test
    fun changingSelectedDayClipKeepsTheSameAbsoluteSleepInstants() {
        val sleepStart = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val sleepEnd = day.plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val sleep = record(
            id = 1,
            type = RecordType.SLEEP,
            timestamp = sleepStart,
            endTimestamp = sleepEnd,
        )
        val onDay = lanesOf(sleep)
        val onNext = buildTimelineLanes(
            records = listOf(sleep),
            clipStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            clipEndExclusiveMs = day.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli(),
            zoneId = zone,
        )
        assertEquals(onDay.sleep.single().startMs, onNext.sleep.single().startMs)
        assertEquals(onDay.sleep.single().endMs, onNext.sleep.single().endMs)
        assertEquals(sleepStart, onNext.sleep.single().startMs)
    }

    @Test
    fun openSleepSplitsUncertainTailAfterStaleFamilySync() {
        val start = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val lastSuccess = start + hours(2)
        val now = lastSuccess + OPEN_SLEEP_UNCERTAIN_AFTER_SYNC_MILLIS
        val lanes = buildTimelineLanes(
            records = listOf(record(1, RecordType.SLEEP, start)),
            clipStartMs = clipStart,
            clipEndExclusiveMs = clipEnd,
            zoneId = zone,
            nowMs = now,
            familyJoined = true,
            lastSuccessAtMs = lastSuccess,
        )
        val seg = lanes.sleep.single()
        assertTrue(seg.detail.contains("同步后未确认"))
        assertEquals(lastSuccess, seg.uncertainFromMs)
        assertTrue(seg.endMs > lastSuccess)
    }

    @Test
    fun openSleepStaysSolidWhenNotFamilyOrSyncIsFreshOrSleepIsLocal() {
        val start = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val lastSuccess = start + hours(2)
        val freshNow = lastSuccess + 10 * 60_000L
        val staleNow = lastSuccess + OPEN_SLEEP_UNCERTAIN_AFTER_SYNC_MILLIS
        val unjoined = buildTimelineLanes(
            records = listOf(record(1, RecordType.SLEEP, start)),
            clipStartMs = clipStart,
            clipEndExclusiveMs = clipEnd,
            zoneId = zone,
            nowMs = staleNow,
            familyJoined = false,
            lastSuccessAtMs = lastSuccess,
        ).sleep.single()
        val fresh = buildTimelineLanes(
            records = listOf(record(2, RecordType.SLEEP, start)),
            clipStartMs = clipStart,
            clipEndExclusiveMs = clipEnd,
            zoneId = zone,
            nowMs = freshNow,
            familyJoined = true,
            lastSuccessAtMs = lastSuccess,
        ).sleep.single()
        val localAfterSync = buildTimelineLanes(
            records = listOf(record(3, RecordType.SLEEP, lastSuccess + 60_000L)),
            clipStartMs = clipStart,
            clipEndExclusiveMs = clipEnd,
            zoneId = zone,
            nowMs = staleNow,
            familyJoined = true,
            lastSuccessAtMs = lastSuccess,
        ).sleep.single()
        assertEquals(null, unjoined.uncertainFromMs)
        assertEquals(null, fresh.uncertainFromMs)
        assertEquals(null, localAfterSync.uncertainFromMs)
        assertTrue(unjoined.detail.contains("未结束"))
    }

    @Test
    fun openSleepUncertainTailBeforeClipIsEntirelyDashed() {
        val lastSuccess = day.minusDays(2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val start = lastSuccess - hours(1)
        val now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val seg = buildTimelineLanes(
            records = listOf(record(1, RecordType.SLEEP, start)),
            clipStartMs = clipStart,
            clipEndExclusiveMs = clipEnd,
            zoneId = zone,
            nowMs = now,
            familyJoined = true,
            lastSuccessAtMs = lastSuccess,
        ).sleep.single()
        assertEquals(seg.startMs, seg.uncertainFromMs)
        assertTrue(seg.detail.contains("同步后未确认"))
    }

    @Test
    fun laneSegmentsCarryRecordSemanticRoles() {
        val at = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = lanesOf(
            record(1, RecordType.SLEEP, at, endTimestamp = at + 30 * 60_000L),
            record(2, RecordType.FORMULA, at),
            record(3, RecordType.NURSING, at),
            record(4, RecordType.PUMPED_FEED, at),
            record(5, RecordType.PUMP_EXPRESS, at),
            record(6, RecordType.PEE, at),
            record(7, RecordType.POOP, at),
            record(8, RecordType.BOTH_DIAPER, at),
            record(9, RecordType.BATH, at),
            record(10, RecordType.TEMPERATURE, at),
            record(11, RecordType.MEDICINE, at),
        )

        assertEquals(listOf(LeziRecordColorRole.Sleep), lanes.sleep.map { it.colorRole })
        assertEquals(
            listOf(
                LeziRecordColorRole.Milk,
                LeziRecordColorRole.Nursing,
                LeziRecordColorRole.Nursing,
                LeziRecordColorRole.Nursing,
            ),
            lanes.feed.map { it.colorRole },
        )
        assertEquals(
            listOf(
                LeziRecordColorRole.Pee,
                LeziRecordColorRole.Poop,
                LeziRecordColorRole.Pee,
                LeziRecordColorRole.Poop,
                LeziRecordColorRole.Wake,
                LeziRecordColorRole.Temperature,
                LeziRecordColorRole.Care,
            ),
            lanes.care.map { it.colorRole },
        )
    }

    private fun lanesOf(vararg records: Record) = buildTimelineLanes(
        records = records.toList(),
        clipStartMs = clipStart,
        clipEndExclusiveMs = clipEnd,
        zoneId = zone,
    )

    private fun hours(value: Long): Long = value * 60L * 60_000L

    private fun record(
        id: Long,
        type: RecordType,
        timestamp: Long,
        endTimestamp: Long? = null,
        payloadJson: String = "{}",
    ) = Record(
        id = id,
        clientUuid = "u$id",
        babyId = 1,
        type = type,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        payloadJson = payloadJson,
        updatedAt = timestamp,
        deletedAt = null,
    )
}
