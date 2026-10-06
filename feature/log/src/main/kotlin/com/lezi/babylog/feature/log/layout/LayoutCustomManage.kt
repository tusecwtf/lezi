package com.lezi.babylog.feature.log.layout
import androidx.compose.runtime.Composable
import com.lezi.babylog.core.ui.CustomItemManageDialog
import com.lezi.babylog.core.ui.CustomItemManageMode
import com.lezi.babylog.core.ui.CustomItemManageRow
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Layout-edit entry point for the **shared** custom-definition manage surface.
 * Mode [CustomItemManageMode.LayoutEdit] hides local-hide/reorder; hide is 本机已删除.
 */
@Composable
internal fun LayoutCustomManageDialog(
    items: List<CustomRecordItem>,
    onDismiss: () -> Unit,
    onAdd: (String, Int, (String?) -> Unit) -> Unit,
    onUpdate: (CustomRecordItem, (String?) -> Unit) -> Unit,
    onDelete: (Long, (String?) -> Unit) -> Unit,
) {
    val byId = items.associateBy { it.id }
    CustomItemManageDialog(
        items = items.map {
            CustomItemManageRow(it.id, it.name, it.iconSlot, it.clientUuid)
        },
        mode = CustomItemManageMode.LayoutEdit,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onUpdate = { row, done ->
            val base = byId[row.id] ?: return@CustomItemManageDialog done("项目不存在")
            onUpdate(base.copy(name = row.name, iconSlot = row.iconSlot), done)
        },
        onDelete = onDelete,
        canManage = { true },
    )
}
