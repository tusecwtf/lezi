package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation
import com.lezi.babylog.sync.conflict.ConflictRootType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ConflictResolverLoad(
    val snapshot: ConflictSnapshot,
    /** Only a freshly authenticated detail response may authorize submit. */
    val fetchedOnline: Boolean,
)

sealed class ConflictResolveOutcome {
    data class Accepted(val stableVersionId: String) : ConflictResolveOutcome()

    data class RefreshRequired(val code: String, val message: String) : ConflictResolveOutcome()

    data class Forbidden(
        val message: String = "仅事实作者或家庭管理员可以解决",
    ) : ConflictResolveOutcome()

    data class TransportFailure(val message: String) : ConflictResolveOutcome()

    data class Rejected(val code: String, val message: String) : ConflictResolveOutcome()
}

/**
 * CareLog seam for open conflict badge, detail load, and CAS resolution.
 *
 * Local save never waits on this path. Detail is on-demand; resolution sends only
 * the receipt token, frozen mutation identity and opaque choices (wire §8.2).
 *
 * On Accepted: validate the terminal root/media through the H05 typed decoder,
 * retain the local conflict handle, and request Foreground. The resolve response
 * does not carry authoritative deleted state, so only the subsequent pull may
 * replace the Room root and close the local summary/cache.
 */
