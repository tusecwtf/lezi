package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerConfirmChromeTest {
    private val tappedAt = 1_700_000_000_000L

    @Test
    fun appearanceIsEnabledOnlyWhenValidAndNotBusy() {
        assertEquals(
            ComposerConfirmAppearance.Enabled,
            confirmAppearance(busy = false, canConfirm = true),
        )
        assertEquals(
            ComposerConfirmAppearance.ExplainedDisabled,
            confirmAppearance(busy = false, canConfirm = false),
        )
        assertEquals(
            ComposerConfirmAppearance.BusyDisabled,
            confirmAppearance(busy = true, canConfirm = true),
        )
        assertEquals(
            ComposerConfirmAppearance.BusyDisabled,
            confirmAppearance(busy = true, canConfirm = false),
        )
    }

    @Test
    fun greyTapShowsConcreteReasonAndRetapClears() {
        val validation = ComposerValidationResult(
            message = "请填写日记正文",
            field = ComposerInvalidField.Body,
        )
        val shown = reduceConfirmChrome(
            ComposerConfirmChromeState(),
            ComposerConfirmChromeEvent.GreyConfirmTapped(validation),
        )
        assertTrue(shown.reasonVisible)
        assertEquals("请填写日记正文", shown.reasonMessage)
        assertEquals(ComposerInvalidField.Body, shown.focusField)

        val cleared = reduceConfirmChrome(
            shown,
            ComposerConfirmChromeEvent.GreyConfirmTapped(validation),
        )
        assertFalse(cleared.reasonVisible)
        assertNull(cleared.reasonMessage)
        assertNull(cleared.focusField)
    }

    @Test
    fun draftEditAndDismissClearReasonCard() {
        val open = ComposerConfirmChromeState(
            reasonVisible = true,
            shownReason = ComposerValidationResult(
                "请填写药品名称",
                ComposerInvalidField.MedicineName,
            ),
        )
        assertFalse(
            reduceConfirmChrome(open, ComposerConfirmChromeEvent.DraftEdited).reasonVisible,
        )
        assertFalse(
            reduceConfirmChrome(open, ComposerConfirmChromeEvent.Dismissed).reasonVisible,
        )
    }

    @Test
    fun busyNeverOpensOrKeepsReasonCard() {
        val open = ComposerConfirmChromeState(
            reasonVisible = true,
            shownReason = ComposerValidationResult(
                "请填写左侧或右侧喂养时长",
                ComposerInvalidField.NursingDuration,
            ),
        )
        val clearedOnBusy = reduceConfirmChrome(
            open,
            ComposerConfirmChromeEvent.BusyChanged(busy = true),
        )
        assertFalse(clearedOnBusy.reasonVisible)

        val stillClosed = reduceConfirmChrome(
            ComposerConfirmChromeState(),
            ComposerConfirmChromeEvent.GreyConfirmTapped(
                ComposerValidationResult("请填写内容", ComposerInvalidField.FoodContent),
            ),
        )
        // Grey tap while not busy still works; busy is enforced by appearance + UI not emitting.
        assertTrue(stillClosed.reasonVisible)

        val busyBlocksKeep = reduceConfirmChrome(
            stillClosed,
            ComposerConfirmChromeEvent.BusyChanged(busy = true),
        )
        assertFalse(busyBlocksKeep.reasonVisible)
        assertNull(busyBlocksKeep.shownReason)
    }

    @Test
    fun greyTapWithoutValidationIsNoOp() {
        val state = ComposerConfirmChromeState()
        val next = reduceConfirmChrome(
            state,
            ComposerConfirmChromeEvent.GreyConfirmTapped(validation = null),
        )
        assertEquals(state, next)
    }

    @Test
    fun validationResultMapsMessageAndFirstField() {
        val nursing = QuickRecordDraft.create(RecordType.NURSING, tappedAt)
        assertEquals(
            ComposerValidationResult(
                "请填写左侧或右侧喂养时长",
                ComposerInvalidField.NursingDuration,
            ),
            nursing.validationResult(nowMillis = tappedAt + 1L),
        )

        val diary = QuickRecordDraft.create(RecordType.DIARY, tappedAt)
        assertEquals(
            ComposerValidationResult("请填写日记正文", ComposerInvalidField.Body),
            diary.validationResult(nowMillis = tappedAt + 1L),
        )

        val medicine = QuickRecordDraft.create(RecordType.MEDICINE, tappedAt)
        assertEquals(
            ComposerValidationResult("请填写药品名称", ComposerInvalidField.MedicineName),
            medicine.validationResult(nowMillis = tappedAt + 1L),
        )

        // Future create drafts enter ScheduleCare and no longer block on start time.
        val future = QuickRecordDraft.create(RecordType.BATH, tappedAt + 1L)
        assertEquals(ComposerWorkMode.ScheduleCare, future.workMode(nowMillis = tappedAt))
        assertNull(future.validationResult(nowMillis = tappedAt))

        // Fulfill mode still rejects future actual times.
        val fulfillFuture = future.copy(carePlanId = 9L)
        assertEquals(
            ComposerValidationResult("不能选未来时刻", ComposerInvalidField.StartTime),
            fulfillFuture.validationResult(nowMillis = tappedAt),
        )

        // Edit record → future is convert-eligible (enabled confirm), not grey future block.
        val convert = QuickRecordDraft.create(RecordType.BATH, tappedAt - 1L).copy(
            existingRecordId = 3L,
            timestamp = tappedAt + 1L,
        )
        assertTrue(convert.needsConvertToCarePlan(nowMillis = tappedAt))
        assertNull(convert.validationResult(nowMillis = tappedAt))
        assertEquals(
            ComposerConfirmAppearance.Enabled,
            confirmAppearance(busy = false, canConfirm = convert.canConfirm(tappedAt)),
        )
        assertEquals("转为护理计划", convert.confirmLabel(nowMillis = tappedAt))

        val sleepMissingEnd = QuickRecordDraft.create(
            type = RecordType.SLEEP,
            timestamp = tappedAt,
            historical = true,
        )
        assertEquals(
            ComposerValidationResult("请选择醒来时刻", ComposerInvalidField.EndTime),
            sleepMissingEnd.validationResult(nowMillis = tappedAt + 60_000L),
        )
    }

    @Test
    fun validationPriorityKeepsTimeBeforeNoteBeforePayload() {
        // Fact mode: future start still fails before note length.
        val factFuture = QuickRecordDraft.create(RecordType.DIARY, tappedAt + 1L)
            .copy(carePlanId = 3L, note = "长".repeat(201), body = "")
        val result = factFuture.validationResult(nowMillis = tappedAt)
        assertNotNull(result)
        assertEquals("不能选未来时刻", result!!.message)
        assertEquals(ComposerInvalidField.StartTime, result.field)

        val noteFirst = factFuture.copy(timestamp = tappedAt, carePlanId = null)
        assertEquals(
            ComposerValidationResult("备注最多 200 字", ComposerInvalidField.Note),
            noteFirst.validationResult(nowMillis = tappedAt + 1L),
        )

        val bodyFirst = noteFirst.copy(note = "ok")
        assertEquals(
            ComposerValidationResult("请填写日记正文", ComposerInvalidField.Body),
            bodyFirst.validationResult(nowMillis = tappedAt + 1L),
        )
    }

    @Test
    fun talkbackSemanticsUseConcreteReasonNeverFixedPrompt() {
        val rejectedPrompt = "暂不可保存，点击查看原因"
        val validation = ComposerValidationResult(
            message = "请填写就诊原因",
            field = ComposerInvalidField.HospitalReason,
        )
        val chrome = reduceConfirmChrome(
            ComposerConfirmChromeState(),
            ComposerConfirmChromeEvent.GreyConfirmTapped(validation),
        )
        val talkBack = when (confirmAppearance(busy = false, canConfirm = false)) {
            ComposerConfirmAppearance.ExplainedDisabled ->
                validation.message
            ComposerConfirmAppearance.Enabled -> "确认记录"
            ComposerConfirmAppearance.BusyDisabled -> "保存中…"
        }
        assertEquals("请填写就诊原因", talkBack)
        assertFalse(talkBack.contains(rejectedPrompt))
        assertEquals("请填写就诊原因", chrome.reasonMessage)
        assertFalse(chrome.reasonMessage!!.contains(rejectedPrompt))
    }

    @Test
    fun fixingDraftRestoresEnabledAppearanceWithoutCard() {
        val invalid = QuickRecordDraft.create(RecordType.MEDICINE, tappedAt)
        assertFalse(invalid.canConfirm(nowMillis = tappedAt + 1L))
        assertEquals(
            ComposerConfirmAppearance.ExplainedDisabled,
            confirmAppearance(busy = false, canConfirm = invalid.canConfirm(tappedAt + 1L)),
        )

        val open = reduceConfirmChrome(
            ComposerConfirmChromeState(),
            ComposerConfirmChromeEvent.GreyConfirmTapped(
                invalid.validationResult(tappedAt + 1L),
            ),
        )
        assertTrue(open.reasonVisible)

        val fixed = invalid.copy(medicineName = "退烧药")
        val afterEdit = reduceConfirmChrome(open, ComposerConfirmChromeEvent.DraftEdited)
        assertFalse(afterEdit.reasonVisible)
        assertTrue(fixed.canConfirm(nowMillis = tappedAt + 1L))
        assertEquals(
            ComposerConfirmAppearance.Enabled,
            confirmAppearance(busy = false, canConfirm = true),
        )
    }
}
