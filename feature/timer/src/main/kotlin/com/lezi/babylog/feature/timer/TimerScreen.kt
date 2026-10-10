package com.lezi.babylog.feature.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import com.lezi.babylog.designsystem.LeziAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.lezi.babylog.core.model.NextFeedPlanOrigin
import com.lezi.babylog.core.model.TimerHandoffAcceptResult
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.designsystem.LeziDetailTopBar
import com.lezi.babylog.designsystem.LeziNextFeedPlanFlow
import com.lezi.babylog.designsystem.LeziPrimaryButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.nextFeedPlanSuccessMessage
import com.lezi.babylog.designsystem.rememberLeziHaptics
import com.lezi.babylog.designsystem.LeziTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class TimerViewportMode {
    Spacious,
    Scrollable,
}

/** Keep every timer action reachable on short viewports and with enlarged system text. */
internal fun timerViewportMode(
    screenHeightDp: Int,
    fontScale: Float,
): TimerViewportMode {
    val scaledMinimumHeight = 520f * fontScale.coerceAtLeast(1f)
    return if (screenHeightDp < scaledMinimumHeight) {
        TimerViewportMode.Scrollable
    } else {
        TimerViewportMode.Spacious
    }
}

/**
 * Pure trigger decision (0.5.4 ticket 15, spec §M3): a committed start/pause is any
 * change to the two running flags once the first state has been observed. The initial
 * composition and steady ticks must stay silent — haptics ride discrete transitions only.
 */
