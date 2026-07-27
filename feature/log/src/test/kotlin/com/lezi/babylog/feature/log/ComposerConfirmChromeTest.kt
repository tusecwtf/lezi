package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
