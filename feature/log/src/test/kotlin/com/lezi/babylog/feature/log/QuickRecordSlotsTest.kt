package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CustomRecordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRecordSlotsTest {
    private val customTouch = CustomRecordItem(
        id = 12L,
        name = "抚触",
        iconSlot = 2,
        sortOrder = 0,
    )

    @Test
    fun emptyAndInvalidRefsBlankWithoutAutoFill() {
        val slots = resolveQuickSlots(
            storedSlots = listOf("", "removed_item", "custom", "custom:999"),
            hiddenItems = emptySet(),
            customItems = listOf(customTouch),
        )
        assertEquals(4, slots.size)
        assertTrue(slots.all { it is QuickDockCell.Empty })
    }

    @Test
    fun hiddenItemBlanksSlotWithoutCollapsingDock() {
        val slots = resolveQuickSlots(
            storedSlots = listOf("pee", "sleep", "nursing", "formula"),
            hiddenItems = setOf("sleep", "nursing"),
            customItems = emptyList(),
        )
        assertEquals(
            listOf(
                QuickDockCell.Bound(
                    identity = RecordItemIdentity.builtIn(RecordType.PEE),
                    label = "尿尿",
                    catalogKey = "pee",
                    recordType = RecordType.PEE,
                ),
                QuickDockCell.Empty,
                QuickDockCell.Empty,
                QuickDockCell.Bound(
                    identity = RecordItemIdentity.builtIn(RecordType.FORMULA),
                    label = "配方奶",
                    catalogKey = "formula",
                    recordType = RecordType.FORMULA,
                ),
            ),
            slots,
        )
        val dock = oneHandQuickDockOrder("left", slots)
        assertEquals(5, dock.size)
        assertEquals(QuickDockCell.More, dock.last())
    }

    @Test
    fun customSlotFollowsLiveRenameAndIcon() {
        val renamed = customTouch.copy(name = "晚间抚触", iconSlot = 5)
        val cell = resolveQuickSlot(
            catalogKey = "custom:12",
            hiddenItems = emptySet(),
            customItems = listOf(renamed),
        )
        val bound = cell as QuickDockCell.Bound
        assertEquals("晚间抚触", bound.label)
        assertEquals(5, bound.customIconSlot)
        assertEquals(RecordItemIdentity.custom(12L), bound.identity)
    }

    @Test
    fun deletedCustomBlanksSlot() {
        val cell = resolveQuickSlot(
            catalogKey = "custom:12",
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        assertEquals(QuickDockCell.Empty, cell)
    }

    @Test
    fun emptySlotOpensQuickRecordSettings() {
        assertEquals(
            QuickDockAction.OpenSlotSettings,
            QuickDockCell.Empty.toAction(),
        )
    }

    @Test
    fun boundAndMoreSlotsKeepTheirDedicatedActions() {
        val bound = resolveQuickSlot("sleep", emptySet(), emptyList()) as QuickDockCell.Bound

        assertEquals(
            QuickDockAction.OpenComposer(bound.identity),
            bound.toAction(),
        )
        assertEquals(QuickDockAction.OpenMore, QuickDockCell.More.toAction())
    }

    @Test
    fun leftHandPutsSlotsNearLeftAndMoreOnFarSide() {
        val slots = resolveQuickSlots(
            DEFAULT_QUICK_RECORD_SLOTS,
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        val order = oneHandQuickDockOrder("left", slots)
        assertEquals(5, order.size)
        assertEquals(QuickDockCell.More, order.last())
        assertTrue(order.first() is QuickDockCell.Bound)
        assertEquals("pee", (order.first() as QuickDockCell.Bound).catalogKey)
    }

    @Test
    fun rightHandPutsMoreOnFarLeftAndSlotsNearRightThumb() {
        val slots = resolveQuickSlots(
            DEFAULT_QUICK_RECORD_SLOTS,
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        val order = oneHandQuickDockOrder("right", slots)
        assertEquals(QuickDockCell.More, order.first())
        assertEquals("formula", (order.last() as QuickDockCell.Bound).catalogKey)
    }

    @Test
    fun sleepBoundCellUsesSleepTypeForStateChrome() {
        val cell = resolveQuickSlot("sleep", emptySet(), emptyList()) as QuickDockCell.Bound
        assertEquals(RecordType.SLEEP, cell.recordType)
        assertEquals("睡眠", cell.label)
    }

    @Test
    fun swapReordersWithoutChangingLength() {
        val swapped = swapQuickRecordSlots(
            listOf("pee", "sleep", "nursing", "formula"),
            fromIndex = 0,
            toIndex = 3,
        )
        assertEquals(listOf("formula", "sleep", "nursing", "pee"), swapped)
    }

    @Test
    fun assignToEmptySlotLeavesOthersUnchanged() {
        val next = assignQuickRecordSlot(
            slots = listOf("pee", "", "nursing", ""),
            index = 1,
            catalogKey = "sleep",
        )
        assertEquals(listOf("pee", "sleep", "nursing", ""), next)
    }

    @Test
    fun assignOverwritesOccupiedSlotWithoutTouchingDirectorySemantics() {
        // Overwrite only clears the shortcut pointer at the target slot.
        val next = assignQuickRecordSlot(
            slots = listOf("pee", "sleep", "nursing", "formula"),
            index = 1,
            catalogKey = "bath",
        )
        assertEquals(listOf("pee", "bath", "nursing", "formula"), next)
        assertEquals(4, next.size)
        assertTrue("sleep" !in next)
    }

    @Test
    fun assignExistingKeySwapsWithTargetForUniqueness() {
        val next = assignQuickRecordSlot(
            slots = listOf("pee", "sleep", "nursing", "formula"),
            index = 0,
            catalogKey = "formula",
        )
        assertEquals(listOf("formula", "sleep", "nursing", "pee"), next)
        assertEquals(1, next.count { it == "formula" })
        assertEquals(1, next.count { it == "pee" })
    }

    @Test
    fun assignExistingKeyOntoEmptySlotMovesPointer() {
        val next = assignQuickRecordSlot(
            slots = listOf("pee", "", "nursing", "formula"),
            index = 1,
            catalogKey = "pee",
        )
        assertEquals(listOf("", "pee", "nursing", "formula"), next)
        assertEquals(1, next.count { it == "pee" })
    }

    @Test
    fun assignBlankCatalogKeyIsNoOp() {
        val slots = listOf("pee", "sleep", "nursing", "formula")
        assertEquals(slots, assignQuickRecordSlot(slots, 1, ""))
        assertEquals(slots, assignQuickRecordSlot(slots, 1, "  "))
    }

    @Test
    fun assignSameKeyToItsSlotIsNoOp() {
        val slots = listOf("pee", "sleep", "nursing", "formula")
        assertEquals(slots, assignQuickRecordSlot(slots, 2, "nursing"))
    }

    @Test
    fun clearSlotOnlyBlanksPointerAndKeepsLength() {
        val next = clearQuickRecordSlot(
            listOf("pee", "sleep", "nursing", "formula"),
            index = 2,
        )
        assertEquals(listOf("pee", "sleep", "", "formula"), next)
        assertEquals(4, next.size)
    }

    @Test
    fun clearAllowsMultipleEmptySlots() {
        val once = clearQuickRecordSlot(listOf("pee", "sleep", "nursing", "formula"), 0)
        val twice = clearQuickRecordSlot(once, 3)
        assertEquals(listOf("", "sleep", "nursing", ""), twice)
    }

    @Test
    fun assignAndClearIgnoreOutOfRangeIndex() {
        val slots = listOf("pee", "sleep", "nursing", "formula")
        assertEquals(slots, assignQuickRecordSlot(slots, -1, "bath"))
        assertEquals(slots, assignQuickRecordSlot(slots, 4, "bath"))
        assertEquals(slots, clearQuickRecordSlot(slots, -1))
        assertEquals(slots, clearQuickRecordSlot(slots, 4))
    }

    @Test
    fun assignPadsShortListsToFourSlots() {
        val next = assignQuickRecordSlot(
            slots = listOf("pee"),
            index = 3,
            catalogKey = "sleep",
        )
        assertEquals(listOf("pee", "", "", "sleep"), next)
    }

    @Test
    fun candidatesExcludeHiddenItemsAndBareCustom() {
        val candidates = quickSlotCandidates(
            hiddenItems = setOf("pee", "custom:12"),
            customItems = listOf(customTouch, CustomRecordItem(3, "药", 0, 1)),
        )
        val keys = candidates.map { it.catalogKey }
        assertTrue("pee" !in keys)
        assertTrue("custom:12" !in keys)
        assertTrue("custom" !in keys)
        assertTrue("custom:3" in keys)
        assertTrue("formula" in keys)
    }
}
