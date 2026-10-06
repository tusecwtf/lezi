package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.shouldOfferNextFeedPlanForFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class QuickRecordDraftScheduleFulfillTest {
    @Test
    fun futurePointInTimeCreateBecomesScheduleCareWhileFulfillRejectsFuture() {
        val now = tappedAt
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = now + 1L,
        )

        assertEquals(ComposerWorkMode.ScheduleCare, draft.workMode(nowMillis = now))
        assertEquals("安排护理", draft.workModeTitle(nowMillis = now))
        assertEquals("安排护理", sheetKicker(draft, nowMillis = now))
        assertEquals("确认安排", draft.confirmLabel(nowMillis = now))
        assertNull(draft.validationError(nowMillis = now))
        assertTrue(draft.canConfirm(nowMillis = now))

        val fact = QuickRecordDraft.create(type = RecordType.BATH, timestamp = now - 1L)
        assertEquals(ComposerWorkMode.RecordFact, fact.workMode(nowMillis = now))
        assertEquals("记录事实", sheetKicker(fact, nowMillis = now))

        val fulfill = draft.copy(carePlanId = 42L)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfill.workMode(nowMillis = now))
        assertEquals("完成护理计划", sheetKicker(fulfill, nowMillis = now))
        assertEquals("确认完成", fulfill.confirmLabel(nowMillis = now))
        // Fulfill allows up to +5 minutes of clock skew on actual time.
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        assertNull(fulfill.copy(timestamp = now + fiveMin).validationError(nowMillis = now))
        assertTrue(fulfill.copy(timestamp = now + fiveMin).canConfirm(nowMillis = now))
        assertEquals(
            "不能选未来时刻",
            fulfill.copy(timestamp = now + fiveMin + 1L).validationError(nowMillis = now),
        )
        assertFalse(fulfill.copy(timestamp = now + fiveMin + 1L).canConfirm(nowMillis = now))
    }

    @Test
    fun explicitScheduleIntentNeverDegradesToAFactAfterItsTimePasses() {
        val scheduledAt = tappedAt + 60_000L
        val afterScheduledAt = scheduledAt + 1L
        val draft = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = scheduledAt,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )

        assertEquals(
            ComposerWorkMode.ScheduleCare,
            draft.workMode(nowMillis = afterScheduledAt),
        )
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            draft.writeDecision(nowMillis = afterScheduledAt),
        )
        assertEquals(
            CARE_PLAN_TIME_NOT_FUTURE_WARNING,
            draft.validationError(nowMillis = afterScheduledAt),
        )
        assertFalse(draft.canConfirm(nowMillis = afterScheduledAt))
        assertEquals("确认安排", draft.confirmLabel(nowMillis = afterScheduledAt))

        val adjusted = draft.copy(timestamp = afterScheduledAt + 60_000L)
        assertNull(adjusted.validationError(nowMillis = afterScheduledAt))
        assertTrue(adjusted.canConfirm(nowMillis = afterScheduledAt))

        val ordinaryFact = QuickRecordDraft.create(
            type = RecordType.BATH,
            timestamp = scheduledAt,
        )
        assertEquals(
            ComposerWriteDecision.AddRecord,
            ordinaryFact.writeDecision(nowMillis = afterScheduledAt),
        )
    }

    @Test
    fun scheduledSleepCreatesAPlanInsteadOfStartingAnOpenSleepFact() {
        val scheduledAt = tappedAt + 60_000L
        val explicit = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = scheduledAt,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        val timestampDerived = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = scheduledAt,
        )

        assertEquals(SleepDraftAction.SleepDown, explicit.sleepAction)
        assertEquals(ComposerWorkMode.ScheduleCare, explicit.workMode(nowMillis = tappedAt))
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            explicit.writeDecision(nowMillis = tappedAt),
        )
        assertEquals(
            ComposerWriteDecision.CreateCarePlan,
            timestampDerived.writeDecision(nowMillis = tappedAt),
        )
    }

    @Test
    fun sleepPlanChromeIsNeutralWhileFactAndFulfillKeepStateActions() {
        val schedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt + 60_000L,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        val schedulePolicy = sleepComposerPolicy(schedule, nowMillis = tappedAt)

        assertTrue(schedulePolicy.isPlanIntent)
        assertEquals("睡眠", schedulePolicy.sheetTitle)
        assertEquals("计划时间", schedulePolicy.timeSectionLabel)
        assertEquals("睡眠", schedulePolicy.primaryTimeLabel)
        assertEquals("月亮图标，安排睡眠", schedulePolicy.animationDescription)
        assertEquals(
            "选择睡眠时刻",
            clockDialogTitle(schedule, selectingEnd = false, nowMillis = tappedAt),
        )

        val derivedSchedule = schedule.copy(
            createIntent = ComposerCreateIntent.DeriveFromTimestamp,
        )
        assertTrue(sleepComposerPolicy(derivedSchedule, nowMillis = tappedAt).isPlanIntent)

        val editPlan = schedule.copy(
            carePlanId = 9L,
            editCarePlan = true,
            timestamp = tappedAt - 1L,
        )
        assertTrue(sleepComposerPolicy(editPlan, nowMillis = tappedAt).isPlanIntent)

        val convert = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt - 60_000L,
        ).copy(
            existingRecordId = 10L,
            timestamp = tappedAt + 60_000L,
        )
        val convertPolicy = sleepComposerPolicy(convert, nowMillis = tappedAt)
        assertTrue(convertPolicy.isPlanIntent)
        assertEquals("睡眠", convertPolicy.sheetTitle)
        assertEquals(
            "选择睡眠时刻",
            clockDialogTitle(convert, selectingEnd = false, nowMillis = tappedAt),
        )

        val fulfill = schedule.copy(
            carePlanId = 9L,
            editCarePlan = false,
            timestamp = tappedAt,
        )
        val fulfillPolicy = sleepComposerPolicy(fulfill, nowMillis = tappedAt + 1L)
        assertFalse(fulfillPolicy.isPlanIntent)
        assertEquals("睡下", fulfillPolicy.sheetTitle)
        assertEquals("睡下时间", fulfillPolicy.timeSectionLabel)
        assertEquals("月亮轻轻摇动，准备睡下", fulfillPolicy.animationDescription)
        assertEquals(
            "选择睡下时刻",
            clockDialogTitle(fulfill, selectingEnd = false, nowMillis = tappedAt + 1L),
        )
    }

    @Test
    fun startTimePickerUsesDraftAwarePlanAndFactRules() {
        val schedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt + 60_000L,
            createIntent = ComposerCreateIntent.ScheduleCare,
        )
        assertNull(
            schedule.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 120_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
        assertEquals(
            CARE_PLAN_TIME_NOT_FUTURE_WARNING,
            schedule.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt - 1L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val editPlan = schedule.copy(carePlanId = 11L, editCarePlan = true)
        assertNull(
            editPlan.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt - 1L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
        assertNull(
            editPlan.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 180_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val convert = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt - 60_000L,
        ).copy(existingRecordId = 12L)
        assertNull(
            convert.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + 240_000L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )

        val fulfill = schedule.copy(carePlanId = 13L, editCarePlan = false)
        // Exactly +5 minutes is allowed on fulfill; +1 ms over fails.
        assertNull(
            fulfill.startTimeRejectionMessage(
                candidateStartTimestamp = tappedAt + RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
        assertEquals(
            FUTURE_TIME_WARNING,
            fulfill.startTimeRejectionMessage(
                candidateStartTimestamp =
                    tappedAt + RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS + 1L,
                candidateEndTimestamp = null,
                nowMillis = tappedAt,
            ),
        )
    }

    @Test
    fun fromCarePlanHydratesPlanFieldSnapshotIntoFulfillDraft() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 9L,
            clientUuid = "plan-uuid",
            babyId = 1L,
            type = RecordType.PEE,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "Asia/Shanghai",
            note = "换尿布",
            payloadJson = """{"pee_amount":1}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt)
        assertEquals(9L, draft.carePlanId)
        assertNull(draft.existingRecordId)
        assertEquals(tappedAt, draft.timestamp)
        assertEquals(1, draft.peeAmount)
        assertEquals("换尿布", draft.note)
        assertEquals(ComposerWorkMode.FulfillPlan, draft.workMode(nowMillis = tappedAt))
        assertEquals("完成护理计划", sheetKicker(draft))
    }

    @Test
    fun fulfillDraftCarriesPlanPhotosWithoutOwningPlanSourcePaths() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 15L,
            clientUuid = "plan-photos",
            babyId = 1L,
            type = RecordType.DIARY,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            note = "记",
            payloadJson = """{"body":"x"}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        // Composer fulfill path retains plan photos as ordered borrowed references.
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt).copy(
            photos = listOf("p1.jpg", "p2.jpg"),
            sourcePhotos = emptyList(),
            borrowedPhotos = listOf("p1.jpg", "p2.jpg"),
        )
        assertEquals(listOf("p1.jpg", "p2.jpg"), draft.photos)
        assertTrue(draft.sourcePhotos.isEmpty())
        assertEquals(listOf("p1.jpg", "p2.jpg"), draft.borrowedPhotos)
        assertEquals(ComposerWorkMode.FulfillPlan, draft.workMode(nowMillis = tappedAt))
    }

    @Test
    fun editPlanModeDoesNotFulfillAndAllowsPastScheduledTime() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 11L,
            clientUuid = "plan-edit",
            babyId = 1L,
            type = RecordType.BATH,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            note = "洗澡",
            payloadJson = "{}",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlanForEdit(plan)
            .copy(timestamp = tappedAt - 5_000L)
        assertTrue(draft.editCarePlan)
        assertEquals(ComposerWorkMode.EditPlan, draft.workMode(nowMillis = tappedAt))
        assertEquals("编辑护理计划", draft.workModeTitle(nowMillis = tappedAt))
        assertEquals("编辑护理计划", sheetKicker(draft, nowMillis = tappedAt))
        assertEquals("保存计划", draft.confirmLabel(nowMillis = tappedAt))
        assertNull(draft.validationError(nowMillis = tappedAt))
        assertTrue(draft.canConfirm(nowMillis = tappedAt))
        // Fulfill path allows +5 minutes, rejects beyond.
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        val fulfillOk = draft.copy(editCarePlan = false, timestamp = tappedAt + fiveMin)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfillOk.workMode(nowMillis = tappedAt))
        assertNull(fulfillOk.validationError(nowMillis = tappedAt))
        val fulfill = draft.copy(editCarePlan = false, timestamp = tappedAt + fiveMin + 1L)
        assertEquals(ComposerWorkMode.FulfillPlan, fulfill.workMode(nowMillis = tappedAt))
        assertEquals("不能选未来时刻", fulfill.validationError(nowMillis = tappedAt))
    }

    @Test
    fun nursingAndSleepScheduleAllowEmptyIntentPayload() {
        val now = tappedAt
        val nursingSchedule = QuickRecordDraft.create(
            type = RecordType.NURSING,
            timestamp = now + 60_000L,
        )
        assertEquals(ComposerWorkMode.ScheduleCare, nursingSchedule.workMode(nowMillis = now))
        assertNull(nursingSchedule.validationError(nowMillis = now))
        assertTrue(nursingSchedule.canConfirm(nowMillis = now))

        val sleepSchedule = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = now + 60_000L,
        )
        assertEquals(ComposerWorkMode.ScheduleCare, sleepSchedule.workMode(nowMillis = now))
        assertNull(sleepSchedule.validationError(nowMillis = now))
        assertTrue(sleepSchedule.canConfirm(nowMillis = now))
    }

    @Test
    fun sleepFulfillDefaultsToOpenSleepDownAction() {
        val plan = com.lezi.babylog.core.model.CarePlan(
            id = 21L,
            clientUuid = "sleep-plan",
            babyId = 1L,
            type = RecordType.SLEEP,
            scheduledAt = tappedAt + 60_000L,
            scheduledZoneId = "UTC",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            schemaVersion = 2,
            updatedAt = tappedAt,
        )
        val draft = QuickRecordDraft.fromCarePlan(plan, actualTimestamp = tappedAt)
        assertEquals(SleepDraftAction.SleepDown, draft.sleepAction)
        assertNull(draft.endTimestamp)
        assertNull(draft.validationError(nowMillis = tappedAt))
        assertTrue(draft.canConfirm(nowMillis = tappedAt))
    }

    @Test
    fun scheduleCareDefaultsProjectToSystemCalendarOn() {
        val now = 1_700_000_000_000L
        val draft = QuickRecordDraft.create(RecordType.FORMULA, now + 60_000L)
        assertEquals(ComposerWorkMode.ScheduleCare, draft.workMode(nowMillis = now))
        assertTrue(draft.projectToSystemCalendar)
        val off = draft.copy(projectToSystemCalendar = false)
        assertFalse(off.projectToSystemCalendar)
    }

    @Test
    fun editingRecordToFutureRequiresConvertNotOrdinarySave() {
        val now = tappedAt
        val source = Record(
            id = 55L,
            clientUuid = "formula-55",
            babyId = 1L,
            type = RecordType.FORMULA,
            timestamp = now - 60_000L,
            note = "原备注",
            payloadJson = """{"amount_ml":120}""",
            updatedAt = now - 60_000L,
        )
        val draft = QuickRecordDraft.fromRecord(source).copy(timestamp = now + 90_000L)

        assertTrue(draft.needsConvertToCarePlan(nowMillis = now))
        // Still RecordFact work mode — convert is an explicit action, not silent schedule.
        assertEquals(ComposerWorkMode.RecordFact, draft.workMode(nowMillis = now))
        assertEquals("转为护理计划", draft.workModeTitle(nowMillis = now))
        assertEquals("转为护理计划", sheetKicker(draft, nowMillis = now))
        assertEquals("转为护理计划", draft.confirmLabel(nowMillis = now))
        assertNull(draft.validationError(nowMillis = now))
        assertTrue(draft.canConfirm(nowMillis = now))

        // Cancel path: putting time back to past removes convert need and restores save.
        val cancelled = draft.copy(timestamp = now - 1_000L)
        assertFalse(cancelled.needsConvertToCarePlan(nowMillis = now))
        assertEquals("保存修改", cancelled.confirmLabel(nowMillis = now))
        assertTrue(cancelled.canConfirm(nowMillis = now))

    }

    @Test
    fun convertSleepAndNursingUseIntentOnlyValidation() {
        val now = tappedAt
        val openSleep = Record(
            id = 70L,
            clientUuid = "sleep-70",
            babyId = 1L,
            type = RecordType.SLEEP,
            timestamp = now - 30_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            updatedAt = now - 30_000L,
        )
        val sleepDraft = QuickRecordDraft.fromRecord(openSleep).copy(timestamp = now + 60_000L)
        assertTrue(sleepDraft.needsConvertToCarePlan(nowMillis = now))
        assertNull(sleepDraft.intervalDurationPreview(nowMillis = now))
        assertNull(sleepDraft.validationError(nowMillis = now))
        assertTrue(sleepDraft.canConfirm(nowMillis = now))

        val nursing = Record(
            id = 71L,
            clientUuid = "nursing-71",
            babyId = 1L,
            type = RecordType.NURSING,
            timestamp = now - 10_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            updatedAt = now - 10_000L,
        )
        val nursingDraft = QuickRecordDraft.fromRecord(nursing).copy(
            timestamp = now + 120_000L,
            leftMin = "0",
            rightMin = "0",
        )
        assertTrue(nursingDraft.needsConvertToCarePlan(nowMillis = now))
        assertNull(nursingDraft.validationError(nowMillis = now))
        assertTrue(nursingDraft.canConfirm(nowMillis = now))
    }
}
