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

class QuickRecordDraftValidationChromeTest {
    @Test
    fun allInvalidDraftsLockConfirmButFooterWaitsForInteraction() {
        val now = tappedAt + 60_000L
        val cases = listOf(
            QuickRecordDraft.create(RecordType.NURSING, tappedAt) to
                "请填写左侧或右侧喂养时长",
            QuickRecordDraft.create(RecordType.DIARY, tappedAt) to
                "请填写日记正文",
        )

        cases.forEach { (draft, expected) ->
            assertFalse(draft.canConfirm(nowMillis = now))
            assertNull(
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = false,
                    attemptedConfirm = false,
                ),
            )
            assertEquals(
                expected,
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = true,
                    attemptedConfirm = false,
                ),
            )
            assertEquals(
                expected,
                draft.footerValidationError(
                    nowMillis = now,
                    isDirty = false,
                    attemptedConfirm = true,
                ),
            )
        }
    }

    @Test
    fun intervalWarningsStayAtTheTimeFieldsInsteadOfRepeatingInTheFooter() {
        val draft = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(
            IntervalDurationPreview.Warning("请选择醒来时刻"),
            draft.intervalDurationPreview(nowMillis = tappedAt + 60_000L),
        )
        assertNull(
            draft.footerValidationError(
                nowMillis = tappedAt + 60_000L,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )
    }

    @Test
    fun inlineValidationDefersWarningsExceptForAMissingRequiredEnd() {
        val now = tappedAt + 60_000L
        val manual = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )

        assertEquals(
            IntervalDurationPreview.Warning("请选择醒来时刻"),
            manual.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )

        val nonPositive = manual.copy(endTimestamp = tappedAt)
        assertNull(
            nonPositive.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
        assertEquals(
            IntervalDurationPreview.Warning("醒来须晚于睡下"),
            nonPositive.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )

        val future = manual.copy(endTimestamp = now + 1L)
        assertNull(
            future.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
        assertEquals(
            IntervalDurationPreview.Warning("不能选未来时刻"),
            future.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = true,
            ),
        )

        val valid = manual.copy(endTimestamp = tappedAt + 30_000L)
        assertEquals(
            IntervalDurationPreview.Duration("时长 0m"),
            valid.visibleIntervalDurationPreview(
                nowMillis = now,
                isDirty = false,
                attemptedConfirm = false,
            ),
        )
    }

    @Test
    fun pointRecordErrorAppearsWithoutInventingDuration() {
        val now = tappedAt + 60 * 60_000L
        val draft = QuickRecordDraft.create(RecordType.WALK, tappedAt)
            .copy(
                endTimestamp = tappedAt + 30 * 60_000L,
                note = "长".repeat(201),
            )

        assertNull(draft.intervalDurationPreview(nowMillis = now))
        assertEquals(
            "备注最多 200 字",
            draft.footerValidationError(
                nowMillis = now,
                isDirty = true,
                attemptedConfirm = false,
            ),
        )
        assertFalse(draft.canConfirm(nowMillis = now))
    }

    @Test
    fun requiredPurposeInformationBlocksEmptyConfirmation() {
        assertEquals(
            "请填写药品名称",
            QuickRecordDraft.create(RecordType.MEDICINE, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
        assertEquals(
            "请填写内容",
            QuickRecordDraft.create(RecordType.BABY_FOOD, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
        assertEquals(
            "请填写左侧或右侧喂养时长",
            QuickRecordDraft.create(RecordType.NURSING, tappedAt)
                .validationError(nowMillis = tappedAt + 1L),
        )
    }
}
