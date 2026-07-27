package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DEFAULT_QUICK_RECORD_SLOTS
import com.lezi.babylog.core.model.QUICK_RECORD_SLOT_COUNT
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.model.isAvailableForNewEntry
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.domain.CustomRecordItem

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

    /** Empty or unresolved slot — keeps its position and opens slot settings. */
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
            visualLabel = "选择",
            contentDescription = "＋ 选择常用记录",
        )
        QuickDockCell.More -> QuickDockPresentation(
            visualLabel = "更多",
            contentDescription = "更多记录",
        )
    }

/** User intent emitted by a quick-dock cell; keeps empty slots actionable. */
internal sealed interface QuickDockAction {
    data class OpenComposer(val identity: RecordItemIdentity) : QuickDockAction

    data object OpenSlotSettings : QuickDockAction

    data object OpenMore : QuickDockAction
}

internal fun QuickDockCell.toAction(): QuickDockAction =
    when (this) {
        is QuickDockCell.Bound -> QuickDockAction.OpenComposer(identity)
        QuickDockCell.Empty -> QuickDockAction.OpenSlotSettings
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
 * Candidate for binding a quick slot (enabled built-ins + concrete customs).
 */
internal data class QuickSlotCandidate(
    val identity: RecordItemIdentity,
    val label: String,
    val catalogKey: String = identity.catalogKey,
)

/**
 * Normalize stored slot keys to exactly [QUICK_RECORD_SLOT_COUNT] entries.
 * Empty strings are kept as intentional blanks.
 */
internal fun normalizeStoredQuickSlots(slots: List<String>): List<String> {
    val padded = slots.map { it.trim() }.toMutableList()
    while (padded.size < QUICK_RECORD_SLOT_COUNT) padded += ""
    return padded.take(QUICK_RECORD_SLOT_COUNT)
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
                identity = identity,
                label = item.name,
                catalogKey = identity.catalogKey,
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
    normalizeStoredQuickSlots(storedSlots).map { key ->
        resolveQuickSlot(key, hiddenItems, customItems)
    }

/**
 * Lay out four slot cells + fixed More for the preferred thumb edge.
 * Slots stay in user order; the block as a whole sits toward the preferred hand,
 * with More on the far side.
 *
 * Left hand: [slot0, slot1, slot2, slot3, More]
 * Right hand: [More, slot0, slot1, slot2, slot3] — More far (left), slots near right thumb.
 *
 * Product: "四槽整体靠偏好手一侧，更多位于远侧". For right hand the four slots
 * should be nearest the right edge, so More is first (left/far). For left hand
 * slots are left-aligned and More is last (right/far).
 */
internal fun oneHandQuickDockOrder(
    preferredHand: String,
    slots: List<QuickDockCell>,
): List<QuickDockCell> {
    val four = slots.take(QUICK_RECORD_SLOT_COUNT).let { list ->
        if (list.size >= QUICK_RECORD_SLOT_COUNT) list
        else list + List(QUICK_RECORD_SLOT_COUNT - list.size) { QuickDockCell.Empty }
    }
    return if (preferredHand == "left") {
        four + QuickDockCell.More
    } else {
        listOf(QuickDockCell.More) + four
    }
}

/** Enabled built-ins and concrete custom definitions available as slot candidates. */
internal fun quickSlotCandidates(
    hiddenItems: Set<String>,
    customItems: List<CustomRecordItem>,
): List<QuickSlotCandidate> {
    val builtIns = RecordType.availableForNewEntry()
        .filter { it.key !in hiddenItems }
        .map {
            QuickSlotCandidate(
                identity = RecordItemIdentity.builtIn(it),
                label = it.presentation.label,
            )
        }
    val customs = customItems
        .filter { RecordItemIdentity.customCatalogKey(it.id) !in hiddenItems }
        .map {
            QuickSlotCandidate(
                identity = RecordItemIdentity.custom(it.id),
                label = it.name,
            )
        }
    return builtIns + customs
}

/**
 * Swap two slot indices (settings interim reorder without full drag).
 * Indices outside 0..3 are ignored.
 */
internal fun swapQuickRecordSlots(
    slots: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String> {
    val normalized = normalizeStoredQuickSlots(slots).toMutableList()
    if (fromIndex !in normalized.indices || toIndex !in normalized.indices) return normalized
    if (fromIndex == toIndex) return normalized
    val tmp = normalized[fromIndex]
    normalized[fromIndex] = normalized[toIndex]
    normalized[toIndex] = tmp
    return normalized
}

/** Default value for first-run / missing preference. */
internal fun defaultQuickRecordSlots(): List<String> = DEFAULT_QUICK_RECORD_SLOTS
