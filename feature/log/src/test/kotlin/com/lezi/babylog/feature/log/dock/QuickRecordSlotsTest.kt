package com.lezi.babylog.feature.log.dock
import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CustomRecordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class QuickRecordSlotsTest {
    private val customTouch = CustomRecordItem(
        id = 12L,
        name = "抚触",
        iconSlot = 2,
        sortOrder = 0,
    )

    @Test
    fun stableCustomSlotRebindsByClientUuidAfterLocalIdsChange() {
        val clientUuid = "123e4567-e89b-12d3-a456-426614174000"
        val storedKey = RecordItemIdentity.customFamilyCatalogKey(clientUuid)
        val wrongSameLocalId = customTouch.copy(
            clientUuid = "223e4567-e89b-12d3-a456-426614174000",
            name = "不应误绑",
        )
        val restored = customTouch.copy(
            id = 87L,
            clientUuid = clientUuid,
            name = "重加入后抚触",
        )

        val cell = resolveQuickSlot(
            catalogKey = storedKey,
            hiddenItems = emptySet(),
            customItems = listOf(wrongSameLocalId, restored),
        ) as QuickDockCell.Bound

        assertEquals("重加入后抚触", cell.label)
        assertEquals(87L, (cell.identity as RecordItemIdentity.Custom).customItemId)
        assertEquals(storedKey, cell.catalogKey)
    }

    @Test
    fun migrationCompatibilitySymbolsAreAbsent() {
        val quickSlotMethods = Class.forName(
            "com.lezi.babylog.feature.log.dock.QuickRecordSlotsKt",
        ).declaredMethods.map { it.name }
        val layoutMethods = Class.forName(
            "com.lezi.babylog.feature.log.layout.LayoutEditModeKt",
        ).declaredMethods.map { it.name }

        assertFalse(
            quickSlotMethods.any {
                it.startsWith("normalizeStoredQuickSlots") ||
                    it.startsWith("oneHandQuickDockOrder") ||
                    it.startsWith("defaultQuickRecordSlots") ||
                    it.startsWith("quickSlotCandidates")
            },
        )
        assertFalse(layoutMethods.any { it.startsWith("LayoutEditModeDialog") })
    }

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
        val dock = fixedQuickDockOrder(slots)
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
    fun emptySlotShortPressIsNoOp() {
        assertEquals(QuickDockAction.None, QuickDockCell.Empty.toAction())
    }

    @Test
    fun emptySlotCopyStatesTruthWithoutPromisingSelection() {
        val presentation = quickDockPresentation(
            cell = QuickDockCell.Empty,
            sleepRunning = false,
        )

        assertEquals("空槽", presentation.visualLabel)
        assertEquals(
            "空槽，短按无操作；可使用编辑常用布局操作",
            presentation.contentDescription,
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
    fun dockOrderIsFourSlotsThenMoreIndependentOfHand() {
        val slots = resolveQuickSlots(
            DEFAULT_QUICK_RECORD_SLOTS,
            hiddenItems = emptySet(),
            customItems = emptyList(),
        )
        val dock = fixedQuickDockOrder(slots)
        assertEquals(5, dock.size)
        assertEquals(QuickDockCell.More, dock.last())
        assertTrue(dock.first() is QuickDockCell.Bound)
        assertEquals("pee", (dock.first() as QuickDockCell.Bound).catalogKey)
        assertEquals(
            "formula",
            (dock[3] as QuickDockCell.Bound).catalogKey,
        )
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

}
