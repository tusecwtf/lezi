package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCatalogOrderTest {
    @Test
    fun parserRejectsNonCurrentArrayEncodings() {
        assertEquals(emptyList<String>(), parseJsonStringArray("[feeding, health]"))
        assertEquals(emptyList<String>(), parseJsonStringArray("[\"feeding\",]"))
        assertEquals(emptyList<String>(), parseJsonStringArray("[1, \"health\"]"))
    }

    @Test
    fun emptyCategoryOrderUsesDefaultEnumOrderIncludingCustom() {
        val sections = orderedRecordSections("[]")
        assertEquals(RecordSection.entries.toList(), sections)
        assertTrue(sections.contains(RecordSection.Custom))
    }

    @Test
    fun categoryReorderAndMissingSectionsAreAppended() {
        val json = encodeCategoryOrder(
            listOf(RecordSection.Health, RecordSection.Feeding),
        )
        val sections = orderedRecordSections(json)
        assertEquals(RecordSection.Health, sections.first())
        assertEquals(RecordSection.Feeding, sections[1])
        assertTrue(sections.contains(RecordSection.Custom))
        assertEquals(RecordSection.entries.size, sections.size)
    }

    @Test
    fun moveCategoryOrderRespectsBounds() {
        val start = "[]"
        val upFromFirst = moveCategoryOrder(start, RecordSection.Feeding, -1)
        assertEquals(encodeCategoryOrder(defaultCategoryOrder()), upFromFirst)

        val swapped = moveCategoryOrder(start, RecordSection.Feeding, 1)
        val ordered = orderedRecordSections(swapped)
        assertEquals(RecordSection.Excretion, ordered[0])
        assertEquals(RecordSection.Feeding, ordered[1])
    }

    @Test
    fun mergeItemOrderPreservesHiddenAndAppendsNewKeys() {
        val known = listOf("pee", "sleep", "nursing", "custom:3")
        val stored = encodeItemOrder(listOf("sleep", "pee", "memo", "unknown"))
        val merged = mergeItemOrder(stored, known)
        assertEquals(listOf("sleep", "pee", "nursing", "custom:3"), merged)
        assertFalse(merged.contains("memo"))
    }

    @Test
    fun itemMoveStaysWithinSectionAndRejectsCrossCategory() {
        val feeding = RecordType.availableForNewEntry()
            .filter { it.presentation.section == RecordSection.Feeding }
            .map { it.key }
        val excretion = RecordType.availableForNewEntry()
            .filter { it.presentation.section == RecordSection.Excretion }
            .map { it.key }
        val known = feeding + excretion + listOf(RecordItemIdentity.customCatalogKey(9))
        val order = encodeItemOrder(known)

        val pee = RecordType.PEE.key
        val moved = moveCatalogKeyWithinSection(order, pee, -1, known)
        // pee is excretion; moving within excretion only
        val excretionOrder = orderedKeysInSection(
            RecordSection.Excretion,
            moved,
            known,
        )
        assertTrue(excretionOrder.contains(pee))
        assertEquals(
            catalogSectionForKey(pee),
            RecordSection.Excretion,
        )

        // Cross-category: moving pee cannot enter feeding keys list as a reassignment
        val afterCrossAttempt = moveCatalogKeyWithinSection(
            moved,
            pee,
            // large delta still clamped inside section
            delta = 100,
            allKnownKeys = known,
        )
        assertEquals(
            RecordSection.Excretion,
            catalogSectionForKey(pee),
        )
        assertTrue(
            orderedKeysInSection(RecordSection.Feeding, afterCrossAttempt, known)
                .none { it == pee },
        )
    }

    @Test
    fun hideDoesNotDropKeyFromMergedOrder() {
        val known = knownCatalogKeys(listOf(3L))
        val order = encodeItemOrder(listOf("pee", "custom:3", "sleep"))
        val merged = mergeItemOrder(order, known)
        assertTrue(merged.contains("custom:3"))
        assertTrue(merged.indexOf("pee") < merged.indexOf("custom:3"))
    }

    @Test
    fun customKeysBelongToCustomSection() {
        assertEquals(
            RecordSection.Custom,
            catalogSectionForKey(RecordItemIdentity.customCatalogKey(4)),
        )
        assertEquals(RecordSection.Feeding, catalogSectionForKey(RecordType.NURSING.key))
    }

    @Test
    fun sortCatalogAppliesCategoryThenItemOrder() {
        data class Entry(val section: RecordSection, val key: String)
        val entries = listOf(
            Entry(RecordSection.Feeding, "nursing"),
            Entry(RecordSection.Custom, "custom:1"),
            Entry(RecordSection.Feeding, "formula"),
            Entry(RecordSection.Excretion, "pee"),
        )
        val categoryOrder = encodeCategoryOrder(
            listOf(RecordSection.Custom, RecordSection.Excretion, RecordSection.Feeding),
        )
        val itemOrder = encodeItemOrder(listOf("formula", "nursing", "pee", "custom:1"))
        val sorted = sortCatalogByLocalOrder(
            entries = entries,
            sectionOf = { it.section },
            catalogKeyOf = { it.key },
            categoryOrderJson = categoryOrder,
            itemOrderJson = itemOrder,
        )
        assertEquals(
            listOf("custom:1", "pee", "formula", "nursing"),
            sorted.map { it.key },
        )
    }
}
