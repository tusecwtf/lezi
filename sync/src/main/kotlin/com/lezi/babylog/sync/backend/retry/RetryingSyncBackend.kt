package com.lezi.babylog.sync.backend.retry

import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.MAX_CAUSAL_UNITS
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.SyncSession

/** Applies [SyncRetryPolicy] only to the idempotent operations owned by H15/H17. */
internal class RetryingSyncBackend(
    private val delegate: SyncBackend,
    clock: SyncRetryClock = SystemSyncRetryClock,
    random: SyncRetryRandom = DefaultSyncRetryRandom,
    delay: SyncRetryDelay = CoroutineSyncRetryDelay,
    events: SyncRetryEventSink = NoOpSyncRetryEventSink,
) : SyncBackend by delegate {
    private val policy = SyncRetryPolicy(clock, random, delay, events)

    override suspend fun authenticatedHandshake(session: SyncSession) =
        policy.execute(SyncRetryOperation.Handshake) {
            delegate.authenticatedHandshake(session)
        }

    override suspend fun pull(session: SyncSession, page: PullPageRequest) =
        policy.execute(SyncRetryOperation.Pull) { delegate.pull(session, page) }

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalCommitBatchResult {
        require(units.size in 1..MAX_CAUSAL_UNITS) {
            "因果同步批次必须包含 1..$MAX_CAUSAL_UNITS 个原子单元"
        }
        return policy.execute(SyncRetryOperation.Commit) {
            delegate.causalCommit(session, units)
        }
    }

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: SyncMediaUploadSource,
        sha256: String,
    ) = policy.execute(SyncRetryOperation.MediaPrepare) {
        delegate.putCausalMediaPreimage(session, mediaUuid, source, sha256)
    }

    override suspend fun fetchConflictSnapshotPage(
        session: SyncSession,
        conflictId: String,
        request: ConflictSnapshotPageRequest,
    ): FetchedConflictSnapshotPage = policy.execute(SyncRetryOperation.ConflictDetail) {
        delegate.fetchConflictSnapshotPage(session, conflictId, request)
    }

    override suspend fun resolveConflict(
        session: SyncSession,
        conflictId: String,
        request: ConflictResolveRequest,
    ): ConflictResolveResult = policy.execute(SyncRetryOperation.Resolution) {
        delegate.resolveConflict(session, conflictId, request)
    }
}
