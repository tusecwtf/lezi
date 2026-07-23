package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziRecordGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPresentationTest {
    @Test
    fun everyRecordTypeHasAnExplicitUsefulPresentation() {
        val presentations = RecordType.entries.associateWith { it.presentation }

        assertEquals(30, presentations.size)
        presentations.forEach { (type, presentation) ->
            assertTrue("$type label", presentation.label.isNotBlank())
            assertTrue("$type tip", presentation.tip.isNotBlank())
        }
        assertEquals(LeziRecordGlyph.Pee, RecordType.PEE.presentation.glyph)
        assertEquals(LeziRecordGlyph.Poop, RecordType.POOP.presentation.glyph)
        assertEquals(LeziRecordGlyph.Poop, RecordType.BOTH_DIAPER.presentation.glyph)
        assertEquals(LeziRecordGlyph.Other, RecordType.CUSTOM.presentation.glyph)
    }

    @Test
    fun sectionsContainEveryRecordTypeExactlyOnce() {
        val grouped = RecordType.entries.groupBy { it.presentation.section }

        assertEquals(RecordSection.entries.toSet(), grouped.keys)
        assertEquals(RecordType.entries.toSet(), grouped.values.flatten().toSet())
    }

    @Test
    fun excretionLabelsMatchPersistedNumericContract() {
        assertEquals(listOf("小", "中", "大"), (1..3).map(::peeAmountLabel))
        assertEquals(listOf("一点", "偏少", "正常", "偏多"), (1..4).map(::stoolAmountLabel))
        assertEquals(listOf("稀", "偏软", "正常", "偏硬"), (1..4).map(::stoolConsistencyLabel))
        assertEquals(
            listOf("未选", "白", "黄", "橙", "褐", "绿", "红", "黑"),
            (0..7).map(::stoolColorLabel),
        )
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
    fun emptySpecializedPayloadUsesHelpfulCopy() {
        assertEquals("自由文本", record(RecordType.OTHER).presentationSummary())
        assertEquals("正文/照片", record(RecordType.DIARY).presentationSummary())
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
    fun activeNapSummaryKeepsBothNapAndRunningState() {
        assertEquals(
            "午睡 · 进行中",
            record(RecordType.SLEEP, """{"is_nap":true}""").presentationSummary(),
        )
    }

    @Test
    fun subMinuteSleepUsesHonestNonZeroCopy() {
        assertEquals(
            "时长 不足1分",
            record(
                type = RecordType.SLEEP,
                endTimestamp = 1_000L + 30_000L,
            ).presentationSummary(),
        )
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
        createdByUserId = 1,
        payloadJson = payload,
        updatedAt = 1_000,
    )
}
