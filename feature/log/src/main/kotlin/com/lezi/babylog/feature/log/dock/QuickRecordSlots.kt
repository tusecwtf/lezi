package com.lezi.babylog.feature.log.dock
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.isAvailableForNewEntry
import com.lezi.babylog.core.model.normalizeQuickRecordSlots
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Resolved home quick-dock cell. Always four configurable cells + fixed "更多";
 * invalid / hidden / deleted refs blank the cell without auto-fill.
 */
internal sealed class QuickDockCell {
    data class Bound(
        val identity: RecordItemIdentity,
        val label: String,
        val catalogKey: String,
        val recordType: RecordType,
        val customIconSlot: Int? = null,
    ) : QuickDockCell()

    /** Empty or unresolved slot — keeps its position; short-press is a no-op. */
    data object Empty : QuickDockCell()

    data object More : QuickDockCell()
}

/** Compact visual copy plus an unabridged TalkBack label for one dock cell. */
internal data class QuickDockPresentation(
    val visualLabel: String,
    val contentDescription: String,
)

internal fun quickDockPresentation(
    cell: QuickDockCell,
    sleepRunning: Boolean,
): QuickDockPresentation =
    when (cell) {
        is QuickDockCell.Bound -> {
            if (cell.recordType == RecordType.SLEEP && sleepRunning) {
                QuickDockPresentation(
                    visualLabel = "醒来",
                    contentDescription = "睡眠，记录醒来",
                )
            } else {
                QuickDockPresentation(
                    visualLabel = cell.label,
                    contentDescription = "${cell.label}，打开记录面板",
                )
            }
        }
        QuickDockCell.Empty -> QuickDockPresentation(
            visualLabel = "空槽",
            contentDescription = "空槽，短按无操作；可使用编辑常用布局操作",
        )
        QuickDockCell.More -> QuickDockPresentation(
            visualLabel = "更多",
            contentDescription = "更多记录",
        )
    }

/** User intent emitted by a quick-dock cell in non-edit mode. */
internal sealed interface QuickDockAction {
    data class OpenComposer(val identity: RecordItemIdentity) : QuickDockAction

    /** Empty slot short-press: no navigation, no settings. */
    data object None : QuickDockAction

    data object OpenMore : QuickDockAction
}

internal fun QuickDockCell.toAction(): QuickDockAction =
    when (this) {
        is QuickDockCell.Bound -> QuickDockAction.OpenComposer(identity)
        QuickDockCell.Empty -> QuickDockAction.None
        QuickDockCell.More -> QuickDockAction.OpenMore
    }

/** Whether an otherwise idle slot should use a selected/emphasized container. */
internal fun quickDockIdleContainerIsEmphasized(cell: QuickDockCell): Boolean =
    when (cell) {
        is QuickDockCell.Bound,
        QuickDockCell.Empty,
        QuickDockCell.More,
        -> false
    }

/**
 * Resolve one stored catalog key against enabled built-ins and concrete custom items.
 * Does **not** auto-fill replacements when the ref is missing, hidden, or deleted.
 */
