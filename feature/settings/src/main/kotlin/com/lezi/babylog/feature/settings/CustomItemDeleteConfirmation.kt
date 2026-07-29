package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.ui.CustomItemManageMode
import com.lezi.babylog.core.ui.CustomItemManageRow
import com.lezi.babylog.domain.CustomRecordItem

/**
 * Feature adapters over [com.lezi.babylog.core.ui] custom-item delete reduce.
 * Tests and settings call sites keep using [CustomRecordItem]; the shipped reduce
 * runs on [CustomItemManageRow] in core.ui.
 */

internal typealias CustomItemDeleteState = com.lezi.babylog.core.ui.CustomItemDeleteState
internal typealias CustomItemDeleteCommand = com.lezi.babylog.core.ui.CustomItemDeleteCommand
internal typealias CustomItemDeleteTransition = com.lezi.babylog.core.ui.CustomItemDeleteTransition
internal typealias CustomItemDeleteConfirmation = com.lezi.babylog.core.ui.CustomItemDeleteConfirmation

internal sealed interface CustomItemDeleteAction {
    data class Request(val item: CustomRecordItem) : CustomItemDeleteAction
    data object Cancel : CustomItemDeleteAction
    data object Confirm : CustomItemDeleteAction
    data class Finished(val error: String?) : CustomItemDeleteAction
}

internal fun reduceCustomItemDelete(
    state: CustomItemDeleteState,
    action: CustomItemDeleteAction,
): CustomItemDeleteTransition {
    val coreAction = when (action) {
        is CustomItemDeleteAction.Request ->
            com.lezi.babylog.core.ui.CustomItemDeleteAction.Request(action.item.toManageRow())
        CustomItemDeleteAction.Cancel ->
            com.lezi.babylog.core.ui.CustomItemDeleteAction.Cancel
        CustomItemDeleteAction.Confirm ->
            com.lezi.babylog.core.ui.CustomItemDeleteAction.Confirm
        is CustomItemDeleteAction.Finished ->
            com.lezi.babylog.core.ui.CustomItemDeleteAction.Finished(action.error)
    }
    return com.lezi.babylog.core.ui.reduceCustomItemDelete(state, coreAction)
}

internal fun customItemDeleteConfirmation(item: CustomRecordItem): CustomItemDeleteConfirmation =
    com.lezi.babylog.core.ui.customItemDeleteConfirmation(
        item.toManageRow(),
        CustomItemManageMode.Settings,
    )

private fun CustomRecordItem.toManageRow(): CustomItemManageRow =
    CustomItemManageRow(id = id, name = name, iconSlot = iconSlot)
