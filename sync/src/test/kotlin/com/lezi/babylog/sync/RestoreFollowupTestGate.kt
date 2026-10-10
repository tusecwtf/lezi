package com.lezi.babylog.sync

import com.lezi.babylog.core.model.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Observe restore's real automatic followup before it can publish the retained draft. */
internal class RestoreFollowupTestGate(private val rig: SyncRig) {
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val exited = CompletableDeferred<Unit>()

    init {
        rig.backend.beforePullReturn = {
            entered.complete(Unit)
            try { release.await() } finally { exited.complete(Unit) }
        }
    }

    suspend fun awaitEntered() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { entered.await() }
    }

    fun resume() { release.complete(Unit) }

    suspend fun cancelRound() {
        rig.foreground.setForeground(false)
        try {
            withContext(Dispatchers.Default) { withTimeout(5_000) { exited.await() } }
        } finally {
            resume()
            rig.foreground.setForeground(true)
        }
    }
}

internal suspend fun SyncRig.awaitAutomaticRecordPublication(uuid: String) = withContext(Dispatchers.Default) {
    withTimeout(5_000) {
        while (records.getByClientUuid(uuid)?.syncDirty != false || port.status().first() == SyncStatus.Syncing)
            kotlinx.coroutines.delay(1)
    }
}
