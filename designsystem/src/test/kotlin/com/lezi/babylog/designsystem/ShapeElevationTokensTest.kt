package com.lezi.babylog.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two-template radius / elevation contract used by apkui chrome.
 * These tokens are the source of truth for warm soft cards vs journal compact geometry.
 */
class ShapeElevationTokensTest {
    @Test
    fun warmRadiiFollowSoftCardScale() {
        assertEquals(RoundedCornerShape(12.dp), LeziShapes.Sm)
        assertEquals(RoundedCornerShape(20.dp), LeziShapes.Md)
        assertEquals(RoundedCornerShape(28.dp), LeziShapes.Lg)
        // Button must not reintroduce a free 14.dp value.
        assertEquals(LeziShapes.Sm, LeziShapes.Button)
    }

    @Test
    fun journalRadiiStayTighterThanWarm() {
        assertEquals(RoundedCornerShape(4.dp), LeziShapes.JournalSm)
        assertEquals(RoundedCornerShape(8.dp), LeziShapes.JournalCard)
        assertEquals(RoundedCornerShape(8.dp), LeziShapes.JournalButton)
        assertEquals(RoundedCornerShape(12.dp), LeziShapes.JournalLg)
        assertEquals(RoundedCornerShape(18.dp), LeziShapes.JournalDialog)

        // Distinct from warm large soft cards.
        assertNotEquals(LeziShapes.Md, LeziShapes.JournalCard)
        assertNotEquals(LeziShapes.Button, LeziShapes.JournalButton)
        assertNotEquals(LeziShapes.Lg, LeziShapes.JournalLg)
        assertNotEquals(LeziShapes.Md, LeziShapes.JournalDialog)
    }

    @Test
    fun elevationStepsSeparateWarmFloatFromJournalFlat() {
        assertEquals(0.dp, LeziElevation.None)
        assertTrue(LeziElevation.CardWarm > LeziElevation.None)
        assertTrue(LeziElevation.ButtonWarm > LeziElevation.CardWarm)
        assertTrue(LeziElevation.DockWarm > LeziElevation.ButtonWarm)
        assertTrue(LeziElevation.ModalWarm >= LeziElevation.DockWarm)

        assertTrue(LeziElevation.DockJournal < LeziElevation.DockWarm)
        assertTrue(LeziElevation.ModalJournal < LeziElevation.ModalWarm)
        assertEquals(3.dp, LeziElevation.JournalHardEdge)
    }

    @Test
    fun materialShapesMapWarmAndJournalScales() {
        val warm = leziShapes(LeziVisualStyle.Warm)
        val journal = leziShapes(LeziVisualStyle.Journal)

        assertEquals(LeziShapes.Sm, warm.small)
        assertEquals(LeziShapes.Md, warm.medium)
        assertEquals(LeziShapes.Lg, warm.large)

        assertEquals(LeziShapes.JournalButton, journal.small)
        assertEquals(LeziShapes.JournalCard, journal.medium)
        assertEquals(LeziShapes.JournalLg, journal.large)
        assertEquals(LeziShapes.JournalDialog, journal.extraLarge)
    }
}
