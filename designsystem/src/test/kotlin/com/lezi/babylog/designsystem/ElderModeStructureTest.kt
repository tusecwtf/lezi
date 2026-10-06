package com.lezi.babylog.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElderModeStructureTest {
    @Test
    fun elderDensityUsesTheSpecFiveFieldLiterals() {
        assertEquals(20.dp, LeziDensity.Elder.cardPad)
        assertEquals(16.dp, LeziDensity.Elder.topBarHorizontal)
        assertEquals(20.dp, LeziDensity.Elder.sectionGap)
        assertEquals(20.dp, LeziDensity.Elder.panelContent)
        assertEquals(12.dp, LeziDensity.Elder.dockOuterHorizontal)
    }

    @Test
    fun densityResolverKeepsTemplateTablesOffAndSwitchesToElder() {
        assertEquals(
            LeziDensity.Warm,
            resolveLeziDensity(LeziVisualStyle.Warm, elder = false),
        )
        assertEquals(
            LeziDensity.Journal,
            resolveLeziDensity(LeziVisualStyle.Journal, elder = false),
        )
        assertEquals(
            LeziDensity.Elder,
            resolveLeziDensity(LeziVisualStyle.Warm, elder = true),
        )
        assertEquals(
            LeziDensity.Elder,
            resolveLeziDensity(LeziVisualStyle.Journal, elder = true),
        )
    }

    @Test
    fun touchTargetResolverKeepsOffAtFortyEightAndLiftsElderPrimary() {
        val off = resolveLeziTouchTarget(elder = false)
        assertEquals(48.dp, off.primary)
        assertEquals(48.dp, off.secondary)
        assertEquals(8.dp, off.gap)
        assertEquals(48.dp, off.dialogClose)

        val elder = resolveLeziTouchTarget(elder = true)
        assertEquals(60.dp, elder.primary)
        assertEquals(48.dp, elder.secondary)
        assertEquals(8.dp, elder.gap)
        assertEquals(44.dp, elder.dialogClose)
        assertTrue(elder.primary.value >= 60f)
        assertTrue(elder.secondary.value >= 48f)
        assertTrue(elder.gap.value >= 8f)
        assertTrue(elder.dialogClose.value >= 44f)
    }

    @Test
    fun touchConstantStaysFortyEight() {
        assertEquals(48.dp, LeziSpacing.Touch)
    }

    @Test
    fun structureResolverKeepsOffChromeAndAppliesElderOverrides() {
        val off = resolveLeziStructure(elder = false)
        assertEquals(68.dp, off.topBarMinHeight)
        assertEquals(64.dp, off.dockCellMinHeight)
        assertEquals(4.dp, off.dockCellSpacing)
        assertEquals(14.dp, off.timeBarTrackMinHeight)
        assertEquals(4, off.summaryColumnCount)
        assertEquals(1, off.chipMetricMaxLines)
        assertFalse(off.chipMetricSoftWrap)

        val elder = resolveLeziStructure(elder = true)
        assertEquals(76.dp, elder.topBarMinHeight)
        assertEquals(84.dp, elder.dockCellMinHeight)
        assertEquals(8.dp, elder.dockCellSpacing)
        assertEquals(22.dp, elder.timeBarTrackMinHeight)
        assertEquals(2, elder.summaryColumnCount)
        assertEquals(2, elder.chipMetricMaxLines)
        assertTrue(elder.chipMetricSoftWrap)
    }

    @Test
    fun recordRowSecondaryLinePutsRelativeBeforeNoteCount() {
        assertEquals("2小时前", recordRowSecondaryLine("2小时前", ""))
        assertEquals("2小时前 · 备注", recordRowSecondaryLine("2小时前", "备注"))
        assertEquals("备注", recordRowSecondaryLine("", "备注"))
        assertEquals("", recordRowSecondaryLine("", ""))
    }
}
