package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CareLogRealServerSeamNursingPlanTest {
    @Test
    fun ordinaryNursingIntentPublishesToPeerButFulfillmentRequiresRealDuration() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val owner = fixture.owner
            val member = fixture.member
            val now = owner.clock.nowMillis()
            val baby = owner.careLog.createBaby(CreateBabyInput("Nursing intent", birthdayEpochDay = 20_000))
            owner.settleLocalWrite()
            val planId = owner.careLog.createPlan(CreateCarePlan(
                CareBabyId(baby), CareFactContent(now + 60_000, NursingPayload()),
                projectToSystemCalendar = false,
            ), now)
            val uuid = owner.careLog.getCarePlan(planId)!!.clientUuid
            owner.settleLocalWrite()
            member.pullForeground()
            val peerPlan = requireNotNull(member.careLog.getCarePlanByClientUuid(uuid))
            assertThat(RecordPayloadCodec.decode(peerPlan.type, peerPlan.payloadJson, peerPlan.schemaVersion).payload)
                .isEqualTo(NursingPayload())
            assertThat(runCatching {
                member.careLog.fulfillPlan(FulfillCarePlan(
                    CarePlanId(peerPlan.id), CareFactContent(now, NursingPayload()),
                ), now)
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            val factId = member.careLog.fulfillPlan(FulfillCarePlan(
                CarePlanId(peerPlan.id), CareFactContent(now, NursingPayload(leftMinutes = 2)),
            ), now)
            val factUuid = member.careLog.getRecord(factId)!!.clientUuid
            member.settleLocalWrite()
            owner.pullForeground()
            assertThat(owner.careLog.getRecordByClientUuid(factUuid)!!.payload.payload)
                .isEqualTo(NursingPayload(leftMinutes = 2))
            assertThat(runCatching {
                owner.careLog.createPlan(CreateCarePlan(
                    CareBabyId(baby), CareFactContent(now + 120_000, MilkPayload(RecordType.FORMULA, 0)),
                    projectToSystemCalendar = false,
                ), now)
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
