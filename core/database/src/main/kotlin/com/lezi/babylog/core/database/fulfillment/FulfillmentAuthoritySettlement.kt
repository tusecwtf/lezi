package com.lezi.babylog.core.database.fulfillment

import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import javax.inject.Inject

/**
 * Atomically projects immutable fulfillment evidence into device-local authority state.
 *
 * Callers provide only the plan identity. This Module owns the evidence read, pure
 * resolution, candidate adoption patches, and plan relink in one Room transaction.
 * Derived writes deliberately preserve sync metadata and converted Record pointers.
 */
class FulfillmentAuthoritySettlement @Inject constructor(
    private val carePlanDao: CarePlanDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    suspend fun settle(carePlanClientUuid: String) {
        require(carePlanClientUuid.isNotBlank()) {
            "护理计划履行裁决标识不能为空"
        }
        transactionRunner.run {
            val live = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
                .filter { it.deletedAt == null }
            if (live.isEmpty()) return@run
            val resolution = FulfillmentAuthority.resolve(
                live.map { candidate ->
                    FulfillmentCandidateEvidence(
                        clientUuid = candidate.clientUuid,
                        recordClientUuid = candidate.recordClientUuid,
                        confirmedAt = candidate.confirmedAt,
                        submitterRole = candidate.submitterRole,
                    )
                },
            ) ?: return@run
            val patches = FulfillmentAuthority.adoptionStatusPatches(
                liveClientUuidToStatus = live.associate { candidate ->
                    candidate.clientUuid to candidate.adoptionStatus
                },
                resolution = resolution,
            )
            if (patches.isNotEmpty()) {
                val byClientUuid = live.associateBy { it.clientUuid }
                patches.forEach { (clientUuid, adoptionStatus) ->
                    val candidate = byClientUuid.getValue(clientUuid)
                    fulfillmentCandidateDao.update(
                        candidate.copy(adoptionStatus = adoptionStatus),
                    )
                }
            }
            val plan = carePlanDao.getByClientUuid(carePlanClientUuid)
                ?.takeIf { it.deletedAt == null }
                ?: return@run
            if (
                FulfillmentAuthority.needsPlanRelink(
                    currentStatusStorageKey = plan.status,
                    currentFulfilledRecordClientUuid = plan.fulfilledRecordClientUuid,
                    currentFulfilledAt = plan.fulfilledAt,
                    resolution = resolution,
                )
            ) {
                carePlanDao.update(
                    plan.copy(
                        status = CarePlanStatus.COMPLETED.storageKey,
                        fulfilledRecordClientUuid = resolution.winnerRecordClientUuid,
                        fulfilledAt = resolution.winnerConfirmedAt,
                    ),
                )
            }
        }
    }
}
