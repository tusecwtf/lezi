package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.PendingReplicaCleanupScope
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class LocalReplicaClearScope {
    RecordsOnly,
    AllLocal,
}

/**
 * Owns the crash-recoverable hand-off from a domain Room clear to replica cleanup.
 *
 * The marker and domain deletion commit in one Room transaction. External file
 * and DataStore work then runs non-cancellably, and a final Room transaction
 * atomically retires captured outbox/media rows with the marker.
 */
internal class LocalReplicaClearCoordinator(
    private val barrier: Mutex,
    private val preferences: SyncPreferences,
    private val outboxDao: OutboxDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
    private val pendingStore: PendingReplicaCleanupStore,
) {
    suspend fun clear(
        scope: LocalReplicaClearScope,
        workflow: LocalClearWorkflow,
        recoverDomain: suspend () -> LocalClearRecoveryScope?,
    ): Result<Unit> = runCatching {
        barrier.withLock {
            // Recovery completes an older committed request. It never satisfies
            // this new explicit clear: the user may have created local data after
            // the older request failed its side-effect finalization.
            recoverDomain()
            recoverPendingLocked()
            workflow.withLocalExclusion {
                val session = preferences.session.first()
                val pending = transactionRunner.run {
                    snapshot(scope, session).also { staged ->
                        pendingStore.stage(staged)
                        workflow.clearRoom()
                    }
                }
                finishCommitted(pending, workflow::finishCommitted)
            }
        }
    }

    suspend fun recoverPending(): Result<Unit> = runCatching {
        barrier.withLock {
            recoverPendingLocked()
            Unit
        }
    }

    /** Caller already owns [barrier]; used before any remote or session mutation. */
    internal suspend fun recoverPendingLocked(): LocalClearRecoveryScope? {
        val pending = pendingStore.load() ?: return null
        finishCommitted(pending)
        return pending.scope.toRecoveryScope()
    }

    private suspend fun snapshot(
        scope: LocalReplicaClearScope,
        session: SyncSession,
    ): PendingReplicaCleanup {
        val media = when (scope) {
            LocalReplicaClearScope.RecordsOnly ->
                mediaDao.listAllIncludingDeleted().filter { it.kind == "log" }
            LocalReplicaClearScope.AllLocal -> mediaDao.listAllIncludingDeleted()
        }
        check(media.all { it.clientUuid.isNotBlank() }) {
            "本机媒体清理快照包含无效同步标识"
        }
        val retainedPaths = if (scope == LocalReplicaClearScope.RecordsOnly) {
            buildSet {
                mediaDao.listAllIncludingDeleted()
                    .filter { it.kind != "log" }
                    .mapTo(this, MediaAssetEntity::localUri)
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        } else {
            emptySet()
        }
        val paths = buildSet {
            addAll(media.map(MediaAssetEntity::localUri))
            if (scope == LocalReplicaClearScope.AllLocal) {
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.filterTo(linkedSetOf()) { it.isNotBlank() && it !in retainedPaths }
        return PendingReplicaCleanup(
            scope = scope.toPendingScope(),
            familyId = session.familyId,
            pullGeneration = session.pullGeneration,
            mediaClientUuids = media.mapTo(linkedSetOf(), MediaAssetEntity::clientUuid),
            localMediaPaths = paths,
        )
    }

    private suspend fun finishCommitted(
        pending: PendingReplicaCleanup,
        finishDomain: suspend () -> Unit = {},
    ) {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                finish(pending)
            } catch (error: Throwable) {
                failure = LocalClearCommittedException(
                    familyServerRetained = pending.familyId.isNotBlank(),
                    cause = error,
                )
            }
            try {
                finishDomain()
            } catch (error: Throwable) {
                val cancellation = error.cancellationCauseOrNull()
                if (cancellation != null) {
                    failure?.takeUnless { it === cancellation }?.let(cancellation::addSuppressed)
                    failure = cancellation
                } else if (failure == null) {
                    failure = error
                } else {
                    failure!!.addSuppressed(error)
                }
            }
        }
        failure?.cancellationCauseOrNull()?.let { cancellation ->
            failure?.takeUnless { it === cancellation }?.let(cancellation::addSuppressed)
            throw cancellation
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            failure?.takeUnless { it === cancellation }?.let(cancellation::addSuppressed)
            throw cancellation
        }
        failure?.let { throw it }
    }

    private suspend fun finish(pending: PendingReplicaCleanup) {
        preferences.updateCursor(
            cursor = 0,
            generation = when (pending.scope) {
                PendingReplicaCleanupScope.RECORDS_ONLY -> pending.pullGeneration
                PendingReplicaCleanupScope.ALL_LOCAL -> ""
            },
        )
        transactionRunner.run {
            // Every committed media ownership change uses this Room write lease
            // (remote materialization is additionally behind [barrier]). Keep the
            // final ownership read and irreversible file deletion in that same
            // lease so no new row can appear between check and delete.
            val protectedPaths = buildSet {
                mediaDao.listAllIncludingDeleted()
                    .filter { it.clientUuid !in pending.mediaClientUuids }
                    .mapTo(this, MediaAssetEntity::localUri)
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            pending.localMediaPaths
                .filterNot(protectedPaths::contains)
                .forEach { mediaFiles.delete(it) }
            when (pending.scope) {
                PendingReplicaCleanupScope.RECORDS_ONLY -> finishRecordsOnly(pending)
                PendingReplicaCleanupScope.ALL_LOCAL -> outboxDao.deleteAll()
            }
            pending.mediaClientUuids.chunked(OUTBOX_DELETE_CHUNK_SIZE).forEach { chunk ->
                mediaDao.deleteByClientUuids(chunk)
            }
            pendingStore.delete()
        }
    }

    private suspend fun finishRecordsOnly(pending: PendingReplicaCleanup) {
        outboxDao.deleteTypeAcrossFamilies("record")
        outboxDao.deleteTypeAcrossFamilies("care_plan")
        outboxDao.deleteTypeAcrossFamilies("fulfillment_candidate")
        pending.mediaClientUuids.chunked(OUTBOX_DELETE_CHUNK_SIZE).forEach { chunk ->
            outboxDao.deleteEntitiesAcrossFamilies("media", chunk)
        }
    }
}

private fun LocalReplicaClearScope.toPendingScope(): PendingReplicaCleanupScope = when (this) {
    LocalReplicaClearScope.RecordsOnly -> PendingReplicaCleanupScope.RECORDS_ONLY
    LocalReplicaClearScope.AllLocal -> PendingReplicaCleanupScope.ALL_LOCAL
}

private fun PendingReplicaCleanupScope.toRecoveryScope(): LocalClearRecoveryScope = when (this) {
    PendingReplicaCleanupScope.RECORDS_ONLY -> LocalClearRecoveryScope.RecordsOnly
    PendingReplicaCleanupScope.ALL_LOCAL -> LocalClearRecoveryScope.AllLocal
}

private const val OUTBOX_DELETE_CHUNK_SIZE = 400

private fun Throwable.cancellationCauseOrNull(): CancellationException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) return current
        current = current.cause
    }
    return null
}
