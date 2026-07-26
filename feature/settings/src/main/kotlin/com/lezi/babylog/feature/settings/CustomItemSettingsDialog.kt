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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

private val CustomItemIcons = listOf("★", "♥", "☀", "☾", "♪", "●", "▲", "◆")

@Composable
internal fun CustomItemSettingsDialog(
    items: List<CustomRecordItem>,
    onDismiss: () -> Unit,
    onAdd: (String, Int, (String?) -> Unit) -> Unit,
    onUpdate: (CustomRecordItem, (String?) -> Unit) -> Unit,
    onMove: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
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
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${CustomItemIcons[item.iconSlot.coerceIn(0, 7)]} ${item.name}",
                            style = LeziTypography.BodyStrong,
                        )
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
                            TextButton(onClick = { onDelete(item.id) }) {
                                Text("删", color = MaterialTheme.colorScheme.error)
                            }
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
                    "删除项目不会改写历史记录；历史标题与图标快照仍保留。",
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
