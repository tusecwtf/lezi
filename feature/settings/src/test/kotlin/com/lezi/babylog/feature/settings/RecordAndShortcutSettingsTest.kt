package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
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

class RecordAndShortcutSettingsTest {
    @Test
    fun crossCategoryMoveIsNoOp() {
        val known = knownCatalogKeys(emptyList())
        val order = encodeItemOrder(mergeItemOrder("[]", known))
        val pee = RecordType.PEE.key
        val next = tryMoveItemAcrossCategory(
            itemOrderJson = order,
            catalogKey = pee,
            targetSection = RecordSection.Feeding,
            allKnownKeys = known,
        )
        assertEquals(
            orderedKeysInSection(RecordSection.Excretion, order, known),
            orderedKeysInSection(RecordSection.Excretion, next, known),
        )
        assertFalse(
            orderedKeysInSection(RecordSection.Feeding, next, known).contains(pee),
        )
    }

    @Test
    fun hideAndRestoreKeepsRelativeOrder() {
        // Product path: Switch only toggles SettingsLocal.hiddenItems; itemOrderJson
        // is never rewritten on hide/show (see AllRecordItemsSettingsDialog copy).
        val customKey = RecordItemIdentity.customCatalogKey(7)
        val known = knownCatalogKeys(listOf(7L))
        val base = listOf("pee", customKey, "sleep", "nursing")
        val orderJson = encodeItemOrder(base)
        val orderBeforeHide = mergeItemOrder(orderJson, known)

        fun visibleKeys(order: List<String>, hidden: Set<String>): List<String> =
            order.filter { it !in hidden }

        // Hide: only the visibility set changes.
        val hiddenWhileOff = setOf(customKey)
        assertTrue(customKey in hiddenWhileOff)
        val orderWhileHidden = mergeItemOrder(orderJson, known)
        assertEquals(
            "hide must not rewrite catalog order JSON",
            orderBeforeHide,
            orderWhileHidden,
        )
        assertEquals(
            listOf("pee", "sleep", "nursing"),
            visibleKeys(orderWhileHidden, hiddenWhileOff).filter { it in base.toSet() },
        )
        assertFalse(customKey in visibleKeys(orderWhileHidden, hiddenWhileOff))

        // Restore: clear hide bit; full relative order returns without reordering.
        val hiddenAfterRestore = emptySet<String>()
        assertFalse(customKey in hiddenAfterRestore)
        val orderAfterRestore = mergeItemOrder(orderJson, known)
        assertEquals(orderBeforeHide, orderAfterRestore)
        assertEquals(base, orderAfterRestore.filter { it in base.toSet() })
        assertEquals(
            base,
            visibleKeys(orderAfterRestore, hiddenAfterRestore).filter { it in base.toSet() },
        )
        assertTrue(orderAfterRestore.indexOf(customKey) > orderAfterRestore.indexOf("pee"))
        assertTrue(orderAfterRestore.indexOf(customKey) < orderAfterRestore.indexOf("sleep"))
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
        val health = builtInKeysInSection(RecordSection.Health)
        assertFalse(health.contains("other"))
        assertFalse(health.contains("custom"))
        assertFalse(health.contains("memo"))
    }
}
