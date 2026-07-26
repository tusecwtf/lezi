package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NursingTimerScheduleGateTest {
    private val now = 1_700_000_000_000L

    @Test
    fun scheduleCareNursingDoesNotExposeStartTimer() {
        val draft = QuickRecordDraft.create(RecordType.NURSING, now + 60_000L)
        assertTrue(draft.workMode(now) == ComposerWorkMode.ScheduleCare)
        assertFalse(
            computeCanStartNursingTimer(
                request = RecordComposerRequest.New(
                    babyId = 1L,
                    type = RecordType.NURSING,
                    timestamp = now + 60_000L,
                    historical = false,
                ),
                draft = draft,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun liveNewNursingExposesStartTimerWhenEnabled() {
        val draft = QuickRecordDraft.create(RecordType.NURSING, now - 1_000L)
        assertTrue(draft.workMode(now) != ComposerWorkMode.ScheduleCare)
        assertTrue(
            computeCanStartNursingTimer(
                request = RecordComposerRequest.New(
                    babyId = 1L,
                    type = RecordType.NURSING,
                    timestamp = now - 1_000L,
                    historical = false,
                ),
                draft = draft,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun fulfillNursingExposesStartTimerEditPlanDoesNot() {
        val fulfill = QuickRecordDraft.create(RecordType.NURSING, now - 1_000L).copy(
            carePlanId = 9L,
            editCarePlan = false,
        )
        assertTrue(
            computeCanStartNursingTimer(
                request = RecordComposerRequest.Fulfill(carePlanId = 9L),
                draft = fulfill,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
        val editPlan = fulfill.copy(editCarePlan = true, timestamp = now + 120_000L)
        assertFalse(
            computeCanStartNursingTimer(
                request = RecordComposerRequest.Fulfill(carePlanId = 9L),
                draft = editPlan,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun movingFromScheduleToNowEnablesTimer() {
        val future = QuickRecordDraft.create(RecordType.NURSING, now + 60_000L)
        val request = RecordComposerRequest.New(
            babyId = 1L,
            type = RecordType.NURSING,
            timestamp = now + 60_000L,
            historical = false,
        )
        assertFalse(
            computeCanStartNursingTimer(
                request = request,
                draft = future,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
        val live = future.copy(timestamp = now - 500L)
        assertTrue(
            computeCanStartNursingTimer(
                request = request,
                draft = live,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun convertRecordToCarePlanDoesNotExposeStartTimer() {
        val convertDraft = QuickRecordDraft.create(RecordType.NURSING, now - 1_000L).copy(
            existingRecordId = 44L,
            timestamp = now + 90_000L,
        )
        assertTrue(convertDraft.needsConvertToCarePlan(nowMillis = now))
        assertFalse(
            computeCanStartNursingTimer(
                request = RecordComposerRequest.Edit(recordId = 44L),
                draft = convertDraft,
                timerEnabled = true,
                nowMillis = now,
            ),
        )
    }
}
