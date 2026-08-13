package com.lezi.babylog.feature.log.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

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

    @Test
    fun localDeletedCopyUsesRestoreLanguageWithoutDeleteJargon() {
        assertEquals("已收起 · 0 项", LayoutEditPresentation.localDeletedHeading(0))
        assertEquals("使用操作或拖出恢复", LayoutEditPresentation.localDeletedHelper)
        assertEquals("松开后收起，并清空常用槽", LayoutEditPresentation.localDeletedHoverHelper)
        assertEquals("暂无收起项目", LayoutEditPresentation.localDeletedEmpty)
        assertEquals("收起", LayoutEditPresentation.hideAction)
        assertEquals("已收起", LayoutEditPresentation.undoMovedToDeleted)
        assertFalse(LayoutEditPresentation.localDeletedHeading(2).contains("本机已删除"))
        assertFalse(LayoutEditPresentation.localDeletedHelper.contains("仅在本机隐藏"))
        assertFalse(LayoutEditPresentation.localDeletedDescription(1).contains("本机已删除"))
        assertTrue(LayoutEditPresentation.localDeletedDescription(1).contains("拖出恢复"))
    }
}
