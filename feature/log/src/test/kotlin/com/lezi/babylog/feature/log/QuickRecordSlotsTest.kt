package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
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
    fun defaultMigrationIsPeeSleepNursingFormula() {
        assertEquals(
            listOf("pee", "sleep", "nursing", "formula"),
            defaultQuickRecordSlots(),
        )
        assertEquals(DEFAULT_QUICK_RECORD_SLOTS, defaultQuickRecordSlots())
        assertEquals(QUICK_RECORD_SLOT_COUNT, defaultQuickRecordSlots().size)
    }

    @Test
    fun emptyAndInvalidRefsBlankWithoutAutoFill() {
        val slots = resolveQuickSlots(
            storedSlots = listOf("", "memo", "custom", "custom:999"),
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
    fun candidatesExcludeHiddenAndRetiredGenerics() {
        val candidates = quickSlotCandidates(
            hiddenItems = setOf("pee", "custom:12"),
            customItems = listOf(customTouch, CustomRecordItem(3, "药", 0, 1)),
        )
        val keys = candidates.map { it.catalogKey }
        assertTrue("pee" !in keys)
        assertTrue("custom:12" !in keys)
        assertTrue("memo" !in keys)
        assertTrue("other" !in keys)
        assertTrue("custom" !in keys)
        assertTrue("custom:3" in keys)
        assertTrue("formula" in keys)
    }
}
