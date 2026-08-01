package com.lezi.babylog.feature.settings.record

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.encodeItemOrder
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.mergeItemOrder
import com.lezi.babylog.core.ui.moveCatalogKeyWithinSection
import com.lezi.babylog.core.ui.orderedKeysInSection
import com.lezi.babylog.core.ui.presentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record-settings catalog knobs via public core.ui seams (no production test helpers).
 */
class RecordSettingsTest {
    @Test
    fun crossCategoryMoveIsNoOp() {
        // No public reassignment API: in-section move cannot place excretion key in feeding.
        val known = knownCatalogKeys(emptyList())
        val order = encodeItemOrder(mergeItemOrder("[]", known))
        val pee = RecordType.PEE.key
        val next = moveCatalogKeyWithinSection(order, pee, 100, known)
        assertEquals(
            orderedKeysInSection(RecordSection.Excretion, order, known).toSet(),
            orderedKeysInSection(RecordSection.Excretion, next, known).toSet(),
        )
        assertFalse(
            orderedKeysInSection(RecordSection.Feeding, next, known).contains(pee),
        )
    }

    @Test
    fun restoreFromLocalDeletedAppendsToSectionEnd() {
        // Product: 本机已删除 restore lands at category visible end (not prior index).
        val customKey = RecordItemIdentity.customCatalogKey(7)
        val known = knownCatalogKeys(listOf(7L))
        val base = listOf("pee", customKey, "sleep", "nursing")
        val orderJson = encodeItemOrder(base)
        val afterRestore = com.lezi.babylog.core.ui.appendCatalogKeyToSectionEnd(
            itemOrderJson = orderJson,
            catalogKey = customKey,
            allKnownKeys = known,
        )
        val customSection = orderedKeysInSection(
            RecordSection.Custom,
            afterRestore,
            known,
        )
        assertEquals(customKey, customSection.last())
    }

    @Test
    fun inCategoryMoveDoesNotPullForeignKeys() {
        val known = knownCatalogKeys(listOf(2L))
        val order = encodeItemOrder(mergeItemOrder("[]", known))
        val nursing = RecordType.NURSING.key
        val after = moveCatalogKeyWithinSection(order, nursing, 1, known)
        val feedingKeys = orderedKeysInSection(RecordSection.Feeding, after, known)
        assertTrue(feedingKeys.all { key ->
            key.startsWith("custom:") ||
                RecordType.fromKey(key)?.let {
                    it.presentation.section == RecordSection.Feeding
                } == true
        })
    }

    @Test
    fun builtInKeysInSectionNeverIncludesRetiredGenerics() {
        val health = RecordType.availableForNewEntry()
            .filter { it.presentation.section == RecordSection.Health }
            .map { it.key }
        assertFalse(health.contains("other"))
        assertFalse(health.contains("custom"))
        assertFalse(health.contains("memo"))
    }
}