internal fun resolveQuickSlot(
    catalogKey: String?,
    hiddenItems: Set<String>,
    customItems: List<CustomRecordItem>,
): QuickDockCell {
    val key = catalogKey?.trim().orEmpty()
    if (key.isEmpty()) return QuickDockCell.Empty
    if (key in hiddenItems) return QuickDockCell.Empty

    val identity = RecordItemIdentity.parseCatalogKey(key) ?: return QuickDockCell.Empty
    return when (identity) {
        is RecordItemIdentity.BuiltIn -> {
            if (!identity.type.isAvailableForNewEntry) return QuickDockCell.Empty
            QuickDockCell.Bound(
                identity = identity,
                label = identity.type.presentation.label,
                catalogKey = identity.catalogKey,
                recordType = identity.type,
            )
        }
        is RecordItemIdentity.Custom -> {
            val item = customItems.firstOrNull { it.id == identity.customItemId }
                ?: return QuickDockCell.Empty
            // Live rename / icon follow the current definition.
            QuickDockCell.Bound(
                identity = RecordItemIdentity.custom(item.id, item.clientUuid),
                label = item.name,
                catalogKey = RecordItemIdentity.custom(item.id, item.clientUuid).catalogKey,
                recordType = RecordType.CUSTOM,
                customIconSlot = item.iconSlot,
            )
        }
        is RecordItemIdentity.FamilyCustom -> {
            val item = customItems.firstOrNull {
                it.clientUuid.equals(identity.clientUuid, ignoreCase = true)
            } ?: return QuickDockCell.Empty
            val resolved = RecordItemIdentity.custom(item.id, item.clientUuid)
            QuickDockCell.Bound(
                identity = resolved,
                label = item.name,
                catalogKey = resolved.catalogKey,
                recordType = RecordType.CUSTOM,
                customIconSlot = item.iconSlot,
            )
        }
    }
}

/**
 * Resolve the four home slots in stored order (not yet handedness-mirrored).
 */
internal fun resolveQuickSlots(
    storedSlots: List<String>,
    hiddenItems: Set<String>,
    customItems: List<CustomRecordItem>,
): List<QuickDockCell> =
    normalizeQuickRecordSlots(storedSlots).map { key ->
        resolveQuickSlot(key, hiddenItems, customItems)
    }

/** Fixed everyday dock: four slots then 更多. */
internal fun fixedQuickDockOrder(slots: List<QuickDockCell>): List<QuickDockCell> {
    val four = slots.take(QUICK_RECORD_SLOT_COUNT).let { list ->
        if (list.size >= QUICK_RECORD_SLOT_COUNT) list
        else list + List(QUICK_RECORD_SLOT_COUNT - list.size) { QuickDockCell.Empty }
    }
    return four + QuickDockCell.More
}

/**
 * Swap two slot indices in layout edit mode.
 * Indices outside 0..3 are ignored.
 */
internal fun swapQuickRecordSlots(
    slots: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String> {
    val normalized = normalizeQuickRecordSlots(slots).toMutableList()
    if (fromIndex !in normalized.indices || toIndex !in normalized.indices) return normalized
    if (fromIndex == toIndex) return normalized
    val tmp = normalized[fromIndex]
    normalized[fromIndex] = normalized[toIndex]
    normalized[toIndex] = tmp
    return normalized
}

/**
 * Point slot [index] at [catalogKey] (trimmed). Blank key is ignored — use
 * [clearQuickRecordSlot]. Out-of-range index is a no-op.
 *
 * Uniqueness: if [catalogKey] already occupies another slot, that slot and
 * [index] swap so the key appears once. Assigning the same key to its current
 * slot is a no-op. Overwriting a different key only drops that shortcut pointer
 * (does not move anything into 本机已删除).
 */
internal fun assignQuickRecordSlot(
    slots: List<String>,
    index: Int,
    catalogKey: String,
): List<String> {
    val normalized = normalizeQuickRecordSlots(slots).toMutableList()
    if (index !in normalized.indices) return normalized
    val key = catalogKey.trim()
    if (key.isEmpty()) return normalized
    val existing = normalized.indexOf(key)
    if (existing == index) return normalized
    if (existing >= 0) {
        val tmp = normalized[index]
        normalized[index] = normalized[existing]
        normalized[existing] = tmp
        return normalized
    }
    normalized[index] = key
    return normalized
}

/**
 * Clear the shortcut at [index] to an empty slot. Out-of-range index is a no-op.
 * Does not alter catalog order or 本机已删除 membership.
 */
internal fun clearQuickRecordSlot(
    slots: List<String>,
    index: Int,
): List<String> {
    val normalized = normalizeQuickRecordSlots(slots).toMutableList()
    if (index !in normalized.indices) return normalized
    normalized[index] = ""
    return normalized
}
