package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.TerminalRejectionReceipt
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Publishes immutable fulfillment facts through the retained atomic-bundle transport.
 * Mutable roots are owned exclusively by [CausalSettlement].
 */
internal class EphemeralPublishPipeline(
    private val backend: SyncBackend,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao? = null,
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
                val receipt = conflictSnapshotCacheDao?.getTerminalReceipt(
                    "fulfillment_candidate",
                    row.clientUuid,
                )
                if (receipt != null && receipt.contentEpoch == row.updatedAt) {
                    pending.consume(listOf(row))
                    continue
                }
                if (session.role == FamilyRole.Member && !belongsToFamilyAuthorityBaby(row)) {
                    writeAbandonedReceipt(row)
                    pending.consume(listOf(row))
                    continue
                }
                val bundleId = AtomicBundleId.forFulfillmentCandidate(
                    row.clientUuid,
                    row.updatedAt,
                )
                try {
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
                } catch (error: SyncHttpException) {
                    if (error.statusCode != 409 && error.statusCode != 422) throw error
                    if (error.isTransientBundleConflict()) throw error
                    writeAbandonedReceipt(row)
                    pending.consume(listOf(row))
                }
            }
        }
    }

    /**
     * 409s that are not terminal for the fact: the family generation moved
     * (the recovery envelope must reach the engine's full-resync handling) or
     * a referenced root is not on the server yet (its causal settle can still
     * land; the server defers the same condition on pull). Abandoning here
     * would silently drop a family fact; rethrowing keeps the row pending —
     * foreground rounds report non-convergence and quiet rounds retry.
     */
    private fun SyncHttpException.isTransientBundleConflict(): Boolean {
        if (statusCode != 409) return false
        val detail = runCatching {
            Json.parseToJsonElement(responseBody).jsonObject["detail"]
        }.getOrNull() ?: return false
        if (detail is JsonObject) {
            return (detail["action"] as? JsonPrimitive)?.contentOrNull == "full_resync"
        }
        return (detail as? JsonPrimitive)?.contentOrNull?.contains("does not exist") == true
    }

    private suspend fun writeAbandonedReceipt(row: PublishCandidate) {
        val cache = conflictSnapshotCacheDao ?: return
        val existing = cache.getTerminalReceipt("fulfillment_candidate", row.clientUuid)
        cache.putTerminalReceipt(
            TerminalRejectionReceipt(
                entityType = "fulfillment_candidate",
                clientUuid = row.clientUuid,
                mutationId = existing?.mutationId.orEmpty(),
                code = "abandoned",
                contentEpoch = row.updatedAt,
                recordedAt = System.currentTimeMillis(),
                abandoned = true,
            ),
        )
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
