package com.lezi.babylog.feature.log.composer
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
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.lezi.babylog.core.model.TimerHandoffBuildResult
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.designsystem.LeziNextFeedPlanFlow
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.StateContainer
import com.lezi.babylog.designsystem.StateKind
import com.lezi.babylog.designsystem.nextFeedPlanSuccessMessage
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

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
    /**
     * Explicit Composer→Timer ownership handoff. Shell navigates with the seed
     * JSON; accept/reject settle via durable [acceptedTimerHandoffSeedJson] /
     * [timerHandoffRejectEpoch] so process death cannot drop release.
     */
    onStartNursingTimer: (TimerHandoffSession) -> Unit,
    /**
     * Durable shell signal: Timer accepted (or AlreadyAccepted after restore).
     * Host releases transferred owned paths without requiring process-local session.
     */
    acceptedTimerHandoffSeedJson: String? = null,
    onAcceptedTimerHandoffConsumed: () -> Unit = {},
    /**
     * Monotonic shell signal: Timer rejected or user left timer before accept.
     * Clears in-flight lock only; draft + owned files stay editable.
     */
    timerHandoffRejectEpoch: Int = 0,
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
    /** Untransferable dirty fields require explicit confirm before handoff. */
    var confirmTimerHandoff by remember(request) { mutableStateOf(false) }
    var timerHandoffConfirmMessage by remember(request) { mutableStateOf<String?>(null) }
    var timerHandoffOverflowMessage by remember(request) { mutableStateOf<String?>(null) }
    var pendingTimerHandoffSeed by remember(request) { mutableStateOf<TimerHandoffSeed?>(null) }
    /**
     * After seed is launched to Timer, block dismiss/import/remove until accept
     * or reject so cleanupAbandoned cannot race transferred owned paths.
     * Source of truth is VM/SavedState — not Compose remember (process-death safe).
     */
    val timerHandoffInFlight = state.timerHandoffInFlight
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnAcceptedConsumed by rememberUpdatedState(onAcceptedTimerHandoffConsumed)
    // Post-save offer + finish copy live in ViewModel/SavedState (single source of truth).
    // Any new composition after rotation observes state — never dual-mastered rememberSaveable
    // locals mutated by a late save callback from a disposed composition.
    val pendingNextFeedOffer = state.pendingNextFeedOffer
    val pendingFinishMessage = state.pendingFinishMessage
    val postSaveActive = hasComposerPostSaveStage(pendingNextFeedOffer, pendingFinishMessage)
    val handoffBusy = timerHandoffInFlight

    fun finishDismiss() {
        if (timerHandoffInFlight) return
        confirmDiscard = false
        vm.close()
        onDismiss()
    }

    fun launchTimerHandoff(seed: TimerHandoffSeed) {
        // Durable lock first — shell session is process-local and may die mid-handoff.
        vm.beginTimerHandoff(seed)
        onStartNursingTimer(
            TimerHandoffSession(
                seed = seed,
                // Accept/reject settle via shell durable tokens observed below.
                onAccepted = { },
                onRejected = { },
            ),
        )
    }

    // Process-death safe release: shell remember session is gone, but accepted seed
    // JSON + Composer pending handoff survive SavedState and re-drive close.
    LaunchedEffect(acceptedTimerHandoffSeedJson) {
        val json = acceptedTimerHandoffSeedJson ?: return@LaunchedEffect
        val accepted = TimerHandoffSeed.fromJson(json)
        if (accepted != null &&
            shouldReleasePendingTimerHandoff(vm.pendingTimerHandoffSeed(), accepted)
        ) {
            vm.closeAfterTimerHandoff(accepted)
            latestOnDismiss()
        }
        latestOnAcceptedConsumed()
    }

    // Reject / leave-timer-before-accept: unlock without reclaiming transferred paths.
    var lastRejectEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(timerHandoffRejectEpoch) {
        if (timerHandoffRejectEpoch > lastRejectEpoch) {
            lastRejectEpoch = timerHandoffRejectEpoch
            // Idempotent: clears durable in-flight only; no file reclaim.
            vm.cancelTimerHandoff()
        }
    }

    fun requestDismiss(source: ComposerDismissSource) {
        when (
            decideRecordComposerDismiss(
                source = source,
                hasUserChanges = state.hasUserChanges,
                busy = state.saving || state.deleting || handoffBusy,
            )
        ) {
            ComposerDismissDecision.DismissNow -> finishDismiss()
            ComposerDismissDecision.ConfirmDiscard -> confirmDiscard = true
            ComposerDismissDecision.IgnoreWhileBusy -> Unit
        }
    }
    val latestHasUserChanges by rememberUpdatedState(state.hasUserChanges)
    val latestBusy by rememberUpdatedState(state.saving || state.deleting || handoffBusy)
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

    // Key on post-save too: after complete/skip, a still-set root must open; while post-save is
    // live, open() refuses re-arming restorable write identity under the durable offer.
    LaunchedEffect(request, postSaveActive) {
        when {
            request == null -> vm.close()
            postSaveActive -> {
                // Keep durable stage; do not re-initialize New under an open offer/finish.
                // Root is consumed by the post-save presentation effect below.
            }
            else -> vm.open(request)
        }
    }

    // Consume restorable root as soon as a post-save stage is observable; present finish once.
    // Include [request] so a conflicting re-open while offer is live re-runs consumption.
    LaunchedEffect(request, pendingNextFeedOffer, pendingFinishMessage) {
        consumeComposerPostSavePresentation(
            pendingNextFeedOffer = pendingNextFeedOffer,
            pendingFinishMessage = pendingFinishMessage,
            onConsumeRootRequest = onPersisted,
            onPresentFinish = { message ->
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
                onSaved(finishedMessage)
                vm.acknowledgeFinishMessage()
            },
        )
    }

    // Write sheet only when root request is live and no post-save stage owns the composition
    // (avoids sheet + next-feed flow stacking after process death with root still set).
    if (request != null && !postSaveActive) {
        val openRequest = request
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
            val ready = state.activeRequest == openRequest
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
                    interactionKey = openRequest,
                    amountStepMl = state.amountStepMl,
                    timeStepMin = state.timeStepMin,
                    timePickerStyle = state.timePickerStyle,
                    preferredHand = state.preferredHand,
                    birthdayEpochDay = state.birthdayEpochDay,
                    infantFeverAdviceEnabled = state.infantFeverAdviceEnabled,
                    saving = state.saving || handoffBusy,
                    deleting = state.deleting,
                    saveError = state.error,
                    canStartNursingTimer =
                        state.canStartNursingTimer && !handoffBusy,
                    systemCalendarConfigured = state.systemCalendarConfigured,
                    onConfigureSystemCalendar = onConfigureSystemCalendar,
                    onDraftChange = if (handoffBusy) {
                        { }
                    } else {
                        vm::updateDraft
                    },
                    onDismiss = ::requestDismiss,
                    onDelete = if (handoffBusy) {
                        null
                    } else if (draft.isEditing || draft.isEditingCarePlan) {
                        {
                            deleteAttempted = false
                            confirmDelete = true
                        }
                    } else {
                        null
                    },
                    onConfirm = {
                        if (handoffBusy) return@QuickRecordSheet
                        // Convert is not ExplainedDisabled alone — require an explicit dialog.
                        if (draft.needsConvertToCarePlan()) {
                            confirmConvert = true
                        } else {
                            vm.save()
                        }
                    },
                    onStartNursingTimer = {
                        if (handoffBusy) return@QuickRecordSheet
                        scope.launch {
                            when (val prepared = vm.buildTimerHandoffFromOpenDraft()) {
                                null -> Unit
                                is TimerHandoffBuildResult.PhotoOverflow -> {
                                    timerHandoffOverflowMessage =
                                        timerHandoffPhotoOverflowMessage(
                                            distinctCount = prepared.distinctCount,
                                            maxAllowed = prepared.maxAllowed,
                                        )
                                }
                                is TimerHandoffBuildResult.Ready -> {
                                    val untransferable = vm.untransferableFieldsForOpenDraft()
                                    if (untransferable.isEmpty()) {
                                        launchTimerHandoff(prepared.seed)
                                    } else {
                                        pendingTimerHandoffSeed = prepared.seed
                                        timerHandoffConfirmMessage =
                                            timerHandoffUntransferableConfirmMessage(
                                                untransferable,
                                            )
                                        confirmTimerHandoff = true
                                    }
                                }
                            }
                        }
                    },
                    onImportPhotos = if (handoffBusy) {
                        { }
                    } else {
                        vm::importPhotos
                    },
                    onRemovePhoto = if (handoffBusy) {
                        { }
                    } else {
                        vm::removePhoto
                    },
                )
            }
        }
    }

    if (confirmTimerHandoff) {
        LeziAlertDialog(
            onDismissRequest = {
                if (!handoffBusy) {
                    confirmTimerHandoff = false
                    pendingTimerHandoffSeed = null
                    timerHandoffConfirmMessage = null
                }
            },
            title = { Text("开始喂奶计时？") },
            text = {
                Text(
                    timerHandoffConfirmMessage
                        ?: "开始计时后，无法带入计时器的草稿字段将被丢弃。确定开始计时吗？",
                )
            },
            confirmButton = {
                LeziTextButton(label = "开始计时", onClick = {
                        val seed = pendingTimerHandoffSeed
                        confirmTimerHandoff = false
                        pendingTimerHandoffSeed = null
                        timerHandoffConfirmMessage = null
                        if (seed != null) {
                            launchTimerHandoff(seed)
                        }
                    })
            },
            dismissButton = {
                LeziTextButton(label = "继续编辑", onClick = {
                        confirmTimerHandoff = false
                        pendingTimerHandoffSeed = null
                        timerHandoffConfirmMessage = null
                    })
            },
        )
    }

    timerHandoffOverflowMessage?.let { message ->
        LeziAlertDialog(
            onDismissRequest = { timerHandoffOverflowMessage = null },
            title = { Text("照片过多") },
            text = { Text(message) },
            confirmButton = {
                LeziTextButton(label = "知道了", onClick = { timerHandoffOverflowMessage = null })
            },
        )
    }

    if (confirmDelete) {
        val planConfirmation = state.draft
            ?.takeIf { it.isEditingCarePlan }
            ?.let(::carePlanDeleteConfirmation)
        LeziAlertDialog(
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
                LeziTextButton(label = if (state.deleting) "删除中…" else "确认删除", onClick = {
                        deleteAttempted = true
                        vm.delete { message ->
                            deleteAttempted = false
                            confirmDelete = false
                            onPersisted()
                            onSaved(message)
                        }
                    }, enabled = !state.deleting, tone = LeziTextButtonTone.Destructive)
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = {
                        deleteAttempted = false
                        confirmDelete = false
                    }, enabled = !state.deleting)
            },
        )
    }

    if (confirmConvert) {
        LeziAlertDialog(
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
                LeziTextButton(label = if (state.saving) "保存中…" else "转为护理计划", onClick = {
                        confirmConvert = false
                        vm.save()
                    }, enabled = !state.saving)
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = { confirmConvert = false }, enabled = !state.saving)
            },
        )
    }

    if (pendingNextFeedOffer != null) {
        val offer = pendingNextFeedOffer
        val notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val scheduledMessage = nextFeedPlanSuccessMessage(notificationPermissionGranted)
        LeziNextFeedPlanFlow(
            flowKey = "composer:${offer.suggestedAtMillis}",
            origin = NextFeedPlanOrigin.RecordComposer,
            factMessage = offer.factMessage,
            suggestedAtMillis = offer.suggestedAtMillis,
            scheduledMessage = scheduledMessage,
            minuteStep = state.timeStepMin,
            timePickerStyle = state.timePickerStyle,
            preferredHand = state.preferredHand,
            onSchedule = vm::scheduleNextFeedPlan,
            onReconcile = vm::reconcileNextFeedPlan,
            onFinishedScheduled = {
                vm.completeNextFeedOffer(scheduledMessage)
            },
            onFinishedWithoutPlan = {
                vm.completeNextFeedOffer("${offer.factMessage}；未安排下次喂养")
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
