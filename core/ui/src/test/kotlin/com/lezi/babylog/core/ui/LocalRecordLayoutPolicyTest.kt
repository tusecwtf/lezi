package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecordLayoutPolicyTest {
    private val known = knownCatalogKeys(listOf(3L, 5L))

    @Test
    fun restoreAppendsToSectionVisibleEnd() {
        val order = encodeItemOrder(
            listOf("nursing", "formula", "pee", "sleep", "custom:3", "custom:5"),
        )
        // Hide formula (was between nursing and pee in feeding? formula is feeding)
        val hidden = setOf("formula", "pee")
        val afterRestore = appendCatalogKeyToSectionEnd(order, "formula", known)
        val feedingVisible = visibleKeysInSection(
            RecordSection.Feeding,
            afterRestore,
            known,
            hidden - "formula",
        )
        assertEquals("formula", feedingVisible.last())
        assertTrue(feedingVisible.indexOf("nursing") < feedingVisible.indexOf("formula"))
    }

    @Test
    fun editVisibleSectionsHidesEmptyNonCustom() {
        val allKeys = knownCatalogKeys(emptyList())
        val hidden = allKeys.filter {
            catalogSectionForKey(it) == RecordSection.Health
        }.toSet()
        val visible = editVisibleSections("[]", "[]", allKeys, hidden)
        assertFalse(visible.contains(RecordSection.Health))
        assertTrue(visible.contains(RecordSection.Custom))
        assertTrue(visible.contains(RecordSection.Feeding))
    }

    @Test
    fun editVisibleSectionsAlwaysIncludesEmptyCustom() {
        val knownOnlyBuiltIns = knownCatalogKeys(emptyList())
        val hiddenAllCustomNone = knownOnlyBuiltIns.toSet() // hide everything
        val visible = editVisibleSections("[]", "[]", knownOnlyBuiltIns, hiddenAllCustomNone)
        assertEquals(listOf(RecordSection.Custom), visible)
    }

    @Test
    fun localDeletedKeysOrdered() {
        val order = encodeItemOrder(listOf("pee", "sleep", "nursing"))
        val keys = localDeletedKeys(order, known, setOf("sleep", "pee", "unknown"))
        assertEquals(listOf("pee", "sleep"), keys)
    }

    @Test
    fun addAndRemoveLocalDeleted() {
        val mid = addToLocalDeleted(emptySet(), "pee")
        assertTrue("pee" in mid)
        assertFalse("pee" in removeFromLocalDeleted(mid, "pee"))
    }
}
