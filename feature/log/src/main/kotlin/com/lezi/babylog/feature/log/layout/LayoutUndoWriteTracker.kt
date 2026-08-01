package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Result of applying a layout-edit intent that needs a durable write.
 * Composition may use [receipt] only for drag-guidance completion side effects;
 * undo phase transitions are owned here on [scope].
 */
internal data class LayoutEditIntentWrite(
    val previousPrefs: DeviceLayoutPrefs,
    val nextPrefs: DeviceLayoutPrefs,
    val receipt: DeviceLayoutWriteReceipt,
)

/**
 * ViewModel-scoped layout undo write + intent application. Configuration
 * recreation keeps the same tracker/scope so AwaitingOriginal → Available and
 * Restoring → Idle/RestoreFailed complete without composition coroutines.
 */
internal class LayoutUndoWriteTracker(
    private val scope: CoroutineScope,
    private val undoSessions: LayoutUndoSessionStore,
    private val editSessions: LayoutEditSessionStore,
    private val submitSnapshot: (DeviceLayoutSnapshot) -> DeviceLayoutWriteReceipt,
) {
    fun remainingOfferMs(): Long = undoSessions.remainingOfferMs()

    /**
     * Reduce intent, update editor prefs, submit the snapshot write, and track
     * original-write completion for undo. Returns null when no write is needed.
     */
    fun applyLayoutEditIntent(
        intent: LayoutEditIntent,
        knownKeys: Collection<String>,
    ): LayoutEditIntentWrite? {
        val current = editSessions.current?.prefs ?: return null
        val next = reduceLayoutEdit(current, intent, knownKeys)
        val token = undoSessions.allocateToken()
        val undoStateBeforeIntent = undoSessions.current.state
        val beforeSnapshot = current.toSnapshot()
        val afterSnapshot = next.toSnapshot()
        val undoReduction = undoSessions.reduce(
            LayoutUndoEvent.IntentApplied(
                token = token,
                intent = intent,
                before = beforeSnapshot,
                after = afterSnapshot,
            ),
        )
        if (
            !shouldWriteLayoutIntentResult(
                undoStateBeforeIntent = undoStateBeforeIntent,
                undoStateAfterIntent = undoReduction.state,
                before = beforeSnapshot,
                after = afterSnapshot,
            )
        ) {
            return null
        }
        editSessions.updatePrefs(prefs = next, hasSubmittedIntent = true)
        val receipt = submitSnapshot(afterSnapshot)
        trackOriginalLayoutWriteForUndo(token, receipt)
        return LayoutEditIntentWrite(
            previousPrefs = current,
            nextPrefs = next,
            receipt = receipt,
        )
    }

    /**
     * Request undo of [token] when Available; submits the before snapshot restore
     * and tracks completion on [scope].
     */
    fun requestLayoutUndo(token: Long): Boolean {
        val current = editSessions.current?.prefs ?: return false
        val reduction = undoSessions.reduce(
            LayoutUndoEvent.UndoRequested(
                token = token,
                currentSnapshot = current.toSnapshot(),
            ),
        )
        val restoring = reduction.state as? LayoutUndoState.Restoring ?: return false
        editSessions.updatePrefs(prefs = current, hasSubmittedIntent = true)
        val receipt = submitSnapshot(restoring.candidate.before)
        trackLayoutUndoWrite(token, receipt)
        return true
    }

    /**
     * Retry a [LayoutUndoState.RestoreFailed] reverse write. Returns true when
     * a restore write was started (state becomes Restoring).
     */
    fun retryFailedLayoutUndo(): Boolean {
        val failed = undoSessions.current.state as? LayoutUndoState.RestoreFailed
            ?: return false
        val current = editSessions.current?.prefs ?: return false
        val reduction = undoSessions.reduce(
            LayoutUndoEvent.RetryUndoRequested(
                token = failed.candidate.token,
                currentSnapshot = current.toSnapshot(),
            ),
        )
        val restoring = reduction.state as? LayoutUndoState.Restoring ?: return false
        val receipt = submitSnapshot(restoring.candidate.before)
        trackLayoutUndoWrite(failed.candidate.token, receipt)
        return true
    }

    /**
     * Await an original layout write on [scope] so AwaitingOriginal →
     * Available/Idle survives composition recreation without the old coroutine scope.
     */
    fun trackOriginalLayoutWriteForUndo(
        token: Long,
        receipt: DeviceLayoutWriteReceipt,
    ) {
        scope.launch {
            val result = receipt.result.await()
            val currentSnapshot =
                editSessions.current?.prefs?.toSnapshot() ?: receipt.snapshot
            undoSessions.reduce(
                LayoutUndoEvent.OriginalWriteFinished(
                    token = token,
                    succeeded = result.isSuccess,
                    currentSnapshot = currentSnapshot,
                ),
            )
        }
    }

    /**
     * Await an undo restore write on [scope]. On durable success, applies the
     * candidate `before` snapshot into the retained editor session.
     */
    fun trackLayoutUndoWrite(
        token: Long,
        receipt: DeviceLayoutWriteReceipt,
    ) {
        scope.launch {
            completeLayoutUndoWrite(token, receipt.result.await())
        }
    }

    fun completeLayoutUndoWrite(token: Long, result: Result<Unit>) {
        val currentSnapshot = editSessions.current?.prefs?.toSnapshot()
        if (currentSnapshot == null) {
            undoSessions.clear()
            return
        }
        val reduction = undoSessions.reduce(
            LayoutUndoEvent.UndoWriteFinished(
                token = token,
                succeeded = result.isSuccess,
                currentSnapshot = currentSnapshot,
            ),
        )
        // Retry/exit busy is retained on the session; clear when the reverse write
        // settles so rotation mid-retry still ends 「重试中…」 without composition await.
        undoSessions.setExitFlushInProgress(false)
        reduction.restoredSnapshot?.let { restored ->
            editSessions.updatePrefs(
                prefs = restored.toLayoutPrefs(),
                hasSubmittedIntent = true,
            )
        }
    }
}
