package com.lezi.babylog.core.ui

/**
 * Device-local catalog visibility and order helpers for 本机已删除 / 布局编辑态.
 * Quick-slot pointer ops stay beside the dock; this module owns trash + section visibility.
 */

/**
 * Move [catalogKey] into 本机已删除 (local hide set).
 * Does not rewrite item order JSON (restore repositions to section visible end).
 */
fun addToLocalDeleted(
    hiddenItems: Set<String>,
    catalogKey: String,
): Set<String> {
    val key = catalogKey.trim()
    if (key.isEmpty()) return hiddenItems
    return hiddenItems + key
}

/**
 * Remove [catalogKey] from 本机已删除.
 */
fun removeFromLocalDeleted(
    hiddenItems: Set<String>,
    catalogKey: String,
): Set<String> {
    val key = catalogKey.trim()
    if (key.isEmpty()) return hiddenItems
    return hiddenItems - key
}

/**
 * Place [catalogKey] at the end of its category among the full known order list.
 * Used when restoring from 本机已删除 so the item appears at the category's
 * current visible tail once un-hidden (keys still hidden stay in the list but
 * the restored key is last among its section).
 */
fun appendCatalogKeyToSectionEnd(
    itemOrderJson: String,
    catalogKey: String,
    allKnownKeys: Collection<String>,
): String {
    val key = catalogKey.trim()
    if (key.isEmpty()) return encodeItemOrder(mergeItemOrder(itemOrderJson, allKnownKeys))
    val section = catalogSectionForKey(key)
        ?: return encodeItemOrder(mergeItemOrder(itemOrderJson, allKnownKeys))
    val full = mergeItemOrder(itemOrderJson, allKnownKeys).toMutableList()
    full.removeAll { it == key }
    val lastSameSection = full.indexOfLast { catalogSectionForKey(it) == section }
    if (lastSameSection >= 0) {
        full.add(lastSameSection + 1, key)
    } else {
        full.add(key)
    }
    for (known in allKnownKeys) {
        if (known !in full) full += known
    }
    return encodeItemOrder(full)
}

/**
 * Visible catalog keys in [section] (not in [hiddenItems]), in local order.
 */
fun visibleKeysInSection(
    section: RecordSection,
    itemOrderJson: String,
    knownKeys: Collection<String>,
    hiddenItems: Set<String>,
): List<String> =
    orderedKeysInSection(section, itemOrderJson, knownKeys)
        .filter { it !in hiddenItems }

/**
 * Categories shown in layout edit mode: hide sections with zero visible items,
 * except [RecordSection.Custom] which always appears (manage / add entry).
 */
fun editVisibleSections(
    categoryOrderJson: String,
    itemOrderJson: String,
    knownKeys: Collection<String>,
    hiddenItems: Set<String>,
): List<RecordSection> =
    orderedRecordSections(categoryOrderJson).filter { section ->
        if (section == RecordSection.Custom) return@filter true
        visibleKeysInSection(section, itemOrderJson, knownKeys, hiddenItems).isNotEmpty()
    }

/**
 * Keys currently in 本机已删除 that are still known, ordered by item order then key.
 */
fun localDeletedKeys(
    itemOrderJson: String,
    knownKeys: Collection<String>,
    hiddenItems: Set<String>,
): List<String> {
    val known = knownKeys.toSet()
    val ordered = mergeItemOrder(itemOrderJson, knownKeys).filter { it in hiddenItems && it in known }
    val missing = hiddenItems.filter { it in known && it !in ordered.toSet() }
    return ordered + missing
}
