package com.lezi.babylog.feature.log

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutDropResolveTest {
    private val slots = mapOf(
        0 to Rect(0f, 0f, 100f, 100f),
        1 to Rect(100f, 0f, 200f, 100f),
        2 to Rect(200f, 0f, 300f, 100f),
        3 to Rect(300f, 0f, 400f, 100f),
    )
    private val trash = Rect(0f, 400f, 400f, 500f)
    private val known = knownCatalogKeys(emptyList())

    @Test
    fun dropOnSlotAssignsCatalogKey() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(150f, 50f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "bath",
            sourceIsDeleted = false,
        )
        assertEquals(LayoutEditIntent.AssignToSlot(1, "bath"), intent)
    }

    @Test
    fun dropOnTrashMovesToLocalDeleted() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(50f, 450f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "pee",
            sourceIsDeleted = false,
        )
        assertEquals(LayoutEditIntent.MoveToLocalDeleted("pee"), intent)
    }

    @Test
    fun dropDeletedOutsideTrashRestores() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(50f, 50f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "sleep",
            sourceIsDeleted = true,
        )
        assertEquals(LayoutEditIntent.RestoreFromLocalDeleted("sleep"), intent)
    }

    @Test
    fun catalogMissReturnsNull() {
        assertNull(
            resolveLayoutDrop(
                pointerWindow = Offset(1000f, 1000f),
                slotBounds = slots,
                trashBounds = trash,
                sourceKey = "pee",
                sourceIsDeleted = false,
            ),
        )
    }

    @Test
    fun boundSlotDragOffClearsShortcutWithoutTrash() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(1000f, 1000f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "sleep",
            sourceIsDeleted = false,
            sourceSlotIndex = 1,
        )
        assertEquals(LayoutEditIntent.ClearSlot(1), intent)
    }

    @Test
    fun boundSlotDragOffThroughReducerClearsOnlyThatSlot() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(-50f, 200f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "nursing",
            sourceIsDeleted = false,
            sourceSlotIndex = 2,
        )
        assertEquals(LayoutEditIntent.ClearSlot(2), intent)
        val prefs = DeviceLayoutPrefs(
            quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
            hiddenItems = emptySet(),
            itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
            categoryOrderJson = "[]",
        )
        val next = reduceLayoutEdit(prefs, intent!!, known)
        assertEquals(listOf("pee", "sleep", "", "formula"), next.quickRecordSlots)
        assertEquals(emptySet<String>(), next.hiddenItems)
    }

    @Test
    fun boundSlotDropOnOtherSlotSwaps() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(350f, 50f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "pee",
            sourceIsDeleted = false,
            sourceSlotIndex = 0,
        )
        assertEquals(LayoutEditIntent.SwapSlots(0, 3), intent)
    }

    @Test
    fun boundSlotDropOnSelfIsNoOp() {
        assertNull(
            resolveLayoutDrop(
                pointerWindow = Offset(50f, 50f),
                slotBounds = slots,
                trashBounds = trash,
                sourceKey = "pee",
                sourceIsDeleted = false,
                sourceSlotIndex = 0,
            ),
        )
    }

    @Test
    fun boundSlotDropOnTrashHidesNotOnlyClear() {
        val intent = resolveLayoutDrop(
            pointerWindow = Offset(50f, 450f),
            slotBounds = slots,
            trashBounds = trash,
            sourceKey = "pee",
            sourceIsDeleted = false,
            sourceSlotIndex = 0,
        )
        assertEquals(LayoutEditIntent.MoveToLocalDeleted("pee"), intent)
    }
}
