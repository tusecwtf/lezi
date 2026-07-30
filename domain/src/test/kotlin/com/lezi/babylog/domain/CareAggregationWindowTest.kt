package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareAggregationWindowTest {
    private val zone = ZoneOffset.UTC
    private val start = LocalDate.of(2026, 6, 24)

    @Test
    fun largeWindowReadsEachSourceRecordOnce() = runTest {
        val source = ReadCountingList(
            List(3_000) { index ->
                val date = start.plusDays((index % 30).toLong())
                record(index.toLong(), date)
            },
        )

        CareAggregation.window(
            records = source,
            startDate = start,
            dayCount = 30,
            zone = zone,
            now = start.plusDays(31).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        assertThat(source.readCount).isEqualTo(source.size)
    }

    @Test
    fun widgetLatestLabelPreservesTheSavedCustomSnapshot() {
        val timestamp = start.atTime(10, 0).toInstant(zone).toEpochMilli()
        val custom = Record(
            id = 9,
            clientUuid = "custom-9",
            babyId = 1,
            type = RecordType.CUSTOM,
            timestamp = timestamp,
            payloadJson =
                """{"title":"抚触","detail":"晚间","custom_item_id":9,"icon_slot":2}""",
            updatedAt = timestamp,
        )

        val summary = CareAggregation.widget(
            records = listOf(custom),
            babyName = "年年",
            date = start,
            zone = zone,
            now = timestamp + 1,
        )

        assertThat(summary.lastLabel).startsWith("抚触 ·")
        assertThat(summary.lastLabel).doesNotContain("custom")
    }

    private fun record(id: Long, date: LocalDate): Record {
        val timestamp = date.atTime(10, 0).toInstant(zone).toEpochMilli()
        return Record(
            id = id,
            clientUuid = "record-$id",
            babyId = 1,
            type = RecordType.FORMULA,
            timestamp = timestamp,
            payloadJson = """{"amount_ml":1}""",
            updatedAt = timestamp,
        )
    }
}

private class ReadCountingList(
    private val records: List<Record>,
) : AbstractList<Record>() {
    var readCount: Int = 0
        private set

    override val size: Int get() = records.size

    override fun get(index: Int): Record {
        readCount += 1
        return records[index]
    }
}
