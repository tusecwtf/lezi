package com.lezi.babylog.feature.widget

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MidnightDatesTest {
    @Test
    fun eachWakeReadsTheZoneAgain() = runTest {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val losAngeles = ZoneId.of("America/Los_Angeles")
        var zone = shanghai
        var wakes = 0
        val dates = midnightDates(
            zone = { zone },
            now = { active ->
                if (wakes == 0) {
                    ZonedDateTime.of(2026, 9, 26, 23, 0, 0, 0, active)
                } else {
                    ZonedDateTime.of(2026, 9, 27, 1, 0, 0, 0, active)
                }
            },
            sleep = {
                wakes += 1
                zone = losAngeles
            },
        ).take(2).toList()

        assertEquals(
            listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27)),
            dates,
        )
        assertEquals(losAngeles, zone)
    }
}
