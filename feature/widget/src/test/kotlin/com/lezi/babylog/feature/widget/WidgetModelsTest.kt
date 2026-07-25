package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelsTest {
    @Test
    fun configuredModel_containsAllThreeSummariesAndOrderedActions() {
        val configuration = WidgetConfiguration(
            widgetId = 7,
            babyId = 11,
            quickTypes = listOf(RecordType.PEE, RecordType.FORMULA),
        )
        val snapshot = WidgetSummarySnapshot(
            widgetId = 7,
            babyId = 11,
            babyName = "年年",
            feedMl = 180,
            sleepMinutes = 135,
            peeCount = 4,
            poopCount = 2,
            lastLabel = "formula · 12:30",
            updatedAtEpochMillis = 100,
        )

        val model = configuredWidgetDisplayModel(configuration, snapshot)

        assertEquals("年年", model.title)
        assertEquals("喂养 180ml · 睡眠 2时15分", model.primarySummary)
        assertEquals("排泄 尿4/便2 · 最近 配方奶 · 12:30", model.secondarySummary)
        assertEquals(listOf(RecordType.PEE, RecordType.FORMULA), model.quickActions.map { it.type })
        assertFalse(model.isStale)
    }

    @Test
    fun snapshotForAnotherBaby_isNeverRendered() {
        val configuration = WidgetConfiguration(7, 11, listOf(RecordType.PEE))
        val wrongBaby = WidgetSummarySnapshot(
            widgetId = 7,
            babyId = 12,
            babyName = "不应显示",
            feedMl = 999,
            sleepMinutes = 999,
            peeCount = 9,
            poopCount = 9,
            lastLabel = null,
            updatedAtEpochMillis = 100,
        )

        val model = configuredWidgetDisplayModel(configuration, wrongBaby)

        assertEquals("乐记", model.title)
        assertEquals("喂养 0ml · 睡眠 0分", model.primarySummary)
        assertTrue(model.isStale)
    }

    @Test
    fun configurationRejectsDuplicateEmptyAndOversizedQuickLists() {
        assertThrows(IllegalArgumentException::class.java) {
            WidgetConfiguration(7, 11, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetConfiguration(7, 11, listOf(RecordType.PEE, RecordType.PEE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetConfiguration(
                7,
                11,
                RecordType.entries.take(MAX_WIDGET_QUICK_TYPES + 1),
            )
        }
    }
}
