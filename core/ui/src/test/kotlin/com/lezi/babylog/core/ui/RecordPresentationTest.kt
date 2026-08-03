package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.businessLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordPresentationTest {
    @Test
    fun presentationLabelsDelegateToTheModelAuthority() {
        RecordType.entries.forEach { type ->
            assertEquals(type.businessLabel(), type.presentation.label)
        }
    }

    @Test
    fun sectionsContainEveryRecordTypeExactlyOnce() {
        val grouped = RecordType.entries.groupBy { it.presentation.section }

        assertEquals(RecordSection.entries.toSet(), grouped.keys)
        assertEquals(RecordType.entries.toSet(), grouped.values.flatten().toSet())
        // Complementary food lives under feeding; there is no separate 辅食 section.
        assertEquals(
            RecordSection.Feeding,
            RecordType.BABY_FOOD.presentation.section,
        )
        assertEquals(RecordSection.Feeding, RecordType.SNACK.presentation.section)
        assertEquals(RecordSection.Feeding, RecordType.DRINK.presentation.section)
        // Concrete custom definitions share the CUSTOM presentation.
        assertEquals(RecordSection.Custom, RecordType.CUSTOM.presentation.section)
    }

    @Test
    fun readableSummaryNeverExposesRawJson() {
        val record = record(
            type = RecordType.BOTH_DIAPER,
            payload = """{"pee_amount":3,"stool_amount":2,"stool_consistency":1,"stool_color":5}""",
            note = "皮肤正常",
        )

        val summary = record.presentationSummary()

        assertEquals("尿量大 · 便量偏少 · 稀 · 绿 · 皮肤正常", summary)
        assertFalse(summary.contains("{"))
        assertFalse(summary.contains("stool_"))
    }

    @Test
    fun quickComposerPayloadsRenderAsReadableTimelineSummaries() {
        assertEquals(
            "6.35kg",
            record(RecordType.WEIGHT, """{"value":6350,"unit":"g"}""")
                .presentationSummary(),
        )
        assertEquals(
            "明显 · 夜间连续",
            record(RecordType.COUGH, """{"severity":3,"description":"夜间连续"}""")
                .presentationSummary(),
        )
        assertEquals(
            "乙肝 · 第2针",
            record(RecordType.VACCINE, """{"name":"乙肝","batch":"第2针"}""")
                .presentationSummary(),
        )
    }

    @Test
    fun recordDurationFormatterBoundaryCases() {
        assertEquals("0m", formatRecordDuration(0L))
        assertEquals("2h5m", formatRecordDuration(125L))
    }

    private fun record(
        type: RecordType,
        payload: String = "{}",
        note: String? = null,
        endTimestamp: Long? = null,
    ) = Record(
        id = 1,
        clientUuid = "test",
        babyId = 1,
        type = type,
        timestamp = 1_000,
        endTimestamp = endTimestamp,
        note = note,
        payloadJson = payload,
        updatedAt = 1_000,
    )
}
