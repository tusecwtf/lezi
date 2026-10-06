package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmChromeTest {
    @Test
    fun `appearance enables valid input explains invalid input and locks busy work`() {
        assertEquals(
            LeziConfirmAppearance.Enabled,
            leziConfirmAppearance(busy = false, canConfirm = true),
        )
        assertEquals(
            LeziConfirmAppearance.ExplainedDisabled,
            leziConfirmAppearance(busy = false, canConfirm = false),
        )
        assertEquals(
            LeziConfirmAppearance.BusyDisabled,
            leziConfirmAppearance(busy = true, canConfirm = true),
        )
    }

    @Test
    fun `concrete reason toggles and edits dismissals or busy work clear it`() {
        val shown = reduceLeziConfirmChrome(
            LeziConfirmChromeState<String>(),
            LeziConfirmChromeEvent.GreyConfirmTapped("请填写左侧或右侧喂养时长"),
        )
        assertTrue(shown.reasonVisible)
        assertEquals("请填写左侧或右侧喂养时长", shown.shownReason)

        val clearedByRetap = reduceLeziConfirmChrome(
            shown,
            LeziConfirmChromeEvent.GreyConfirmTapped(shown.shownReason),
        )
        assertFalse(clearedByRetap.reasonVisible)
        assertNull(clearedByRetap.shownReason)
        assertFalse(
            reduceLeziConfirmChrome(shown, LeziConfirmChromeEvent.DraftEdited).reasonVisible,
        )
        assertFalse(
            reduceLeziConfirmChrome(shown, LeziConfirmChromeEvent.Dismissed).reasonVisible,
        )
        assertFalse(
            reduceLeziConfirmChrome(
                shown,
                LeziConfirmChromeEvent.BusyChanged(busy = true),
            ).reasonVisible,
        )
    }
}
