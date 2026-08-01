package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.core.ui.visibleKeysInSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LocalLayoutEditPolicyTest {
    private val known = knownCatalogKeys(listOf(7L))
    private val base = DeviceLayoutPrefs(
        quickRecordSlots = listOf("pee", "sleep", "nursing", "formula"),
        hiddenItems = emptySet(),
        itemOrderJson = encodeItemOrder(mergeItemOrder("[]", known)),
        categoryOrderJson = "[]",
    )

    @Test
    fun moveToLocalDeletedClearsMatchingSlots() {
        val next = reduceLayoutEdit(
            base,
            LayoutEditIntent.MoveToLocalDeleted("sleep"),
            known,
        )
        assertTrue("sleep" in next.hiddenItems)
        assertEquals(listOf("pee", "", "nursing", "formula"), next.quickRecordSlots)
        assertEquals(4, next.quickRecordSlots.size)
    }

    @Test
    fun moveToLocalDeletedClearsAllDuplicateSlotRefs() {
        val dup = base.copy(quickRecordSlots = listOf("pee", "pee", "nursing", ""))
        // uniqueness is preferred, but if legacy dups exist, all clear
        val next = reduceLayoutEdit(dup, LayoutEditIntent.MoveToLocalDeleted("pee"), known)
        assertEquals(listOf("", "", "nursing", ""), next.quickRecordSlots)
    }

    @Test
    fun restoreDoesNotRepinSlotsAndAppendsSectionEnd() {
        val trashed = reduceLayoutEdit(base, LayoutEditIntent.MoveToLocalDeleted("formula"), known)
        assertEquals("", trashed.quickRecordSlots[3])
        val restored = reduceLayoutEdit(
            trashed,
            LayoutEditIntent.RestoreFromLocalDeleted("formula"),
            known,
        )
        assertFalse("formula" in restored.hiddenItems)
        assertEquals("", restored.quickRecordSlots[3])
        val feeding = visibleKeysInSection(
            RecordSection.Feeding,
            restored.itemOrderJson,
            known,
            restored.hiddenItems,
        )
        assertEquals("formula", feeding.last())
    }

    @Test
    fun allowAllItemsInLocalDeleted() {
        var prefs = base
        for (key in known) {
            prefs = reduceLayoutEdit(prefs, LayoutEditIntent.MoveToLocalDeleted(key), known)
        }
        assertEquals(known.toSet(), prefs.hiddenItems.intersect(known.toSet()))
        assertTrue(prefs.quickRecordSlots.all { it.isEmpty() || it !in known })
        val sections = layoutEditVisibleSections(prefs, known)
        assertEquals(listOf(RecordSection.Custom), sections)
    }

    @Test
    fun assignAndClearThroughReducer() {
        val assigned = reduceLayoutEdit(
            base.copy(quickRecordSlots = listOf("", "", "", "")),
            LayoutEditIntent.AssignToSlot(0, "bath"),
            known,
        )
        assertEquals("bath", assigned.quickRecordSlots[0])
        val cleared = reduceLayoutEdit(
            assigned,
            LayoutEditIntent.ClearSlot(0),
            known,
        )
        assertEquals("", cleared.quickRecordSlots[0])
    }

    @Test
    fun uniqueAssignSwapsViaReducer() {
        val next = reduceLayoutEdit(
            base,
            LayoutEditIntent.AssignToSlot(0, "formula"),
            known,
        )
        assertEquals(listOf("formula", "sleep", "nursing", "pee"), next.quickRecordSlots)
    }

    @Test
    fun customCatalogKeyTrashAndRestore() {
        val customKey = RecordItemIdentity.customCatalogKey(7L)
        val withCustom = base.copy(
            quickRecordSlots = listOf(customKey, "pee", "", ""),
        )
        val trashed = reduceLayoutEdit(
            withCustom,
            LayoutEditIntent.MoveToLocalDeleted(customKey),
            known,
        )
        assertEquals("", trashed.quickRecordSlots[0])
        assertTrue(customKey in trashed.hiddenItems)
        val restored = reduceLayoutEdit(
            trashed,
            LayoutEditIntent.RestoreFromLocalDeleted(customKey),
            known,
        )
        assertFalse(customKey in restored.hiddenItems)
        val customVisible = visibleKeysInSection(
            RecordSection.Custom,
            restored.itemOrderJson,
            known,
            restored.hiddenItems,
        )
        assertEquals(customKey, customVisible.last())
    }

    @Test
    fun reorderItemInSectionMovesToAbsoluteIndex() {
        val feedingKeys = listOf(
            "nursing",
            "formula",
            "pumped_feed",
            "pump_express",
        )
        val prefs = base.copy(itemOrderJson = encodeItemOrder(feedingKeys))
        val next = reduceLayoutEdit(
            prefs,
            LayoutEditIntent.ReorderItemInSection("nursing", 2),
            feedingKeys,
        )
        val after = visibleKeysInSection(
            RecordSection.Feeding,
            next.itemOrderJson,
            feedingKeys,
            next.hiddenItems,
        )
        assertEquals(
            listOf("formula", "pumped_feed", "nursing", "pump_express"),
            after,
        )
    }

    @Test
    fun moveCategoryToIndexChangesOnlyTheLiteralCategoryOrderSnapshotField() {
        val prefs = base.copy(
            categoryOrderJson =
                """["feeding","excretion","routine","health","growth","custom"]""",
        )

        val first = reduceLayoutEdit(
            prefs,
            LayoutEditIntent.MoveCategoryToIndex(RecordSection.Custom, 0),
            known,
        )
        val middle = reduceLayoutEdit(
            prefs,
            LayoutEditIntent.MoveCategoryToIndex(RecordSection.Feeding, 3),
            known,
        )
        val last = reduceLayoutEdit(
            prefs,
            LayoutEditIntent.MoveCategoryToIndex(RecordSection.Feeding, 5),
            known,
        )

        assertEquals(
            prefs.copy(
                categoryOrderJson =
                    """["custom","feeding","excretion","routine","health","growth"]""",
            ),
            first,
        )
        assertEquals(
            prefs.copy(
                categoryOrderJson =
                    """["excretion","routine","health","feeding","growth","custom"]""",
            ),
            middle,
        )
        assertEquals(
            prefs.copy(
                categoryOrderJson =
                    """["excretion","routine","health","growth","custom","feeding"]""",
            ),
            last,
        )
    }
}
