package com.lezi.babylog.feature.log

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.Window
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lezi.babylog.core.model.NextFeedPlanOrigin
import com.lezi.babylog.designsystem.LeziNextFeedPlanFlow
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.nextFeedPlanSuccessMessage

internal const val RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED = true

@Composable
private fun RecordComposerModalBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(view, enabled) {
        val owner = view.findDialogWindow()?.callback as? OnBackPressedDispatcherOwner
        if (owner == null) {
            onDispose { }
        } else {
            val callback = object : OnBackPressedCallback(enabled) {
                override fun handleOnBackPressed() = latestOnBack()
            }
            owner.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }
}

private fun View.findDialogWindow(): Window? {
    var ancestor: Any? = this
    while (ancestor != null) {
        if (ancestor is DialogWindowProvider) {
            return ancestor.window
        }
        ancestor = (ancestor as? View)?.parent
    }
    return null
}

/**
 * Material3 owns a separate dialog window for a modal sheet. Its built-in back callback assumes
 * [onDismissRequest] always removes that window, so using the callback to open a discard prompt
 * leaves a retained sheet translated off-screen after predictive back. Keep system back inside the
 * dialog content tree and let the Composer's shared dismiss gate decide whether the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordComposerModalSheet(
    sheetState: SheetState,
    systemBackEnabled: Boolean,
    modalOverlayVisible: Boolean,
    onSystemBack: () -> Unit,
    onDismissRequest: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        RecordComposerModalBackHandler(enabled = systemBackEnabled, onBack = onSystemBack)
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (modalOverlayVisible) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                    ),
                content = content,
            )
            overlay()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordComposerHost(
    request: RecordComposerRequest?,
    onDismiss: () -> Unit,
    /** Consumes the restorable root request as soon as a database write succeeds. */
    onPersisted: () -> Unit,
    onSaved: (String) -> Unit,
    onStartNursingTimer: (note: String, amountMl: String, carePlanId: Long?, babyId: Long?) -> Unit,
    /** Optional: open device-local system calendar setup (ticket 21). Cancel still saves plan. */
    onConfigureSystemCalendar: (() -> Unit)? = null,
    vm: RecordComposerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember(request) { mutableStateOf(false) }
    var deleteAttempted by remember(request) { mutableStateOf(false) }
    /** Explicit convert confirm; cancel keeps the draft and original record untouched. */
    var confirmConvert by remember(request) { mutableStateOf(false) }
    var confirmDiscard by rememberRecordComposerDiscardPrompt(request)
    // These outlive request=null so a saved fact can finish its optional next-plan flow after
    // the restorable root request has already been consumed. rememberSaveable also preserves the
    // prompt across process recreation without ever reopening the persisted New request.
    var pendingSavedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSuggestedNextFeedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    fun finishSaved(message: String) {
        pendingSavedMessage = null
        pendingSuggestedNextFeedAt = null
        onSaved(message)
    }

    fun finishDismiss() {
        confirmDiscard = false
        vm.close()
        onDismiss()
    }

    fun requestDismiss(source: ComposerDismissSource) {
        when (
            decideRecordComposerDismiss(
                source = source,
                hasUserChanges = state.hasUserChanges,
                busy = state.saving || state.deleting,
            )
        ) {
            ComposerDismissDecision.DismissNow -> finishDismiss()
            ComposerDismissDecision.ConfirmDiscard -> confirmDiscard = true
            ComposerDismissDecision.IgnoreWhileBusy -> Unit
        }
    }
    val latestHasUserChanges by rememberUpdatedState(state.hasUserChanges)
    val latestBusy by rememberUpdatedState(state.saving || state.deleting)
    val sheetConfirmValueChange: (SheetValue) -> Boolean = remember(request) {
        { target ->
            if (target != SheetValue.Hidden) {
                true
            } else {
                when (
                    decideRecordComposerDismiss(
                        source = ComposerDismissSource.SheetDismiss,
                        hasUserChanges = latestHasUserChanges,
                        busy = latestBusy,
                    )
                ) {
                    ComposerDismissDecision.DismissNow -> true
                    ComposerDismissDecision.ConfirmDiscard -> {
                        confirmDiscard = true
                        false
                    }
                    ComposerDismissDecision.IgnoreWhileBusy -> false
                }
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = RECORD_COMPOSER_SKIP_PARTIALLY_EXPANDED,
        confirmValueChange = sheetConfirmValueChange,
    )

    LaunchedEffect(request) {
        if (request == null) {
            vm.close()
        } else {
            vm.open(request)
        }
    }

    if (request != null) {
        RecordComposerModalSheet(
            onDismissRequest = { requestDismiss(ComposerDismissSource.SheetDismiss) },
            sheetState = sheetState,
            systemBackEnabled = !confirmDelete && !confirmConvert,
            modalOverlayVisible = confirmDiscard,
            onSystemBack = {
                if (confirmDiscard) {
                    confirmDiscard = false
                } else {
                    requestDismiss(ComposerDismissSource.SystemBack)
                }
            },
            overlay = {
                if (confirmDiscard) {
                    RecordComposerDiscardDialog(
                        busy = state.saving || state.deleting,
                        onContinueEditing = { confirmDiscard = false },
                        onDiscard = ::finishDismiss,
                    )
                }
            },
        ) {
            val ready = state.activeRequest == request
            val draft = state.draft.takeIf { ready }
            when {
                !ready || state.loading -> ComposerState(
                    kind = StateKind.Loading,
                    title = "正在准备记录",
                    message = "请稍候…",
                )
                draft == null -> ComposerState(
                    kind = StateKind.Error,
                    title = "无法打开记录",
                    message = state.error ?: "记录加载失败",
                    actionLabel = "关闭",
                    onAction = {
                        vm.close()
                        onDismiss()
                    },
                )
                else -> QuickRecordSheet(
                    draft = draft,
                    interactionKey = request,
                    amountStepMl = state.amountStepMl,
                    timeStepMin = state.timeStepMin,
                    timePickerStyle = state.timePickerStyle,
                    preferredHand = state.preferredHand,
                    birthdayEpochDay = state.birthdayEpochDay,
                    infantFeverAdviceEnabled = state.infantFeverAdviceEnabled,
                    saving = state.saving,
                    deleting = state.deleting,
                    saveError = state.error,
                    canStartNursingTimer =
                        state.canStartNursingTimer,
                    systemCalendarConfigured = state.systemCalendarConfigured,
                    onConfigureSystemCalendar = onConfigureSystemCalendar,
                    onDraftChange = vm::updateDraft,
                    onDismiss = ::requestDismiss,
                    onDelete = if (draft.isEditing || draft.isEditingCarePlan) {
                        {
                            deleteAttempted = false
                            confirmDelete = true
                        }
                    } else {
                        null
                    },
                    onConfirm = {
                        // Convert is not ExplainedDisabled alone — require an explicit dialog.
                        if (draft.needsConvertToCarePlan()) {
                            confirmConvert = true
                        } else {
                            vm.save { message, suggestedNextFeedAt ->
                                val hasNotificationPermission =
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) == PackageManager.PERMISSION_GRANTED
                                val finishedMessage = carePlanSaveMessageWithPermission(
                                    baseMessage = message,
                                    notificationPermissionGranted = hasNotificationPermission,
                                    isCarePlanWrite = isCarePlanSaveMessage(message),
                                )
                                dispatchRecordSaveCompletion(
                                    message = finishedMessage,
                                    suggestedNextFeedAt = suggestedNextFeedAt,
                                    onOfferReminder = { savedMessage, suggestedAt ->
                                        pendingSavedMessage = savedMessage
                                        pendingSuggestedNextFeedAt = suggestedAt
                                    },
                                    onPersisted = onPersisted,
                                    onFinished = onSaved,
                                )
                            }
                        }
                    },
                    onStartNursingTimer = {
                        onStartNursingTimer(
                            draft.note,
                            draft.nursingAmountMl,
                            draft.carePlanId?.takeUnless { draft.editCarePlan },
                            state.babyId,
                        )
                    },
                    onImportPhotos = vm::importPhotos,
                    onRemovePhoto = vm::removePhoto,
                )
            }
        }
    }

    if (confirmDelete) {
        val planConfirmation = state.draft
            ?.takeIf { it.isEditingCarePlan }
            ?.let(::carePlanDeleteConfirmation)
        AlertDialog(
            onDismissRequest = {
                if (!state.deleting) {
                    deleteAttempted = false
                    confirmDelete = false
                }
            },
            title = {
                Text(planConfirmation?.title ?: RECORD_DELETE_TITLE)
            },
            text = {
                Text(
                    deleteConfirmationMessage(
                        impact = planConfirmation?.message ?: RECORD_DELETE_IMPACT,
                        error = state.error.takeIf { deleteAttempted },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = {
                        deleteAttempted = true
                        vm.delete { message ->
                            deleteAttempted = false
                            confirmDelete = false
                            onPersisted()
                            onSaved(message)
                        }
                    },
                ) {
                    Text(
                        if (state.deleting) "删除中…" else "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = {
                        deleteAttempted = false
                        confirmDelete = false
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (confirmConvert) {
        AlertDialog(
            onDismissRequest = {
                if (!state.saving) confirmConvert = false
            },
            title = { Text("转为护理计划？") },
            text = {
                Text(
                    "原记录会从时间轴和汇总中移除，字段、备注和照片会保存为待履行的护理计划。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = {
                        confirmConvert = false
                        vm.save { message, suggestedNextFeedAt ->
                            val hasNotificationPermission =
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                            val finishedMessage = carePlanSaveMessageWithPermission(
                                baseMessage = message,
                                notificationPermissionGranted = hasNotificationPermission,
                                isCarePlanWrite = isCarePlanSaveMessage(message),
                            )
                            dispatchRecordSaveCompletion(
                                message = finishedMessage,
                                suggestedNextFeedAt = suggestedNextFeedAt,
                                onOfferReminder = { savedMessage, suggestedAt ->
                                    pendingSavedMessage = savedMessage
                                    pendingSuggestedNextFeedAt = suggestedAt
                                },
                                onPersisted = onPersisted,
                                onFinished = onSaved,
                            )
                        }
                    },
                ) {
                    Text(if (state.saving) "保存中…" else "转为护理计划")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = { confirmConvert = false },
                ) {
                    Text("取消")
                }
            },
        )
    }

    val savedMessage = pendingSavedMessage
    val suggestedNextFeedAt = pendingSuggestedNextFeedAt
    if (savedMessage != null && suggestedNextFeedAt != null) {
        val notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val scheduledMessage = nextFeedPlanSuccessMessage(notificationPermissionGranted)
        LeziNextFeedPlanFlow(
            flowKey = "composer:$suggestedNextFeedAt",
            origin = NextFeedPlanOrigin.RecordComposer,
            factMessage = savedMessage,
            suggestedAtMillis = suggestedNextFeedAt,
            scheduledMessage = scheduledMessage,
            minuteStep = state.timeStepMin,
            timePickerStyle = state.timePickerStyle,
            preferredHand = state.preferredHand,
            onSchedule = vm::scheduleNextFeedPlan,
            onReconcile = vm::reconcileNextFeedPlan,
            onFinishedScheduled = {
                vm.dismissNextFeedPlan()
                finishSaved(scheduledMessage)
            },
            onFinishedWithoutPlan = {
                vm.dismissNextFeedPlan()
                finishSaved("$savedMessage；未安排下次喂养")
            },
        )
    }
}

@Composable
private fun ComposerState(
    kind: StateKind,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp)
            .padding(LeziSpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        StateContainer(
            kind = kind,
            title = title,
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}
