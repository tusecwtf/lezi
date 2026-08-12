package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession

/**
 * Publishes immutable fulfillment facts through the retained atomic-bundle transport.
 * Mutable roots are owned exclusively by [CausalSettlement].
 */
internal class EphemeralPublishPipeline(
    private val backend: SyncBackend,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    suspend fun pushPending(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) {
        require(candidates.all { it.entityType == "fulfillment_candidate" }) {
            "atomic bundle publisher only accepts immutable fulfillment facts"
        }
        val pending = EphemeralPublishPlan(candidates)
        while (!pending.isEmpty) {
            val rows = pending.peek(MAX_PUSH_BATCH_SIZE)
            for (row in rows) {
                if (session.role == FamilyRole.Member && !belongsToFamilyAuthorityBaby(row)) {
                    pending.consume(listOf(row))
                    continue
                }
                val bundleId = AtomicBundleId.forFulfillmentCandidate(
                    row.clientUuid,
                    row.updatedAt,
                )
                requireRemoteAllowed(session)
                backend.stageBundle(
                    session,
                    AtomicBundleDraft(
                        bundleId = bundleId,
                        root = row.toSyncEntity(),
                        media = emptyList(),
                    ),
                )
                requireRemoteAllowed(session)
                backend.commitBundle(session, bundleId)
                fulfillmentCandidateDao.markSynced(row.clientUuid, row.updatedAt)
                pending.consume(listOf(row))
            }
        }
    }

    private suspend fun belongsToFamilyAuthorityBaby(row: PublishCandidate): Boolean =
        fulfillmentCandidateDao.getByClientUuid(row.clientUuid)
            ?.let { candidate -> carePlanDao.getByClientUuid(candidate.carePlanClientUuid) }
            ?.let { plan -> babyDao.getIncludingDeleted(plan.babyId)?.familyAuthority }
            ?: false

    private fun PublishCandidate.toSyncEntity(): SyncEntity = SyncEntity(
        type = entityType,
        clientUuid = clientUuid,
        payloadJson = payloadJson,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
}
