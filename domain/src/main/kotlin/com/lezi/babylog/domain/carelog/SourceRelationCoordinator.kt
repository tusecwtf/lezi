package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationStatus
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.sync.backend.SourceRelationResult

/**
 * Explicit author declare / Owner group resolve for 疑似重复组.
 *
 * Never writes Record.deletedAt or ordinary media tombstones. Local source
 * relation rows keep provenance for timeline "来源详情".
 *
 * Author declare is a half-edge (2 members): only the author's UUID becomes
 * source and leaves soft grouping; residual members of a larger component
 * remain open until Owner full-group resolve covers them.
 */
internal class SourceRelationCoordinator(
    private val recordDao: RecordDao,
    private val sourceRelationDao: SourceRelationDao,
    private val syncPort: SyncPort,
    private val currentMembershipId: suspend () -> String,
    private val isFamilyOwner: suspend () -> Boolean,
    private val nowMillis: () -> Long,
) {
    /**
     * Soft open groups from live records, excluding source-role UUIDs already
     * bound in a source relation (display may still group with residual peers).
     */
    suspend fun openSuspectedGroups(records: List<Record>): List<SuspectedDuplicateGroup> {
        val excluded = sourceRoleClientUuids()
        return SuspectedDuplicateGrouping.group(records, excludedClientUuids = excluded)
    }

    /** Source-role UUIDs to drop from ordinary timeline/stats projection. */
    suspend fun sourceRoleClientUuids(): Set<String> =
        sourceRelationDao.listAllMembers()
            .filter { it.role == SourceRelationRole.SOURCE }
            .mapTo(mutableSetOf()) { it.recordClientUuid }

    /** All record UUIDs already bound in any source relation. */
    suspend fun relatedRecordClientUuids(): Set<String> =
        sourceRelationDao.listAllMembers().mapTo(mutableSetOf()) { it.recordClientUuid }

    /**
     * Author declares [recordClientUuid] (must be self-authored) equivalent to
     * [equivalentToClientUuid]. CAS uses each record's baseVersion.
     */
    suspend fun declareEquivalent(
        recordClientUuid: String,
        equivalentToClientUuid: String,
    ): SourceRelationOutcome {
        val membershipId = currentMembershipId()
        val record = recordDao.getByClientUuid(recordClientUuid)
            ?: return SourceRelationOutcome.Rejected("not_found", "记录不存在")
        if (record.createdByMembershipId != membershipId) {
            return SourceRelationOutcome.Rejected("forbidden", "只能声明自己的记录为重复")
        }
        val other = recordDao.getByClientUuid(equivalentToClientUuid)
            ?: return SourceRelationOutcome.Rejected("not_found", "对照记录不存在")
        val expectedRecord = record.baseVersion
            ?: return SourceRelationOutcome.Rejected("missing_version", "记录尚未获得稳定版本")
        val expectedOther = other.baseVersion
            ?: return SourceRelationOutcome.Rejected("missing_version", "对照记录尚未获得稳定版本")
        val mutationId = newClientUuid()
        val pending = SourceRelationDeclarationEntity(
            mutationId = mutationId,
            recordClientUuid = recordClientUuid,
            equivalentToClientUuid = equivalentToClientUuid,
            expectedRecordVersion = expectedRecord,
            expectedOtherVersion = expectedOther,
            authorMembershipId = membershipId,
            status = SourceRelationDeclarationStatus.PENDING,
            createdAt = nowMillis(),
        )
        sourceRelationDao.upsertDeclaration(pending)
        val result = runCatching {
            syncPort.declareSourceRelation(
                SourceRelationDeclareRequest(
                    mutationId = mutationId,
                    recordClientUuid = recordClientUuid,
                    equivalentToClientUuid = equivalentToClientUuid,
                    expectedRecordVersion = expectedRecord,
                    expectedOtherVersion = expectedOther,
                ),
            )
        }.getOrElse { error ->
            sourceRelationDao.upsertDeclaration(
                pending.copy(status = SourceRelationDeclarationStatus.FAILED),
            )
            return SourceRelationOutcome.Rejected(
                "transport",
                error.message ?: "声明来源关系失败",
            )
        }
        return consumeResult(
            result = result,
            pendingDeclaration = pending,
            reason = SourceRelationReason.AUTHOR_DECLARE,
            membershipId = membershipId,
        )
    }

    /**
     * Owner resolves a complete group: [displayClientUuid] is shown; others become sources.
     * CAS requires every member's current baseVersion.
     */
    suspend fun resolveGroupAsOwner(
        memberClientUuids: List<String>,
        displayClientUuid: String,
    ): SourceRelationOutcome {
        if (!isFamilyOwner()) {
            return SourceRelationOutcome.Rejected("forbidden", "仅家庭管理员可解决整组疑似重复")
        }
        val members = memberClientUuids.distinct().sorted()
        if (members.size < 2) {
            return SourceRelationOutcome.Rejected("invalid", "组内至少需要两条记录")
        }
        if (displayClientUuid !in members) {
            return SourceRelationOutcome.Rejected("invalid", "展示版本必须属于该组")
        }
        val expected = linkedMapOf<String, String>()
        for (uuid in members) {
            val row = recordDao.getByClientUuid(uuid)
                ?: return SourceRelationOutcome.Rejected("not_found", "组内记录不存在")
            val version = row.baseVersion
                ?: return SourceRelationOutcome.Rejected("missing_version", "组内记录缺少稳定版本")
            expected[uuid] = version
        }
        val mutationId = newClientUuid()
        val result = runCatching {
            syncPort.resolveSourceRelationGroup(
                SourceRelationResolveGroupRequest(
                    mutationId = mutationId,
                    memberClientUuids = members,
                    displayClientUuid = displayClientUuid,
                    expectedVersions = expected,
                ),
            )
        }.getOrElse { error ->
            return SourceRelationOutcome.Rejected(
                "transport",
                error.message ?: "解决疑似重复组失败",
            )
        }
        return consumeResult(
            result = result,
            pendingDeclaration = null,
            reason = SourceRelationReason.OWNER_GROUP_RESOLVE,
            membershipId = currentMembershipId(),
            ownerMutationId = mutationId,
        )
    }

    private suspend fun consumeResult(
        result: SourceRelationResult,
        pendingDeclaration: SourceRelationDeclarationEntity?,
        reason: String,
        membershipId: String,
        ownerMutationId: String? = null,
    ): SourceRelationOutcome {
        val mutationId = pendingDeclaration?.mutationId ?: ownerMutationId.orEmpty()
        when (result.status) {
            "accepted" -> {
                val relationId = result.relationId
                    ?: return failDeclaration(
                        pendingDeclaration,
                        SourceRelationOutcome.Rejected("invalid", "服务器未返回 relation_id"),
                    )
                val display = result.displayClientUuid
                    ?: return failDeclaration(
                        pendingDeclaration,
                        SourceRelationOutcome.Rejected("invalid", "服务器未返回 display"),
                    )
                val sources = result.sourceClientUuids
                persistRelation(
                    relationId = relationId,
                    display = display,
                    sources = sources,
                    reason = reason,
                    mutationId = mutationId,
                    membershipId = membershipId,
                    declaration = pendingDeclaration?.copy(
                        status = SourceRelationDeclarationStatus.CONSUMED,
                    ),
                )
                return SourceRelationOutcome.Accepted(
                    relationId = relationId,
                    displayClientUuid = display,
                    sourceClientUuids = sources,
                )
            }
            "cas_mismatch" -> {
                if (pendingDeclaration != null) {
                    sourceRelationDao.upsertDeclaration(
                        pendingDeclaration.copy(
                            status = SourceRelationDeclarationStatus.SUPERSEDED,
                        ),
                    )
                }
                return SourceRelationOutcome.CasMismatch(
                    latestVersions = result.latestVersions,
                    message = "疑似重复内容已更新，请根据最新版本重新确认",
                )
            }
            else -> {
                return failDeclaration(
                    pendingDeclaration,
                    SourceRelationOutcome.Rejected(
                        result.code ?: result.status,
                        "来源关系请求被拒绝",
                    ),
                )
            }
        }
    }

    private suspend fun failDeclaration(
        pending: SourceRelationDeclarationEntity?,
        outcome: SourceRelationOutcome.Rejected,
    ): SourceRelationOutcome.Rejected {
        if (pending != null) {
            sourceRelationDao.upsertDeclaration(
                pending.copy(status = SourceRelationDeclarationStatus.FAILED),
            )
        }
        return outcome
    }

    private suspend fun persistRelation(
        relationId: String,
        display: String,
        sources: List<String>,
        reason: String,
        mutationId: String,
        membershipId: String,
        declaration: SourceRelationDeclarationEntity?,
    ) {
        val relation = SourceRelationEntity(
            relationId = relationId,
            displayClientUuid = display,
            mediaRetained = true,
            reason = reason,
            mutationId = mutationId,
            createdByMembershipId = membershipId,
            createdAt = nowMillis(),
        )
        val members = buildList {
            add(
                SourceRelationMemberEntity(
                    relationId = relationId,
                    recordClientUuid = display,
                    role = SourceRelationRole.DISPLAY,
                ),
            )
            for (source in sources) {
                add(
                    SourceRelationMemberEntity(
                        relationId = relationId,
                        recordClientUuid = source,
                        role = SourceRelationRole.SOURCE,
                    ),
                )
            }
        }
        when (reason) {
            SourceRelationReason.AUTHOR_DECLARE -> {
                requireNotNull(declaration) { "author declare requires declaration status" }
                sourceRelationDao.applyAuthorDeclaration(relation, members, declaration)
            }
            SourceRelationReason.OWNER_GROUP_RESOLVE -> {
                sourceRelationDao.applyOwnerGroupResolution(relation, members)
            }
            else -> sourceRelationDao.applyRelation(relation, members, declaration)
        }
    }
}

sealed class SourceRelationOutcome {
    data class Accepted(
        val relationId: String,
        val displayClientUuid: String,
        val sourceClientUuids: List<String>,
    ) : SourceRelationOutcome()

    data class CasMismatch(
        val latestVersions: Map<String, String>,
        val message: String,
    ) : SourceRelationOutcome()

    data class Rejected(val code: String, val message: String) : SourceRelationOutcome()
}
