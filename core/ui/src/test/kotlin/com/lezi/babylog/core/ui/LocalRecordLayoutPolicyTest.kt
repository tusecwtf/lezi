package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecordLayoutPolicyTest {
    private val firstCustomKey = RecordItemIdentity.customFamilyCatalogKey(
        "33333333-3333-4333-8333-333333333333",
    )
    private val secondCustomKey = RecordItemIdentity.customFamilyCatalogKey(
        "55555555-5555-4555-8555-555555555555",
    )
    private val known = knownCatalogKeys(
        listOf(
            "33333333-3333-4333-8333-333333333333",
            "55555555-5555-4555-8555-555555555555",
        ),
    )

    @Test
    fun restoreAppendsToSectionVisibleEnd() {
        val order = encodeItemOrder(
            listOf("nursing", "formula", "pee", "sleep", firstCustomKey, secondCustomKey),
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
