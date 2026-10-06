package com.lezi.babylog.feature.log.timeline

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDayGridTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun coveringMidnightsSpanTheRequestedAbsoluteRange() {
        val day = LocalDate.of(2026, 7, 22)
        val start = day.minusDays(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val end = day.plusDays(2).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, shanghai)

        assertEquals(hours(72), grid.durationMs)
        assertEquals(
            listOf(
                day.minusDays(1),
                day,
                day.plusDays(1),
                day.plusDays(2),
            ).map { it.atStartOfDay(shanghai).toInstant().toEpochMilli() },
            grid.localMidnightEpochMillis,
        )
        assertEquals(
            listOf(
                day.atStartOfDay(shanghai).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(shanghai).toInstant().toEpochMilli(),
            ),
            grid.dayBoundariesMs,
        )
    }

    @Test
    fun springForwardThreeLocalDaysAre71Hours() {
        val start = LocalDate.of(2026, 3, 7).atStartOfDay(newYork).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 10).atStartOfDay(newYork).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, newYork)

        assertEquals(hours(71), grid.durationMs)
        assertEquals(4, grid.localMidnightEpochMillis.size)
        assertEquals(hours(23), grid.localMidnightEpochMillis[2] - grid.localMidnightEpochMillis[1])
    }

    @Test
    fun fallBackThreeLocalDaysAre73Hours() {
        val start = LocalDate.of(2026, 10, 31).atStartOfDay(newYork).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 3).atStartOfDay(newYork).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, newYork)

        assertEquals(hours(73), grid.durationMs)
        assertEquals(hours(25), grid.localMidnightEpochMillis[2] - grid.localMidnightEpochMillis[1])
    }

    @Test
    fun shanghaiThreeLocalDaysRemain72Hours() {
        val start = LocalDate.of(2026, 3, 7).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 10).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, shanghai)

        assertEquals(hours(72), grid.durationMs)
    }

    @Test
    fun springGapHasNo0200Tick() {
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 9).atStartOfDay(newYork).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, newYork)

        assertTrue(grid.localDateTimeToInstants(LocalDateTime.of(2026, 3, 8, 2, 30)).isEmpty())
        assertFalse(
            grid.hourTicks(stepHours = 1).any {
                it.localDateTime == LocalDateTime.of(2026, 3, 8, 2, 0)
            },
        )
    }

    @Test
    fun fallOverlapMapsBoth0130InstantsAndDisambiguatesHourLabels() {
        val start = LocalDate.of(2026, 11, 1).atStartOfDay(newYork).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 2).atStartOfDay(newYork).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, newYork)
        val repeated = LocalDateTime.of(2026, 11, 1, 1, 30)
        val instants = grid.localDateTimeToInstants(repeated)

        assertEquals(2, instants.size)
        assertEquals(hours(1), instants[1] - instants[0])
        instants.forEach { instantMs ->
            assertTrue(grid.contains(instantMs))
        }

        val repeatedLabels = grid.hourTicks(stepHours = 1)
            .filter { it.localDateTime == LocalDateTime.of(2026, 11, 1, 1, 0) }
            .map { it.label }
        assertEquals(listOf("01:00 -04:00", "01:00 -05:00"), repeatedLabels)
    }

    @Test
    fun crossDstSleepIntervalsUseElapsedTimeNotLocalClockSubtraction() {
        val spring = LocalDayGrid(
            startMs = LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().toEpochMilli(),
            endExclusiveMs = LocalDate.of(2026, 3, 9).atStartOfDay(newYork).toInstant().toEpochMilli(),
            zoneId = newYork,
        )
        val springSleep = requireNotNull(
            spring.clipInterval(
                startInstantMs = localInstantMs(2026, 3, 8, 1, 30, ZoneOffset.ofHours(-5)),
                endExclusiveInstantMs = localInstantMs(2026, 3, 8, 3, 30, ZoneOffset.ofHours(-4)),
            ),
        )
        assertEquals(hours(1), springSleep.durationMs)

        val fall = LocalDayGrid(
            startMs = LocalDate.of(2026, 11, 1).atStartOfDay(newYork).toInstant().toEpochMilli(),
            endExclusiveMs = LocalDate.of(2026, 11, 2).atStartOfDay(newYork).toInstant().toEpochMilli(),
            zoneId = newYork,
        )
        val fallSleep = requireNotNull(
            fall.clipInterval(
                startInstantMs = localInstantMs(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-4)),
                endExclusiveInstantMs = localInstantMs(2026, 11, 1, 1, 30, ZoneOffset.ofHours(-5)),
            ),
        )
        assertEquals(hours(1), fallSleep.durationMs)
    }

    @Test
    fun intervalClippingCanRepresentTheExclusiveRangeEnd() {
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 9).atStartOfDay(newYork).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, newYork)
        val clipped = requireNotNull(
            grid.clipInterval(
                startInstantMs = start - minutes(30),
                endExclusiveInstantMs = end + minutes(30),
            ),
        )

        assertEquals(start, clipped.startMs)
        assertEquals(end, clipped.endExclusiveMs)
        assertEquals(grid.durationMs, clipped.durationMs)
    }

    @Test
    fun monthDayLabelOmitsLeadingZeros() {
        val start = LocalDate.of(2026, 8, 8).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 9, 12).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, shanghai)
        val aug8 = LocalDate.of(2026, 8, 8).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val sep11 = LocalDate.of(2026, 9, 11).atStartOfDay(shanghai).toInstant().toEpochMilli()

        assertEquals("8/8", grid.monthDayLabel(aug8))
        assertEquals("9/11", grid.monthDayLabel(sep11))
        assertEquals(
            listOf(sep11 to "9/11"),
            grid.dayBoundaryLabels().filter { it.first == sep11 },
        )
    }

    @Test
    fun instantsOutsideTheRangeAreRejected() {
        val start = LocalDate.of(2026, 7, 22).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 7, 23).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val grid = LocalDayGrid(start, end, shanghai)

        assertFalse(grid.contains(start - 1L))
        assertTrue(grid.contains(start))
        assertFalse(grid.contains(end))
        assertNull(grid.clipInterval(end, end + minutes(30)))
    }

    private fun hours(value: Long): Long = value * 60L * 60_000L

    private fun minutes(value: Long): Long = value * 60_000L

    private fun localInstantMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        offset: ZoneOffset,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .toInstant(offset)
        .toEpochMilli()
}
