package com.lezi.babylog.core.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography

/** Minimal row for the shared custom-definition manage surface. */
data class CustomItemManageRow(
    val id: Long,
    val name: String,
    val iconSlot: Int,
    val clientUuid: String = "",
)

/**
 * Where the manage dialog is opened from.
 * Settings exposes local-hide + reorder + ACL; layout edit is definition CRUD only.
 */
enum class CustomItemManageMode {
    /** 记录设置：本机显示开关、排序、家庭 ACL. */
    Settings,

    /** 布局编辑态：轻量新增/改名/删除家庭定义（隐藏走本机已删除）. */
    LayoutEdit,
}

fun customItemDialogScopeGuidance(mode: CustomItemManageMode): String = when (mode) {
    CustomItemManageMode.Settings ->
        "「本机显示」只影响本机目录与快捷坞；关闭不等于删除家庭共享定义。"
    CustomItemManageMode.LayoutEdit ->
        "此处新增/改名/删除为家庭共享定义；本机隐藏请在布局编辑态拖入「本机已删除」。"
}

fun customItemLocalHideHint(manageable: Boolean, locallyHidden: Boolean): String =
    when {
        manageable && locallyHidden -> "本机已关闭 · 家庭共享项目仍保留"
        manageable -> "本机显示 · 删除会同步移除家庭共享项目"
        locallyHidden -> "本机已关闭 · 不等于删除家庭定义"
        else -> "由其他成员创建 · 可本机关闭，不删除共享定义"
    }

data class CustomItemDeleteState(
    val target: CustomItemManageRow? = null,
    val deleting: Boolean = false,
    val error: String? = null,
)

sealed interface CustomItemDeleteAction {
    data class Request(val item: CustomItemManageRow) : CustomItemDeleteAction
    data object Cancel : CustomItemDeleteAction
    data object Confirm : CustomItemDeleteAction
    data class Finished(val error: String?) : CustomItemDeleteAction
}

data class CustomItemDeleteCommand(val itemId: Long)

data class CustomItemDeleteTransition(
    val state: CustomItemDeleteState,
    val command: CustomItemDeleteCommand? = null,
)

fun reduceCustomItemDelete(
    state: CustomItemDeleteState,
    action: CustomItemDeleteAction,
): CustomItemDeleteTransition = when (action) {
    is CustomItemDeleteAction.Request -> CustomItemDeleteTransition(
        state.copy(target = action.item, deleting = false, error = null),
    )
    CustomItemDeleteAction.Cancel ->
        if (state.deleting) {
            CustomItemDeleteTransition(state)
        } else {
            CustomItemDeleteTransition(CustomItemDeleteState())
        }
    CustomItemDeleteAction.Confirm -> {
        val target = state.target
        if (target == null || state.deleting) {
            CustomItemDeleteTransition(state)
        } else {
            CustomItemDeleteTransition(
                state = state.copy(deleting = true, error = null),
                command = CustomItemDeleteCommand(target.id),
            )
        }
    }
    is CustomItemDeleteAction.Finished ->
        if (action.error == null) {
            CustomItemDeleteTransition(CustomItemDeleteState())
        } else {
            CustomItemDeleteTransition(
                state.copy(deleting = false, error = action.error),
            )
        }
}

data class CustomItemDeleteConfirmation(
    val title: String,
    val message: String,
)

fun customItemDeleteConfirmation(
    item: CustomItemManageRow,
    mode: CustomItemManageMode,
): CustomItemDeleteConfirmation {
    val name = item.name.trim()
    return when (mode) {
        CustomItemManageMode.Settings -> CustomItemDeleteConfirmation(
            title = "删除共享项目「$name」？",
            message = "确认后，这个项目会从家庭共享项目中移除，所有家人之后都不能再用它新建记录或护理计划；" +
                "已有记录与计划仍保留原名称和图标。若只想在本机不显示，请关闭「本机显示」。" +
                "删除后无法撤销。",
        )
        CustomItemManageMode.LayoutEdit -> CustomItemDeleteConfirmation(
            title = "删除自定义项目？",
            message = "确认后将从家庭共享项目中移除「$name」，家人不能再用它新建；" +
                "已有记录仍保留名称快照。若只想本机不显示，请用布局编辑的本机已删除。",
        )
    }
}

/**
 * Single custom-definition manage surface for settings and layout edit.
 * [mode] toggles local-hide / reorder / ACL chrome; CRUD + delete confirm stay shared.
 */
