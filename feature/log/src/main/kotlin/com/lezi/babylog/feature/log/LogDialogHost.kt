package com.lezi.babylog.feature.log
import androidx.compose.foundation.layout.Row
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.sync.localRecordPublishDetail
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

internal data class PublishChromeTarget(
    val recordId: Long,
    val title: String,
    val publicationState: RootPublicationState,
)

/** List-level delete confirmation target (swipe path; Composer not opened first). */
internal sealed interface ListDeleteTarget {
    data class Plan(val plan: CarePlan) : ListDeleteTarget
    data class RecordItem(val record: Record) : ListDeleteTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogDialogHost(
    showCustomManage: Boolean,
    customItems: List<CustomRecordItem>,
    onDismissCustomManage: () -> Unit,
    onAddCustomItem: (String, Int, (String?) -> Unit) -> Unit,
    onUpdateCustomItem: (CustomRecordItem, (String?) -> Unit) -> Unit,
    onDeleteCustomItem: (Long, (String?) -> Unit) -> Unit,
    inLayoutEdit: Boolean,
    layoutFailure: DeviceLayoutWriteState.Failed?,
    failedLayoutUndo: LayoutUndoState.RestoreFailed?,
    dismissedLayoutFailure: Long?,
    layoutExitInProgress: Boolean,
    onDismissLayoutFailure: () -> Unit,
    onRetryLayoutFailure: () -> Unit,
    publishChromeRecord: PublishChromeTarget?,
    lastSyncFailed: Boolean,
    onDismissPublishChrome: () -> Unit,
    onRetryPublishChrome: () -> Unit,
    onEditPublishChrome: (Long) -> Unit,
    listDeleteTarget: ListDeleteTarget?,
    onDismissListDelete: () -> Unit,
    onDeleteCarePlan: (Long, (Result<String>) -> Unit) -> Unit,
    onDeleteRecord: (Long, (Result<String>) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    showMore: Boolean,
    settings: SettingsLocal,
    onDismissMore: () -> Unit,
    onPickMore: (RecordItemIdentity) -> Unit,
    onLongPressMore: () -> Unit,
) {
    if (showCustomManage) {
        LayoutCustomManageDialog(
            items = customItems,
            onDismiss = onDismissCustomManage,
            onAdd = onAddCustomItem,
            onUpdate = onUpdateCustomItem,
            onDelete = onDeleteCustomItem,
        )
    }

    if (
        inLayoutEdit &&
        layoutFailure != null &&
        layoutFailure.sequence != dismissedLayoutFailure
    ) {
        LeziAlertDialog(
            onDismissRequest = onDismissLayoutFailure,
            title = {
                Text(if (failedLayoutUndo != null) "撤销未完成" else "布局尚未保存")
            },
            text = {
                Text(
                    if (failedLayoutUndo != null) {
                        "撤销布局保存失败，当前布局保持不变。可重试撤销，或继续编辑。"
                    } else {
                        "上一项布局更改保存失败。当前页面仍保留更改，可重试后再退出。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !layoutExitInProgress,
                    onClick = onRetryLayoutFailure,
                ) { Text(if (layoutExitInProgress) "重试中…" else "重试") }
            },
            dismissButton = {
                TextButton(
                    enabled = !layoutExitInProgress,
                    onClick = onDismissLayoutFailure,
                ) { Text("继续编辑") }
            },
        )
    }

    publishChromeRecord?.let { target ->
        LeziAlertDialog(
            onDismissRequest = onDismissPublishChrome,
            title = { Text(target.title) },
            text = {
                Text(
                    localRecordPublishDetail(
                        lastSyncFailed = lastSyncFailed,
                        publicationState = target.publicationState,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onRetryPublishChrome) { Text("重试同步") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { onEditPublishChrome(target.recordId) },
                    ) { Text("编辑") }
                    TextButton(onClick = onDismissPublishChrome) { Text("关闭") }
                }
            },
        )
    }

    listDeleteTarget?.let { target ->
        var deleteActionState by remember(target) {
            mutableStateOf<ManagementActionState>(ManagementActionState.Idle)
        }
        val planConfirmation = (target as? ListDeleteTarget.Plan)?.let {
            carePlanDeleteConfirmation(it.plan)
        }
        val deleteRequest = when (target) {
            is ListDeleteTarget.Plan -> ManagementActionRequest(
                ManagementActionKind.DeletePlan,
                target.plan.id,
            )
            is ListDeleteTarget.RecordItem -> ManagementActionRequest(
                ManagementActionKind.DeleteRecord,
                target.record.id,
            )
        }
        val deleteRunning = deleteActionState == ManagementActionState.Running(deleteRequest)
        val deleteFeedback = managementActionFeedback(deleteActionState, deleteRequest)
        LeziAlertDialog(
            onDismissRequest = {
                if (!deleteRunning) onDismissListDelete()
            },
            title = {
                Text(planConfirmation?.title ?: RECORD_DELETE_TITLE)
            },
            text = {
                Text(
                    deleteConfirmationMessage(
                        impact = planConfirmation?.message ?: RECORD_DELETE_IMPACT,
                        error = deleteFeedback,
                    ),
                    modifier = Modifier
                        .testTag("list_delete_feedback")
                        .semantics {
                            if (deleteFeedback != null) {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = deleteFeedback
                            }
                        },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteRunning,
                    onClick = {
                        val started = beginManagementAction(deleteActionState, deleteRequest)
                        deleteActionState = started.state
                        if (!started.accepted) return@TextButton

                        fun finishDelete(result: Result<String>) {
                            val finished = finishManagementAction(
                                state = deleteActionState,
                                request = deleteRequest,
                                result = result,
                            )
                            if (!finished.accepted) return
                            deleteActionState = finished.state
                            if (result.isSuccess) {
                                onDismissListDelete()
                                finished.announcement?.let(onMessage)
                            }
                        }
                        when (target) {
                            is ListDeleteTarget.Plan -> onDeleteCarePlan(
                                target.plan.id,
                                ::finishDelete,
                            )
                            is ListDeleteTarget.RecordItem -> onDeleteRecord(
                                target.record.id,
                                ::finishDelete,
                            )
                        }
                    },
                ) {
                    Text(
                        if (deleteRunning) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteRunning,
                    onClick = onDismissListDelete,
                ) { Text("取消") }
            },
        )
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = onDismissMore,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            MoreSheet(
                settings = settings,
                customItems = customItems,
                onPick = onPickMore,
                onLongPressItem = onLongPressMore,
            )
        }
    }
}