internal class ConflictResolutionCoordinator(
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao,
    private val syncPort: SyncPort,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    private val snapshotProjection = ConflictSnapshotProjection(
        summaries = conflictSummaryDao,
        snapshots = conflictSnapshotCacheDao,
        transactions = transactionRunner,
    )

    fun observeInbox(): Flow<ConflictInbox> = combine(
        conflictSummaryDao.observeInboxProjection(),
        syncPort.familyMemberDirectory(),
    ) { rows, members ->
        val actorNames = members.asSequence()
            .map { it.membershipId.trim() to it.displayName.trim() }
            .filter { (membershipId, displayName) -> membershipId.isNotEmpty() && displayName.isNotEmpty() }
            .toMap()
        val items = rows.mapNotNull { row ->
            val rootType = runCatching { ConflictRootType.fromWire(row.entityType) }.getOrNull()
                ?: return@mapNotNull null
            val snapshot = row.snapshotJson
                ?.let { runCatching { ConflictSnapshotCodec.decode(it) }.getOrNull() }
                ?.takeIf {
                    it.conflictId == row.conflictId &&
                        it.entityType == rootType &&
                        it.clientUuid == row.clientUuid
                }
            val stableRoot = snapshot?.stable?.root
            val actorId = snapshot?.stable?.actorId?.ifBlank { stableRoot?.actorId().orEmpty() }
                ?: row.localActorId.orEmpty()
            val actor = actorId.takeIf(String::isNotBlank)?.let { membershipId ->
                ConflictInboxActor.Known(
                    membershipId = membershipId,
                    label = actorNames[membershipId]
                        ?.takeIf(String::isNotBlank)
                        ?: membershipId,
                )
            } ?: ConflictInboxActor.RequiresDetail
            val media = snapshot?.let { complete ->
                ConflictInboxMedia.Known(
                    totalCount = (complete.stable.media + complete.branches.flatMap { it.media })
                        .distinctBy { it.mediaUuid }
                        .size,
                )
            } ?: ConflictInboxMedia.RequiresDetail(row.localMediaCount)
            ConflictInboxItem(
                conflictId = row.conflictId,
                rootType = rootType,
                clientUuid = row.clientUuid,
                rootLabel = rootType.presentationLabel(),
                title = stableRoot?.presentationTitle(row.clientUuid)
                    ?: localConflictTitle(rootType, row.localTitle, row.clientUuid),
                babyLabel = row.babyLabel,
                actor = actor,
                stableTombstone = snapshot?.stable?.deleted ?: row.localTombstone,
                branchTombstone = snapshot?.let {
                    ConflictInboxBranchTombstone.Known(it.branches.any { branch -> branch.deleted })
                } ?: ConflictInboxBranchTombstone.RequiresDetail,
                media = media,
                updatedAt = row.updatedAt,
            )
        }
        ConflictInbox(items)
    }

    /**
     * Load detail for resolver. Prefers fresh network detail when available;
     * falls back to the same typed, complete snapshot persisted below the UI seam.
     */
    suspend fun loadDetail(conflictId: String, forceRefresh: Boolean = true): ConflictResolverLoad? {
        if (forceRefresh) {
            val fetched = runCatching { syncPort.fetchConflictSnapshot(conflictId) }.getOrNull()
            if (fetched?.conflictId == conflictId) {
                if (fetched.pageIndex == 0 && fetched.complete && fetched.continuation == null) {
                    snapshotProjection.replaceComplete(fetched)
                }
                return ConflictResolverLoad(fetched, fetchedOnline = true)
            }
        }
        return snapshotProjection.read(conflictId)?.let {
            ConflictResolverLoad(it, fetchedOnline = false)
        }
    }

    /**
     * Submit only the receipt token, frozen mutation identity and opaque choices.
     * The persisted typed snapshot is rechecked before transport; root/media/deleted
     * reconstruction remains exclusively server-owned.
     */
    suspend fun resolve(
        conflictId: String,
        request: ConflictResolveRequest,
    ): ConflictResolveOutcome {
        val snapshot = snapshotProjection.read(conflictId)
        if (snapshot == null || snapshot.snapshotToken != request.snapshotToken) {
            return ConflictResolveOutcome.Rejected(
                code = "invalid_snapshot_token",
                message = "冲突快照已失效，请联网刷新",
            )
        }
        val expected = snapshot.conflicting.associate { path ->
            path.path to path.candidates.mapTo(hashSetOf()) { it.choiceId }
        }
        val actual = request.choices.groupBy { it.path }
        if (actual.keys != expected.keys || actual.values.any { it.size != 1 } ||
            actual.any { (path, choices) -> choices.single().choiceId !in expected.getValue(path) }
        ) {
            return ConflictResolveOutcome.Rejected(
                code = "incomplete_choices",
                message = "必须为每个冲突字段明确选择一次",
            )
        }
        val commandIsCanonical = runCatching {
            ConflictSnapshotValidation.requireResolutionChoices(
                snapshotToken = request.snapshotToken,
                resolutionMutationId = request.resolutionMutationId,
                choices = request.choices.map { it.path to it.choiceId },
                context = "domain conflict resolve",
            )
        }.isSuccess
        if (!commandIsCanonical) {
            return ConflictResolveOutcome.Rejected(
                code = "non_canonical_value",
                message = "解决操作不是 canonical choice-only command",
            )
        }
        val result = runCatching {
            syncPort.resolveConflict(conflictId, request)
        }.getOrElse { error ->
            return ConflictResolveOutcome.TransportFailure(
                message = error.message ?: "提交失败，请稍后重试",
            )
        }
        return when (result) {
            is ConflictResolveResult.Accepted -> {
                if (result.resolutionMutationId != request.resolutionMutationId) {
                    return ConflictResolveOutcome.Rejected(
                        code = "transport_mismatch",
                        message = "服务器回执与本次解决操作不一致",
                    )
                }
                val projectionIsCanonical = runCatching {
                    ConflictSnapshotValidation.requireBounded(
                        result.stableVersionId,
                        min = 1,
                        max = 128,
                        context = "resolve accepted.stable_version_id",
                    )
                    ConflictSnapshotCodec.validateAcceptedProjection(
                        entityType = snapshot.entityType,
                        stableRootJson = result.stableRootJson,
                        stableMedia = result.stableMedia,
                    )
                }.isSuccess
                if (!projectionIsCanonical) {
                    return ConflictResolveOutcome.Rejected(
                        code = "transport_mismatch",
                        message = "服务器解决回执不是完整权威投影，请下拉同步后重试",
                    )
                }
                // Do not project/clear here: accepted omits deleted state. Pull owns
                // the only complete root/media/deleted projection and closes H05 rows.
                syncPort.requestSync(SyncTrigger.Foreground)
                ConflictResolveOutcome.Accepted(result.stableVersionId)
            }
            is ConflictResolveResult.Rejected -> when (result.code) {
                "forbidden" -> ConflictResolveOutcome.Forbidden(
                    message = if (snapshot.entityType.wireName == "baby") {
                        "仅家庭管理员可以解决宝宝资料冲突"
                    } else {
                        "仅事实作者或家庭管理员可以解决"
                    },
                )
                "snapshot_expired", "snapshot_stale", "invalid_snapshot_token", "cas_mismatch" ->
                    ConflictResolveOutcome.RefreshRequired(
                        code = result.code,
                        message = "冲突内容已变化，请联网刷新后重新选择",
                    )
                else -> ConflictResolveOutcome.Rejected(
                    code = result.code,
                    message = terminalMessage(result.code),
                )
            }
        }
    }

}

private fun terminalMessage(code: String): String = when (code) {
    "invalid_choice", "duplicate_choice", "incomplete_choices" ->
        "所选内容不完整，请重新打开冲突详情"
    "missing_restore_base", "incomplete_restore_base", "missing_restore_media" ->
        "原始事实或照片不完整，无法安全恢复"
    "content_drift" -> "本次解决内容与先前提交不一致"
    "capability_mismatch" -> "家庭服务版本不匹配，未提交任何修改"
    else -> "冲突解决被拒绝（$code）"
}
