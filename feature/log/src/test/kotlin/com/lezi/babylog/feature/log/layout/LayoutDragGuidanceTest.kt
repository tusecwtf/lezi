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

class LayoutDragGuidanceTest {
    @Test
    fun firstEntryIsAutomaticWhileCompletedEntryStartsHidden() {
        assertEquals(
            LayoutDragGuidanceState(
                completed = false,
                visibility = LayoutDragGuidanceVisibility.Auto,
            ),
            initialLayoutDragGuidanceState(completed = false),
        )
        assertEquals(
            LayoutDragGuidanceState(
                completed = true,
                visibility = LayoutDragGuidanceVisibility.Hidden,
            ),
            initialLayoutDragGuidanceState(completed = true),
        )
    }

    @Test
    fun closingAutomaticGuidanceHidesItAndRequestsMonotonicCompletion() {
        val reduction = reduceLayoutDragGuidance(
            initialLayoutDragGuidanceState(completed = false),
            LayoutDragGuidanceEvent.CloseRequested,
        )

        assertEquals(LayoutDragGuidanceVisibility.Hidden, reduction.state.visibility)
        assertFalse(reduction.state.completed)
        assertTrue(reduction.markCompleted)

        val persisted = reduceLayoutDragGuidance(
            reduction.state,
            LayoutDragGuidanceEvent.CompletionMarkerFinished(succeeded = true),
        )
        assertTrue(persisted.state.completed)
        assertEquals(LayoutDragGuidanceVisibility.Hidden, persisted.state.visibility)
        assertFalse(persisted.markCompleted)
    }

    @Test
    fun helpRevisitsWithoutResettingCompletionAndManualCloseOnlyHides() {
        val completed = initialLayoutDragGuidanceState(completed = true)
        val reopened = reduceLayoutDragGuidance(
            completed,
            LayoutDragGuidanceEvent.HelpRequested,
        )

        assertEquals(LayoutDragGuidanceVisibility.Manual, reopened.state.visibility)
        assertTrue(reopened.state.completed)
        assertFalse(reopened.markCompleted)

        val closed = reduceLayoutDragGuidance(
            reopened.state,
            LayoutDragGuidanceEvent.CloseRequested,
        )
        assertEquals(LayoutDragGuidanceVisibility.Hidden, closed.state.visibility)
        assertTrue(closed.state.completed)
        assertFalse(closed.markCompleted)
    }

    @Test
    fun failedCompletionMarkerLeavesTheSessionHiddenButIncomplete() {
        val hidden = reduceLayoutDragGuidance(
            initialLayoutDragGuidanceState(completed = false),
            LayoutDragGuidanceEvent.CloseRequested,
        ).state

        val failed = reduceLayoutDragGuidance(
            hidden,
            LayoutDragGuidanceEvent.CompletionMarkerFinished(succeeded = false),
        )

        assertFalse(failed.state.completed)
        assertEquals(LayoutDragGuidanceVisibility.Hidden, failed.state.visibility)
    }

    @Test
    fun onlyChangedTouchDragWithBothDurableReceiptsCompletesGuidance() {
        val incomplete = initialLayoutDragGuidanceState(completed = false)
        val rejectedCases = listOf(
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = false,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
                markerReceiptSucceeded = true,
            ),
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.AlternativeAction,
                layoutReceiptSucceeded = true,
                markerReceiptSucceeded = true,
            ),
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = false,
                markerReceiptSucceeded = true,
            ),
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
                markerReceiptSucceeded = false,
            ),
        )
        rejectedCases.forEach { event ->
            assertEquals(incomplete, reduceLayoutDragGuidance(incomplete, event).state)
        }

        val completed = reduceLayoutDragGuidance(
            incomplete,
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
                markerReceiptSucceeded = true,
            ),
        )
        assertTrue(completed.state.completed)
        assertEquals(LayoutDragGuidanceVisibility.Hidden, completed.state.visibility)
    }

    @Test
    fun markerWriteIsRequestedOnlyAfterAChangedTouchLayoutReceiptSucceeds() {
        val incomplete = initialLayoutDragGuidanceState(completed = false)
        assertTrue(
            shouldRequestLayoutDragGuidanceCompletion(
                state = incomplete,
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
            ),
        )
        assertFalse(
            shouldRequestLayoutDragGuidanceCompletion(
                state = incomplete,
                changed = false,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
            ),
        )
        assertFalse(
            shouldRequestLayoutDragGuidanceCompletion(
                state = incomplete,
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = false,
            ),
        )
        assertFalse(
            shouldRequestLayoutDragGuidanceCompletion(
                state = incomplete,
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.AlternativeAction,
                layoutReceiptSucceeded = true,
            ),
        )
        assertFalse(
            shouldRequestLayoutDragGuidanceCompletion(
                state = initialLayoutDragGuidanceState(completed = true),
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
            ),
        )
    }

    @Test
    fun completedManualRevisitIsNotClosedByLaterDragReceipts() {
        val manual = reduceLayoutDragGuidance(
            initialLayoutDragGuidanceState(completed = true),
            LayoutDragGuidanceEvent.HelpRequested,
        ).state

        val afterDrag = reduceLayoutDragGuidance(
            manual,
            LayoutDragGuidanceEvent.DragCompletionFinished(
                changed = true,
                inputOrigin = LayoutGuidanceInputOrigin.TouchDrag,
                layoutReceiptSucceeded = true,
                markerReceiptSucceeded = true,
            ),
        )

        assertEquals(manual, afterDrag.state)
    }
}
