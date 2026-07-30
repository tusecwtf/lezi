package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.TimelineAxis
import com.lezi.babylog.domain.DayChartCategory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTimelineLanesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 7, 22)
    private val window = threeDayContentWindow(day, zone)

    @Test
    fun overnightSleepIsOneContinuousSegmentAcrossMidnight() {
        // Sleep 22:00 on D → 06:00 on D+1 (crosses midnight once).
        val sleepStart = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val sleepEnd = day.plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(
                record(
                    id = 1,
                    type = RecordType.SLEEP,
                    timestamp = sleepStart,
                    endTimestamp = sleepEnd,
                    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                ),
            ),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
        )

        assertEquals(1, lanes.sleep.size)
        val seg = lanes.sleep.single()
        val expectedStart = TimelineAxis.PRIMARY_DAY_START_MINUTES + 22 * 60
        val expectedEnd = TimelineAxis.PRIMARY_DAY_START_MINUTES + TimelineAxis.MINUTES_PER_DAY + 6 * 60
        assertEquals(expectedStart, seg.startMinOfDay)
        assertEquals(expectedEnd, seg.endMinOfDay)
        assertEquals(DayChartCategory.SLEEP.name, seg.dayChartCategoryKey)
        // Continuity: a single span longer than remaining minutes of D.
        assertTrue(seg.endMinOfDay - seg.startMinOfDay > TimelineAxis.MINUTES_PER_DAY - 22 * 60)
    }

    @Test
    fun sleepSpanningWindowStartIsClippedOnlyAtWindowEdge() {
        // Started 2h before D−1 00:00, ends 03:00 on D−1 — only the in-window tail is drawn.
        val windowOrigin = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sleepStart = windowOrigin - 2 * 60 * 60_000L
        val sleepEnd = day.minusDays(1).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(
                record(
                    id = 2,
                    type = RecordType.SLEEP,
                    timestamp = sleepStart,
                    endTimestamp = sleepEnd,
                    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                ),
            ),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
        )

        assertEquals(1, lanes.sleep.size)
        val seg = lanes.sleep.single()
        assertEquals(0, seg.startMinOfDay)
        assertEquals(3 * 60, seg.endMinOfDay)
    }

    @Test
    fun eventsFromAllThreeDaysMapOntoContentAxis() {
        val dMinus = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dNoon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dPlus = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(
                record(10, RecordType.PEE, dMinus),
                record(11, RecordType.FORMULA, dNoon, payloadJson = """{"amount_ml":120}"""),
                record(12, RecordType.POOP, dPlus),
            ),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
        )

        assertEquals(1, lanes.feed.size)
        assertEquals(2, lanes.care.size)
        assertEquals(12 * 60, lanes.care[0].startMinOfDay)
        assertEquals(TimelineAxis.PRIMARY_DAY_START_MINUTES + 12 * 60, lanes.feed.single().startMinOfDay)
        assertEquals(
            TimelineAxis.PRIMARY_DAY_START_MINUTES + TimelineAxis.MINUTES_PER_DAY + 12 * 60,
            lanes.care[1].startMinOfDay,
        )
    }

    @Test
    fun sameCategoryAcrossThreeDaysSharesKey_for72hFilterHighlight() {
        // Once page selection is PEE, every matching rail mark (neighbor + D) lights
        // via selectedCategoryKey equality — no per-day highlight pass.
        val dMinus = day.minusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val dNoon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dPlus = day.plusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(
                record(20, RecordType.PEE, dMinus),
                record(21, RecordType.PEE, dNoon),
                record(22, RecordType.PEE, dPlus),
                record(23, RecordType.FORMULA, dNoon, payloadJson = """{"amount_ml":90}"""),
            ),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
        )

        assertEquals(3, lanes.care.size)
        assertTrue(lanes.care.all { it.dayChartCategoryKey == DayChartCategory.PEE.name })
        // Content minutes span all three days on the continuous 72h axis.
        assertTrue(lanes.care.any { it.startMinOfDay < TimelineAxis.PRIMARY_DAY_START_MINUTES })
        assertTrue(
            lanes.care.any {
                it.startMinOfDay in TimelineAxis.PRIMARY_DAY_START_MINUTES until
                    (TimelineAxis.PRIMARY_DAY_START_MINUTES + TimelineAxis.MINUTES_PER_DAY)
            },
        )
        assertTrue(
            lanes.care.any {
                it.startMinOfDay >= TimelineAxis.PRIMARY_DAY_START_MINUTES + TimelineAxis.MINUTES_PER_DAY
            },
        )
        val selectedKey = DayChartCategory.PEE.name
        assertTrue(lanes.care.all { it.dayChartCategoryKey == selectedKey })
        assertTrue(lanes.feed.none { it.dayChartCategoryKey == selectedKey })
        assertEquals(DayChartCategory.MILK.name, lanes.feed.single().dayChartCategoryKey)
    }

    @Test
    fun eventsOutsideWindowAreIgnored() {
        val outside = day.minusDays(2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(record(99, RecordType.PEE, outside)),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
        )
        assertTrue(lanes.care.isEmpty())
        assertTrue(lanes.feed.isEmpty())
        assertTrue(lanes.sleep.isEmpty())
    }

    @Test
    fun laneSegmentsCarryRecordSemanticRoles() {
        val at = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lanes = buildTimelineLanes(
            records = listOf(
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
            ),
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            zone = zone,
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
