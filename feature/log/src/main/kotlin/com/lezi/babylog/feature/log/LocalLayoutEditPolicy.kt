package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.addToLocalDeleted
import com.lezi.babylog.core.ui.appendCatalogKeyToSectionEnd
import com.lezi.babylog.core.ui.editVisibleSections
import com.lezi.babylog.core.ui.localDeletedKeys
import com.lezi.babylog.core.ui.moveCatalogKeyWithinSection
import com.lezi.babylog.core.ui.moveCategoryOrder
import com.lezi.babylog.core.ui.removeFromLocalDeleted
import com.lezi.babylog.core.ui.visibleKeysInSection

/**
 * Device-local layout prefs mutated by 布局编辑态 intents.
 * Pure data — UI persists via SettingsStore after each reduce.
 */
internal data class DeviceLayoutPrefs(
    val quickRecordSlots: List<String>,
    val hiddenItems: Set<String>,
    val itemOrderJson: String,
    val categoryOrderJson: String,
)

internal sealed interface LayoutEditIntent {
    data class AssignToSlot(val slotIndex: Int, val catalogKey: String) : LayoutEditIntent
    data class SwapSlots(val fromIndex: Int, val toIndex: Int) : LayoutEditIntent
    data class ClearSlot(val slotIndex: Int) : LayoutEditIntent
    data class MoveToLocalDeleted(val catalogKey: String) : LayoutEditIntent
    data class RestoreFromLocalDeleted(val catalogKey: String) : LayoutEditIntent
    data class MoveItemInSection(val catalogKey: String, val delta: Int) : LayoutEditIntent
    data class MoveCategory(val section: RecordSection, val delta: Int) : LayoutEditIntent
}

/**
 * Apply one layout-edit intent. [knownKeys] must include built-ins + concrete customs.
 */
internal fun reduceLayoutEdit(
    prefs: DeviceLayoutPrefs,
    intent: LayoutEditIntent,
    knownKeys: Collection<String>,
): DeviceLayoutPrefs {
    return when (intent) {
        is LayoutEditIntent.AssignToSlot -> prefs.copy(
            quickRecordSlots = assignQuickRecordSlot(
                prefs.quickRecordSlots,
                intent.slotIndex,
                intent.catalogKey,
            ),
        )
        is LayoutEditIntent.SwapSlots -> prefs.copy(
            quickRecordSlots = swapQuickRecordSlots(
                prefs.quickRecordSlots,
                intent.fromIndex,
                intent.toIndex,
            ),
        )
        is LayoutEditIntent.ClearSlot -> prefs.copy(
            quickRecordSlots = clearQuickRecordSlot(
                prefs.quickRecordSlots,
                intent.slotIndex,
            ),
        )
        is LayoutEditIntent.MoveToLocalDeleted -> {
            val key = intent.catalogKey.trim()
            if (key.isEmpty()) prefs
            else {
                val clearedSlots = normalizeStoredQuickSlots(prefs.quickRecordSlots).map { slot ->
                    if (slot == key) "" else slot
                }
                prefs.copy(
                    quickRecordSlots = clearedSlots,
                    hiddenItems = addToLocalDeleted(prefs.hiddenItems, key),
                )
            }
        }
        is LayoutEditIntent.RestoreFromLocalDeleted -> {
            val key = intent.catalogKey.trim()
            if (key.isEmpty()) prefs
            else {
                prefs.copy(
                    hiddenItems = removeFromLocalDeleted(prefs.hiddenItems, key),
                    itemOrderJson = appendCatalogKeyToSectionEnd(
                        itemOrderJson = prefs.itemOrderJson,
                        catalogKey = key,
                        allKnownKeys = knownKeys,
                    ),
                )
            }
        }
        is LayoutEditIntent.MoveItemInSection -> prefs.copy(
            itemOrderJson = moveCatalogKeyWithinSection(
                itemOrderJson = prefs.itemOrderJson,
                catalogKey = intent.catalogKey,
                delta = intent.delta,
                allKnownKeys = knownKeys,
            ),
        )
        is LayoutEditIntent.MoveCategory -> prefs.copy(
            categoryOrderJson = moveCategoryOrder(
                categoryOrderJson = prefs.categoryOrderJson,
                section = intent.section,
                delta = intent.delta,
            ),
        )
    }
}

/** Snapshot helpers for edit canvas (pure). */
internal fun layoutEditVisibleSections(
    prefs: DeviceLayoutPrefs,
    knownKeys: Collection<String>,
): List<RecordSection> =
    editVisibleSections(
        categoryOrderJson = prefs.categoryOrderJson,
        itemOrderJson = prefs.itemOrderJson,
        knownKeys = knownKeys,
        hiddenItems = prefs.hiddenItems,
    )

internal fun layoutEditVisibleKeys(
    prefs: DeviceLayoutPrefs,
    section: RecordSection,
    knownKeys: Collection<String>,
): List<String> =
    visibleKeysInSection(
        section = section,
        itemOrderJson = prefs.itemOrderJson,
        knownKeys = knownKeys,
        hiddenItems = prefs.hiddenItems,
    )

internal fun layoutEditDeletedKeys(
    prefs: DeviceLayoutPrefs,
    knownKeys: Collection<String>,
): List<String> =
    localDeletedKeys(
        itemOrderJson = prefs.itemOrderJson,
        knownKeys = knownKeys,
        hiddenItems = prefs.hiddenItems,
    )

internal fun layoutEditSlotCount(): Int = QUICK_RECORD_SLOT_COUNT
