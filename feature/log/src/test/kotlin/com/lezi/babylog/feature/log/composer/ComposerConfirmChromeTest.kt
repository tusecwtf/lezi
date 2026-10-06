package com.lezi.babylog.feature.log.composer
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class ComposerConfirmChromeTest {
    private val tappedAt = 1_700_000_000_000L

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
