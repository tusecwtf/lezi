package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

/**
 * JVM coverage for ViewModel-scoped undo write completion: receipt settle after a
 * simulated composition drop still advances AwaitingOriginal → Available and
 * Restoring → Idle/RestoreFailed, and applies restored prefs on success.
 */
class LayoutUndoWriteTrackerTest {
    @Test
    fun originalWriteCompletionSurvivesSimulatedCompositionDropToAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val undo = LayoutUndoSessionStore(nowMs = { 1_000L }, offerDurationMs = 4_000L)
        val edit = LayoutEditSessionStore()
        val before = prefs("pee")
        val after = prefs("")
        openEditor(edit, before)

        val release = CompletableDeferred<Unit>()
        val tracker = LayoutUndoWriteTracker(
            scope = scope,
            undoSessions = undo,
            editSessions = edit,
            submitSnapshot = { snapshot ->
                val result = CompletableDeferred<Result<Unit>>()
                scope.launch {
                    release.await()
                    result.complete(Result.success(Unit))
                }
                DeviceLayoutWriteReceipt(
                    sequence = 1L,
                    snapshot = snapshot,
                    result = result,
                )
            },
        )

        val write = tracker.applyLayoutEditIntent(
            intent = LayoutEditIntent.ClearSlot(0),
            knownKeys = setOf("pee", "sleep", "poop", "bath"),
        )
        requireNotNull(write)
        assertTrue(undo.current.state is LayoutUndoState.AwaitingOriginal)

        // "Composition dropped" — only the tracker scope remains.
        release.complete(Unit)
        awaitState(undo) { it is LayoutUndoState.Available }

        val available = undo.current.state as LayoutUndoState.Available
        assertEquals(after.toSnapshot(), available.candidate.after)
        assertEquals(5_000L, undo.current.offerExpiresAtEpochMs)
        assertEquals(after, edit.current?.prefs)
        scope.cancel()
    }

    @Test
    fun undoWriteFailureSurvivesCompositionDropAsRestoreFailedWithoutApplyingBefore() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val undo = LayoutUndoSessionStore(nowMs = { 0L })
            val edit = LayoutEditSessionStore()
            val before = prefs("pee")
            val after = prefs("")
            openEditor(edit, after)
            seedAvailable(undo, before.toSnapshot(), after.toSnapshot())

            val release = CompletableDeferred<Unit>()
            val tracker = LayoutUndoWriteTracker(
                scope = scope,
                undoSessions = undo,
                editSessions = edit,
                submitSnapshot = { snapshot ->
                    val result = CompletableDeferred<Result<Unit>>()
                    scope.launch {
                        release.await()
                        result.complete(Result.failure(IllegalStateException("persist failed")))
                    }
                    DeviceLayoutWriteReceipt(
                        sequence = 1L,
                        snapshot = snapshot,
                        result = result,
                    )
                },
            )

            undo.setExitFlushInProgress(true)
            assertTrue(tracker.requestLayoutUndo(token = 1L))
            assertTrue(undo.current.state is LayoutUndoState.Restoring)

            // Simulated recreation mid-restore: composition gone, scope retained.
            release.complete(Unit)
            awaitState(undo) { it is LayoutUndoState.RestoreFailed }

            assertFalse(undo.current.exitFlushInProgress)
            assertEquals(after, edit.current?.prefs)
            assertEquals(1L, (undo.current.state as LayoutUndoState.RestoreFailed).candidate.token)
            scope.cancel()
        }

    @Test
    fun undoWriteSuccessAppliesRestoredPrefsAndClearsExitBusy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val undo = LayoutUndoSessionStore(nowMs = { 0L })
        val edit = LayoutEditSessionStore()
        val before = prefs("pee")
        val after = prefs("")
        openEditor(edit, after)
        seedAvailable(undo, before.toSnapshot(), after.toSnapshot())

        val release = CompletableDeferred<Unit>()
        val tracker = LayoutUndoWriteTracker(
            scope = scope,
            undoSessions = undo,
            editSessions = edit,
            submitSnapshot = { snapshot ->
                val result = CompletableDeferred<Result<Unit>>()
                scope.launch {
                    release.await()
                    result.complete(Result.success(Unit))
                }
                DeviceLayoutWriteReceipt(
                    sequence = 1L,
                    snapshot = snapshot,
                    result = result,
                )
            },
        )

        undo.setExitFlushInProgress(true)
        undo.setExitAfterLayoutRetry(true)
        assertTrue(tracker.requestLayoutUndo(token = 1L))
        release.complete(Unit)
        awaitState(undo) { it is LayoutUndoState.Idle }

        assertEquals(before, edit.current?.prefs)
        assertEquals("布局已撤销", undo.consumeAnnouncement())
        assertFalse(undo.current.exitFlushInProgress)
        assertTrue(undo.current.exitAfterLayoutRetry)
        scope.cancel()
    }

    @Test
    fun remainingOfferMsUsesInjectedSessionClock() {
        var now = 10_000L
        val undo = LayoutUndoSessionStore(nowMs = { now }, offerDurationMs = 4_000L)
        val edit = LayoutEditSessionStore()
        openEditor(edit, prefs("pee"))
        val tracker = LayoutUndoWriteTracker(
            scope = CoroutineScope(SupervisorJob()),
            undoSessions = undo,
            editSessions = edit,
            submitSnapshot = { snap ->
                DeviceLayoutWriteReceipt(
                    sequence = 1L,
                    snapshot = snap,
                    result = CompletableDeferred(Result.success(Unit)),
                )
            },
        )
        seedAvailable(undo, prefs("pee").toSnapshot(), prefs("").toSnapshot())
        now = 12_000L
        assertEquals(2_000L, tracker.remainingOfferMs())
    }

    private suspend fun awaitState(
        undo: LayoutUndoSessionStore,
        predicate: (LayoutUndoState) -> Boolean,
    ) {
        withTimeout(5_000L) {
            while (!predicate(undo.current.state)) {
                delay(10L)
            }
        }
    }

    private fun openEditor(edit: LayoutEditSessionStore, prefs: DeviceLayoutPrefs) {
        edit.open(
            context = LayoutEditSessionContext(babyId = 1L, day = LocalDate.of(2026, 8, 1)),
            prefs = prefs,
            guidanceCompleted = true,
        )
    }

    private fun seedAvailable(
        undo: LayoutUndoSessionStore,
        before: DeviceLayoutSnapshot,
        after: DeviceLayoutSnapshot,
    ) {
        val token = undo.allocateToken()
        undo.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = LayoutEditIntent.ClearSlot(0),
                before = before,
                after = after,
            ),
        )
        undo.reduce(
            LayoutUndoEvent.OriginalWriteFinished(
                token = token,
                succeeded = true,
                currentSnapshot = after,
            ),
        )
        assertTrue(undo.current.state is LayoutUndoState.Available)
    }

    private fun prefs(firstSlot: String): DeviceLayoutPrefs =
        DeviceLayoutPrefs(
            quickRecordSlots = listOf(firstSlot, "", "", ""),
            hiddenItems = emptySet(),
            itemOrderJson = "[]",
            categoryOrderJson = "[]",
        )
}
