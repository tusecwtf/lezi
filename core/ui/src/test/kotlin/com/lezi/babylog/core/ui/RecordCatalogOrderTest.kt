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
    fun moveCategoryToIndexSupportsFirstMiddleAndLast() {
        val start = encodeCategoryOrder(defaultCategoryOrder())

        assertEquals(
            listOf(
                RecordSection.Custom,
                RecordSection.Feeding,
                RecordSection.Excretion,
                RecordSection.Routine,
                RecordSection.Health,
                RecordSection.Growth,
            ),
            orderedRecordSections(moveCategoryToIndex(start, RecordSection.Custom, 0)),
        )
        assertEquals(
            listOf(
                RecordSection.Excretion,
                RecordSection.Routine,
                RecordSection.Health,
                RecordSection.Feeding,
                RecordSection.Growth,
                RecordSection.Custom,
            ),
            orderedRecordSections(moveCategoryToIndex(start, RecordSection.Feeding, 3)),
        )
        assertEquals(
            listOf(
                RecordSection.Excretion,
                RecordSection.Routine,
                RecordSection.Health,
                RecordSection.Growth,
                RecordSection.Custom,
                RecordSection.Feeding,
            ),
            orderedRecordSections(moveCategoryToIndex(start, RecordSection.Feeding, 5)),
        )
    }

    @Test
    fun mergeItemOrderPreservesHiddenAndAppendsNewKeys() {
        val known = listOf("pee", "sleep", "nursing", "custom:3")
        val stored = encodeItemOrder(listOf("sleep", "pee", "removed_item", "unknown"))
        val merged = mergeItemOrder(stored, known)
        assertEquals(listOf("sleep", "pee", "nursing", "custom:3"), merged)
        assertFalse(merged.contains("removed_item"))
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
        val clientUuid = "33333333-3333-4333-8333-333333333333"
        val customKey = RecordItemIdentity.customFamilyCatalogKey(clientUuid)
        val known = knownCatalogKeys(listOf(clientUuid))
        val order = encodeItemOrder(listOf("pee", customKey, "sleep"))
        val merged = mergeItemOrder(order, known)
        assertTrue(merged.contains(customKey))
        assertTrue(merged.indexOf("pee") < merged.indexOf(customKey))
        assertFalse(merged.contains(RecordItemIdentity.customCatalogKey(3L)))
    }

    @Test
    fun knownCatalogKeysUseStableFamilyIdentityInsteadOfReusableLocalRowId() {
        val firstUuid = "11111111-1111-4111-8111-111111111111"
        val secondUuid = "22222222-2222-4222-8222-222222222222"

        val first = knownCatalogKeys(listOf(firstUuid))
        val second = knownCatalogKeys(listOf(secondUuid))

        assertTrue(first.contains(RecordItemIdentity.customFamilyCatalogKey(firstUuid)))
        assertFalse(first.contains(RecordItemIdentity.customCatalogKey(7L)))
        assertFalse(second.contains(RecordItemIdentity.customFamilyCatalogKey(firstUuid)))
        assertTrue(second.contains(RecordItemIdentity.customFamilyCatalogKey(secondUuid)))
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
    fun moveCatalogKeyToIndexInSectionIsAbsolute() {
        val feeding = RecordType.availableForNewEntry()
            .filter { it.presentation.section == RecordSection.Feeding }
            .map { it.key }
        require(feeding.size >= 3)
        val known = feeding
        val order = encodeItemOrder(feeding)
        val first = feeding.first()
        val moved = moveCatalogKeyToIndexInSection(order, first, feeding.lastIndex, known)
        val section = orderedKeysInSection(RecordSection.Feeding, moved, known)
        assertEquals(first, section.last())
        assertEquals(feeding.size, section.size)
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
