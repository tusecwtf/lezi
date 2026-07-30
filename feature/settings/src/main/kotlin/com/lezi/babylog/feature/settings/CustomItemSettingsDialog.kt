package com.lezi.babylog.feature.settings

import androidx.compose.runtime.Composable
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.ui.CustomItemManageDialog
import com.lezi.babylog.core.ui.CustomItemManageMode
import com.lezi.babylog.core.ui.CustomItemManageRow
import com.lezi.babylog.domain.CustomRecordItem
import kotlinx.coroutines.CancellationException

/** Settings entry: full ACL + local-hide + reorder on the shared manage surface. */
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
    canManage: (CustomRecordItem) -> Boolean = { true },
) {
    val byId = items.associateBy { it.id }
    CustomItemManageDialog(
        items = items.map { it.toManageRow() },
        mode = CustomItemManageMode.Settings,
        hiddenItems = hiddenItems,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onUpdate = { row, done ->
            val base = byId[row.id] ?: return@CustomItemManageDialog done("项目不存在")
            onUpdate(base.copy(name = row.name, iconSlot = row.iconSlot), done)
        },
        onMove = onMove,
        onDelete = onDelete,
        onToggleLocalHidden = onToggleLocalHidden,
        canManage = { row -> byId[row.id]?.let(canManage) ?: false },
    )
}

internal suspend fun executeCustomItemDelete(
    itemId: Long,
    deleteById: suspend (Long) -> Unit,
): String? {
    try {
        deleteById(itemId)
        return null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        return productUiError(error, "删除失败，请重试")
    }
}

private fun CustomRecordItem.toManageRow(): CustomItemManageRow =
    CustomItemManageRow(id = id, name = name, iconSlot = iconSlot)
