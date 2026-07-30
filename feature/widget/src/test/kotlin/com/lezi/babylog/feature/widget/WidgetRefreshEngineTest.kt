package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshEngineTest {
    @Test
    fun refreshLoadsOnlyTheConfiguredBabyAndPersistsAStaticSnapshot() = runTest {
        val store = InMemoryWidgetStateStore().apply {
            saveConfiguration(WidgetConfiguration(7, 42, listOf(RecordType.PEE)))
        }
        val requestedBabyIds = mutableListOf<Long>()
        val engine = WidgetRefreshEngine(
            store = store,
            summarySource = WidgetSummarySource { babyId ->
                requestedBabyIds += babyId
                WidgetSummaryData("年年", 90, 60, 3, 1, "尿尿 · 09:30")
            },
            now = { 1234L },
        )

        val fresh = engine.refresh(7)
        val cached = engine.cachedDisplay(7)

        assertEquals(listOf(42L), requestedBabyIds)
        assertEquals("年年", fresh.title)
        assertEquals(fresh, cached)
        assertEquals(1234L, store.snapshot(7)?.updatedAtEpochMillis)
        assertTrue(store.snapshot(7)?.lastLabelIsCanonical == true)
        assertTrue(fresh.secondarySummary.endsWith("尿尿 · 09:30"))
        assertFalse(fresh.isStale)
    }

    @Test
    fun failedRefreshFallsBackToSameBabySnapshotAndMarksItStale() = runTest {
        val store = InMemoryWidgetStateStore().apply {
            saveConfiguration(WidgetConfiguration(7, 42, listOf(RecordType.PEE)))
            saveSnapshot(
                WidgetSummarySnapshot(
                    7, 42, "年年", 90, 60, 3, 1, null, 1234,
                ),
            )
        }
        val engine = WidgetRefreshEngine(
            store = store,
            summarySource = WidgetSummarySource { error("database unavailable") },
        )

        val model = engine.refresh(7)

        assertEquals("年年", model.title)
        assertTrue(model.isStale)
    }
}

private class InMemoryWidgetStateStore : WidgetStateStore {
    private val configurations = mutableMapOf<Int, WidgetConfiguration>()
    private val snapshots = mutableMapOf<Int, WidgetSummarySnapshot>()

    override fun configurations(): List<WidgetConfiguration> =
        configurations.values.sortedBy(WidgetConfiguration::widgetId)

    override fun configuration(widgetId: Int): WidgetConfiguration? = configurations[widgetId]

    override fun saveConfiguration(configuration: WidgetConfiguration) {
        configurations[configuration.widgetId] = configuration
    }

    override fun snapshot(widgetId: Int): WidgetSummarySnapshot? = snapshots[widgetId]

    override fun saveSnapshot(snapshot: WidgetSummarySnapshot) {
        snapshots[snapshot.widgetId] = snapshot
    }

    override fun remove(widgetId: Int) {
        configurations.remove(widgetId)
        snapshots.remove(widgetId)
    }
}
