package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.parseDismissedEntityCacheKey
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.ConflictWithdrawRequest
import com.lezi.babylog.sync.backend.ConflictWithdrawResult
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.isDismissibleUnresolvedEntity
import com.lezi.babylog.sync.session.LIVE_CENSUS_LOCAL_EXTRA_CODE
import com.lezi.babylog.sync.session.SkippedPullItem
import com.lezi.babylog.sync.session.UnacceptedFactPresentation
import com.lezi.babylog.sync.session.formatEntityTypeChinese
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class ConflictResolverLoad(
    val snapshot: ConflictSnapshot,
    /** Only a freshly authenticated detail response may authorize submit. */
    val fetchedOnline: Boolean,
)

sealed class ConflictResolveOutcome {
    data class Accepted(val stableVersionId: String) : ConflictResolveOutcome()

    data class Withdrawn(
        val stableVersionId: String,
        val stillOpen: Boolean,
    ) : ConflictResolveOutcome()

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
        syncPort.skippedPullItems(),
        syncPort.unacceptedFacts(),
        conflictSnapshotCacheDao.observeDismissedEntityJournals(),
    ) { rows, members, skipped, rejected, dismissedJournals ->
        val actorNames = members.asSequence()
            .map { it.membershipId.trim() to it.displayName.trim() }
            .filter { (membershipId, displayName) -> membershipId.isNotEmpty() && displayName.isNotEmpty() }
            .toMap()
        // S1 (0.5.4 ticket 01): an entity dismissed via 只从这台手机去掉 carries a
        // durable dismissed-entity journal entry. It never surfaces as an inbox
        // card again — not from a lingering branched summary (zombie card), and
        // not from skipped/rejected projections.
        val dismissedKeys = dismissedJournals
            .mapNotNull { parseDismissedEntityCacheKey(it.journalKey) }
            .toSet()
        val branched = rows.mapNotNull { row ->
            if (row.entityType to row.clientUuid in dismissedKeys) return@mapNotNull null
            val rootType = runCatching { ConflictRootType.fromWire(row.entityType) }.getOrNull()
                ?: return@mapNotNull null
            val snapshot = row.snapshotJson
                ?.let { runCatching { ConflictSnapshotCodec.decodeComplete(it) }.getOrNull() }
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
        ConflictInbox(branched + unalignedInboxItems(branched, skipped, rejected, dismissedKeys))
    }.distinctUntilChanged()

    /**
     * Load detail for resolver. Prefers fresh network detail when available;
     * falls back to the same typed, complete snapshot persisted below the UI seam.
     */
    suspend fun loadDetail(conflictId: String, forceRefresh: Boolean = true): ConflictResolverLoad? {
        if (forceRefresh) {
            val fetched = try {
                syncPort.fetchConflictSnapshot(conflictId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (fetched?.conflictId == conflictId &&
                fetched.pageIndex == 0 && fetched.complete && fetched.continuation == null
            ) {
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
            return ConflictResolveOutcome.RefreshRequired(
                code = "invalid_snapshot_token",
                message = "冲突内容已变化，请联网刷新后重新选择",
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
                message = "请先点选要采用的一版",
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
        val result = try {
            syncPort.resolveConflict(conflictId, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
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
                syncPort.requestAuthoritativeForegroundSync()
                ConflictResolveOutcome.Accepted(result.stableVersionId)
            }
            is ConflictResolveResult.Rejected -> when (result.code) {
                "forbidden" -> ConflictResolveOutcome.Forbidden(
                    message = when {
                        snapshot.entityType.wireName == "baby" ->
                            "仅家庭管理员可以解决宝宝资料冲突"
                        snapshot.branches.isNotEmpty() && !snapshot.stable.deleted ->
                            "分叉后只有家庭管理员能采用新稳定"
                        else -> "仅事实作者或家庭管理员可以解决"
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

    suspend fun withdraw(
        conflictId: String,
        request: ConflictWithdrawRequest,
    ): ConflictResolveOutcome {
        val snapshot = snapshotProjection.read(conflictId)
        if (snapshot == null ||
            snapshot.stable.versionId != request.expectedStableVersionId ||
            snapshot.branches.map { it.versionId }.sorted() != request.expectedBranchVersionIds
        ) {
            return ConflictResolveOutcome.RefreshRequired(
                code = "snapshot_stale",
                message = "冲突内容已变化，请联网刷新后重新选择",
            )
        }
        val result = try {
            syncPort.withdrawConflictBranches(conflictId, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ConflictResolveOutcome.TransportFailure(
                message = error.message ?: "提交失败，请稍后重试",
            )
        }
        return when (result) {
            is ConflictWithdrawResult.Accepted -> {
                if (result.withdrawalMutationId != request.withdrawalMutationId ||
                    result.stableVersionId != request.expectedStableVersionId
                ) {
                    return ConflictResolveOutcome.Rejected(
                        code = "transport_mismatch",
                        message = "服务器回执与本次撤回不一致",
                    )
                }
                syncPort.requestAuthoritativeForegroundSync()
                ConflictResolveOutcome.Withdrawn(
                    stableVersionId = result.stableVersionId,
                    stillOpen = result.conflictStatus == "open" ||
                        result.remainingBranchVersionIds.isNotEmpty(),
                )
            }
            is ConflictWithdrawResult.Rejected -> when (result.code) {
                "forbidden" -> ConflictResolveOutcome.Forbidden(
                    message = if (snapshot.entityType.wireName == "baby") {
                        "仅家庭管理员可以撤回宝宝资料冲突"
                    } else {
                        "只能撤回自己提交的修改"
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

private fun unalignedInboxItems(
    branched: List<ConflictInboxItem>,
    skipped: List<SkippedPullItem>,
    rejected: List<UnacceptedFactPresentation>,
    dismissedKeys: Set<Pair<String, String>>,
): List<ConflictInboxItem> {
    val seen = branched.map { item ->
        (item.rootType?.wireName ?: "") to item.clientUuid
    }.toMutableSet()
    val items = mutableListOf<ConflictInboxItem>()
    rejected.forEach { fact ->
        val key = fact.entityType to fact.clientUuid
        if (!seen.add(key)) return@forEach
        // S1 (0.5.4 ticket 01): dismissed entities never come back as cards.
        if (key in dismissedKeys) return@forEach
        items += unresolvedInboxItem(
            kind = ConflictInboxKind.Rejected,
            entityType = fact.entityType,
            clientUuid = fact.clientUuid,
            updatedAt = fact.recordedAt,
            reasonLabel = "家里没收下",
        )
    }
    skipped.forEach { skip ->
        val key = skip.entityType to skip.clientUuid
        if (!seen.add(key)) return@forEach
        if (key in dismissedKeys) return@forEach
        val extra = skip.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
        items += unresolvedInboxItem(
            kind = if (extra) ConflictInboxKind.LocalExtra else ConflictInboxKind.PullHole,
            entityType = skip.entityType,
            clientUuid = skip.clientUuid,
            updatedAt = skip.recordedAt,
            reasonLabel = skip.reasonGateDisplay,
        )
    }
    return items.sortedBy { it.updatedAt }
}

private fun unresolvedInboxItem(
    kind: ConflictInboxKind,
    entityType: String,
    clientUuid: String,
    updatedAt: Long,
    reasonLabel: String,
): ConflictInboxItem {
    val rootType = unresolvedRootType(entityType)
    return ConflictInboxItem(
        conflictId = UnresolvedInboxIds.encode(kind, entityType, clientUuid),
        rootType = rootType,
        clientUuid = clientUuid,
        rootLabel = rootType?.presentationLabel() ?: formatEntityTypeChinese(entityType),
        title = unresolvedTitle(entityType, clientUuid),
        babyLabel = null,
        actor = ConflictInboxActor.RequiresDetail,
        stableTombstone = null,
        branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
        media = ConflictInboxMedia.RequiresDetail(0),
        updatedAt = updatedAt,
        kind = kind,
        reasonLabel = reasonLabel,
        dismissible = isDismissibleUnresolvedEntity(entityType),
    )
}

private fun terminalMessage(code: String): String = when (code) {
    "invalid_choice", "duplicate_choice", "incomplete_choices" ->
        "请先点选要采用的一版"
    "missing_restore_base", "incomplete_restore_base", "missing_restore_media" ->
        "原始事实或照片不完整，无法安全恢复"
    "content_drift" -> "本次解决内容与先前提交不一致"
    "capability_mismatch" -> "家庭服务版本不匹配，未提交任何修改"
    else -> "冲突解决被拒绝（$code）"
}
