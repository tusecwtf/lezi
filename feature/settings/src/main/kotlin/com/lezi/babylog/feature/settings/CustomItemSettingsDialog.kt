package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

private val CustomItemIcons = listOf("★", "♥", "☀", "☾", "♪", "●", "▲", "◆")

/** Copy for shared definitions: local close is not family delete. */
internal fun customItemLocalHideHint(manageable: Boolean, locallyHidden: Boolean): String =
    when {
        manageable && locallyHidden -> "本机已关闭 · 家庭定义仍在，删才会 tombstone"
        manageable -> "本机显示 · 删会 tombstone 共享定义"
        locallyHidden -> "本机已关闭 · 不等于删除家庭定义"
        else -> "由其他成员创建 · 可本机关闭，不删除共享定义"
    }

@Composable
internal fun CustomItemSettingsDialog(
    items: List<CustomRecordItem>,
    hiddenItems: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onAdd: (String, Int, (String?) -> Unit) -> Unit,
    onUpdate: (CustomRecordItem, (String?) -> Unit) -> Unit,
    onMove: (Long, Int) -> Unit,
    onDelete: (Long, (String?) -> Unit) -> Unit,
    onToggleLocalHidden: (Long) -> Unit = {},
    /** When false, edit/move/delete controls stay hidden (shared definition ACL). */
    canManage: (CustomRecordItem) -> Boolean = { true },
) {
    var editing by remember { mutableStateOf<CustomRecordItem?>(null) }
    var name by remember { mutableStateOf("") }
    var iconSlot by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reset() {
        editing = null
        name = ""
        iconSlot = 0
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("自定义项目（${items.size}/10）") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    "「本机显示」只影响本机目录与快捷坞；关闭不等于删除家庭共享定义。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.forEachIndexed { index, item ->
                    val manageable = canManage(item)
                    val catalogKey = RecordItemIdentity.customCatalogKey(item.id)
                    val locallyHidden = catalogKey in hiddenItems
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${CustomItemIcons[item.iconSlot.coerceIn(0, 7)]} ${item.name}",
                                    style = LeziTypography.BodyStrong,
                                )
                                Text(
                                    customItemLocalHideHint(manageable, locallyHidden),
                                    style = LeziTypography.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (manageable) {
                                Row {
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = { onMove(item.id, -1) },
                                    ) { Text("↑") }
                                    TextButton(
                                        enabled = index < items.lastIndex,
                                        onClick = { onMove(item.id, 1) },
                                    ) { Text("↓") }
                                    TextButton(
                                        onClick = {
                                            editing = item
                                            name = item.name
                                            iconSlot = item.iconSlot
                                            error = null
                                        },
                                    ) { Text("改") }
                                    TextButton(
                                        onClick = {
                                            onDelete(item.id) { message ->
                                                if (message != null) error = message
                                            }
                                        },
                                    ) {
                                        Text("删", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (locallyHidden) "本机显示：关" else "本机显示：开",
                                style = LeziTypography.Label,
                            )
                            Switch(
                                checked = !locallyHidden,
                                onCheckedChange = { onToggleLocalHidden(item.id) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(20)
                        error = null
                    },
                    label = { Text(if (editing == null) "新项目名称" else "修改名称") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("固定图标槽", style = LeziTypography.Label)
                CustomItemIcons.chunked(4).forEachIndexed { rowIndex, icons ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        icons.forEachIndexed { columnIndex, icon ->
                            val slot = rowIndex * 4 + columnIndex
                            FilterChip(
                                selected = iconSlot == slot,
                                onClick = { iconSlot = slot },
                                label = { Text(icon) },
                            )
                        }
                    }
                }
                TextButton(
                    enabled = name.isNotBlank() && (editing != null || items.size < 10),
                    onClick = {
                        val current = editing
                        if (current == null) {
                            onAdd(name, iconSlot) { message ->
                                if (message == null) reset() else error = message
                            }
                        } else {
                            onUpdate(
                                current.copy(name = name, iconSlot = iconSlot),
                            ) { message ->
                                if (message == null) reset() else error = message
                            }
                        }
                    },
                ) {
                    Text(if (editing == null) "添加项目" else "保存修改")
                }
                if (editing != null) {
                    TextButton(onClick = { reset() }) { Text("取消修改") }
                }
                Text(
                    "删除项目不会改写历史记录；历史标题与图标快照仍保留。本机关闭只改本机偏好，家庭定义仍在。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
