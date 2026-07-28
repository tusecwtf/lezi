package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeEditDeleteSettleTest {
    @Test
    fun smallOffsetCloses() {
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(0.10f),
        )
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(-0.10f),
        )
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(0f),
        )
    }

    @Test
    fun halfTravelRevealsWithoutCommit() {
        assertEquals(
            SwipeEditDeleteSettle.RevealedDelete,
            settleSwipeEditDelete(SWIPE_REVEAL_RATIO),
        )
        assertEquals(
            SwipeEditDeleteSettle.RevealedDelete,
            settleSwipeEditDelete(0.40f),
        )
        assertEquals(
            SwipeEditDeleteSettle.RevealedEdit,
            settleSwipeEditDelete(-SWIPE_REVEAL_RATIO),
        )
        assertEquals(
            SwipeEditDeleteSettle.RevealedEdit,
            settleSwipeEditDelete(-0.40f),
        )
    }

    @Test
    fun fullTravelCommits() {
        assertEquals(
            SwipeEditDeleteSettle.CommitDelete,
            settleSwipeEditDelete(SWIPE_COMMIT_RATIO),
        )
        assertEquals(
            SwipeEditDeleteSettle.CommitDelete,
            settleSwipeEditDelete(0.90f),
        )
        assertEquals(
            SwipeEditDeleteSettle.CommitEdit,
            settleSwipeEditDelete(-SWIPE_COMMIT_RATIO),
        )
        assertEquals(
            SwipeEditDeleteSettle.CommitEdit,
            settleSwipeEditDelete(-0.90f),
        )
    }

    @Test
    fun disabledSideDoesNotRevealOrCommit() {
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(0.40f, deleteEnabled = false),
        )
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(0.90f, deleteEnabled = false),
        )
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(-0.40f, editEnabled = false),
        )
        assertEquals(
            SwipeEditDeleteSettle.SettledClosed,
            settleSwipeEditDelete(-0.90f, editEnabled = false),
        )
        // Opposite side still works when only one is disabled.
        assertEquals(
            SwipeEditDeleteSettle.RevealedEdit,
            settleSwipeEditDelete(-0.30f, deleteEnabled = false),
        )
        assertEquals(
            SwipeEditDeleteSettle.CommitDelete,
            settleSwipeEditDelete(0.60f, editEnabled = false),
        )
    }

    @Test
    fun targetOffsetMatchesRevealSide() {
        assertEquals(0f, targetOffsetRatioForSettle(SwipeEditDeleteSettle.SettledClosed))
        assertEquals(0f, targetOffsetRatioForSettle(SwipeEditDeleteSettle.CommitEdit))
        assertEquals(0f, targetOffsetRatioForSettle(SwipeEditDeleteSettle.CommitDelete))
        assertEquals(
            SWIPE_REVEAL_RATIO,
            targetOffsetRatioForSettle(SwipeEditDeleteSettle.RevealedDelete),
        )
        assertEquals(
            -SWIPE_REVEAL_RATIO,
            targetOffsetRatioForSettle(SwipeEditDeleteSettle.RevealedEdit),
        )
    }

    @Test
    fun thresholdsMatchDesignWithinTolerance() {
        // Design: ~28% reveal, ~55% commit; engineering may use ±5%.
        assertTrue(SWIPE_REVEAL_RATIO in 0.23f..0.33f)
        assertTrue(SWIPE_COMMIT_RATIO in 0.50f..0.60f)
        assertTrue(SWIPE_COMMIT_RATIO > SWIPE_REVEAL_RATIO)
    }

    @Test
    fun hapticFiresOnceWhenCrossingCommit() {
        var crossed = false
        var fireCount = 0
        fun step(offset: Float) {
            maybeHaptic(offset, widthPx = 100f, alreadyCrossed = crossed) { next ->
                if (next && !crossed) fireCount++
                crossed = next
            }
        }
        step(10f)
        assertFalse(crossed)
        assertEquals(0, fireCount)
        step(55f)
        assertTrue(crossed)
        assertEquals(1, fireCount)
        step(70f)
        assertEquals(1, fireCount)
        step(20f)
        assertFalse(crossed)
        step(60f)
        assertEquals(2, fireCount)
    }
}
