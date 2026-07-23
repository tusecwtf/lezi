package com.lezi.babylog.feature.log

import org.junit.Assert.assertEquals
import org.junit.Test

class OneHandQuickActionOrderTest {
    @Test
    fun leftHand_putsOneTapPeeAtLeftThumbEdge() {
        assertEquals(
            listOf(
                OneHandQuickAction.Pee,
                OneHandQuickAction.Sleep,
                OneHandQuickAction.Nursing,
                OneHandQuickAction.Formula,
                OneHandQuickAction.More,
            ),
            oneHandQuickActionOrder(preferredHand = "left", timerEnabled = true),
        )
    }

    @Test
    fun rightHand_putsOneTapPeeAtRightThumbEdge() {
        assertEquals(
            listOf(
                OneHandQuickAction.More,
                OneHandQuickAction.Formula,
                OneHandQuickAction.Nursing,
                OneHandQuickAction.Sleep,
                OneHandQuickAction.Pee,
            ),
            oneHandQuickActionOrder(preferredHand = "right", timerEnabled = true),
        )
    }

    @Test
    fun disabledTimer_removesOnlyNursingAction() {
        assertEquals(
            listOf(
                OneHandQuickAction.Pee,
                OneHandQuickAction.Sleep,
                OneHandQuickAction.Formula,
                OneHandQuickAction.More,
            ),
            oneHandQuickActionOrder(preferredHand = "left", timerEnabled = false),
        )
    }
}
