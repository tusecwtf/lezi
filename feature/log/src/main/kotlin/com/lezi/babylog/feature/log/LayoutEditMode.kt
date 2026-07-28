package com.lezi.babylog.feature.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem
import kotlin.math.abs

/**
 * Full-screen 布局编辑态 canvas: 常用四槽 · 分类所有记录 · 本机已删除.
 * Short-press does not write records; drag intents call [onIntent].
 */
@Composable
internal fun LayoutEditModeDialog(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
) {
    val known = remember(customItems) { knownCatalogKeys(customItems.map { it.id }) }
    val labels = remember(customItems) {
        buildMap {
            RecordType.availableForNewEntry().forEach { put(it.key, it.presentation.label) }
            customItems.forEach {
                put(RecordItemIdentity.customCatalogKey(it.id), it.name)
            }
        }
    }
    val slots = remember(prefs.quickRecordSlots) {
        normalizeStoredQuickSlots(prefs.quickRecordSlots)
    }
    val sections = remember(prefs, known) { layoutEditVisibleSections(prefs, known) }
    val deleted = remember(prefs, known) { layoutEditDeletedKeys(prefs, known) }

    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }

    fun dropAt(windowPos: Offset, sourceKey: String, sourceIsDeleted: Boolean) {
        val inTrash = trashBounds?.contains(windowPos) == true
        if (inTrash && !sourceIsDeleted) {
            onIntent(LayoutEditIntent.MoveToLocalDeleted(sourceKey))
            return
        }
        if (!inTrash && sourceIsDeleted) {
            // Dropping deleted item anywhere above trash restores.
            if (trashBounds?.contains(windowPos) != true) {
                onIntent(LayoutEditIntent.RestoreFromLocalDeleted(sourceKey))
            }
            return
        }
        val hitSlot = slotBounds.entries
            .filter { it.value.contains(windowPos) }
            .minByOrNull { abs(it.value.center.x - windowPos.x) }
            ?.key
        if (hitSlot != null && !sourceIsDeleted) {
            onIntent(LayoutEditIntent.AssignToSlot(hitSlot, sourceKey))
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("layout_edit_mode"),
        title = {
            Text("编辑布局", style = LeziTypography.TitleSm)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    "长按拖到常用槽可替换；拖到底部本机已删除可隐藏；完成退出。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("常用记录", style = LeziTypography.Label)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    slots.forEachIndexed { index, key ->
                        val label = if (key.isBlank()) "空" else labels[key] ?: key
                        Column(
                            Modifier
                                .weight(1f)
                                .onGloballyPositioned { coords ->
                                    slotBounds[index] = coords.boundsInWindow()
                                }
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(4.dp)
                                .testTag("layout_edit_slot_$index"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                style = LeziTypography.Label,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (key.isNotBlank()) {
                                            Modifier.draggableCatalogKey(
                                                catalogKey = key,
                                                onDragStart = {
                                                    dragKey = key
                                                    dragPointer = it
                                                },
                                                onDrag = { dragPointer = it },
                                                onDragEnd = { pos ->
                                                    // Slot-to-slot: hit another slot → swap/assign
                                                    val other = slotBounds.entries
                                                        .firstOrNull { (i, rect) ->
                                                            i != index && rect.contains(pos)
                                                        }
                                                        ?.key
                                                    if (other != null) {
                                                        onIntent(
                                                            LayoutEditIntent.SwapSlots(index, other),
                                                        )
                                                    } else {
                                                        dropAt(pos, key, sourceIsDeleted = false)
                                                    }
                                                    dragKey = null
                                                    dragPointer = null
                                                },
                                                onDragCancel = {
                                                    dragKey = null
                                                    dragPointer = null
                                                },
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                            if (key.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        onIntent(LayoutEditIntent.ClearSlot(index))
                                    },
                                    modifier = Modifier.testTag("layout_edit_slot_clear_$index"),
                                ) {
                                    Text("×", style = LeziTypography.BodyStrong)
                                }
                            }
                        }
                    }
                }

                Text("所有记录", style = LeziTypography.Label)
                sections.forEach { section ->
                    val keys = layoutEditVisibleKeys(prefs, section, known)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp)
                            .testTag("layout_edit_section_${section.storageKey}"),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(section.title, style = LeziTypography.BodyStrong)
                            Row {
                                TextButton(
                                    onClick = {
                                        onIntent(LayoutEditIntent.MoveCategory(section, -1))
                                    },
                                ) { Text("↑类") }
                                TextButton(
                                    onClick = {
                                        onIntent(LayoutEditIntent.MoveCategory(section, 1))
                                    },
                                ) { Text("↓类") }
                            }
                        }
                        keys.forEachIndexed { idx, key ->
                            val label = labels[key] ?: key
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .draggableCatalogKey(
                                        catalogKey = key,
                                        onDragStart = {
                                            dragKey = key
                                            dragPointer = it
                                        },
                                        onDrag = { dragPointer = it },
                                        onDragEnd = { pos ->
                                            dropAt(pos, key, sourceIsDeleted = false)
                                            // Within-section reorder by vertical delta vs neighbors
                                            // if drop missed zones: small vertical moves via buttons below
                                            dragKey = null
                                            dragPointer = null
                                        },
                                        onDragCancel = {
                                            dragKey = null
                                            dragPointer = null
                                        },
                                    )
                                    .testTag("layout_edit_item_$key"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, Modifier.weight(1f), style = LeziTypography.Body)
                                TextButton(
                                    enabled = idx > 0,
                                    onClick = {
                                        onIntent(LayoutEditIntent.MoveItemInSection(key, -1))
                                    },
                                ) { Text("↑") }
                                TextButton(
                                    enabled = idx < keys.lastIndex,
                                    onClick = {
                                        onIntent(LayoutEditIntent.MoveItemInSection(key, 1))
                                    },
                                ) { Text("↓") }
                            }
                        }
                        if (section == RecordSection.Custom) {
                            TextButton(
                                onClick = onOpenCustomManage,
                                modifier = Modifier.testTag("layout_edit_custom_manage"),
                            ) {
                                Text("管理自定义项目（新增/改名/删除）")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Text("本机已删除", style = LeziTypography.Label)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .onGloballyPositioned { trashBounds = it.boundsInWindow() }
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp)
                        .testTag("layout_edit_local_deleted"),
                ) {
                    if (deleted.isEmpty()) {
                        Text(
                            "拖到此处隐藏 · 可再拖回",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        deleted.forEach { key ->
                            val label = labels[key] ?: key
                            Text(
                                label,
                                style = LeziTypography.Body,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .draggableCatalogKey(
                                        catalogKey = key,
                                        onDragStart = {
                                            dragKey = key
                                            dragPointer = it
                                        },
                                        onDrag = { dragPointer = it },
                                        onDragEnd = { pos ->
                                            dropAt(pos, key, sourceIsDeleted = true)
                                            dragKey = null
                                            dragPointer = null
                                        },
                                        onDragCancel = {
                                            dragKey = null
                                            dragPointer = null
                                        },
                                    )
                                    .testTag("layout_edit_deleted_$key"),
                            )
                        }
                    }
                }
                dragKey?.let { key ->
                    Text(
                        "拖动中：${labels[key] ?: key}",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("layout_edit_done"),
            ) { Text("完成") }
        },
    )
}

