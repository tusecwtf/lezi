package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.sourcerelation.SourceRelationCommandRefreshRequiredException
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.sync.backend.SourceRelationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    @Suppress("UNUSED_PARAMETER") nowMillis: () -> Long,
) {
    /**
     * Soft open groups from live records, excluding source-role UUIDs already
     * bound in a source relation (display may still group with residual peers).
     */
    suspend fun openSuspectedGroups(records: List<Record>): List<SuspectedDuplicateGroup> {
        val excluded = sourceRoleClientUuids()
        return openSuspectedGroups(records, excluded)
    }

    fun openSuspectedGroups(
        records: List<Record>,
        sourceRoleClientUuids: Set<String>,
    ): List<SuspectedDuplicateGroup> = SuspectedDuplicateGrouping.group(
        records,
        excludedClientUuids = sourceRoleClientUuids,
    )

    /** Source-role UUIDs to drop from ordinary timeline/stats projection. */
    suspend fun sourceRoleClientUuids(): Set<String> =
        sourceRelationDao.listAllMembers()
            .filter { it.role == SourceRelationRole.SOURCE }
            .mapTo(mutableSetOf()) { it.recordClientUuid }

    fun observeSourceRoleClientUuids(): Flow<Set<String>> =
        sourceRelationDao.observeAllMembers().map { members ->
            members.asSequence()
                .filter { it.role == SourceRelationRole.SOURCE }
                .mapTo(mutableSetOf()) { it.recordClientUuid }
        }.distinctUntilChanged()

    suspend fun autoAlignedDisplayClientUuids(): Set<String> =
        sourceRelationDao.listAutoAlignedDisplayClientUuids().toSet()

    fun observeAutoAlignedDisplayClientUuids(): Flow<Set<String>> =
        sourceRelationDao.observeAutoAlignedDisplayClientUuids()
            .map { it.toSet() }
            .distinctUntilChanged()

    suspend fun sourceRecordsByDisplay(
        records: List<Record>,
    ): Map<String, List<Record>> {
        val byUuid = records.associateBy(Record::clientUuid)
        val displayByRelation = sourceRelationDao.listAll()
            .filter { it.displayClientUuid.isNotBlank() }
            .associate { it.relationId to it.displayClientUuid }
        val grouped = linkedMapOf<String, MutableList<Record>>()
        sourceRelationDao.listAllMembers()
            .filter { it.role == SourceRelationRole.SOURCE }
            .forEach { member ->
                val display = displayByRelation[member.relationId] ?: return@forEach
                val source = byUuid[member.recordClientUuid] ?: return@forEach
                grouped.getOrPut(display, ::mutableListOf) += source
            }
        return grouped
    }

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
        val result = try {
            syncPort.declareSourceRelation(
                SourceRelationDeclareRequest(
                    mutationId = mutationId,
                    recordClientUuid = recordClientUuid,
                    equivalentToClientUuid = equivalentToClientUuid,
                    expectedRecordVersion = expectedRecord,
                    expectedOtherVersion = expectedOther,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refresh: SourceRelationCommandRefreshRequiredException) {
            return SourceRelationOutcome.ConfirmedRefreshRequired(requireNotNull(refresh.message))
        } catch (error: Throwable) {
            return SourceRelationOutcome.Rejected(
                "transport",
                error.message ?: "声明来源关系失败",
            )
        }
        return consumeResult(result)
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
        if (members.size > 64) {
            return SourceRelationOutcome.Rejected("invalid", "组内最多允许 64 条记录")
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
        val result = try {
            syncPort.resolveSourceRelationGroup(
                SourceRelationResolveGroupRequest(
                    mutationId = mutationId,
                    memberClientUuids = members,
                    displayClientUuid = displayClientUuid,
                    expectedVersions = expected,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refresh: SourceRelationCommandRefreshRequiredException) {
            return SourceRelationOutcome.ConfirmedRefreshRequired(requireNotNull(refresh.message))
        } catch (error: Throwable) {
            return SourceRelationOutcome.Rejected(
                "transport",
                error.message ?: "解决疑似重复组失败",
            )
        }
        return consumeResult(result)
    }

    /** SyncPort returns only after its canonical rows and command evidence settle atomically. */
    private fun consumeResult(result: SourceRelationResult): SourceRelationOutcome = when (result.status) {
        "accepted" -> {
            val relationId = result.relationId
            val display = result.displayClientUuid
            val current = result.currentProjection
            val stillCurrent = current == null || current.sourceRelations.any { group ->
                group.relationId == relationId && group.displayClientUuid == display &&
                    group.sourceClientUuids.toSet() == result.sourceClientUuids.toSet()
            }
            when {
                !stillCurrent -> SourceRelationOutcome.ConfirmedAndRefreshed("选择已确认，已同步服务器当前来源关系")
                relationId.isNullOrBlank() -> SourceRelationOutcome.Rejected("invalid", "服务器未返回 relation_id")
                display.isNullOrBlank() -> SourceRelationOutcome.Rejected("invalid", "服务器未返回 display")
                else -> SourceRelationOutcome.Accepted(relationId, display, result.sourceClientUuids)
            }
        }
        "cas_mismatch" -> SourceRelationOutcome.CasMismatch(
            latestVersions = result.latestVersions,
            message = "疑似重复内容已更新，请根据最新版本重新确认",
        )
        else -> SourceRelationOutcome.Rejected(
            result.code ?: result.status,
            "来源关系请求被拒绝",
        )
    }
}

sealed class SourceRelationOutcome {
    data class ConfirmedRefreshRequired(val message: String) : SourceRelationOutcome()
    data class ConfirmedAndRefreshed(val message: String) : SourceRelationOutcome()

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
