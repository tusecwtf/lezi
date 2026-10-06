package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("喂养 180ml · 睡眠 2h15m", model.primarySummary)
        assertEquals("排泄 尿4/便2 · 最近 配方奶 · 12:30", model.secondarySummary)
        assertEquals(listOf(RecordType.PEE, RecordType.FORMULA), model.quickActions.map { it.type })
        assertFalse(model.isStale)
    }

    @Test
    fun glancePayloadRoundTripsTheSnapshotAndClearsToUnconfigured() {
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
            lastLabelIsCanonical = true,
        )
        val model = configuredWidgetDisplayModel(configuration, snapshot)
        val decoded = WidgetGlancePayload.decode(WidgetGlancePayload.encode(model))

        assertEquals(model.title, decoded?.title)
        assertEquals(model.primarySummary, decoded?.primarySummary)
        assertEquals(model.secondarySummary, decoded?.secondarySummary)
        assertEquals(model.quickActions.map { it.type }, decoded?.quickActions?.map { it.type })
        assertFalse(decoded!!.isStale)

        val stale = WidgetGlancePayload.decode(WidgetGlancePayload.encode(model.copy(isStale = true)))
        assertTrue(stale!!.isStale)
        assertEquals(model.primarySummary, stale.primarySummary)

        val cleared = WidgetGlancePayload.decode(
            WidgetGlancePayload.encode(unconfiguredWidgetDisplayModel(7)),
        )
        assertEquals(unconfiguredWidgetDisplayModel(7), cleared)

        assertEquals(model, WidgetGlancePayload.resolve(encoded = null, widgetId = 7) { model })
        assertFalse(
            WidgetGlancePayload.resolve(
                encoded = WidgetGlancePayload.encode(unconfiguredWidgetDisplayModel(7)),
                widgetId = 7,
            ) { model }.isConfigured,
        )
        assertNull(WidgetGlancePayload.decode("{"))
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
        assertEquals("喂养 0ml · 睡眠 0m", model.primarySummary)
        assertTrue(model.isStale)
    }

    @Test
    fun labelsUseTheCanonicalMappingAndUnknownStoredKeysDoNotLeak() {
        val configuration = WidgetConfiguration(7, 11, listOf(RecordType.PEE, RecordType.FORMULA))
        val unknown = WidgetSummarySnapshot(
            widgetId = 7,
            babyId = 11,
            babyName = "年年",
            feedMl = 0,
            sleepMinutes = 0,
            peeCount = 0,
            poopCount = 0,
            lastLabel = "future_record_type · 12:30",
            updatedAtEpochMillis = 100,
        )

        val model = configuredWidgetDisplayModel(configuration, unknown)

        assertEquals(listOf("尿尿", "配方奶"), model.quickActions.map { it.label })
        assertEquals("排泄 尿0/便0 · 最近 未知记录 · 12:30", model.secondarySummary)
    }

    @Test
    fun alreadyLocalizedCustomSnapshotRemainsVisible() {
        val configuration = WidgetConfiguration(7, 11, listOf(RecordType.PEE))
        val custom = WidgetSummarySnapshot(
            widgetId = 7,
            babyId = 11,
            babyName = "年年",
            feedMl = 0,
            sleepMinutes = 0,
            peeCount = 0,
            poopCount = 0,
            lastLabel = "抚触 · 晚间 · 12:30",
            updatedAtEpochMillis = 100,
            lastLabelIsCanonical = true,
        )

        val model = configuredWidgetDisplayModel(configuration, custom)

        assertEquals("排泄 尿0/便0 · 最近 抚触 · 晚间 · 12:30", model.secondarySummary)
    }

    @Test
    fun canonicalCustomSnapshotThatLooksLikeAStorageKeyIsNeverRelocalized() {
        val configuration = WidgetConfiguration(7, 11, listOf(RecordType.PEE))
        val custom = WidgetSummarySnapshot(
            widgetId = 7,
            babyId = 11,
            babyName = "年年",
            feedMl = 0,
            sleepMinutes = 0,
            peeCount = 0,
            poopCount = 0,
            lastLabel = "bath · 晚间 · 12:30",
            updatedAtEpochMillis = 100,
            lastLabelIsCanonical = true,
        )

        val model = configuredWidgetDisplayModel(configuration, custom)

        assertEquals("排泄 尿0/便0 · 最近 bath · 晚间 · 12:30", model.secondarySummary)
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
