package com.lezi.babylog.domain.careplan

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.domain.Fakes
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FulfillmentAuthoritySettlementTest {
    @Test
    fun ownerEvidenceAtomicallyReplacesEarlierMemberAuthorityWithoutDirtyingDerivedState() =
        runTest {
            val fakes = Fakes()
            fakes.wireTransactionalSnapshots()
            fakes.carePlans.upsert(
                CarePlanEntity(
                    clientUuid = "plan-a",
                    babyId = 1L,
                    type = "formula",
                    scheduledAt = 10L,
                    scheduledZoneId = "UTC",
                    status = CarePlanStatus.COMPLETED.storageKey,
                    fulfilledRecordClientUuid = "record-member",
                    fulfilledAt = 20L,
                    updatedAt = 90L,
                    syncDirty = false,
                ),
            )
            fakes.fulfillmentCandidates.upsert(
                candidate(
                    clientUuid = "candidate-member",
                    recordClientUuid = "record-member",
                    role = "member",
                    confirmedAt = 20L,
                    updatedAt = 100L,
                    syncDirty = false,
                    convertedRecordClientUuid = "record-independent",
                ),
            )
            fakes.fulfillmentCandidates.upsert(
                candidate(
                    clientUuid = "candidate-owner",
                    recordClientUuid = "record-owner",
                    role = "owner",
                    confirmedAt = 30L,
                    updatedAt = 110L,
                    syncDirty = true,
                ),
            )

            val settlement = FulfillmentAuthoritySettlement(
                carePlanDao = fakes.carePlans,
                fulfillmentCandidateDao = fakes.fulfillmentCandidates,
                transactionRunner = fakes.transactions,
            )
            settlement.settle("plan-a")

            val candidates = fakes.fulfillmentCandidates.listForCarePlan("plan-a")
                .associateBy { it.clientUuid }
            assertThat(candidates.getValue("candidate-owner").adoptionStatus)
                .isEqualTo(FulfillmentAdoptionStatus.ADOPTED)
            assertThat(candidates.getValue("candidate-member").adoptionStatus)
                .isEqualTo(FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
            assertThat(candidates.getValue("candidate-member").updatedAt).isEqualTo(100L)
            assertThat(candidates.getValue("candidate-member").syncDirty).isFalse()
            assertThat(candidates.getValue("candidate-member").convertedRecordClientUuid)
                .isEqualTo("record-independent")
            val plan = fakes.carePlans.getByClientUuid("plan-a")!!
            assertThat(plan.fulfilledRecordClientUuid).isEqualTo("record-owner")
            assertThat(plan.fulfilledAt).isEqualTo(30L)
            assertThat(plan.updatedAt).isEqualTo(90L)
            assertThat(plan.syncDirty).isFalse()
            val candidatesAfterFirst = fakes.fulfillmentCandidates.listForCarePlan("plan-a")

            settlement.settle("plan-a")

            assertThat(fakes.fulfillmentCandidates.listForCarePlan("plan-a"))
                .containsExactlyElementsIn(candidatesAfterFirst)
                .inOrder()
            assertThat(fakes.carePlans.getByClientUuid("plan-a")).isEqualTo(plan)
            assertThat(fakes.transactions.runCount).isEqualTo(2)
        }

    private fun candidate(
        clientUuid: String,
        recordClientUuid: String,
        role: String,
        confirmedAt: Long,
        updatedAt: Long,
        syncDirty: Boolean,
        convertedRecordClientUuid: String = "",
    ) = FulfillmentCandidateEntity(
        clientUuid = clientUuid,
        carePlanClientUuid = "plan-a",
        recordClientUuid = recordClientUuid,
        confirmedAt = confirmedAt,
        submitterRole = role,
        adoptionStatus = "",
        convertedRecordClientUuid = convertedRecordClientUuid,
        updatedAt = updatedAt,
        syncDirty = syncDirty,
    )
}
