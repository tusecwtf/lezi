package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStateCleanerTest {
    @Test
    fun clearAllRemovesEveryConfigurationAndPriorFamilySnapshot() {
        val store = RecordingWidgetStateStore().apply {
            saveConfiguration(WidgetConfiguration(7, 42, listOf(RecordType.PEE)))
            saveConfiguration(WidgetConfiguration(8, 43, listOf(RecordType.POOP)))
            saveSnapshot(WidgetSummarySnapshot(7, 42, "年年", 0, 0, 1, 0, null, 1))
            saveSnapshot(WidgetSummarySnapshot(8, 43, "岁岁", 0, 0, 0, 1, null, 1))
        }

        val clearedWidgetIds = WidgetStateCleaner(store).clearAll()

        assertEquals(listOf(7, 8), clearedWidgetIds)
        assertEquals(emptyList<WidgetConfiguration>(), store.configurations())
        assertNull(store.snapshot(7))
        assertNull(store.snapshot(8))
    }

    @Test
    fun clearAllRemovesSnapshotEvenWhenItsConfigurationIndexIsMissing() {
        val store = RecordingWidgetStateStore().apply {
            saveSnapshot(WidgetSummarySnapshot(9, 44, "旧家庭", 0, 0, 0, 0, null, 1))
        }

        WidgetStateCleaner(store).clearAll()

        assertNull(store.snapshot(9))
    }
}

private class RecordingWidgetStateStore : WidgetStateStore {
    private val configurations = linkedMapOf<Int, WidgetConfiguration>()
    private val snapshots = linkedMapOf<Int, WidgetSummarySnapshot>()

    override fun configurations(): List<WidgetConfiguration> = configurations.values.toList()

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

    override fun clearAll() {
        configurations.clear()
        snapshots.clear()
    }
}
