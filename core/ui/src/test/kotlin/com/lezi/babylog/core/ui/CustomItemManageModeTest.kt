package com.lezi.babylog.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the shipped reduce + mode-specific confirmation copy used by both
 * settings and layout-edit entry points.
 */
class CustomItemManageModeTest {
    @Test
    fun confirmCommandIsSharedAcrossModes() {
        val row = CustomItemManageRow(7L, "辅食", 1)
        val requested = reduceCustomItemDelete(
            CustomItemDeleteState(),
            CustomItemDeleteAction.Request(row),
        )
        val confirmed = reduceCustomItemDelete(
            requested.state,
            CustomItemDeleteAction.Confirm,
        )
        assertEquals(7L, confirmed.command?.itemId)
        assertTrue(confirmed.state.deleting)
    }

    @Test
    fun deleteCopyDiffersByModeButSharesTitleName() {
        val row = CustomItemManageRow(1L, "抚触", 0)
        val settings = customItemDeleteConfirmation(row, CustomItemManageMode.Settings)
        val layout = customItemDeleteConfirmation(row, CustomItemManageMode.LayoutEdit)
        assertTrue(settings.title.contains("抚触"))
        assertTrue(layout.message.contains("抚触"))
        assertTrue(settings.message.contains("本机显示"))
        assertTrue(layout.message.contains("本机已删除"))
        assertFalse(settings.message == layout.message)
    }

    @Test
    fun scopeGuidanceIsModeSpecific() {
        assertTrue(
            customItemDialogScopeGuidance(CustomItemManageMode.Settings).contains("本机显示"),
        )
        assertTrue(
            customItemDialogScopeGuidance(CustomItemManageMode.LayoutEdit).contains("本机已删除"),
        )
    }
}
