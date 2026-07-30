package com.lezi.babylog.feature.log

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.lezi.babylog.core.ui.RecordSection

internal data class LayoutAlternativeAction(
    val label: String,
    val intent: LayoutEditIntent,
)

/** One dispatch seam shared by TalkBack custom actions and hardware-key chords. */
internal fun Modifier.layoutAlternativeInput(
    actions: List<LayoutAlternativeAction>,
    keyIntent: (KeyEvent) -> LayoutEditIntent?,
    onIntent: (LayoutEditIntent) -> Unit,
): Modifier = this
    .onPreviewKeyEvent { event ->
        val intent = keyIntent(event) ?: return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> {
                onIntent(intent)
                true
            }
            else -> false
        }
    }
    .focusable()
    .semantics(mergeDescendants = true) {
        customActions = actions.map { action ->
            CustomAccessibilityAction(action.label) {
                onIntent(action.intent)
                true
            }
        }
    }

internal fun catalogActions(
    catalogKey: String,
    visibleIndex: Int,
    visibleCount: Int,
): List<LayoutAlternativeAction> = buildList {
    repeat(QuickDockVisualSpec.configurableSlotCount) { slotIndex ->
        add(
            LayoutAlternativeAction(
                label = "设为常用槽${slotIndex + 1}",
                intent = LayoutEditIntent.AssignToSlot(slotIndex, catalogKey),
            ),
        )
    }
    if (visibleIndex > 0) {
        add(
            LayoutAlternativeAction(
                "在本类别前移",
                LayoutEditIntent.MoveItemInSection(catalogKey, -1),
            ),
        )
    }
    if (visibleIndex in 0 until (visibleCount - 1)) {
        add(
            LayoutAlternativeAction(
                "在本类别后移",
                LayoutEditIntent.MoveItemInSection(catalogKey, 1),
            ),
        )
    }
    add(
        LayoutAlternativeAction(
            "仅在本机隐藏",
            LayoutEditIntent.MoveToLocalDeleted(catalogKey),
        ),
    )
}

internal fun catalogKeyIntent(
    catalogKey: String,
    visibleIndex: Int,
    visibleCount: Int,
    event: KeyEvent,
): LayoutEditIntent? {
    if (event.key == Key.Delete) return LayoutEditIntent.MoveToLocalDeleted(catalogKey)
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> if (visibleIndex > 0) {
            LayoutEditIntent.MoveItemInSection(catalogKey, -1)
        } else {
            null
        }
        Key.DirectionRight -> if (visibleIndex in 0 until (visibleCount - 1)) {
            LayoutEditIntent.MoveItemInSection(catalogKey, 1)
        } else {
            null
        }
        Key.One, Key.NumPad1 -> LayoutEditIntent.AssignToSlot(0, catalogKey)
        Key.Two, Key.NumPad2 -> LayoutEditIntent.AssignToSlot(1, catalogKey)
        Key.Three, Key.NumPad3 -> LayoutEditIntent.AssignToSlot(2, catalogKey)
        Key.Four, Key.NumPad4 -> LayoutEditIntent.AssignToSlot(3, catalogKey)
        else -> null
    }
}

internal fun categoryActions(
    section: RecordSection,
    index: Int,
    count: Int,
): List<LayoutAlternativeAction> = buildList {
    if (index > 0) {
        add(LayoutAlternativeAction("分类前移", LayoutEditIntent.MoveCategory(section, -1)))
    }
    if (index in 0 until (count - 1)) {
        add(LayoutAlternativeAction("分类后移", LayoutEditIntent.MoveCategory(section, 1)))
    }
}

internal fun categoryKeyIntent(
    section: RecordSection,
    index: Int,
    count: Int,
    event: KeyEvent,
): LayoutEditIntent? {
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionUp -> if (index > 0) {
            LayoutEditIntent.MoveCategory(section, -1)
        } else {
            null
        }
        Key.DirectionDown -> if (index in 0 until (count - 1)) {
            LayoutEditIntent.MoveCategory(section, 1)
        } else {
            null
        }
        else -> null
    }
}

internal fun slotActions(index: Int, key: String): List<LayoutAlternativeAction> = buildList {
    if (index > 0) {
        add(LayoutAlternativeAction("向左移动", LayoutEditIntent.SwapSlots(index, index - 1)))
    }
    if (index < QuickDockVisualSpec.configurableSlotCount - 1) {
        add(LayoutAlternativeAction("向右移动", LayoutEditIntent.SwapSlots(index, index + 1)))
    }
    add(LayoutAlternativeAction("清空常用槽", LayoutEditIntent.ClearSlot(index)))
    add(
        LayoutAlternativeAction(
            "仅在本机隐藏该项目",
            LayoutEditIntent.MoveToLocalDeleted(key),
        ),
    )
}

internal fun slotKeyIntent(index: Int, event: KeyEvent): LayoutEditIntent? {
    if (event.key == Key.Delete) return LayoutEditIntent.ClearSlot(index)
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> (index - 1).takeIf { it >= 0 }
            ?.let { LayoutEditIntent.SwapSlots(index, it) }
        Key.DirectionRight -> (index + 1)
            .takeIf { it < QuickDockVisualSpec.configurableSlotCount }
            ?.let { LayoutEditIntent.SwapSlots(index, it) }
        else -> null
    }
}
