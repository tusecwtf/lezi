package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handedness layout for the configurable four-slot dock + fixed More.
 * Replaces the former fixed Pee/Sleep/Nursing/Formula action enum tests.
 */
class OneHandQuickActionOrderTest {
    private fun defaultSlots() = resolveQuickSlots(
        storedSlots = DEFAULT_QUICK_RECORD_SLOTS,
        hiddenItems = emptySet(),
        customItems = emptyList(),
    )

    @Test
    fun leftHand_putsFirstSlotAtLeftThumbEdgeAndMoreFar() {
        val order = oneHandQuickDockOrder(preferredHand = "left", slots = defaultSlots())
        assertEquals(5, order.size)
        val first = order.first() as QuickDockCell.Bound
        assertEquals(RecordType.PEE, first.recordType)
        assertEquals(QuickDockCell.More, order.last())
    }

    @Test
    fun rightHand_putsMoreOnFarLeftAndLastSlotAtRightThumbEdge() {
        val order = oneHandQuickDockOrder(preferredHand = "right", slots = defaultSlots())
        assertEquals(QuickDockCell.More, order.first())
        val last = order.last() as QuickDockCell.Bound
        assertEquals(RecordType.FORMULA, last.recordType)
    }

    @Test
    fun alwaysFiveCellsEvenWhenSlotsBlank() {
        val blanks = resolveQuickSlots(
            storedSlots = listOf("", "", "", ""),
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        val left = oneHandQuickDockOrder("left", blanks)
        val right = oneHandQuickDockOrder("right", blanks)
        assertEquals(5, left.size)
        assertEquals(5, right.size)
        assertTrue(left.take(4).all { it is QuickDockCell.Empty })
        assertEquals(QuickDockCell.More, left.last())
        assertEquals(QuickDockCell.More, right.first())
        assertTrue(right.drop(1).all { it is QuickDockCell.Empty })
    }

    @Test
    fun userSlotOrderIsPreservedInsideTheFourBlock() {
        val slots = resolveQuickSlots(
            storedSlots = listOf("formula", "nursing", "sleep", "pee"),
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        val order = oneHandQuickDockOrder("left", slots)
        assertEquals(
            listOf("formula", "nursing", "sleep", "pee"),
            order.filterIsInstance<QuickDockCell.Bound>().map { it.catalogKey },
        )
    }

    @Test
    fun idleContainerNeverInventsSelectionForEitherHand() {
        listOf("left", "right").forEach { hand ->
            val order = oneHandQuickDockOrder(hand, defaultSlots())

            assertTrue(order.none(::quickDockIdleContainerIsEmphasized))
            assertTrue(
                order.filterIsInstance<QuickDockCell.Bound>()
                    .map { it.recordType }
                    .containsAll(listOf(RecordType.PEE, RecordType.SLEEP)),
            )
        }
    }

    @Test
    fun customOrderDoesNotMovePseudoSelectionWithPee() {
        val slots = resolveQuickSlots(
            storedSlots = listOf("sleep", "formula", "pee", "nursing"),
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )

        listOf("left", "right").forEach { hand ->
            assertTrue(
                oneHandQuickDockOrder(hand, slots)
                    .none(::quickDockIdleContainerIsEmphasized),
            )
        }
    }
}
