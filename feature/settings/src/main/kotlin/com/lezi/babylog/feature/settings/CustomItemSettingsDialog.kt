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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

private val CustomItemIcons = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

internal fun customItemDialogScopeGuidance(): String =
    "「本机显示」只影响本机目录与快捷坞；关闭不等于删除家庭共享定义。"

/** Copy for shared definitions: local close is not family delete. */
internal fun customItemLocalHideHint(manageable: Boolean, locallyHidden: Boolean): String =
    when {
        manageable && locallyHidden -> "本机已关闭 · 家庭共享项目仍保留"
        manageable -> "本机显示 · 删除会同步移除家庭共享项目"
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
    var deleteState by remember { mutableStateOf(CustomItemDeleteState()) }

    fun reset() {
        editing = null
        name = ""
        iconSlot = 0
        error = null
    }

    fun dispatchDelete(action: CustomItemDeleteAction) {
        val transition = reduceCustomItemDelete(deleteState, action)
        deleteState = transition.state
        transition.command?.let { command ->
            onDelete(command.itemId) { message ->
                deleteState = reduceCustomItemDelete(
                    deleteState,
                    CustomItemDeleteAction.Finished(message),
                ).state
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!deleteState.deleting) onDismiss()
        },
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
                    customItemDialogScopeGuidance(),
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
                                            dispatchDelete(CustomItemDeleteAction.Request(item))
                                        },
                                        modifier = Modifier.testTag("custom_item_delete_${item.id}"),
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
                Text("选择图标", style = LeziTypography.Label)
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleteState.deleting,
                onClick = onDismiss,
            ) { Text("完成") }
        },
    )

    deleteState.target?.let { target ->
        val confirmation = customItemDeleteConfirmation(target)
        AlertDialog(
            onDismissRequest = {
                dispatchDelete(CustomItemDeleteAction.Cancel)
            },
            title = { Text(confirmation.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text(confirmation.message)
                    deleteState.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteState.deleting,
                    onClick = {
                        dispatchDelete(CustomItemDeleteAction.Confirm)
                    },
                    modifier = Modifier.testTag("custom_item_delete_confirm"),
                ) {
                    Text(
                        if (deleteState.deleting) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteState.deleting,
                    onClick = {
                        dispatchDelete(CustomItemDeleteAction.Cancel)
                    },
                ) { Text("取消") }
            },
        )
    }
}