internal fun timerToggleHaptic(
    previous: Pair<Boolean, Boolean>,
    next: Pair<Boolean, Boolean>,
): Boolean = previous != next

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerRoute(
    onDone: () -> Unit,
    onLeaveRunning: () -> Unit = onDone,
    onLeavePaused: () -> Unit = onDone,
    initialNote: String = "",
    initialAmountMl: String = "",
    /** When set, bind this open nursing care plan to the timer session (ticket 16). */
    carePlanId: Long? = null,
    babyId: Long? = null,
    /**
     * Explicit Composer→Timer ownership handoff (Ticket 09). Accepted before
     * Composer releases draft ownership; rejected keeps Composer draft editable.
     */
    handoffSeed: TimerHandoffSeed? = null,
    onHandoffAccepted: (TimerHandoffSeed) -> Unit = {},
    onHandoffRejected: (TimerHandoffSeed) -> Unit = {},
    /**
     * Notification 「结束」 request (ticket 12): open the existing completion form once
     * the restored session is known — the timer is never silently dropped.
     */
    autoOpenCompletion: Boolean = false,
    onAutoOpenCompletionConsumed: () -> Unit = {},
    vm: TimerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val completionUi by vm.completionUi.collectAsStateWithLifecycle()
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    LaunchedEffect(autoOpenCompletion, sessionReady) {
        if (!autoOpenCompletion || !sessionReady) return@LaunchedEffect
        // Consume first so a recomposition cannot replay the open.
        onAutoOpenCompletionConsumed()
        if (completionUi.hasPostSaveStage) return@LaunchedEffect
        val snapshot = state
        if (snapshot.hasTimerData() || snapshot.handoffSeed != null || snapshot.carePlanId != null) {
            vm.openCompletion(
                initialNote = initialNote,
                initialAmountMl = initialAmountMl,
            )
        }
    }
    LaunchedEffect(handoffSeed?.handoffId) {
        val seed = handoffSeed ?: return@LaunchedEffect
        when (vm.acceptHandoffSeed(seed)) {
            is TimerHandoffAcceptResult.Accepted,
            TimerHandoffAcceptResult.AlreadyAccepted,
            -> onHandoffAccepted(seed)
            TimerHandoffAcceptResult.RejectedConflict -> onHandoffRejected(seed)
        }
    }
    LaunchedEffect(carePlanId, babyId, handoffSeed?.handoffId) {
        // Seed accept already binds baby/plan; keep legacy bind for non-seed entry.
        if (handoffSeed == null) {
            vm.bindCarePlanIfIdle(carePlanId = carePlanId, babyId = babyId)
        }
    }
    val timeStepMin by vm.timeStepMin.collectAsStateWithLifecycle()
    val timePickerStyle by vm.timePickerStyle.collectAsStateWithLifecycle()
    val preferredHand by vm.preferredHand.collectAsStateWithLifecycle()
    // 5 Hz elapsed clock, deliberately NOT read at this route scope: while a
    // side runs, only TimerSessionPane (which derives leftMs/rightMs from the
    // tick) should recompose — not the scaffold, top bar, or dialog hosts.
    val tickState = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val running = state.leftRunning || state.rightRunning
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(running) {
        tickState.longValue = SystemClock.elapsedRealtime()
        if (!running) return@LaunchedEffect
        // Tick only while RESUMED: backgrounded, the foreground-service
        // notification chronometer owns the display; elapsedRealtime resyncs
        // the first resumed frame exactly.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            tickState.longValue = SystemClock.elapsedRealtime()
            while (true) {
                delay(200)
                tickState.longValue = SystemClock.elapsedRealtime()
            }
        }
    }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val viewportMode = timerViewportMode(
        screenHeightDp = configuration.screenHeightDp,
        fontScale = LocalDensity.current.fontScale,
    )
    val timerScrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Domain-committed completion: one confirm haptic + the saved snackbar (0.5.4 ticket 15).
    TimerCompletionSavedFeedback(
        timerClearPending = completionUi.timerClearPending,
        snackbarHostState = snackbarHostState,
    )

    fun requestBack() {
        when (timerBackDecision(state, completionUi)) {
            TimerBackDecision.Leave -> onDone()
            TimerBackDecision.LeaveRunningInBackground -> onLeaveRunning()
            TimerBackDecision.LeavePaused -> onLeavePaused()
            TimerBackDecision.BlockSaving -> scope.launch {
                snackbarHostState.showSnackbar("正在保存，完成后会自动退出")
            }
            TimerBackDecision.BlockNextFeed -> scope.launch {
                snackbarHostState.showSnackbar("请先在下方完成「下次喂养安排」再退出")
            }
        }
    }

    BackHandler(enabled = true, onBack = ::requestBack)

    // Consumable exit after success without next-feed: navigate first, then acknowledge so a
    // process death mid-exit still rehydrates pendingExit; re-subscribe after ack does not re-fire.
    LaunchedEffect(completionUi.readyToExit) {
        if (completionUi.readyToExit) {
            onDone()
            vm.acknowledgeCompletionExit()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LeziDetailTopBar(title = "喂奶计时", onBack = ::requestBack)
        },
    ) { padding ->
        TimerSessionPane(
            state = state,
            tickState = tickState,
            viewportMode = viewportMode,
            actionsEnabled = !completionUi.hasPostSaveStage,
            onToggleLeft = vm::toggleLeft,
            onToggleRight = vm::toggleRight,
            onComplete = {
                vm.openCompletion(
                    initialNote = initialNote,
                    initialAmountMl = initialAmountMl,
                )
            },
            onDiscard = {
                if (state.hasTimerData()) {
                    showDiscardConfirmation = true
                } else {
                    vm.clear(onDone)
                }
            },
            onRetryService = vm::retryServiceStart,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(
                    if (viewportMode == TimerViewportMode.Scrollable) {
                        Modifier.verticalScroll(timerScrollState)
                    } else {
                        Modifier
                    },
                ),
        )
    }

    if (showDiscardConfirmation) {
        LeziAlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("丢弃本次计时？") },
            text = { Text("已累计的喂奶计时将不会保存，此操作无法撤销。") },
            confirmButton = {
                LeziTextButton(
                    label = "确认丢弃",
                    onClick = {
                        showDiscardConfirmation = false
                        vm.clear(onDone)
                    },
                    tone = LeziTextButtonTone.Destructive,
                )
            },
            dismissButton = {
                LeziTextButton(label = "继续计时", onClick = { showDiscardConfirmation = false })
            },
        )
    }

    completionUi.draft?.let { draft ->
        TimerCompletionModalSheet(
            saving = completionUi.saving,
            onDismiss = vm::dismissCompletion,
        ) {
            NursingCompletionSheet(
                draft = draft,
                saving = completionUi.saving,
                saveError = completionUi.saveError,
                timeStepMin = timeStepMin,
                timePickerStyle = timePickerStyle,
                preferredHand = preferredHand,
                onDraftChange = vm::updateCompletionDraft,
                onDismiss = {
                    if (!completionUi.saving) {
                        vm.dismissCompletion()
                    }
                },
                onConfirm = vm::confirmCompletion,
            )
        }
    }

    completionUi.pendingNextFeedSuggestedAt
        ?.takeUnless { completionUi.timerClearPending }
        ?.let { suggestedAt ->
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        LeziNextFeedPlanFlow(
            flowKey = "timer:$suggestedAt",
            origin = NextFeedPlanOrigin.NursingTimer,
            factMessage = "记录已保存",
            suggestedAtMillis = suggestedAt,
            scheduledMessage = nextFeedPlanSuccessMessage(permissionGranted),
            minuteStep = timeStepMin,
            timePickerStyle = timePickerStyle,
            preferredHand = preferredHand,
            onSchedule = vm::scheduleNextFeedPlan,
            onReconcile = vm::reconcileNextFeedPlan,
            // Navigation is owned by LaunchedEffect(pendingExit) after finish publishes exit.
            onFinishedScheduled = { vm.dismissNextFeedPlan() },
            onFinishedWithoutPlan = { vm.dismissNextFeedPlan() },
        )
        }
}

