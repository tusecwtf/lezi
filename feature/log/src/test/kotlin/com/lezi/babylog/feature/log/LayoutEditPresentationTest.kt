package com.lezi.babylog.feature.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditPresentationTest {
    @Test
    fun editorOwnsFullscreenChromeAndDoesNotRepeatCommonSupplement() {
        assertEquals("编辑布局", LayoutEditPresentation.title)
        assertFalse(LayoutEditPresentation.showsDateChrome)
        assertFalse(LayoutEditPresentation.showsPrimaryNavigation)
        assertFalse(LayoutEditPresentation.showsCommonSupplement)
    }

    @Test
    fun catalogUsesAuthoritativeFourColumnRows() {
        assertEquals(4, RecordCatalogVisualSpec.columnCount)
        assertEquals(
            listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8), listOf(9)),
            recordCatalogRows((1..9).toList()),
        )
    }

    @Test
    fun editorKeepsFourConfigurableSlotsThenLockedMore() {
        val dock = LayoutEditPresentation.dockCells

        assertEquals(QuickDockVisualSpec.configurableSlotCount + 1, dock.size)
        assertEquals(
            listOf(0, 1, 2, 3),
            dock.filterIsInstance<LayoutEditDockCell.Configurable>().map { it.index },
        )
        assertTrue(dock.last() is LayoutEditDockCell.LockedMore)
    }

    @Test
    fun everyVisibleCatalogSectionHasAHeading() {
        assertEquals(
            LayoutEditPresentation.catalogSections.size,
            LayoutEditPresentation.catalogSections.map { it.title }.distinct().size,
        )
        assertTrue(LayoutEditPresentation.catalogSections.all { it.title.isNotBlank() })
        assertTrue(LayoutEditPresentation.showsLocalDeletedHeading)
    }
}
