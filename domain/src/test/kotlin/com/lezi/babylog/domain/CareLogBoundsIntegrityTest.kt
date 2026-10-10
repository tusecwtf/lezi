package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.IntBound
import com.lezi.babylog.domain.carelog.LongBound
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogBoundsIntegrityTest {
    private val day = LocalDate.of(2024, 6, 1)
    private val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Test
    fun nextDayDuplicateRemainsAZeroContributionChoiceForSelectedDay() = runTest {
        val care = Fakes().careLog()
        val rows = listOf(
            Record(clientUuid = "before", babyId = 1, type = RecordType.FORMULA,
                timestamp = start + 24 * hour - 600_000, updatedAt = start,
                payloadJson = """{"amount_ml":100}""", createdByMembershipId = "a"),
            Record(clientUuid = "after", babyId = 1, type = RecordType.FORMULA,
                timestamp = start + 24 * hour + 600_000, updatedAt = start,
                payloadJson = """{"amount_ml":120}""", createdByMembershipId = "b"),
        )
        val result = care.suspectedDuplicateProjection(rows, day, 1, ZoneOffset.UTC, start + 48 * hour)
        assertThat(result.bounds.formulaMl).isEqualTo(IntBound(0, 100))
    }

    @Test
    fun historicalBoundsUseRealClockForStaleOpenSleepAndExactMidnightForClosedSleep() = runTest {
        val care = Fakes().careLog()
        fun sleep(uuid: String, end: Long?) = Record(
            clientUuid = uuid, babyId = 1, type = RecordType.SLEEP,
            timestamp = start + 23 * hour, endTimestamp = end, updatedAt = start,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        val stale = care.suspectedDuplicateProjection(
            listOf(sleep("open", null)), day, 1, ZoneOffset.UTC, start + 72 * hour,
        )
        assertThat(stale.bounds.sleepMinutes).isEqualTo(LongBound(0, 0))
        val closed = care.suspectedDuplicateProjection(
            listOf(sleep("closed", start + 24 * hour)), day, 1, ZoneOffset.UTC, start + 72 * hour,
        )
        assertThat(closed.bounds.sleepMinutes).isEqualTo(LongBound(60, 60))
    }
}
