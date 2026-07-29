package com.lezi.babylog.feature.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem

private val CustomIcons = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

/**
 * Lightweight custom-definition manage surface for 布局编辑态.
 * Family delete is explicit; distinct from 本机已删除 hide.
 */
@Composable
internal fun LayoutCustomManageDialog(
    items: List<CustomRecordItem>,
    onDismiss: () -> Unit,
    onAdd: (String, Int, (String?) -> Unit) -> Unit,
    onUpdate: (CustomRecordItem, (String?) -> Unit) -> Unit,
    onDelete: (Long, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var iconSlot by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CustomRecordItem?>(null) }
    var pendingDelete by remember { mutableStateOf<CustomRecordItem?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理自定义项目") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    "此处新增/改名/删除为家庭共享定义；本机隐藏请在布局编辑态拖入「本机已删除」。",
                    style = LeziTypography.Meta,
                )
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${CustomIcons[item.iconSlot.coerceIn(0, 7)]} ${item.name}",
                            style = LeziTypography.Body,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            editing = item
                            name = item.name
                            iconSlot = item.iconSlot
                        }) { Text("改") }
                        TextButton(onClick = { pendingDelete = item }) { Text("删") }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (editing == null) "新项目名称" else "名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("layout_custom_name"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CustomIcons.forEachIndexed { idx, glyph ->
                        TextButton(onClick = { iconSlot = idx }) {
                            Text(if (idx == iconSlot) "[$glyph]" else glyph)
                        }
                    }
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) {
                            error = "请输入名称"
                            return@TextButton
                        }
                        val current = editing
                        if (current == null) {
                            onAdd(trimmed, iconSlot) { msg ->
                                error = msg
                                if (msg == null) {
                                    name = ""
                                    iconSlot = 0
                                }
                            }
                        } else {
                            onUpdate(current.copy(name = trimmed, iconSlot = iconSlot)) { msg ->
                                error = msg
                                if (msg == null) {
                                    editing = null
                                    name = ""
                                    iconSlot = 0
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("layout_custom_save"),
                ) {
                    Text(if (editing == null) "新增" else "保存改名")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除自定义项目？") },
            text = {
                Text(
                    "确认后将从家庭共享项目中移除「${item.name}」，家人不能再用它新建；" +
                        "已有记录仍保留名称快照。若只想本机不显示，请用布局编辑的本机已删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item.id) { msg ->
                            error = msg
                            if (msg == null) pendingDelete = null
                        }
                    },
                ) { Text("删除定义") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