private fun Modifier.draggableCatalogKey(
    catalogKey: String,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
): Modifier {
    val originHolder = floatArrayOf(0f, 0f)
    return this
        .onGloballyPositioned { coords ->
            val p = coords.positionInWindow()
            originHolder[0] = p.x
            originHolder[1] = p.y
        }
        .pointerInput(catalogKey) {
            var lastWindow = Offset.Zero
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    lastWindow = Offset(originHolder[0], originHolder[1]) + local
                    onDragStart(lastWindow)
                },
                onDrag = { change, _ ->
                    change.consume()
                    lastWindow = Offset(originHolder[0], originHolder[1]) + change.position
                    onDrag(lastWindow)
                },
                onDragEnd = { onDragEnd(lastWindow) },
                onDragCancel = onDragCancel,
            )
        }
}

/**
 * Resolve drop using window coordinates. Call sites pass window offsets when available.
 * For local-only last pointer, [resolveLayoutDrop] maps slot index by horizontal fraction.
 */
internal fun resolveLayoutDrop(
    pointerWindow: Offset?,
    slotBounds: Map<Int, Rect>,
    trashBounds: Rect?,
    sourceKey: String,
    sourceIsDeleted: Boolean,
): LayoutEditIntent? {
    val pos = pointerWindow ?: return null
    if (trashBounds?.contains(pos) == true && !sourceIsDeleted) {
        return LayoutEditIntent.MoveToLocalDeleted(sourceKey)
    }
    if (sourceIsDeleted && trashBounds?.contains(pos) != true) {
        return LayoutEditIntent.RestoreFromLocalDeleted(sourceKey)
    }
    val hit = slotBounds.entries
        .filter { it.value.contains(pos) }
        .minByOrNull { abs(it.value.center.x - pos.x) }
        ?.key
    if (hit != null && !sourceIsDeleted) {
        return LayoutEditIntent.AssignToSlot(hit, sourceKey)
    }
    return null
}
