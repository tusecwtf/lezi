package com.lezi.babylog.feature.log

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDropResolveTest {
    private val slots = mapOf(
        0 to Rect(0f, 0f, 100f, 100f),
        1 to Rect(100f, 0f, 200f, 100f),
        2 to Rect(200f, 0f, 300f, 100f),
        3 to Rect(300f, 0f, 400f, 100f),
    )
    private val trash = Rect(0f, 400f, 400f, 500f)

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
    fun missReturnsNull() {
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
}
