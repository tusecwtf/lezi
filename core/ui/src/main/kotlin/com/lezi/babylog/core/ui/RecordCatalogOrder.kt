package com.lezi.babylog.core.ui

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Device-local catalog layout policy shared by “所有记录项目” and the more-sheet.
 *
 * - [categoryOrderJson]: ordered section storage keys (see [RecordSection.storageKey])
 * - [itemOrderJson]: ordered catalog keys including built-in type keys and `custom:{id}`
 *
 * Items never cross categories. Hide/show keeps keys in the order list so re-enable
 * restores the previous position.
 *
 * JSON helpers are pure-Kotlin (no org.json) so JVM unit tests and Android share one path.
 */
fun parseJsonStringArray(json: String): List<String> {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(trimmed).jsonArray.mapNotNull { element ->
            val primitive = element.jsonPrimitive
            require(primitive.isString) { "Catalog order values must be JSON strings" }
            primitive.content.trim().takeIf { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())
}

fun encodeJsonStringArray(items: List<String>): String =
    buildJsonArray { items.forEach { add(JsonPrimitive(it)) } }.toString()

val RecordSection.storageKey: String
    get() = when (this) {
        RecordSection.Feeding -> "feeding"
        RecordSection.Excretion -> "excretion"
        RecordSection.Routine -> "routine"
        RecordSection.Health -> "health"
        RecordSection.Growth -> "growth"
        RecordSection.Custom -> "custom"
    }

fun recordSectionFromStorageKey(raw: String): RecordSection? =
    RecordSection.entries.firstOrNull {
        it.storageKey == raw.lowercase() || it.name.equals(raw, ignoreCase = true)
    }

/** Default category order when preference is empty/missing. */
fun defaultCategoryOrder(): List<RecordSection> = RecordSection.entries.toList()

/**
 * Resolve category display order. Unknown keys are dropped; missing sections are
 * appended in enum order so upgrades that add sections stay complete.
 */
fun orderedRecordSections(categoryOrderJson: String): List<RecordSection> {
    val configured = parseJsonStringArray(categoryOrderJson)
        .mapNotNull { recordSectionFromStorageKey(it) }
    val missing = RecordSection.entries.filterNot { it in configured }
    return (configured + missing).distinct()
}

fun encodeCategoryOrder(sections: List<RecordSection>): String =
    encodeJsonStringArray(sections.map { it.storageKey })

/**
 * Move a category by [delta] (−1 up / +1 down). No-op when out of bounds.
 * Returns the new [categoryOrderJson].
 */
fun moveCategoryOrder(
    categoryOrderJson: String,
    section: RecordSection,
    delta: Int,
): String {
    val order = orderedRecordSections(categoryOrderJson).toMutableList()
    val from = order.indexOf(section)
    if (from < 0) return encodeCategoryOrder(order)
    val to = from + delta
    if (to !in order.indices) return encodeCategoryOrder(order)
    val moved = order.removeAt(from)
    order.add(to, moved)
    return encodeCategoryOrder(order)
}

/**
 * All catalog keys known for layout (built-ins available for new entry + concrete customs).
 * Includes currently hidden keys so order survives disable/re-enable.
 */
fun knownCatalogKeys(
    customItemIds: Collection<Long>,
    builtIns: List<RecordType> = RecordType.availableForNewEntry(),
): List<String> {
    val builtInKeys = builtIns.map { it.key }
    val customKeys = customItemIds
        .filter { it > 0L }
        .distinct()
        .map { RecordItemIdentity.customCatalogKey(it) }
    return builtInKeys + customKeys
}

/**
 * Merge stored order with known keys:
 * - keep relative order of known keys that appear in storage
 * - drop retired/unknown keys that are not in [knownKeys]
 * - append newly known keys not yet present (stable tail)
 * - preserve keys that are only hidden (still in knownKeys)
 */
fun mergeItemOrder(
    itemOrderJson: String,
    knownKeys: Collection<String>,
): List<String> {
    val known = knownKeys.toSet()
    val configured = parseJsonStringArray(itemOrderJson).filter { it in known }
    val missing = knownKeys.filterNot { it in configured.toSet() }
    return (configured + missing).distinct()
}

fun encodeItemOrder(keys: List<String>): String = encodeJsonStringArray(keys)

fun catalogSectionForKey(catalogKey: String): RecordSection? {
    val identity = RecordItemIdentity.parseCatalogKey(catalogKey) ?: return null
    return when (identity) {
        is RecordItemIdentity.BuiltIn -> identity.type.presentation.section
        is RecordItemIdentity.Custom -> RecordSection.Custom
    }
}

/**
 * Keys belonging to [section], ordered by [itemOrderJson] policy.
 * Includes hidden keys when they are still in [knownKeysForSection].
 */
fun orderedKeysInSection(
    section: RecordSection,
    itemOrderJson: String,
    knownKeysForSection: Collection<String>,
): List<String> {
    val known = knownKeysForSection.filter { catalogSectionForKey(it) == section }.toSet()
    val configured = parseJsonStringArray(itemOrderJson).filter { it in known }
    val missing = known.filterNot { it in configured.toSet() }
    return (configured + missing).distinct()
}

/**
 * Move a catalog key within its section only by relative [delta] (−1 / +1 …).
 * Cross-section moves are rejected (order unchanged).
 */
fun moveCatalogKeyWithinSection(
    itemOrderJson: String,
    catalogKey: String,
    delta: Int,
    allKnownKeys: Collection<String>,
): String {
    val section = catalogSectionForKey(catalogKey)
        ?: return encodeItemOrder(mergeItemOrder(itemOrderJson, allKnownKeys))
    val full = mergeItemOrder(itemOrderJson, allKnownKeys)
    val sectionKeys = orderedKeysInSection(section, encodeItemOrder(full), allKnownKeys)
    val from = sectionKeys.indexOf(catalogKey)
    if (from < 0) return encodeItemOrder(full)
    val to = from + delta
    if (to !in sectionKeys.indices) return encodeItemOrder(full)
    return moveCatalogKeyToIndexInSection(itemOrderJson, catalogKey, to, allKnownKeys)
}

/**
 * Move [catalogKey] to absolute [toIndex] within its section only.
 * [toIndex] is clamped to the section list; cross-section / unknown keys are no-ops.
 * Returns new item-order JSON covering all [allKnownKeys].
 */
fun moveCatalogKeyToIndexInSection(
    itemOrderJson: String,
    catalogKey: String,
    toIndex: Int,
    allKnownKeys: Collection<String>,
): String {
    val section = catalogSectionForKey(catalogKey)
        ?: return encodeItemOrder(mergeItemOrder(itemOrderJson, allKnownKeys))
    val full = mergeItemOrder(itemOrderJson, allKnownKeys).toMutableList()
    val sectionKeys = orderedKeysInSection(section, encodeItemOrder(full), allKnownKeys).toMutableList()
    val from = sectionKeys.indexOf(catalogKey)
    if (from < 0) return encodeItemOrder(full)
    val target = toIndex.coerceIn(0, sectionKeys.lastIndex.coerceAtLeast(0))
    if (from == target) return encodeItemOrder(full)
    val moved = sectionKeys.removeAt(from)
    sectionKeys.add(target, moved)

    // Rebuild full order: rewrite only this section's subsequence in place.
    val sectionKeySet = sectionKeys.toSet()
    val result = mutableListOf<String>()
    var sectionWritten = false
    for (key in full) {
        if (key in sectionKeySet) {
            if (!sectionWritten) {
                result += sectionKeys
                sectionWritten = true
            }
        } else {
            result += key
        }
    }
    if (!sectionWritten) result += sectionKeys
    for (key in allKnownKeys) {
        if (key !in result) result += key
    }
    return encodeItemOrder(result)
}

/**
 * Sort catalog entries for the more-sheet / settings lists.
 * Section order comes from [categoryOrderJson]; within a section from [itemOrderJson].
 */
fun <T> sortCatalogByLocalOrder(
    entries: List<T>,
    sectionOf: (T) -> RecordSection,
    catalogKeyOf: (T) -> String,
    categoryOrderJson: String,
    itemOrderJson: String,
): List<T> {
    val sectionRank = orderedRecordSections(categoryOrderJson)
        .withIndex()
        .associate { it.value to it.index }
    val itemRank = parseJsonStringArray(itemOrderJson)
        .withIndex()
        .associate { it.value to it.index }
    return entries.sortedWith(
        compareBy<T> { sectionRank[sectionOf(it)] ?: Int.MAX_VALUE }
            .thenBy { itemRank[catalogKeyOf(it)] ?: Int.MAX_VALUE }
            .thenBy { catalogKeyOf(it) },
    )
}