@Composable
fun CustomItemManageDialog(
    items: List<CustomItemManageRow>,
    mode: CustomItemManageMode,
    onDismiss: () -> Unit,
    onAdd: (String, Int, (String?) -> Unit) -> Unit,
    onUpdate: (CustomItemManageRow, (String?) -> Unit) -> Unit,
    onDelete: (Long, (String?) -> Unit) -> Unit,
    hiddenItems: Set<String> = emptySet(),
    saveBusyLabel: String? = null,
    layoutBusy: Boolean = false,
    onMove: (Long, Int) -> Unit = { _, _ -> },
    onToggleLocalHidden: (Long) -> Unit = {},
    canManage: (CustomItemManageRow) -> Boolean = { true },
) {
    val itemsById = items.associateBy(CustomItemManageRow::id)
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = editingId?.let(itemsById::get)
    var name by rememberSaveable { mutableStateOf("") }
    var iconSlot by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteState by remember { mutableStateOf(CustomItemDeleteState()) }
    val externalBusy = saveBusyLabel != null || layoutBusy
    val showLocalHide = mode == CustomItemManageMode.Settings
    val showReorder = mode == CustomItemManageMode.Settings
    val nameFieldTag = when (mode) {
        CustomItemManageMode.LayoutEdit -> "layout_custom_name"
        CustomItemManageMode.Settings -> "custom_item_name"
    }
    val saveFieldTag = when (mode) {
        CustomItemManageMode.LayoutEdit -> "layout_custom_save"
        CustomItemManageMode.Settings -> "custom_item_save"
    }

    fun reset() {
        editingId = null
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
            if (!deleteState.deleting && !externalBusy) onDismiss()
        },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = {
            Text(
                when (mode) {
                    CustomItemManageMode.Settings -> "自定义项目（${items.size}/10）"
                    CustomItemManageMode.LayoutEdit -> "管理自定义项目"
                },
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = if (mode == CustomItemManageMode.Settings) 560.dp else 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                Text(
                    customItemDialogScopeGuidance(mode),
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.forEachIndexed { index, item ->
                    val manageable = canManage(item)
                    val catalogKey = RecordItemIdentity.custom(
                        item.id,
                        item.clientUuid,
                    ).catalogKey
                    val locallyHidden = catalogKey in hiddenItems
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${customItemIconGlyph(item.iconSlot)} ${item.name}",
                                    style = if (mode == CustomItemManageMode.Settings) {
                                        LeziTypography.BodyStrong
                                    } else {
                                        LeziTypography.Body
                                    },
                                )
                                if (showLocalHide) {
                                    Text(
                                        customItemLocalHideHint(manageable, locallyHidden),
                                        style = LeziTypography.Meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (manageable) {
                                Row {
                                    if (showReorder) {
                                        TextButton(
                                            enabled = !externalBusy && index > 0,
                                            onClick = { onMove(item.id, -1) },
                                        ) { Text("↑") }
                                        TextButton(
                                            enabled = !externalBusy && index < items.lastIndex,
                                            onClick = { onMove(item.id, 1) },
                                        ) { Text("↓") }
                                    }
                                    TextButton(
                                        enabled = !externalBusy,
                                        onClick = {
                                            editingId = item.id
                                            name = item.name
                                            iconSlot = item.iconSlot
                                            error = null
                                        },
                                    ) { Text("改") }
                                    TextButton(
                                        enabled = !externalBusy,
                                        onClick = {
                                            dispatchDelete(CustomItemDeleteAction.Request(item))
                                        },
                                        modifier = Modifier.testTag("custom_item_delete_${item.id}"),
                                    ) {
                                        Text(
                                            "删",
                                            color = if (mode == CustomItemManageMode.Settings) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (showLocalHide) {
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
                                    enabled = !externalBusy,
                                    checked = !locallyHidden,
                                    onCheckedChange = { onToggleLocalHidden(item.id) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    enabled = !externalBusy,
                    value = name,
                    onValueChange = {
                        name = it.take(20)
                        error = null
                    },
                    label = {
                        Text(
                            when {
                                editing == null && mode == CustomItemManageMode.LayoutEdit ->
                                    "新项目名称"
                                editing == null -> "新项目名称"
                                mode == CustomItemManageMode.LayoutEdit -> "名称"
                                else -> "修改名称"
                            },
                        )
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(nameFieldTag),
                )
                Text("选择图标", style = LeziTypography.Label)
                CUSTOM_ITEM_ICON_GLYPHS.chunked(4).forEachIndexed { rowIndex, icons ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        icons.forEachIndexed { columnIndex, icon ->
                            val slot = rowIndex * 4 + columnIndex
                            FilterChip(
                                enabled = !externalBusy,
                                selected = iconSlot == slot,
                                onClick = { iconSlot = slot },
                                label = { Text(icon) },
                            )
                        }
                    }
                }
                TextButton(
                    enabled = !externalBusy &&
                        name.isNotBlank() &&
                        (editing != null || items.size < 10),
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) {
                            error = "请输入名称"
                            return@TextButton
                        }
                        val current = editing
                        if (current == null) {
                            onAdd(trimmed, iconSlot) { message ->
                                if (message == null) reset() else error = message
                            }
                        } else {
                            onUpdate(
                                current.copy(name = trimmed, iconSlot = iconSlot),
                            ) { message ->
                                if (message == null) reset() else error = message
                            }
                        }
                    },
                    modifier = Modifier.testTag(saveFieldTag),
                ) {
                    Text(
                        when {
                            saveBusyLabel != null -> saveBusyLabel
                            editing == null && mode == CustomItemManageMode.LayoutEdit -> "新增"
                            editing == null -> "添加项目"
                            mode == CustomItemManageMode.LayoutEdit -> "保存改名"
                            else -> "保存修改"
                        },
                    )
                }
                if (editing != null && mode == CustomItemManageMode.Settings) {
                    TextButton(
                        enabled = !externalBusy,
                        onClick = { reset() },
                    ) { Text("取消修改") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleteState.deleting && !externalBusy,
                onClick = onDismiss,
            ) { Text("完成") }
        },
    )

    deleteState.target?.let { target ->
        val confirmation = customItemDeleteConfirmation(target, mode)
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
                        if (deleteState.deleting) {
                            "删除中…"
                        } else if (mode == CustomItemManageMode.LayoutEdit) {
                            "删除定义"
                        } else {
                            "确认删除"
                        },
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
