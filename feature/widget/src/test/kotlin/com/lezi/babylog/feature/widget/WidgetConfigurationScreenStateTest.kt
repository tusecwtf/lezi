package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigurationScreenStateTest {
    @Test
    fun selectionKeepsOrderEnforcesMaximumAndCanRemove() {
        var state = WidgetConfigurationScreenState(
            widgetId = 7,
            babies = listOf(WidgetBabyOption(42, "年年")),
            selectedBabyId = 42,
            selectedTypes = listOf(
                RecordType.NURSING,
                RecordType.FORMULA,
                RecordType.PEE,
            ),
        )

        state = state.toggle(RecordType.SLEEP)
        state = state.toggle(RecordType.POOP)
        assertEquals(
            listOf(
                RecordType.NURSING,
                RecordType.FORMULA,
                RecordType.PEE,
                RecordType.SLEEP,
            ),
            state.selectedTypes,
        )

        state = state.toggle(RecordType.FORMULA)
        assertFalse(RecordType.FORMULA in state.selectedTypes)
        assertTrue(state.canSave)
    }

    @Test
    fun emptySelectionCannotBeSaved() {
        val state = WidgetConfigurationScreenState(
            widgetId = 7,
            babies = listOf(WidgetBabyOption(42, "年年")),
            selectedBabyId = 42,
            selectedTypes = emptyList(),
        )

        assertFalse(state.canSave)
    }
}
