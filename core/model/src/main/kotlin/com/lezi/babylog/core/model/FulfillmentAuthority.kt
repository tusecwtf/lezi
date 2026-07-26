package com.lezi.babylog.core.model

/**
 * Local adoption marks for multi-candidate fulfillment.
 * Derived on each device from frozen server evidence; not family wire fields.
 */
object FulfillmentAdoptionStatus {
    const val ADOPTED: String = "adopted"
    const val CONFLICT_NOT_ADOPTED: String = "conflict_not_adopted"
}

/**
 * Immutable evidence for one fulfillment attempt. Only fields that participate in
 * winner selection; NAS arrival time, actual care time, and device updatedAt are
 * deliberately absent.
 */
data class FulfillmentCandidateEvidence(
    val clientUuid: String,
    val recordClientUuid: String,
    val confirmedAt: Long,
    /** Server-stamped role at first accept (`owner` / `member`); empty before stamp. */
    val submitterRole: String = "",
)

/**
 * Result of deterministic multi-candidate adjudication for one care plan.
 */
data class FulfillmentResolution(
    val winnerClientUuid: String,
    val winnerRecordClientUuid: String,
    val winnerConfirmedAt: Long,
    /** Candidate clientUuid → [FulfillmentAdoptionStatus] value. */
    val adoptionByCandidateUuid: Map<String, String>,
)

/**
 * Pure multi-candidate authority comparator for concurrent care-plan fulfills.
 *
 * Comparison keys (in order):
 * 1. Submitter is family admin/owner (admin wins)
 * 2. Earlier immutable [FulfillmentCandidateEvidence.confirmedAt] wins
 * 3. Stable candidate [FulfillmentCandidateEvidence.clientUuid] ascending
 *
 * NAS arrival order, editable actual care time, device updatedAt, and later role
 * changes must not affect this pure function — only the frozen stamped fields.
 */
object FulfillmentAuthority {
    fun isAdminRole(role: String): Boolean {
        val normalized = role.trim().lowercase()
        return normalized == "owner" || normalized == "admin"
    }

    /**
     * Compare two candidates for min-selection: negative means [a] ranks better
     * (should win against [b]).
     */
    fun compare(a: FulfillmentCandidateEvidence, b: FulfillmentCandidateEvidence): Int {
        val aAdmin = isAdminRole(a.submitterRole)
        val bAdmin = isAdminRole(b.submitterRole)
        if (aAdmin != bAdmin) return if (aAdmin) -1 else 1
        val byTime = a.confirmedAt.compareTo(b.confirmedAt)
        if (byTime != 0) return byTime
        return a.clientUuid.compareTo(b.clientUuid)
    }

    fun selectWinner(
        candidates: Collection<FulfillmentCandidateEvidence>,
    ): FulfillmentCandidateEvidence? =
        candidates.minWithOrNull(::compare)

    /**
     * Resolve the full adoption map for a plan's live candidates.
     * Empty input → null. Single candidate → adopted. Multi → one winner, rest
     * [FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED].
     */
    fun resolve(
        candidates: Collection<FulfillmentCandidateEvidence>,
    ): FulfillmentResolution? {
        if (candidates.isEmpty()) return null
        val winner = selectWinner(candidates) ?: return null
        val adoption = candidates.associate { candidate ->
            candidate.clientUuid to if (candidate.clientUuid == winner.clientUuid) {
                FulfillmentAdoptionStatus.ADOPTED
            } else {
                FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED
            }
        }
        return FulfillmentResolution(
            winnerClientUuid = winner.clientUuid,
            winnerRecordClientUuid = winner.recordClientUuid,
            winnerConfirmedAt = winner.confirmedAt,
            adoptionByCandidateUuid = adoption,
        )
    }

    /**
     * Human-readable reason a loser lost multi-candidate authority against [winner].
     * Pure; mirrors [compare] key order for audit detail copy.
     */
    fun notAdoptedReason(
        loser: FulfillmentCandidateEvidence,
        winner: FulfillmentCandidateEvidence,
    ): String {
        val loserAdmin = isAdminRole(loser.submitterRole)
        val winnerAdmin = isAdminRole(winner.submitterRole)
        return when {
            loserAdmin != winnerAdmin && winnerAdmin ->
                "未采纳：另一履行由管理员确认"
            loser.confirmedAt != winner.confirmedAt ->
                "未采纳：另一履行确认时间更早"
            else ->
                "未采纳：候选身份排序后落选"
        }
    }
}
