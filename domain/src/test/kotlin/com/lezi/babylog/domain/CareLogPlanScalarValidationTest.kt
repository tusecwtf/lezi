package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.model.*
import com.lezi.babylog.domain.carelog.*
import com.lezi.babylog.sync.engine.SyncWireMapper
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogPlanScalarValidationTest {
    private val now = 1_700_000_000_000L
    private fun RecordPayload.json() = RecordPayloadCodec.encode(
        RecordPayloadDocument(type, this, CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION),
    )

    @Test
    fun typedAndLegacyPlanWritesUseSharedScalarRulesBeforeChangingAnyFact() = runTest {
        val cases = listOf(
            MilkPayload(RecordType.FORMULA, -1) to false,
            MilkPayload(RecordType.FORMULA, 0) to false,
            MilkPayload(RecordType.FORMULA, 1) to true,
            MilkPayload(RecordType.FORMULA, 999) to true,
            MilkPayload(RecordType.FORMULA, 1000) to false,
            MilkPayload(RecordType.FORMULA, 120, preparedMl = -1) to false,
            MilkPayload(RecordType.FORMULA, 120, preparedMl = 1000) to false,
            MilkPayload(RecordType.FORMULA, 120, durationMinutes = -1) to false,
            MilkPayload(RecordType.FORMULA, 120, durationMinutes = 1441) to false,
            PeePayload(0) to false,
            PeePayload(4) to false,
        )
        for ((payload, valid) in cases) for (typed in listOf(false, true)) {
            for (operation in listOf("create", "edit", "convert")) {
                val sync = RecordingSyncPort()
                val care = Fakes(sync).careLog()
                val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
                val seed: RecordPayload = if (payload is MilkPayload) MilkPayload(payload.type, 120) else PeePayload(2)
                val plan = care.createPlan(CreateCarePlan(baby, CareFactContent(now + 60_000, seed),
                    attachments = CareAttachments.Replace(listOf("plan.jpg")),
                    projectToSystemCalendar = false), now)
                val record = care.createRecord(CreateCareRecord(baby, CareFactContent(now, seed),
                    CareAttachments.Replace(listOf("record.jpg"))), now)
                val beforePlan = care.getCarePlan(plan)!!
                val beforeRecord = care.getRecord(record)
                val notifications = sync.requests
                val fields = CareFactContent(now + 120_000, payload)
                val result = runCatching {
                    when (operation) {
                        "create" -> if (typed) care.createPlan(CreateCarePlan(baby, fields,
                            projectToSystemCalendar = false, writeId = CareWriteId("scalar-new")), now)
                        else care.createCarePlan(baby.value, payload.type, fields.timestamp,
                            payloadJson = payload.json(), nowMillis = now,
                            projectToSystemCalendar = false, clientUuid = "scalar-new")
                        "edit" -> {
                            if (typed) care.editPlan(EditCarePlan(CarePlanId(plan), fields,
                                CareAttachments.RemoveAll), now)
                            else care.updateCarePlan(plan, fields.timestamp, payloadJson = payload.json(),
                                photoLocalPaths = emptyList(), nowMillis = now)
                            plan
                        }
                        else -> if (typed) care.convertRecordToPlan(ConvertCareRecordToPlan(
                            CareRecordId(record), fields, projectToSystemCalendar = false,
                            writeId = CareWriteId("scalar-new")), now)
                        else care.convertRecordToCarePlan(record, fields.timestamp,
                            payloadJson = payload.json(), nowMillis = now,
                            projectToSystemCalendar = false, clientUuid = "scalar-new")
                    }
                }
                if (valid) {
                    assertThat(result.isSuccess).isTrue()
                    val saved = care.getCarePlan(result.getOrThrow())!!
                    // Feed public CareLog output to the production wire mapper, not a fake codec.
                    SyncWireMapper.carePlan(
                        CarePlanEntity(
                            clientUuid = saved.clientUuid, babyId = saved.babyId, type = saved.type.key,
                            scheduledAt = saved.scheduledAt, scheduledZoneId = saved.scheduledZoneId,
                            note = saved.note, payloadJson = saved.payloadJson,
                            schemaVersion = saved.schemaVersion, status = saved.status.storageKey,
                            updatedAt = saved.updatedAt,
                        ),
                        babyClientUuid = "synthetic-baby", customItemClientUuid = null,
                    )
                } else {
                    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
                    assertThat(care.getCarePlan(plan)).isEqualTo(beforePlan)
                    assertThat(care.getRecord(record)).isEqualTo(beforeRecord)
                    assertThat(care.listCarePlanPhotoPaths(plan)).containsExactly("plan.jpg")
                    assertThat(care.listRecordPhotoPaths(record)).containsExactly("record.jpg")
                    assertThat(care.listFulfillmentCandidatesForPlan(beforePlan.clientUuid)).isEmpty()
                    assertThat(care.getCarePlanByClientUuid("scalar-new")).isNull()
                    assertThat(sync.requests).isEqualTo(notifications)
                }
            }
        }
    }

    @Test
    fun markedNextFeedStillAllowsZeroAmount() = runTest {
        val care = Fakes().careLog()
        val baby = care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1))
        val id = care.scheduleNextFeedCarePlan(baby, RecordType.FORMULA, now + 60_000, nowMillis = now)
        val plan = care.getCarePlan(id)!!
        // CareLog's presentation model deliberately strips the internal marker.
        assertThat(plan.note).isNull()
        care.editPlan(EditCarePlan(CarePlanId(id),
            CareFactContent(now + 120_000, MilkPayload(RecordType.FORMULA, 0))), now)
        assertThat(care.getCarePlan(id)!!.payloadJson).contains("\"amount_ml\":0")
        assertThat(care.scheduleNextFeedCarePlan(
            baby, RecordType.FORMULA, now + 180_000, nowMillis = now,
        )).isEqualTo(id)
    }

    @Test
    fun nursingPlanPreservesExistingZeroIntentButStillRejectsNegativeDuration() = runTest {
        val care = Fakes().careLog()
        val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
        val id = care.createPlan(CreateCarePlan(baby,
            CareFactContent(now + 60_000, NursingPayload()), projectToSystemCalendar = false), now)
        val before = care.getCarePlan(id)
        val failure = runCatching {
            care.editPlan(EditCarePlan(CarePlanId(id),
                CareFactContent(now + 120_000, NursingPayload(leftMinutes = -1))), now)
        }.exceptionOrNull()
        assertThat(failure).hasMessageThat().isEqualTo("喂养时长不能为负数")
        assertThat(care.getCarePlan(id)).isEqualTo(before)
        // Ordinary nursing intent is accepted by the shared plan-only policy;
        // the cross-language corpus separately proves wire/server agreement.
    }
}
