package com.lezi.gf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.app.ui.theme.LeziMotion
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.CareService
import com.lezi.gf.care.RecordType
import com.lezi.gf.kernel.GfResult
import kotlin.math.roundToInt

/**
 * Full-screen layout editor: reorder 4 dock slots, hide types, custom defs.
 * Spec 02 E6: long-press drag reorder (up/down still available as a11y).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutEditorScreen(
    care: CareService,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    reduceMotion: Boolean = false,
) {
    var refresh by remember { mutableStateOf(0) }
    var newTitle by remember { mutableStateOf("") }
    var renameUuid by remember { mutableStateOf<String?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }
    val animMs = LeziMotion.millis(reduceMotion, LeziMotion.Emphasized)

    @Suppress("UNUSED_EXPRESSION", "UNUSED_VARIABLE")
    val _anim = animMs
    @Suppress("UNUSED_EXPRESSION")
    refresh
    val layout = care.store().layout
    val customs = care.liveCustomDefs()
    val slots = DockModel.resolveSlots(layout, customs)

    Surface(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "布局编辑全屏" },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("编辑常用布局", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Text(
                "四槽坞绝对左→右；「更多」锁定不在坞内。长按拖动手柄重排，或点上/下。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            slots.forEach { slot ->
                val isDragging = draggingIndex == slot.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .offset {
                            IntOffset(0, if (isDragging) dragOffsetY.roundToInt() else 0)
                        }
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = if (isDragging) 0.7f else 0.4f,
                            ),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Drag handle
                    Box(
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "拖动手柄 ${slot.label}" }
                            .pointerInput(slot.index, slots.map { it.bindingKey }) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = slot.index
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        val from = draggingIndex
                                        if (from >= 0) {
                                            val steps = (dragOffsetY / rowHeightPx).roundToInt()
                                            val to = (from + steps).coerceIn(0, 3)
                                            if (to != from) {
                                                care.moveDockSlot(from, to)
                                                refresh++
                                                onChanged()
                                            }
                                        }
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("≡", style = MaterialTheme.typography.titleLarge)
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(
                                LeziTypeGlyph.accent(slot.bindingKey).copy(alpha = 0.2f),
                                RoundedCornerShape(20.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(LeziTypeGlyph.glyph(slot.bindingKey))
                    }
                    Text(
                        "${slot.index + 1}. ${slot.label}",
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(
                        enabled = slot.index > 0,
                        onClick = {
                            care.moveDockSlot(slot.index, slot.index - 1)
                            refresh++
                            onChanged()
                        },
                    ) { Text("上移") }
                    TextButton(
                        enabled = slot.index < 3,
                        onClick = {
                            care.moveDockSlot(slot.index, slot.index + 1)
                            refresh++
                            onChanged()
                        },
                    ) { Text("下移") }
                    TextButton(onClick = {
                        val list = DockModel.normalizeSlots(layout).toMutableList()
                        list[slot.index] = null
                        care.setDockSlots(list)
                        refresh++
                        onChanged()
                    }) { Text("清空") }
                }
            }
            Row {
                TextButton(
                    enabled = care.canUndoLayout(),
                    onClick = {
                        val restored = care.undoLayout()
                        message = if (restored != null) "已撤销一级布局" else "无可撤销"
                        refresh++
                        onChanged()
                    },
                ) { Text("单级撤销") }
            }

            Spacer(Modifier.height(16.dp))
            Text("绑定到空槽 / 替换", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordType.allBuiltin().take(12).forEach { t ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            val list = DockModel.normalizeSlots(layout).toMutableList()
                            val empty = list.indexOfFirst { it == null }
                            if (empty >= 0) {
                                list[empty] = t.key
                                care.setDockSlots(list)
                                refresh++
                                onChanged()
                            } else {
                                message = "坞已满，请先清空一槽"
                            }
                        },
                        label = { Text(t.chineseLabel) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("本机隐藏", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RecordType.allBuiltin().take(10).forEach { t ->
                    val hidden = t.key in layout.hiddenTypeKeys
                    FilterChip(
                        selected = hidden,
                        onClick = {
                            if (hidden) care.unhideType(t.key) else care.hideType(t.key)
                            refresh++
                            onChanged()
                        },
                        label = { Text(if (hidden) "显示${t.chineseLabel}" else "隐藏${t.chineseLabel}") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("自定义项目（≤10）", style = MaterialTheme.typography.titleMedium)
            customs.forEach { def ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(def.title)
                    Row {
                        TextButton(onClick = {
                            renameUuid = def.clientUuid
                            renameTitle = def.title
                        }) { Text("改名") }
                        TextButton(onClick = {
                            val list = DockModel.normalizeSlots(layout).toMutableList()
                            val empty = list.indexOfFirst { it == null }
                            if (empty >= 0) {
                                list[empty] = "custom:${def.clientUuid}"
                                care.setDockSlots(list)
                                refresh++
                                onChanged()
                            } else {
                                message = "坞已满"
                            }
                        }) { Text("入坞") }
                        TextButton(onClick = {
                            care.deleteCustomDef(def.clientUuid)
                            refresh++
                            onChanged()
                        }) { Text("删除") }
                    }
                }
            }
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("新自定义名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                when (val r = care.addCustomDef(newTitle)) {
                    is GfResult.Ok -> {
                        newTitle = ""
                        message = "已添加 ${r.value.title}"
                        refresh++
                        onChanged()
                    }
                    is GfResult.Err -> message = r.error.toString()
                }
            }) { Text("添加自定义") }

            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

            renameUuid?.let { uuid ->
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("新名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    care.renameCustomDef(uuid, renameTitle)
                    renameUuid = null
                    refresh++
                    onChanged()
                }) { Text("保存改名") }
            }
        }
    }
}

/** Dialog wrapper retained for menu deep-link compatibility. */
@Composable
fun LayoutCustomEditorDialog(
    care: CareService,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("布局与自定义项目") },
        text = {
            Text("请在记录页长按坞或点「布局」打开全屏编辑。")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
