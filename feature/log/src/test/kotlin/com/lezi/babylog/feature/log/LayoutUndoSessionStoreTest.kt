package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutUndoSessionStoreTest {
    @Test
    fun retainedStoreKeepsAvailableOfferAndStableDeadlineWhileColdStartIsIdle() {
        var now = 1_000_000L
        val retained = LayoutUndoSessionStore(
            nowMs = { now },
            offerDurationMs = 4_000L,
        )
        val before = simpleSnapshot("pee")
        val after = simpleSnapshot("")
        val token = retained.allocateToken()

        retained.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        )
        retained.reduce(
            LayoutUndoEvent.OriginalWriteFinished(
                token = token,
                succeeded = true,
                currentSnapshot = after,
            ),
        )

        val available = retained.current.state as LayoutUndoState.Available
        assertEquals(token, available.candidate.token)
        assertEquals(before, available.candidate.before)
        assertEquals(1_004_000L, retained.current.offerExpiresAtEpochMs)

        // Configuration recreation keeps the same ViewModel-owned store.
        now = 1_002_500L
        assertTrue(retained.current.state is LayoutUndoState.Available)
        assertEquals(1_004_000L, retained.current.offerExpiresAtEpochMs)
        assertEquals(1_500L, retained.remainingOfferMs(nowMs = now))

        // Process death / cold start uses a fresh store and must not invent undo.
        val cold = LayoutUndoSessionStore(nowMs = { now })
        assertEquals(LayoutUndoState.Idle, cold.current.state)
        assertNull(cold.current.offerExpiresAtEpochMs)
    }

    @Test
    fun writePhaseTransitionsAndRetainedStoreIdentitySurviveSimulatedRecreation() {
        val retained = LayoutUndoSessionStore(nowMs = { 10_000L })
        val before = simpleSnapshot("pee")
        val after = simpleSnapshot("")
        val token = retained.allocateToken()

        retained.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        )
        assertTrue(retained.current.state is LayoutUndoState.AwaitingOriginal)

        // Simulated configuration recreation keeps the same store identity + phase.
        val afterRotate = retained
        assertSame(retained, afterRotate)
        assertTrue(afterRotate.current.state is LayoutUndoState.AwaitingOriginal)

        retained.reduce(
            LayoutUndoEvent.OriginalWriteFinished(
                token = token,
                succeeded = true,
                currentSnapshot = after,
            ),
        )
        retained.reduce(
            LayoutUndoEvent.UndoRequested(
                token = token,
                currentSnapshot = after,
            ),
        )
        assertTrue(retained.current.state is LayoutUndoState.Restoring)
        assertSame(retained, afterRotate)
        assertTrue(afterRotate.current.state is LayoutUndoState.Restoring)

        retained.reduce(
            LayoutUndoEvent.UndoWriteFinished(
                token = token,
                succeeded = false,
                currentSnapshot = after,
            ),
        )
        assertTrue(retained.current.state is LayoutUndoState.RestoreFailed)
        assertEquals(token, (retained.current.state as LayoutUndoState.RestoreFailed).candidate.token)

        // Distinct cold store never invents the failed restore.
        val cold = LayoutUndoSessionStore(nowMs = { 10_000L })
        assertEquals(LayoutUndoState.Idle, cold.current.state)
        assertFalse(cold === retained)
    }

    @Test
    fun dismissedFailureAndExitChromeSurviveRecreationUntilCleared() {
        val retained = LayoutUndoSessionStore(nowMs = { 0L })
        retained.dismissLayoutFailure(sequence = 9L)
        retained.setExitFlushInProgress(true)
        retained.setExitAfterLayoutRetry(true)

        assertEquals(9L, retained.current.dismissedLayoutFailureSequence)
        assertTrue(retained.current.exitFlushInProgress)
        assertTrue(retained.current.exitAfterLayoutRetry)

        // Same store after "rotation".
        assertEquals(9L, retained.current.dismissedLayoutFailureSequence)
        assertTrue(retained.current.exitFlushInProgress)

        retained.clear()
        assertNull(retained.current.dismissedLayoutFailureSequence)
        assertFalse(retained.current.exitFlushInProgress)
        assertFalse(retained.current.exitAfterLayoutRetry)
        assertEquals(LayoutUndoState.Idle, LayoutUndoSessionStore().current.state)
    }

    @Test
    fun editorExitAndOpenClearAvailableAndFailedWithoutKeepingStaleDeadline() {
        val store = LayoutUndoSessionStore(nowMs = { 50_000L }, offerDurationMs = 4_000L)
        val before = simpleSnapshot("bath")
        val after = simpleSnapshot("")
        val token = store.allocateToken()
        store.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.MoveToLocalDeleted("bath"),
                before = before,
                after = after,
            ),
        )
        store.reduce(
            LayoutUndoEvent.OriginalWriteFinished(
                token = token,
                succeeded = true,
                currentSnapshot = after,
            ),
        )
        assertTrue(store.current.state is LayoutUndoState.Available)
        assertEquals(54_000L, store.current.offerExpiresAtEpochMs)

        store.reduce(LayoutUndoEvent.EditorExited)
        assertEquals(LayoutUndoState.Idle, store.current.state)
        assertNull(store.current.offerExpiresAtEpochMs)

        store.clear()
        assertEquals(LayoutUndoState.Idle, store.current.state)
        assertNull(store.current.offerExpiresAtEpochMs)
    }

    @Test
    fun successfulUndoSurfacesOneShotAnnouncementAndRestoredSnapshot() {
        val store = LayoutUndoSessionStore(nowMs = { 0L })
        val before = simpleSnapshot("pee")
        val after = simpleSnapshot("")
        val token = store.allocateToken()
        store.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        )
        store.reduce(
            LayoutUndoEvent.OriginalWriteFinished(token, true, after),
        )
        store.reduce(LayoutUndoEvent.UndoRequested(token, after))
        val reduction = store.reduce(
            LayoutUndoEvent.UndoWriteFinished(token, true, after),
        )

        assertEquals(before, reduction.restoredSnapshot)
        assertEquals("布局已撤销", reduction.announcement)
        assertEquals("布局已撤销", store.current.pendingAnnouncement)
        assertEquals("布局已撤销", store.consumeAnnouncement())
        assertNull(store.consumeAnnouncement())
        assertNull(store.current.pendingAnnouncement)
    }

    @Test
    fun remainingOfferMsIsZeroAfterDeadlineAndDoesNotReviveStaleOffer() {
        var now = 100L
        val store = LayoutUndoSessionStore(nowMs = { now }, offerDurationMs = 4_000L)
        val token = store.allocateToken()
        val after = simpleSnapshot("")
        store.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.ClearSlot(0),
                before = simpleSnapshot("pee"),
                after = after,
            ),
        )
        store.reduce(LayoutUndoEvent.OriginalWriteFinished(token, true, after))
        now = 4_100L
        assertEquals(0L, store.remainingOfferMs())
        store.reduce(LayoutUndoEvent.OfferExpired(token))
        assertEquals(LayoutUndoState.Idle, store.current.state)
        assertNull(store.current.offerExpiresAtEpochMs)
    }

    private fun simpleSnapshot(firstSlot: String): DeviceLayoutSnapshot =
        DeviceLayoutSnapshot(quickRecordSlots = listOf(firstSlot, "", "", ""))
}
