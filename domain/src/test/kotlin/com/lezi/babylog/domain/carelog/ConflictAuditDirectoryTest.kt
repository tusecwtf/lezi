package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.domain.Fakes
import com.lezi.babylog.domain.RecordingSyncPort
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConflictAuditDirectoryTest {
    @Test
    fun submitterDisplayNameResolvesFromDirectoryWithoutLiveRoster() = runTest {
        var liveRosterCalls = 0
        val base = RecordingSyncPort(
            membershipId = "m-owner",
            role = FamilyRole.Owner,
            familyId = "fam-audit",
            deviceId = "dev-owner",
        )
        val port = object : SyncPort by base {
            override fun familyMemberDirectory() = flowOf(
                listOf(
                    FamilyMember(
                        displayName = "豆豆妈妈",
                        role = FamilyRole.Member,
                        isSelf = false,
                        membershipId = "m-member",
                    ),
                ),
            )

            override suspend fun listFamilyMembers(): Result<List<FamilyMember>> {
                liveRosterCalls += 1
                return Result.failure(IllegalStateException("live roster"))
            }
        }
        val fakes = Fakes(port)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = 20_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = 1_000L,
            projectToSystemCalendar = false,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        fakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "loser-cand",
                carePlanClientUuid = planUuid,
                recordClientUuid = "missing-record",
                confirmedAt = 2_000L,
                submitterMembershipId = "m-member",
                submitterRole = "member",
                adoptionStatus = FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                updatedAt = 2_000L,
            ),
        )

        val audit = care.listConflictNotAdoptedAudits(planUuid).single()

        assertThat(audit.submitterDisplayName).isEqualTo("豆豆妈妈")
        assertThat(liveRosterCalls).isEqualTo(0)
    }
}
