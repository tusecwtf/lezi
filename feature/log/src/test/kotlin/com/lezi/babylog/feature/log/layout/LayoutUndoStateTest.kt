package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.ui.RecordSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutUndoStateTest {
    @Test
    fun successfulClearPublishesOneUndoWithTheCompleteBeforeSnapshot() {
        val before = snapshot(
            slots = listOf("pee", "sleep", "nursing", "bath"),
            hidden = setOf("custom:7"),
            itemOrder = "[\"feeding\",\"custom:7\"]",
            categoryOrder = "[\"routine\",\"feeding\"]",
        )
        val after = before.copy(quickRecordSlots = listOf("", "sleep", "nursing", "bath"))

        val awaiting = reduceLayoutUndo(
            LayoutUndoState.Idle,
            LayoutUndoEvent.IntentApplied(
                token = 41L,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        ).state
        val available = reduceLayoutUndo(
            awaiting,
            LayoutUndoEvent.OriginalWriteFinished(
                token = 41L,
                succeeded = true,
                currentSnapshot = after,
            ),
        ).state

        assertEquals(
            LayoutUndoState.Available(
                LayoutUndoCandidate(
                    token = 41L,
                    kind = LayoutUndoKind.ClearSlot,
                    before = before,
                    after = after,
                ),
            ),
            available,
        )
    }

    @Test
    fun timeoutDismissesOnlyTheMatchingAvailableUndo() {
        val candidate = LayoutUndoCandidate(
            token = 7L,
            kind = LayoutUndoKind.MoveToLocalDeleted,
            before = simpleSnapshot("bath"),
            after = simpleSnapshot(""),
        )
        val available = LayoutUndoState.Available(candidate)

        assertEquals(
            available,
            reduceLayoutUndo(
                available,
                LayoutUndoEvent.OfferExpired(token = 6L),
            ).state,
        )
        assertEquals(
            LayoutUndoState.Idle,
            reduceLayoutUndo(
                available,
                LayoutUndoEvent.OfferExpired(token = 7L),
            ).state,
        )
    }

    @Test
    fun undoFailureKeepsAfterSnapshotAndSameTokenRetryRestoresBeforeOnSuccess() {
        val before = simpleSnapshot("bath")
        val after = simpleSnapshot("")
        val candidate = LayoutUndoCandidate(
            token = 9L,
            kind = LayoutUndoKind.MoveToLocalDeleted,
            before = before,
            after = after,
        )

        val restoring = reduceLayoutUndo(
            LayoutUndoState.Available(candidate),
            LayoutUndoEvent.UndoRequested(token = 9L, currentSnapshot = after),
        ).state
        assertEquals(LayoutUndoState.Restoring(candidate), restoring)

        val failed = reduceLayoutUndo(
            restoring,
            LayoutUndoEvent.UndoWriteFinished(
                token = 9L,
                succeeded = false,
                currentSnapshot = after,
            ),
        )
        assertEquals(LayoutUndoState.RestoreFailed(candidate), failed.state)
        assertEquals(null, failed.restoredSnapshot)

        val retrying = reduceLayoutUndo(
            failed.state,
            LayoutUndoEvent.RetryUndoRequested(token = 9L, currentSnapshot = after),
        ).state
        assertEquals(LayoutUndoState.Restoring(candidate), retrying)

        val restored = reduceLayoutUndo(
            retrying,
            LayoutUndoEvent.UndoWriteFinished(
                token = 9L,
                succeeded = true,
                currentSnapshot = after,
            ),
        )
        assertEquals(LayoutUndoState.Idle, restored.state)
        assertEquals(before, restored.restoredSnapshot)
        assertEquals("布局已撤销", restored.announcement)
    }

    @Test
    fun exitingEditorDropsAvailableAndFailedUndoWithoutRestoringAnything() {
        val candidate = LayoutUndoCandidate(
            token = 11L,
            kind = LayoutUndoKind.ClearSlot,
            before = simpleSnapshot("pee"),
            after = simpleSnapshot(""),
        )

        listOf(
            LayoutUndoState.Available(candidate),
            LayoutUndoState.RestoreFailed(candidate),
        ).forEach { state ->
            val result = reduceLayoutUndo(state, LayoutUndoEvent.EditorExited)
            assertEquals(LayoutUndoState.Idle, result.state)
            assertEquals(null, result.restoredSnapshot)
        }
    }

    @Test
    fun onlyClearAndLocalDeleteCanBecomeUndoableAfterDurableSuccess() {
        val before = simpleSnapshot("pee")
        val after = simpleSnapshot("")
        val undoable = listOf(
            LayoutEditIntent.ClearSlot(0) to LayoutUndoKind.ClearSlot,
            LayoutEditIntent.MoveToLocalDeleted("pee") to LayoutUndoKind.MoveToLocalDeleted,
        )
        undoable.forEachIndexed { index, (intent, kind) ->
            val token = index.toLong() + 1L
            val awaiting = reduceLayoutUndo(
                LayoutUndoState.Idle,
                LayoutUndoEvent.IntentApplied(token, intent, before, after),
            ).state
            val available = reduceLayoutUndo(
                awaiting,
                LayoutUndoEvent.OriginalWriteFinished(token, true, after),
            ).state as LayoutUndoState.Available
            assertEquals(kind, available.candidate.kind)
        }

        val safeIntents = listOf(
            LayoutEditIntent.AssignToSlot(0, "sleep"),
            LayoutEditIntent.SwapSlots(0, 1),
            LayoutEditIntent.RestoreFromLocalDeleted("pee"),
            LayoutEditIntent.MoveItemInSection("pee", 1),
            LayoutEditIntent.ReorderItemInSection("pee", 1),
            LayoutEditIntent.MoveCategory(RecordSection.Feeding, 1),
            LayoutEditIntent.MoveCategoryToIndex(RecordSection.Feeding, 1),
        )
        safeIntents.forEachIndexed { index, intent ->
            assertEquals(
                LayoutUndoState.Idle,
                reduceLayoutUndo(
                    LayoutUndoState.Idle,
                    LayoutUndoEvent.IntentApplied(
                        token = index.toLong() + 10L,
                        intent = intent,
                        before = before,
                        after = after,
                    ),
                ).state,
            )
        }
    }

    @Test
    fun failedOriginalWriteNeverOffersUndoAndCurrentSnapshotMustStillEqualAfter() {
        val before = simpleSnapshot("pee")
        val after = simpleSnapshot("")
        val awaiting = reduceLayoutUndo(
            LayoutUndoState.Idle,
            LayoutUndoEvent.IntentApplied(
                token = 21L,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        ).state

        assertEquals(
            LayoutUndoState.Idle,
            reduceLayoutUndo(
                awaiting,
                LayoutUndoEvent.OriginalWriteFinished(21L, false, after),
            ).state,
        )
        assertEquals(
            LayoutUndoState.Idle,
            reduceLayoutUndo(
                awaiting,
                LayoutUndoEvent.OriginalWriteFinished(21L, true, before),
            ).state,
        )
    }

    @Test
    fun newerIntentOwnsTheOnlyTokenAndOldCompletionsCannotRestoreOverIt() {
        val firstBefore = simpleSnapshot("pee")
        val firstAfter = simpleSnapshot("")
        val firstAvailable = LayoutUndoState.Available(
            LayoutUndoCandidate(
                token = 31L,
                kind = LayoutUndoKind.ClearSlot,
                before = firstBefore,
                after = firstAfter,
            ),
        )
        val secondAfter = firstAfter.copy(hiddenItems = setOf("bath"))
        val secondAwaiting = reduceLayoutUndo(
            firstAvailable,
            LayoutUndoEvent.IntentApplied(
                token = 32L,
                intent = LayoutEditIntent.MoveToLocalDeleted("bath"),
                before = firstAfter,
                after = secondAfter,
            ),
        ).state

        assertEquals(
            secondAwaiting,
            reduceLayoutUndo(
                secondAwaiting,
                LayoutUndoEvent.OriginalWriteFinished(31L, true, firstAfter),
            ).state,
        )
        val secondAvailable = reduceLayoutUndo(
            secondAwaiting,
            LayoutUndoEvent.OriginalWriteFinished(32L, true, secondAfter),
        ).state
        val restoring = reduceLayoutUndo(
            secondAvailable,
            LayoutUndoEvent.UndoRequested(32L, secondAfter),
        ).state
        val afterSafeIntent = reduceLayoutUndo(
            restoring,
            LayoutUndoEvent.IntentApplied(
                token = 33L,
                intent = LayoutEditIntent.AssignToSlot(0, "sleep"),
                before = secondAfter,
                after = secondAfter.copy(
                    quickRecordSlots = listOf("sleep", "", "", ""),
                ),
            ),
        ).state
        val staleUndoCompletion = reduceLayoutUndo(
            afterSafeIntent,
            LayoutUndoEvent.UndoWriteFinished(32L, true, secondAfter),
        )

        assertEquals(LayoutUndoState.Idle, staleUndoCompletion.state)
        assertEquals(null, staleUndoCompletion.restoredSnapshot)
        assertEquals(null, staleUndoCompletion.announcement)
    }

    @Test
    fun noOpIntentDuringRestoreRequiresAfterSnapshotCompensationWrite() {
        val after = simpleSnapshot("")
        val restoring = LayoutUndoState.Restoring(
            LayoutUndoCandidate(
                token = 51L,
                kind = LayoutUndoKind.ClearSlot,
                before = simpleSnapshot("pee"),
                after = after,
            ),
        )

        val invalidated = reduceLayoutUndo(
            restoring,
            LayoutUndoEvent.IntentApplied(
                token = 52L,
                intent = LayoutEditIntent.ClearSlot(0),
                before = after,
                after = after,
            ),
        ).state

        assertEquals(LayoutUndoState.Idle, invalidated)
        assertTrue(
            shouldWriteLayoutIntentResult(
                undoStateBeforeIntent = restoring,
                undoStateAfterIntent = invalidated,
                before = after,
                after = after,
            ),
        )
        assertFalse(
            shouldWriteLayoutIntentResult(
                undoStateBeforeIntent = LayoutUndoState.Available(restoring.candidate),
                undoStateAfterIntent = LayoutUndoState.Idle,
                before = after,
                after = after,
            ),
        )
    }

    @Test
    fun editorExitHoldsRestoringAndRestoreFailedSoInFlightUndoCanSettle() {
        val candidate = LayoutUndoCandidate(
            token = 9L,
            kind = LayoutUndoKind.ClearSlot,
            before = simpleSnapshot("pee"),
            after = simpleSnapshot(""),
        )
        assertTrue(shouldHoldLayoutUndoAcrossEditorExit(LayoutUndoState.Restoring(candidate)))
        assertTrue(shouldHoldLayoutUndoAcrossEditorExit(LayoutUndoState.RestoreFailed(candidate)))
        assertFalse(shouldHoldLayoutUndoAcrossEditorExit(LayoutUndoState.Idle))
        assertFalse(
            shouldHoldLayoutUndoAcrossEditorExit(LayoutUndoState.Available(candidate)),
        )
        assertFalse(
            shouldHoldLayoutUndoAcrossEditorExit(LayoutUndoState.AwaitingOriginal(candidate)),
        )

        // Holding Restoring means EditorExited is not applied; a late failure still lands.
        val stillRestoring = LayoutUndoState.Restoring(candidate)
        val failed = reduceLayoutUndo(
            stillRestoring,
            LayoutUndoEvent.UndoWriteFinished(
                token = 9L,
                succeeded = false,
                currentSnapshot = candidate.after,
            ),
        ).state
        assertEquals(LayoutUndoState.RestoreFailed(candidate), failed)
    }

    private fun snapshot(
        slots: List<String>,
        hidden: Set<String>,
        itemOrder: String,
        categoryOrder: String,
    ): DeviceLayoutSnapshot = DeviceLayoutSnapshot(
        quickRecordSlots = slots,
        hiddenItems = hidden,
        itemOrderJson = itemOrder,
        categoryOrderJson = categoryOrder,
    )

    private fun simpleSnapshot(firstSlot: String): DeviceLayoutSnapshot =
        DeviceLayoutSnapshot(quickRecordSlots = listOf(firstSlot, "", "", ""))
}
