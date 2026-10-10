package com.lezi.babylog.sync.clear

import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.lezi.babylog.sync.LocalClearWorkflow
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.ScopedMediaSpoolClear
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession

/**
 * Owns the crash-recoverable hand-off from a domain Room clear to replica cleanup.
 *
 * The marker and domain deletion commit in one Room transaction. External file
 * and DataStore work then runs non-cancellably. Captured media rows commit before
 * restore-file reclamation takes its path gates; the marker retires only after
 * that outside-Room reclamation succeeds.
 */
internal class LocalReplicaClearCoordinator(
    private val barrier: Mutex,
    private val preferences: SyncPreferences,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
    private val mediaSpoolClear: ScopedMediaSpoolClear,
    private val transactionRunner: DatabaseTransactionRunner,
    private val pendingStore: PendingReplicaCleanupStore,
    // Production supplies the lifecycle hooks; defaults support isolated legacy clear tests.
    private val terminalSpoolOwner: () -> com.lezi.babylog.sync.disasterrecovery.RestoreTerminalSpoolRetirementOwner? = { null },
    private val reclaimRestoreFiles: suspend () -> Unit = {},
    private val restoreOwnedPaths: suspend () -> Set<String> = { emptySet() },
    private val clearSourceEvidence: suspend (LocalDataClearScope, SyncSession, suspend () -> Unit) -> Unit =
        { _, _, clear -> clear() },
) {
    suspend fun clear(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
        recoverDomain: suspend () -> LocalDataClearScope?,
    ): Result<Unit> = runCatching {
        barrier.withLock {
            clearUnderBarrier(scope, workflow, recoverDomain)
        }
    }

    /** Only the sync owner may call this while retaining its terminal identity barrier. */
    internal suspend fun clearUnderBarrier(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
        recoverDomain: suspend () -> LocalDataClearScope?,
    ) {
        // Recovery completes an older committed request. It never satisfies
        // this new explicit clear: the user may have created local data after
        // the older request failed its side-effect finalization.
        recoverDomain()
        recoverPendingLocked()
        workflow.withLocalExclusion {
            val session = preferences.session.first()
            val pending = transactionRunner.run {
                val terminalCapture = terminalSpoolOwner()?.prepareCommittedClear(scope)
                val selected = snapshot(scope, session)
                selected.copy(mediaClientUuids = selected.mediaClientUuids + terminalCapture?.mediaClientUuids.orEmpty()).also { staged ->
                    pendingStore.stage(staged)
                    clearSourceEvidence(scope, session) {
                        mediaSpoolClear.preserveRetainedEvidence(scope, workflow::clearRoom)
                    }
                    if (terminalCapture != null) terminalSpoolOwner()?.bindCommittedClear(staged, terminalCapture)
                }
            }
            finishCommitted(pending, workflow::finishCommitted)
        }
    }

    suspend fun recoverPending(): Result<Unit> = runCatching {
        barrier.withLock {
            recoverPendingLocked()
            Unit
        }
    }

    /** Caller already owns [barrier]; used before any remote or session mutation. */
    internal suspend fun recoverPendingLocked(): LocalDataClearScope? {
        val pending = pendingStore.load() ?: return null
        finishCommitted(pending)
        return pending.scope
    }

    private suspend fun snapshot(
        scope: LocalDataClearScope,
        session: SyncSession,
    ): PendingReplicaCleanup {
        val media = when (scope) {
            // Care clear removes records/plans and wake observations; reclaim both
            // log and wake media bytes. Avatar rows stay with retained babies.
            LocalDataClearScope.RecordsOnly ->
                mediaDao.listAllIncludingDeleted().filter { it.kind == "log" || it.kind == "wake" }
            LocalDataClearScope.AllLocalData -> mediaDao.listAllIncludingDeleted()
        }
        check(media.all { it.clientUuid.isNotBlank() }) {
            "本机媒体清理快照包含无效同步标识"
        }
        val retainedPaths = if (scope == LocalDataClearScope.RecordsOnly) {
            buildSet {
                mediaDao.listAllIncludingDeleted()
                    .filter { it.kind != "log" && it.kind != "wake" }
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
            if (scope == LocalDataClearScope.AllLocalData) {
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.filterTo(linkedSetOf()) { it.isNotBlank() && it !in retainedPaths }
        return PendingReplicaCleanup(
            scope = scope,
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
                failure = localClearCommittedFailure(
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
                LocalDataClearScope.RecordsOnly -> pending.pullGeneration
                LocalDataClearScope.AllLocalData -> ""
            },
        )
        // Dedicated restore files stay under their lifecycle owner's path/holder
        // checks, including any paths adopted by current MediaAsset rows. Resolve
        // the authoritative inventory before Room; never infer ownership by name.
        val restorePaths = restoreOwnedPaths()
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
                .filterNot(restorePaths::contains)
                // Private spool bytes, including historical aliases, belong to the
                // durable spool owner. Generic fact cleanup must not partially unlink
                // a sealed group before its deleting intent commits.
                .filterNot { path ->
                    (mediaDao as? com.lezi.babylog.core.database.causal.PrivateSpoolPublicationGuarded)
                        ?.privateSpoolPathPolicy?.isPrivatePath(path) == true
                }
                .forEach { mediaFiles.delete(it) }
            // A Composer import can exist before it has a MediaAsset owner. Sweep
            // product-owned roots after the final ownership recheck so an explicit
            // local clear fulfils its privacy promise for those orphan drafts too.
            // Domain writers are excluded by LocalClearWorkflow's clear epoch. This
            // legacy pass keeps its Room lease; dedicated restore paths are excluded
            // and use their lifecycle owner's gated reclamation outside Room below.
            mediaFiles.sweepUnreferenced(
                scope = pending.scope,
                retainedLocalUris = protectedPaths + restorePaths,
            )
            pending.mediaClientUuids.chunked(MEDIA_DELETE_CHUNK_SIZE).forEach { chunk ->
                mediaDao.deleteByClientUuids(chunk)
            }
        }
        // The owner takes path gates before short Room CAS leases. The original
        // durable marker survives failure or process death across this boundary,
        // so restart repeats the same committed cleanup without recreating rows.
        terminalSpoolOwner()?.reclaimAfterCommittedClear()
        reclaimRestoreFiles()
        mediaSpoolClear.sweepAfterCommittedClear()
        transactionRunner.run {
            check(pendingStore.load() == pending) { "本机清理状态已变化" }
            mediaSpoolClear.retireCommittedClearBinding()
            pendingStore.delete()
        }
    }
}

private const val MEDIA_DELETE_CHUNK_SIZE = 400
