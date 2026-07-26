package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.domain.CustomRecordItem
import kotlinx.coroutines.CancellationException

internal data class CustomItemDeleteState(
    val target: CustomRecordItem? = null,
    val deleting: Boolean = false,
    val error: String? = null,
)

internal sealed interface CustomItemDeleteAction {
    data class Request(val item: CustomRecordItem) : CustomItemDeleteAction
    data object Cancel : CustomItemDeleteAction
    data object Confirm : CustomItemDeleteAction
    data class Finished(val error: String?) : CustomItemDeleteAction
}

internal data class CustomItemDeleteCommand(val itemId: Long)

internal data class CustomItemDeleteTransition(
    val state: CustomItemDeleteState,
    val command: CustomItemDeleteCommand? = null,
)

internal fun reduceCustomItemDelete(
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

internal data class CustomItemDeleteConfirmation(
    val title: String,
    val message: String,
)

internal fun customItemDeleteConfirmation(item: CustomRecordItem): CustomItemDeleteConfirmation =
    CustomItemDeleteConfirmation(
        title = "删除共享项目「${item.name.trim()}」？",
        message = "确认后，这个项目会从家庭共享项目中移除，所有家人之后都不能再用它新建记录或护理计划；" +
            "已有记录与计划仍保留原名称和图标。若只想在本机不显示，请关闭「本机显示」。" +
            "删除后无法撤销。",
    )

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
