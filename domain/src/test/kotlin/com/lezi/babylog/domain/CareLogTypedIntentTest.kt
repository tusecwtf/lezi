package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.EmptyPayload
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogTypedIntentTest {
    private val now = 1_700_000_000_000L

    @Test
    fun typedRootEditsDistinguishKeepReplaceAndRemoveAllAttachments() = runTest {
        val care = Fakes().careLog()
        val baby = care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1))
        val content = CareFactContent(now, EmptyPayload(RecordType.BATH))
        val id = care.createRecord(CreateCareRecord(
            CareBabyId(baby), content, CareAttachments.Replace(listOf("one.jpg")),
        ), now)
        care.editRecord(EditCareRecord(CareRecordId(id), content.copy(note = "保留")), now)
        assertThat(care.listRecordPhotoPaths(id)).containsExactly("one.jpg")
        care.editRecord(EditCareRecord(CareRecordId(id), content,
            CareAttachments.Replace(listOf("two.jpg"))), now)
        assertThat(care.listRecordPhotoPaths(id)).containsExactly("two.jpg")
        care.editRecord(EditCareRecord(CareRecordId(id), content, CareAttachments.RemoveAll), now)
        assertThat(care.listRecordPhotoPaths(id)).isEmpty()
    }

    @Test
    fun typedBackfillAndWakeCorrectionPreserveIdentityAndReplayOneClosedFact() = runTest {
        val care = Fakes().careLog()
        val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
        val intent = BackfillCareSleep(baby, now - 60_000, now)
        val id = care.backfillSleep(intent, now)
        assertThat(care.backfillSleep(intent, now)).isEqualTo(id)
        val wake = care.projectSleepRecord(id)!!.observations.single()
        care.correctWake(CorrectCareWake(CareWakeId(wake.clientUuid), now - 10_000, "修正"), now)
        assertThat(care.projectSleepRecord(id)!!.interval.endTimestamp).isEqualTo(now - 10_000)
        assertThat(care.projectSleepRecord(id)!!.observations.single().clientUuid).isEqualTo(wake.clientUuid)
    }

    @Test
    fun typedAndLegacyRecordCreationHaveEqualPublicContentAndTransactionBudget() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1))
        val beforeLegacy = fakes.transactions.runCount
        val legacy = care.addRecord(baby, RecordType.BATH, now, note = "相同事实",
            photoLocalPaths = listOf("same.jpg"), nowMillis = now)
        val legacyWrites = fakes.transactions.runCount - beforeLegacy
        val beforeTyped = fakes.transactions.runCount
        val typed = care.createRecord(CreateCareRecord(
            CareBabyId(baby), CareFactContent(now, EmptyPayload(RecordType.BATH), "相同事实"),
            CareAttachments.Replace(listOf("same.jpg")),
        ), now)
        assertThat(fakes.transactions.runCount - beforeTyped).isEqualTo(legacyWrites)
        val oldFact = care.getRecord(legacy)!!
        val newFact = care.getRecord(typed)!!
        assertThat(newFact.type).isEqualTo(oldFact.type)
        assertThat(newFact.timestamp).isEqualTo(oldFact.timestamp)
        assertThat(newFact.note).isEqualTo(oldFact.note)
        assertThat(newFact.payloadJson).isEqualTo(oldFact.payloadJson)
        assertThat(care.listRecordPhotoPaths(typed)).isEqualTo(care.listRecordPhotoPaths(legacy))
    }

    @Test
    fun closedPlanFulfillmentRollsBackWakeFailureThenReplaysOneAuthoredGraph() = runTest {
        val fakes = Fakes(RecordingSyncPort(
            membershipId = "care-member", familyId = "family", deviceId = "device",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
        ))
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val baby = CareBabyId(fakes.seedFamilyAuthorityBaby())
        val plan = care.createPlan(CreateCarePlan(
            baby, CareFactContent(now + 60_000, com.lezi.babylog.core.model.SleepPayload()),
            projectToSystemCalendar = false,
        ), now)
        val intent = FulfillCarePlan(
            CarePlanId(plan),
            CareFactContent(now, com.lezi.babylog.core.model.SleepPayload(), endTimestamp = now + 60_000),
        )
        val diskFailure = IllegalStateException("synthetic wake storage failure")
        fakes.wakeObservations.upsertFailure = diskFailure
        assertThat(runCatching { care.fulfillPlan(intent, now + 60_000) }.exceptionOrNull())
            .isSameInstanceAs(diskFailure)
        assertThat(care.getRecordByClientUuid(intent.writeId.value)).isNull()
        assertThat(care.listWakeObservations(intent.writeId.value)).isEmpty()
        assertThat(care.getCarePlan(plan)!!.status)
            .isEqualTo(com.lezi.babylog.core.model.CarePlanStatus.PENDING)

        fakes.wakeObservations.upsertFailure = null
        val id = care.fulfillPlan(intent, now + 60_000)
        val graph = care.projectSleepRecord(id)!!
        assertThat(graph.interval.endTimestamp).isEqualTo(now + 60_000)
        assertThat(graph.observations).hasSize(1)
        assertThat(graph.observations.single().observerMembershipId).isEqualTo("care-member")
        assertThat(care.getRecord(id)!!.createdByMembershipId).isEqualTo("care-member")
        assertThat(care.fulfillPlan(intent, now + 60_000)).isEqualTo(id)
        assertThat(care.projectSleepRecord(id)!!.observations).hasSize(1)
    }

    @Test
    fun typedOpenSleepEditMatchesLegacyLatestTargetGuardAndKeepsAttachments() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
        val older = care.startSleep(StartCareSleep(baby, now - 60_000), now)
        // A separately authored concurrent start arrives at the database boundary.
        val newer = fakes.records.upsert(fakes.records.get(older)!!.copy(
            id = 0, clientUuid = "cf06fa91-ef40-49f2-91c5-4a4676518258", timestamp = now - 30_000,
        ))
        val payload = com.lezi.babylog.core.model.SleepPayload()
        val legacyFailure = runCatching {
            care.confirmSleep(
                baby.value, older, now - 50_000, null, "改睡下",
                """{"is_nap":false,"anomaly_flag":false}""", nowMillis = now,
            )
        }.exceptionOrNull()
        val typedFailure = runCatching {
            care.editOpenSleep(EditOpenCareSleep(
                baby, CareSleepId(older), now - 50_000, payload, "改睡下",
            ), now)
        }.exceptionOrNull()
        assertThat(legacyFailure).isInstanceOf(SleepStateChangedException::class.java)
        assertThat(typedFailure).isInstanceOf(SleepStateChangedException::class.java)
        assertThat(care.getRecord(older)!!.timestamp).isEqualTo(now - 60_000)

        care.editRecord(EditCareRecord(
            CareRecordId(newer), CareFactContent(now - 30_000, payload),
            CareAttachments.Replace(listOf("keep.jpg")),
        ), now)
        care.editOpenSleep(EditOpenCareSleep(
            baby, CareSleepId(newer), now - 20_000, payload, "保留照片",
        ), now)
        assertThat(care.getRecord(newer)!!.timestamp).isEqualTo(now - 20_000)
        assertThat(care.listRecordPhotoPaths(newer)).containsExactly("keep.jpg")
    }
}
