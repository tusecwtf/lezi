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
    fun hubExposesFourFixedDestinationsInSpecOrder() {
        val titles = recordShortcutHubDestinations().map { it.title }
        assertEquals(
            listOf("常用记录", "所有记录项目", "分项目设置", "护理计划与日历"),
            titles,
        )
    }

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
        val customKey = RecordItemIdentity.customCatalogKey(7)
        val known = knownCatalogKeys(listOf(7L))
        val base = listOf("pee", customKey, "sleep", "nursing")
        val orderJson = encodeItemOrder(base)
        // "hide" does not rewrite order — only hiddenItems set changes in UI.
        val stillPresent = mergeItemOrder(orderJson, known)
        assertEquals(base.filter { it in stillPresent.toSet() }.let { kept ->
            // all base keys that are known stay
            base
        }, stillPresent.filter { it in base.toSet() }.let {
            // relative order of base keys preserved
            stillPresent.filter { key -> key in base.toSet() }
        })
        val restoredPosition = stillPresent.indexOf(customKey)
        assertTrue(restoredPosition > stillPresent.indexOf("pee"))
        assertTrue(restoredPosition < stillPresent.indexOf("sleep") || stillPresent.indexOf("sleep") < 0)
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
