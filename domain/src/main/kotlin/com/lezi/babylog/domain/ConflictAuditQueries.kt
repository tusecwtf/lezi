package com.lezi.babylog.domain

import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.sync.SyncPort
import kotlinx.coroutines.flow.first

/**
 * Admin-facing conflict-not-adopted fulfillment audits (read surface).
 *
 * Conversion to an independent record stays on [CareLog] because it mutates
 * records, sleep state, and outbox through the care lifecycle.
 */
internal class ConflictAuditQueries(
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val carePlanDao: CarePlanDao,
    private val recordDao: RecordDao,
    private val mediaAssetDao: MediaAssetDao,
    private val syncPort: SyncPort,
    private val listRecordPhotoPaths: suspend (Long) -> List<String>,
) {
    /**
     * Admin-only conflict-not-adopted audits for a plan (or all babies when [carePlanClientUuid]
     * is null). Non-admins receive an empty list — button hide is not the only gate.
     */
    suspend fun listConflictNotAdoptedAudits(
        carePlanClientUuid: String? = null,
        babyId: Long? = null,
    ): List<ConflictNotAdoptedAudit> {
        if (!isFamilyAdmin()) return emptyList()
        val candidates = if (carePlanClientUuid != null) {
            fulfillmentCandidateDao.listConflictNotAdoptedForCarePlan(carePlanClientUuid)
        } else {
            fulfillmentCandidateDao.listConflictNotAdopted()
        }
        if (candidates.isEmpty()) return emptyList()
        val nameByMembership = resolveSubmitterDisplayNames(candidates)
        return candidates.mapNotNull { candidate ->
            buildConflictNotAdoptedAudit(candidate, nameByMembership)
        }.filter { babyId == null || it.babyId == babyId }
    }

    /**
     * Admin-only single audit detail. Null when missing, not conflict-not-adopted,
     * or the actor is not an admin (fail closed for deep links / direct calls).
     */
    suspend fun getConflictNotAdoptedAudit(
        candidateClientUuid: String,
    ): ConflictNotAdoptedAudit? {
        if (!isFamilyAdmin()) return null
        val candidate = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
            ?: return null
        if (candidate.deletedAt != null) return null
        if (candidate.adoptionStatus != FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED) {
            return null
        }
        val nameByMembership = resolveSubmitterDisplayNames(listOf(candidate))
        return buildConflictNotAdoptedAudit(candidate, nameByMembership)
    }

    private suspend fun resolveSubmitterDisplayNames(
        candidates: List<FulfillmentCandidateEntity>,
    ): Map<String, String> {
        val members = syncPort.listFamilyMembers().getOrNull().orEmpty()
        val byMembership = members.mapNotNull { member ->
            val id = member.membershipId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = member.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (member.role == com.lezi.babylog.sync.FamilyRole.Owner) {
                    "家庭管理员"
                } else {
                    "家庭成员"
                }
            id to name
        }.toMap()
        // Ensure every candidate membership key exists for fallback labels.
        return candidates.associate { candidate ->
            val mid = candidate.submitterMembershipId.trim()
            mid to (
                byMembership[mid]
                    ?: when {
                        mid.isEmpty() -> "未知提交者"
                        FulfillmentAuthority.isAdminRole(candidate.submitterRole) -> "家庭管理员"
                        candidate.submitterRole.isNotBlank() -> "家庭成员"
                        else -> mid
                    }
                )
        }
    }

    private suspend fun buildConflictNotAdoptedAudit(
        candidate: FulfillmentCandidateEntity,
        nameByMembership: Map<String, String>,
    ): ConflictNotAdoptedAudit? {
        val plan = carePlanDao.getByClientUuid(candidate.carePlanClientUuid) ?: return null
        val source = recordDao.getByClientUuid(candidate.recordClientUuid)
        val type = source?.let { RecordType.fromKey(it.type) }
            ?: RecordType.fromKey(plan.type)
            ?: return null
        val photos = when {
            source != null && source.deletedAt == null -> listRecordPhotoPaths(source.id)
            source != null -> mediaAssetDao.listForRecord(source.id)
                .map(MediaAssetEntity::localUri)
                .filter(String::isNotBlank)
                .distinct()
            else -> emptyList()
        }
        val live = fulfillmentCandidateDao.listForCarePlan(candidate.carePlanClientUuid)
            .filter { it.deletedAt == null }
        val evidences = live.map {
            FulfillmentCandidateEvidence(
                clientUuid = it.clientUuid,
                recordClientUuid = it.recordClientUuid,
                confirmedAt = it.confirmedAt,
                submitterRole = it.submitterRole,
            )
        }
        val winner = FulfillmentAuthority.selectWinner(evidences)
        val loserEvidence = FulfillmentCandidateEvidence(
            clientUuid = candidate.clientUuid,
            recordClientUuid = candidate.recordClientUuid,
            confirmedAt = candidate.confirmedAt,
            submitterRole = candidate.submitterRole,
        )
        val reason = if (winner != null && winner.clientUuid != candidate.clientUuid) {
            FulfillmentAuthority.notAdoptedReason(loserEvidence, winner)
        } else {
            "未采纳：履行冲突裁决落选"
        }
        val convertedUuid = candidate.convertedRecordClientUuid.trim()
        val converted = convertedUuid.takeIf { it.isNotEmpty() }
            ?.let { recordDao.getByClientUuid(it) }
            ?.takeIf { it.deletedAt == null }
        val typeLabel = when {
            source != null -> source.toModel().displayLabel()
            else -> plan.toModel().displayLabel()
        }
        val mid = candidate.submitterMembershipId.trim()
        return ConflictNotAdoptedAudit(
            candidateClientUuid = candidate.clientUuid,
            carePlanClientUuid = candidate.carePlanClientUuid,
            carePlanId = plan.id,
            babyId = plan.babyId,
            type = type,
            typeLabel = typeLabel,
            note = source?.note,
            actualTimestamp = candidate.actualTimestamp ?: source?.timestamp,
            confirmedAt = candidate.confirmedAt,
            submitterMembershipId = candidate.submitterMembershipId,
            submitterRole = candidate.submitterRole,
            submitterDisplayName = nameByMembership[mid] ?: mid.ifBlank { "未知提交者" },
            notAdoptedReason = reason,
            photoLocalPaths = photos,
            sourceRecordClientUuid = candidate.recordClientUuid,
            sourceRecordId = source?.id,
            convertedRecordClientUuid = converted?.clientUuid.orEmpty(),
            convertedRecordId = converted?.id,
        )
    }


    private suspend fun isFamilyAdmin(): Boolean {
        val session = syncPort.session().first()
        return session.role == com.lezi.babylog.sync.FamilyRole.Owner
    }
}
