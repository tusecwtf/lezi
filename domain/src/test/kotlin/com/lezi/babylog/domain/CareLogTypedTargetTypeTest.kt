package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogTypedTargetTypeTest {
    private val now = 1_700_000_000_000L
    private fun content(type: RecordType, at: Long = now) =
        CareFactContent(at, MilkPayload(type, 120))

    @Test
    fun mismatchedTypedRootEditDoesNotReinterpretSameShapeOrChangeAttachments() = runTest {
        val sync = RecordingSyncPort()
        val care = Fakes(sync).careLog()
        val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
        val id = care.createRecord(CreateCareRecord(baby, content(RecordType.PUMPED_FEED),
            CareAttachments.Replace(listOf("keep.jpg"))), now)
        val before = care.getRecord(id)
        val notifications = sync.requests
        val failure = runCatching {
            care.editRecord(EditCareRecord(CareRecordId(id), content(RecordType.FORMULA),
                CareAttachments.RemoveAll), now)
        }.exceptionOrNull()
        assertThat(failure).hasMessageThat().isEqualTo("护理内容类型与目标不匹配")
        assertThat(care.getRecord(id)).isEqualTo(before)
        assertThat(care.listRecordPhotoPaths(id)).containsExactly("keep.jpg")
        assertThat(sync.requests).isEqualTo(notifications)
        // The compatible raw adapter still interprets raw fields using its target type.
        care.updateRecord(id, now, null, null, """{"amount_ml":121}""", nowMillis = now)
        assertThat(care.getRecord(id)!!.payload.payload).isEqualTo(MilkPayload(RecordType.PUMPED_FEED, 121))
    }

    @Test
    fun typedPlanEditFulfillAndConversionRejectSameShapeMismatchesBeforeAnyMutation() = runTest {
        for (operation in listOf("edit", "fulfill", "convert")) {
            val sync = RecordingSyncPort()
            val care = Fakes(sync).careLog()
            val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
            val plan = care.createPlan(CreateCarePlan(baby,
                content(RecordType.PUMPED_FEED, now + 60_000),
                attachments = CareAttachments.Replace(listOf("plan.jpg")),
                projectToSystemCalendar = false), now)
            val record = care.createRecord(CreateCareRecord(baby, content(RecordType.PUMPED_FEED),
                CareAttachments.Replace(listOf("record.jpg"))), now)
            val oldPlan = care.getCarePlan(plan)!!
            val oldRecord = care.getRecord(record)
            val notifications = sync.requests
            val failure = runCatching {
                when (operation) {
                    "edit" -> care.editPlan(EditCarePlan(CarePlanId(plan),
                        content(RecordType.FORMULA, now + 120_000), CareAttachments.RemoveAll), now)
                    "fulfill" -> care.fulfillPlan(FulfillCarePlan(CarePlanId(plan),
                        content(RecordType.FORMULA), writeId = CareWriteId("wrong-fulfillment")), now)
                    else -> care.convertRecordToPlan(ConvertCareRecordToPlan(CareRecordId(record),
                        content(RecordType.FORMULA, now + 120_000),
                        writeId = CareWriteId("wrong-conversion")), now)
                }
            }.exceptionOrNull()
            assertThat(failure).hasMessageThat().isEqualTo("护理内容类型与目标不匹配")
            assertThat(care.getCarePlan(plan)).isEqualTo(oldPlan)
            assertThat(care.getRecord(record)).isEqualTo(oldRecord)
            assertThat(care.listRecordPhotoPaths(record)).containsExactly("record.jpg")
            assertThat(care.listCarePlanPhotoPaths(plan)).containsExactly("plan.jpg")
            assertThat(care.listFulfillmentCandidatesForPlan(oldPlan.clientUuid)).isEmpty()
            assertThat(care.getRecordByClientUuid("wrong-fulfillment")).isNull()
            assertThat(care.getCarePlanByClientUuid("wrong-conversion")).isNull()
            assertThat(sync.requests).isEqualTo(notifications)
        }
    }

    @Test
    fun replayCannotBypassTypedTargetCheck() = runTest {
        val care = Fakes().careLog()
        val baby = CareBabyId(care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1)))
        val plan = care.createPlan(CreateCarePlan(baby,
            content(RecordType.PUMPED_FEED, now + 60_000), projectToSystemCalendar = false), now)
        val fulfill = FulfillCarePlan(CarePlanId(plan), content(RecordType.PUMPED_FEED))
        care.fulfillPlan(fulfill, now)
        assertThat(runCatching {
            care.fulfillPlan(fulfill.copy(content = content(RecordType.FORMULA)), now)
        }.exceptionOrNull()).hasMessageThat().isEqualTo("护理内容类型与目标不匹配")
        val record = care.createRecord(CreateCareRecord(baby, content(RecordType.PUMPED_FEED)), now)
        val convert = ConvertCareRecordToPlan(CareRecordId(record),
            content(RecordType.PUMPED_FEED, now + 60_000), projectToSystemCalendar = false)
        care.convertRecordToPlan(convert, now)
        assertThat(runCatching {
            care.convertRecordToPlan(convert.copy(content = content(RecordType.FORMULA, now + 60_000)), now)
        }.exceptionOrNull()).hasMessageThat().isEqualTo("护理内容类型与目标不匹配")
    }
}
