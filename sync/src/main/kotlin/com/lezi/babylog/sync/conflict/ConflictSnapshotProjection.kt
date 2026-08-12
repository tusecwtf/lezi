package com.lezi.babylog.sync.conflict

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.conflictSnapshotStageCacheKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One owner for lossless snapshot persistence and its list/root summary.
 * Incomplete pages live under a private staging key, so an interrupted refresh
 * cannot replace the last complete offline snapshot.
 */
class ConflictSnapshotProjection(
    private val summaries: ConflictSummaryDao,
    private val snapshots: ConflictSnapshotCacheDao,
    private val transactions: DatabaseTransactionRunner,
) {
    internal suspend fun replaceComplete(snapshot: ConflictSnapshot) {
        require(snapshot.pageIndex == 0 && snapshot.complete && snapshot.continuation == null) {
            "只接受已组装的 complete ConflictSnapshot"
        }
        validateCanonical(snapshot)
        transactions.run {
            promoteComplete(snapshot)
        }
    }

    /**
     * Loads one bounded page at a time. Every accepted incomplete page is durable
     * before the next continuation request; only the final transaction promotes.
     */
    suspend fun loadComplete(
        conflictId: String,
        persistenceBarrier: Mutex? = null,
        isLoadCurrent: suspend () -> Boolean = { true },
        fetchPage: suspend (ConflictSnapshotPageRequest) -> FetchedConflictSnapshotPage,
    ): ConflictSnapshot {
        ConflictSnapshotValidation.requireUuid(conflictId, "conflict detail request.conflict_id")
        check(isLoadCurrent()) { "conflict snapshot load 在 lease 前已失效" }
        var cursor = persistCurrent(persistenceBarrier, isLoadCurrent) {
            beginOrResumeLoad(conflictId)
        }
        try {
            var staged = cursor.stage
            var request: ConflictSnapshotPageRequest =
                staged?.nextRequest ?: ConflictSnapshotPageRequest.First
            while (true) {
                check(isLoadCurrent()) { "conflict snapshot load 在 page request 前已失效" }
                val fetched = fetchPage(request)
                val next = validateNextPage(conflictId, staged, request, fetched)
                if (fetched.snapshot.complete) {
                    val complete = assembleComplete(next)
                    persistCurrent(persistenceBarrier, isLoadCurrent) {
                        requireLoadStillCurrent(conflictId, cursor.expectedStageJson)
                        promoteComplete(complete)
                    }
                    return complete
                }
                val nextEntity = next.toEntity()
                persistCurrent(persistenceBarrier, isLoadCurrent) {
                    requireLoadStillCurrent(conflictId, cursor.expectedStageJson)
                    snapshots.putTransportJournal(
                        nextEntity.key,
                        nextEntity.payload,
                        nextEntity.epoch,
                    )
                }
                staged = next
                cursor = LoadCursor(next, nextEntity.payload)
                request = next.nextRequest
            }
        } catch (failure: Throwable) {
            if (cursor.stage == null) {
                val cleanupFailure = runCatching {
                    withContext(NonCancellable) {
                        persist(persistenceBarrier) {
                            discardOwnedEmptyLease(conflictId, cursor.expectedStageJson)
                        }
                    }
                }.exceptionOrNull()
                if (cleanupFailure != null && cleanupFailure !== failure) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    suspend fun read(conflictId: String): ConflictSnapshot? {
        val row = snapshots.get(conflictId) ?: return null
        if (row.legacyStableRootSentinel != "{}" ||
            row.legacyBaseRootSentinel != null ||
            row.legacyConflictPathsSentinel != "[]"
        ) return null
        return runCatching { ConflictSnapshotCodec.decodeComplete(row.snapshotJson) }
            .getOrNull()
            ?.takeIf {
                it.conflictId == conflictId && it.pageIndex == 0 &&
                    it.complete && it.continuation == null
            }
    }

    suspend fun clearRoot(entityType: ConflictRootType, clientUuid: String) {
        transactions.run {
            summaries.listForRoot(entityType.wireName, clientUuid).forEach { summary ->
                snapshots.deleteConflictState(summary.conflictId)
                summaries.delete(summary.conflictId)
            }
        }
    }

    suspend fun clear(conflictId: String) {
        transactions.run {
            snapshots.deleteConflictState(conflictId)
            summaries.delete(conflictId)
        }
    }

    /** Drops only resumable evidence; the last complete offline snapshot is retained. */
    suspend fun discardStaging(conflictId: String): Boolean {
        val key = conflictSnapshotStageCacheKey(conflictId)
        if (snapshots.getTransportJournal(key) == null) return false
        transactions.run { snapshots.deleteTransportJournal(key) }
        return true
    }

    private suspend fun beginOrResumeLoad(conflictId: String): LoadCursor {
        val key = conflictSnapshotStageCacheKey(conflictId)
        val row = snapshots.getTransportJournal(key)
        val staged = row?.let { stored ->
            runCatching {
                ConflictSnapshotStageCodec.decode(stored.payloadJson)
                    .also {
                        validateStage(conflictId, it)
                        require(!it.last.complete) {
                            "complete conflict snapshot 不得留在 staging"
                        }
                    }
            }.getOrNull()
        }
        if (staged != null) {
            return LoadCursor(staged, requireNotNull(row).payloadJson)
        }
        // A unique durable lease closes the fetch-outside-transaction race: any
        // pull/session/local clear deletes it, so the stale response cannot promote.
        val leaseJson = newLoadLeaseJson()
        snapshots.putTransportJournal(key, leaseJson, 0)
        return LoadCursor(stage = null, expectedStageJson = leaseJson)
    }

    private fun validateNextPage(
        conflictId: String,
        staged: StagedConflictSnapshot?,
        request: ConflictSnapshotPageRequest,
        fetched: FetchedConflictSnapshotPage,
    ): StagedConflictSnapshot {
        val page = fetched.snapshot
        require(page.conflictId == conflictId) { "conflict page.conflict_id 与请求不一致" }
        require(fetched.encodedBytes in 1..ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES) {
            "conflict page 超出 encoded-byte budget"
        }
        require(page.branches.size <= ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE) {
            "conflict page 超出 branch count budget"
        }
        require(page.complete == (page.continuation == null)) {
            "conflict page complete/continuation 不一致"
        }
        require(page.branches.isNotEmpty() || (page.pageIndex == 0 && page.complete)) {
            "只有 page-0 complete tombstone snapshot 可为空"
        }
        when (request) {
            ConflictSnapshotPageRequest.First -> {
                require(staged == null && page.pageIndex == 0) {
                    "first conflict page ordinal 无效"
                }
            }
            is ConflictSnapshotPageRequest.Continuation -> {
                val current = requireNotNull(staged) { "continuation 没有持久 stage" }
                require(request == current.nextRequest) { "continuation 与持久 stage 不一致" }
                require(page.pageIndex == current.pages.size) { "conflict page duplicate/skipped" }
                require(page.snapshotToken == request.snapshotToken) {
                    "conflict page receipt 发生漂移"
                }
                require(page.sameSnapshotView(current.first)) {
                    "conflict page common snapshot view 发生漂移"
                }
            }
        }
        val next = StagedConflictSnapshot(
            pages = staged.orEmptyPages() + ConflictSnapshotPageEvidence(page, fetched.encodedBytes),
        )
        validateStage(conflictId, next)
        return next
    }

    private fun validateStage(conflictId: String, stage: StagedConflictSnapshot) {
        require(stage.pages.size in 1..ConflictSnapshotPaging.MAX_PAGES_PER_SNAPSHOT) {
            "conflict snapshot page budget 超限"
        }
        require(stage.totalBranchCount <= ConflictSnapshotPaging.MAX_BRANCHES_PER_SNAPSHOT) {
            "conflict snapshot branch budget 超限"
        }
        require(stage.totalEncodedBytes <= ConflictSnapshotPaging.MAX_ENCODED_SNAPSHOT_BYTES) {
            "conflict snapshot byte budget 超限"
        }
        stage.pages.forEachIndexed { index, evidence ->
            val page = evidence.snapshot
            require(page.conflictId == conflictId && page.pageIndex == index) {
                "conflict snapshot page ordinal/identity 无效"
            }
            require(evidence.encodedBytes in 1..ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES) {
                "conflict snapshot page byte evidence 无效"
            }
            require(page.branches.size <= ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE) {
                "conflict snapshot page count evidence 无效"
            }
            require(page.complete == (page.continuation == null)) {
                "conflict snapshot page complete/continuation evidence 不一致"
            }
            require(page.branches.isNotEmpty() || (index == 0 && page.complete)) {
                "conflict snapshot page 没有推进 branch 集"
            }
            require(page.sameSnapshotView(stage.first)) {
                "conflict snapshot page view 不一致"
            }
            require(if (index == stage.pages.lastIndex) true else !page.complete) {
                "complete conflict page 后仍有后续页"
            }
        }
        val continuations = stage.pages.mapNotNull { it.snapshot.continuation }
        require(continuations.distinct().size == continuations.size) {
            "conflict snapshot continuation 重复"
        }
        val branchIds = stage.pages.flatMap { evidence ->
            evidence.snapshot.branches.map { it.versionId }
        }
        require(branchIds == branchIds.sorted() && branchIds.distinct().size == branchIds.size) {
            "conflict snapshot branch duplicate/non-monotonic"
        }
    }

    private fun assembleComplete(stage: StagedConflictSnapshot): ConflictSnapshot {
        require(stage.last.complete && stage.last.continuation == null) {
            "conflict snapshot 最后一页不完整"
        }
        val complete = stage.first.copy(
            branches = stage.pages.flatMap { it.snapshot.branches },
            pageIndex = 0,
            continuation = null,
            complete = true,
        )
        validateCanonical(complete)
        return complete
    }

    private fun validateCanonical(snapshot: ConflictSnapshot) {
        val snapshotJson = ConflictSnapshotCodec.encode(snapshot)
        require(ConflictSnapshotCodec.decodeComplete(snapshotJson) == snapshot) {
            "ConflictSnapshot 不是 canonical typed snapshot"
        }
    }

    private suspend fun promoteComplete(snapshot: ConflictSnapshot) {
        removeOtherSnapshotsForRoot(snapshot)
        snapshots.upsert(
            ConflictSnapshotCacheEntity(
                conflictId = snapshot.conflictId,
                snapshotJson = ConflictSnapshotCodec.encode(snapshot),
                cachedAt = snapshot.cachedAt(),
            ),
        )
        snapshots.deleteTransportJournal(conflictSnapshotStageCacheKey(snapshot.conflictId))
        summaries.upsert(snapshot.toSummary())
    }

    private suspend fun requireLoadStillCurrent(conflictId: String, expectedStageJson: String) {
        val row = snapshots.getTransportJournal(conflictSnapshotStageCacheKey(conflictId))
        if (row?.payloadJson != expectedStageJson) {
            throw ConflictSnapshotLoadInvalidatedException()
        }
    }

    private suspend fun discardOwnedEmptyLease(conflictId: String, expectedStageJson: String) {
        val key = conflictSnapshotStageCacheKey(conflictId)
        val row = snapshots.getTransportJournal(key)
        if (row?.payloadJson == expectedStageJson) {
            snapshots.deleteTransportJournal(key)
        }
    }

    private suspend fun removeOtherSnapshotsForRoot(snapshot: ConflictSnapshot) {
        summaries.listForRoot(snapshot.entityType.wireName, snapshot.clientUuid)
            .filter { it.conflictId != snapshot.conflictId }
            .forEach { stale ->
                snapshots.deleteConflictState(stale.conflictId)
                summaries.delete(stale.conflictId)
            }
    }

    private suspend fun <T> persistCurrent(
        barrier: Mutex?,
        isLoadCurrent: suspend () -> Boolean,
        block: suspend () -> T,
    ): T {
        suspend fun checkedTransaction(): T {
            check(isLoadCurrent()) { "conflict snapshot load session/clear epoch 已失效" }
            return transactions.run(block)
        }
        return if (barrier == null) {
            checkedTransaction()
        } else {
            barrier.withLock { checkedTransaction() }
        }
    }

    private suspend fun <T> persist(
        barrier: Mutex?,
        block: suspend () -> T,
    ): T {
        suspend fun transaction(): T = transactions.run(block)
        return if (barrier == null) transaction() else barrier.withLock { transaction() }
    }
}

private data class LoadCursor(
    val stage: StagedConflictSnapshot?,
    val expectedStageJson: String,
)

private class ConflictSnapshotLoadInvalidatedException : IllegalStateException(
    "conflict snapshot load 已被较新的 pull/session/local clear 失效",
)

private fun newLoadLeaseJson(): String = buildJsonObject {
    put("contract", JsonPrimitive("conflict_snapshot_load_lease_v1"))
    put("load_id", JsonPrimitive(UUID.randomUUID().toString()))
}.toString()

private fun StagedConflictSnapshot?.orEmptyPages(): List<ConflictSnapshotPageEvidence> =
    this?.pages.orEmpty()

private data class StagedTransportJournal(
    val key: String,
    val payload: String,
    val epoch: Long,
)

private fun StagedConflictSnapshot.toEntity(): StagedTransportJournal = StagedTransportJournal(
    key = conflictSnapshotStageCacheKey(first.conflictId),
    payload = ConflictSnapshotStageCodec.encode(this),
    epoch = pages.maxOf { it.snapshot.cachedAt() },
)

private fun ConflictSnapshot.sameSnapshotView(other: ConflictSnapshot): Boolean =
    conflictId == other.conflictId &&
        entityType == other.entityType &&
        clientUuid == other.clientUuid &&
        snapshotToken == other.snapshotToken &&
        expiresAt == other.expiresAt &&
        stable == other.stable &&
        conflicting == other.conflicting &&
        autoMerged == other.autoMerged

private fun ConflictSnapshot.cachedAt(): Long = maxOf(
    stable.receivedAt,
    branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
)

private fun ConflictSnapshot.toSummary(): ConflictSummaryEntity =
    ConflictSummaryEntity(
        conflictId = conflictId,
        entityType = entityType.wireName,
        clientUuid = clientUuid,
        baseVersionId = stable.baseVersion,
        stableVersionId = stable.versionId,
        status = "open",
        kind = if (branches.isEmpty()) "tombstone_restore" else "concurrent",
        branchVersionIdsJson = JsonArray(branchVersionIds.map(::JsonPrimitive)).toString(),
        updatedAt = maxOf(
            stable.receivedAt,
            branches.maxOfOrNull { it.receivedAt } ?: Long.MIN_VALUE,
        ),
    )