/**
 * Domain-committed completion feedback (0.5.4 ticket 15, spec §M3): when the saved stage
 * flips live ([timerClearPending]), emit one confirm haptic — the "记录已保存" acknowledgment —
 * then the existing saved snackbar. Extracted below the Hilt VM boundary so the haptic
 * point stays device-testable; the route keeps single source of truth.
 */
@Composable
internal fun TimerCompletionSavedFeedback(
    timerClearPending: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val haptics = rememberLeziHaptics()
    LaunchedEffect(timerClearPending) {
        if (timerClearPending) {
            haptics.confirm()
            snackbarHostState.showSnackbar("记录已保存，正在完成计时清理")
        }
    }
}

@Composable
internal fun TimerSessionPane(
    state: TimerState,
    tickState: LongState,
    viewportMode: TimerViewportMode,
    actionsEnabled: Boolean,
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
    onComplete: () -> Unit,
    onDiscard: () -> Unit,
    onRetryService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leftMs = state.leftMs(tickState.longValue)
    val rightMs = state.rightMs(tickState.longValue)
    val haptics = rememberLeziHaptics()
    // 开始/暂停的轻确认 (0.5.4 ticket 15): only a committed flip of either running flag
    // confirms — the first observed state and steady ticks stay silent. Never per-frame.
    var observedSides by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(state.leftRunning, state.rightRunning) {
        val sides = state.leftRunning to state.rightRunning
        val previous = observedSides
        if (previous != null && timerToggleHaptic(previous, sides)) {
            haptics.confirm()
        }
        observedSides = sides
    }
    Column(
        modifier.padding(LeziSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (viewportMode == TimerViewportMode.Scrollable) {
            Arrangement.spacedBy(LeziSpacing.Lg)
        } else {
            Arrangement.SpaceBetween
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (state.lastSide) {
                    "L" -> "上次停在左侧"
                    "R" -> "上次停在右侧"
                    else -> "左右均可开始"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "合计 ${formatTimerMs(leftMs + rightMs)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            state.serviceFeedbackText()?.let { feedback ->
                Spacer(Modifier.height(8.dp))
                Text(
                    feedback,
                    color = if (state.serviceState == TimerServiceState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (
                    state.serviceState == TimerServiceState.FAILED ||
                    state.serviceState == TimerServiceState.RECOVERABLE
                ) {
                    LeziTextButton(label = "重试启动", onClick = onRetryService)
                }
            }
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth(),
        ) {
            val buttonSize = (maxWidth * 0.42f)
                .coerceIn(120.dp, 200.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideButton(
                    label = "左",
                    time = formatTimerMs(leftMs),
                    running = state.leftRunning,
                    color = MaterialTheme.colorScheme.primary,
                    runningContentColor = MaterialTheme.colorScheme.onPrimary,
                    size = buttonSize,
                    enabled = actionsEnabled,
                    onClick = onToggleLeft,
                )
                SideButton(
                    label = "右",
                    time = formatTimerMs(rightMs),
                    running = state.rightRunning,
                    color = MaterialTheme.colorScheme.tertiary,
                    runningContentColor = MaterialTheme.colorScheme.onTertiary,
                    size = buttonSize,
                    enabled = actionsEnabled,
                    onClick = onToggleRight,
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            LeziPrimaryButton(
                label = "完成并记录",
                enabled = actionsEnabled,
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (LeziThemeExt.isElder) Modifier else Modifier.height(56.dp)),
            )
            LeziTextButton(
                label = "丢弃",
                enabled = actionsEnabled,
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth(),
                tone = LeziTextButtonTone.Destructive,
            )
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    time: String,
    running: Boolean,
    color: Color,
    runningContentColor: Color,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        label = "sideButtonScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (running) color else color.copy(alpha = 0.18f),
        label = "sideButtonBackground",
    )
    val labelColor by animateColorAsState(
        targetValue = if (running) runningContentColor else color,
        label = "sideButtonLabel",
    )
    val timeColor by animateColorAsState(
        targetValue = if (running) {
            runningContentColor
        } else {
            MaterialTheme.colorScheme.onBackground
        },
        label = "sideButtonTime",
    )
    val actionColor by animateColorAsState(
        targetValue = if (running) {
            runningContentColor.copy(alpha = 0.9f)
        } else {
            // Idle: action word shares the label's color family — two tiers, not three.
            color
        },
        label = "sideButtonAction",
    )
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = labelColor,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                time,
                color = timeColor,
                // Timer numerals ride the Metric family grade (0.5.4 ticket 16):
                // 28sp from the type scale, never an inline override.
                style = LeziThemeExt.typography.MetricLg,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                if (running) "暂停" else "开始",
                color = actionColor,
            )
        }
    }
}

/** The retained completion command owns dismissal across drag, scrim and system back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerCompletionModalSheet(
    saving: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val currentSaving by rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !currentSaving },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!currentSaving) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // Material's predictive-back dismissal can translate a retained sheet off-screen.
        BackHandler { if (!currentSaving) onDismiss() }
        content()
    }
}
