package com.lezi.babylog.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSettingsMenuTest {
    @Test
    fun recordSettingsSectionsAreOnlyPerItemAndPlanCalendar() {
        val sections = recordSettingsSections()
        assertEquals(2, sections.size)
        assertEquals(
            listOf(RecordSettingsSection.PerItem, RecordSettingsSection.PlanCalendar),
            sections,
        )
        val titles = sections.map { it.title }
        assertFalse(titles.any { it.contains("常用") })
        assertFalse(titles.any { it.contains("所有记录") })
        assertTrue(titles.contains("分项目设置") || sections.any { it == RecordSettingsSection.PerItem })
        assertTrue(sections.any { it == RecordSettingsSection.PlanCalendar })
    }

    @Test
    fun legacyHubDestinationsAliasMatchesRecordSettingsOnly() {
        assertEquals(recordSettingsSections(), recordShortcutHubDestinations())
    }
}
