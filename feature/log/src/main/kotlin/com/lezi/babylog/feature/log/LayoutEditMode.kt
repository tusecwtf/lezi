package com.lezi.babylog.feature.log

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.model.normalizeQuickRecordSlots
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CustomRecordItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class LayoutDragState(
    val token: Long,
    val source: LayoutDragSource,
    val catalogKey: String,
    val label: String,
    val pointerWindow: Offset,
    val hitRegion: LayoutHitRegion,
    val currentTarget: LayoutDropTarget?,
    val colorRole: LeziRecordColorRole?,
    val recordType: RecordType?,
    val customIconSlot: Int?,
)

private data class LayoutConfigurationKey(
    val orientation: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val uiMode: Int,
    val fontScale: Float,
    val themeIdentity: Any?,
)

/** Full-screen layout editor continuing the 添加记录 categorized-card visual language. */
@Composable
internal fun LayoutEditCanvas(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onTouchDragIntent: ((LayoutEditIntent) -> Unit)? = null,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
    writeState: DeviceLayoutWriteState = DeviceLayoutWriteState.Saved(),
    hasSubmittedIntent: Boolean = false,
    cancelDragSignal: Long = 0L,
    undoCandidate: LayoutUndoCandidate? = null,
    /**
     * Absolute epoch millis when the current [undoCandidate] offer ends. Recreation
     * re-shows the snackbar with only the remaining window; null falls back to the
     * default short offer length from first show.
     */
    undoOfferExpiresAtEpochMs: Long? = null,
    onUndo: (Long) -> Unit = {},
    onUndoExpired: (Long) -> Unit = {},
    initialCatalogScroll: LayoutCatalogScrollPosition = LayoutCatalogScrollPosition(),
    onCatalogScrollChanged: (LayoutCatalogScrollPosition) -> Unit = {},
    configurationSessionKey: Any? = null,
    dragGuidance: LayoutDragGuidanceState = initialLayoutDragGuidanceState(
        completed = true,
    ),
    onDragGuidanceHelp: () -> Unit = {},
    onDragGuidanceClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val known = remember(customItems) { knownCatalogKeys(customItems.map { it.id }) }
    val labels = remember(customItems) {
        buildMap {
            RecordType.availableForNewEntry().forEach { put(it.key, it.presentation.label) }
            customItems.forEach {
                put(RecordItemIdentity.customCatalogKey(it.id), it.name)
            }
        }
    }
    val visualByKey = remember(customItems) {
        buildMap {
            RecordType.availableForNewEntry().forEach { type ->
                put(
                    type.key,
                    LayoutItemVisual(
                        label = type.presentation.label,
                        recordType = type,
                        customIconSlot = null,
                        colorRole = type.presentation.colorRole,
                    ),
                )
            }
            customItems.forEach { item ->
                put(
                    RecordItemIdentity.customCatalogKey(item.id),
                    LayoutItemVisual(
                        label = item.name,
                        recordType = RecordType.CUSTOM,
                        customIconSlot = item.iconSlot,
                        colorRole = RecordType.CUSTOM.presentation.colorRole,
                    ),
                )
            }
        }
    }
    val slots = remember(prefs.quickRecordSlots) {
        normalizeQuickRecordSlots(prefs.quickRecordSlots)
    }
    val sections = remember(prefs, known) { layoutEditVisibleSections(prefs, known) }
    val fullCategoryOrder = remember(prefs.categoryOrderJson) {
        orderedRecordSections(prefs.categoryOrderJson)
    }
    val deleted = remember(prefs, known) { layoutEditDeletedKeys(prefs, known) }
    val retainedInitialCatalogScroll = remember { initialCatalogScroll }
    val catalogScrollState = rememberScrollState(retainedInitialCatalogScroll.value)
    var catalogScrollRestored by remember { mutableStateOf(false) }
    val localDeletedScrollState = rememberScrollState()
    val doneFocusRequester = remember { FocusRequester() }
    val categoryFocusRequesters = remember {
        RecordSection.entries.associateWith { FocusRequester() }
    }
    val itemFocusRequesters = remember(known) {
        known.associateWith { FocusRequester() }
    }
    val undoSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(catalogScrollState.maxValue) {
        if (catalogScrollRestored) return@LaunchedEffect
        if (
            retainedInitialCatalogScroll.maxValue > 0 &&
            catalogScrollState.maxValue == 0
        ) {
            return@LaunchedEffect
        }
        catalogScrollState.scrollTo(
            retainedInitialCatalogScroll.valueFor(catalogScrollState.maxValue),
        )
        catalogScrollRestored = true
    }
    LaunchedEffect(catalogScrollState, catalogScrollRestored) {
        if (!catalogScrollRestored) return@LaunchedEffect
        snapshotFlow {
            LayoutCatalogScrollPosition(
                value = catalogScrollState.value,
                maxValue = catalogScrollState.maxValue,
            )
        }.distinctUntilChanged().collect { position ->
            onCatalogScrollChanged(position)
        }
    }

    val targetRegistry = remember { LayoutVisibleTargetRegistry() }
    var targetRegistryEpoch by remember { mutableLongStateOf(0L) }
    var rootWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var catalogViewportWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var drag by remember { mutableStateOf<LayoutDragState?>(null) }
    var activeSession by remember { mutableStateOf<LayoutDragSession?>(null) }
    var nextDragToken by remember { mutableLongStateOf(0L) }
    var feedbackState by remember {
        mutableStateOf<LayoutDragFeedbackState>(LayoutDragFeedbackState.Idle)
    }
    var feedbackPulse by remember { mutableStateOf<LayoutDragVisualPulse?>(null) }
    var feedbackPulseWindow by remember { mutableStateOf<Offset?>(null) }
    val feedbackProgress = remember { Animatable(0f) }
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current

    fun dispatchDragFeedback(
        event: LayoutDragFeedbackEvent,
        pointerWindow: Offset,
    ) {
        val reduction = reduceLayoutDragFeedback(feedbackState, event)
        feedbackState = reduction.state
        if (reduction.clearVisualPulse) {
            feedbackPulse = null
            feedbackPulseWindow = null
        }
        reduction.visualPulse?.let { pulse ->
            feedbackPulse = pulse
            feedbackPulseWindow = pointerWindow
        }
        reduction.haptic?.let { haptic ->
            hapticFeedback.performHapticFeedback(
                when (haptic) {
                    LayoutDragHaptic.Target -> HapticFeedbackType.TextHandleMove
                    LayoutDragHaptic.Pickup,
                    LayoutDragHaptic.Drop,
                    -> HapticFeedbackType.LongPress
                },
            )
        }
    }

    LaunchedEffect(feedbackPulse) {
        val activePulse = feedbackPulse ?: return@LaunchedEffect
        feedbackProgress.snapTo(1f)
        val durationMillis = layoutDragFeedbackDurationMillis(
            coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f,
        )
        if (durationMillis == 0) {
            feedbackProgress.snapTo(0f)
        } else {
            feedbackProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = durationMillis),
            )
        }
        if (feedbackPulse == activePulse) {
            feedbackPulse = null
            feedbackPulseWindow = null
        }
    }

    fun beginDrag(
        source: LayoutDragSource,
        key: String,
        windowPos: Offset,
        labelOverride: String? = null,
    ): Long {
        nextDragToken += 1L
        val token = nextDragToken
        val session = LayoutDragSession(token = token, source = source)
        activeSession = session
        val resolution = session.update(token, windowPos, targetRegistry.snapshot())
        val visual = visualByKey[key]
        drag = LayoutDragState(
            token = token,
            source = source,
            catalogKey = key,
            label = labelOverride ?: labels[key] ?: key,
            pointerWindow = windowPos,
            hitRegion = resolution.hitRegion,
            currentTarget = resolution.currentTarget,
            colorRole = visual?.colorRole,
            recordType = visual?.recordType,
            customIconSlot = visual?.customIconSlot,
        )
        dispatchDragFeedback(
            event = LayoutDragFeedbackEvent.PickedUp(
                token = token,
                initialTarget = resolution.currentTarget,
            ),
            pointerWindow = windowPos,
        )
        return token
    }

    fun updateDrag(token: Long, windowPos: Offset) {
        val current = drag?.takeIf { it.token == token } ?: return
        val resolution = activeSession?.update(
            token = token,
            pointerWindow = windowPos,
            targets = targetRegistry.snapshot(),
        ) ?: return
        if (resolution.accepted) {
            drag = current.copy(
                pointerWindow = windowPos,
                hitRegion = resolution.hitRegion,
                currentTarget = resolution.currentTarget,
            )
            dispatchDragFeedback(
                event = LayoutDragFeedbackEvent.CurrentTargetChanged(
                    token = token,
                    target = resolution.currentTarget,
                ),
                pointerWindow = windowPos,
            )
        }
    }

    fun finishDrag(token: Long, windowPos: Offset) {
        if (drag?.token != token) return
        val intent = activeSession?.finish(
            token = token,
            pointerWindow = windowPos,
            targets = targetRegistry.snapshot(),
        )
        val accepted = intent?.let { reduceLayoutEdit(prefs, it, known) != prefs } == true
        dispatchDragFeedback(
            event = LayoutDragFeedbackEvent.DropFinished(token, accepted),
            pointerWindow = windowPos,
        )
        activeSession = null
        drag = null
        if (intent != null) {
            onTouchDragIntent?.invoke(intent) ?: onIntent(intent)
        }
    }

    fun cancelActiveDrag(reason: LayoutDragCancelReason, token: Long? = null) {
        val current = drag ?: return
        if (token != null && token != current.token) return
        activeSession?.cancel(reason)
        dispatchDragFeedback(
            event = LayoutDragFeedbackEvent.Cancelled(current.token),
            pointerWindow = current.pointerWindow,
        )
        activeSession = null
        drag = null
    }

    fun notifyTargetRegistryChanged() {
        targetRegistryEpoch += 1L
    }

    LaunchedEffect(targetRegistryEpoch) {
        val current = drag ?: return@LaunchedEffect
        updateDrag(current.token, current.pointerWindow)
    }

    val autoScrollStepPx = drag?.let { current ->
        catalogViewportWindowBounds?.let { viewport ->
            LayoutEdgeAutoScrollPolicy.stepPx(
                source = current.source,
                pointerWindow = current.pointerWindow,
                catalogViewport = viewport,
                hitRegion = current.hitRegion,
                canScrollBackward = catalogScrollState.canScrollBackward,
                canScrollForward = catalogScrollState.canScrollForward,
                edgeBandPx = with(density) { 64.dp.toPx() },
                maxStepPx = with(density) { 12.dp.toPx() },
            )
        }
    } ?: 0f
    LaunchedEffect(drag?.token, autoScrollStepPx) {
        if (autoScrollStepPx == 0f) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (catalogScrollState.scrollBy(autoScrollStepPx) == 0f) break
        }
    }

    val configuration = LocalConfiguration.current
    val configurationKey = LayoutConfigurationKey(
        orientation = configuration.orientation,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        uiMode = configuration.uiMode,
        fontScale = configuration.fontScale,
        themeIdentity = configurationSessionKey,
    )
    val lifecyclePolicy = remember {
        LayoutDragLifecyclePolicy(
            initialCancelSignal = cancelDragSignal,
            initialConfigurationKey = configurationKey,
        )
    }
    LaunchedEffect(cancelDragSignal) {
        lifecyclePolicy.cancelReasonForSignal(cancelDragSignal)?.let { reason ->
            cancelActiveDrag(reason)
        }
    }
    LaunchedEffect(configurationKey) {
        lifecyclePolicy.cancelReasonForConfiguration(configurationKey)
            ?.let { reason -> cancelActiveDrag(reason) }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSession?.cancel(lifecyclePolicy.disposeReason())
        }
    }
    LaunchedEffect(Unit) {
        doneFocusRequester.requestFocus()
    }
    LaunchedEffect(undoCandidate?.token, undoOfferExpiresAtEpochMs) {
        val candidate = undoCandidate
        undoSnackbarHostState.currentSnackbarData?.dismiss()
        if (candidate == null) return@LaunchedEffect
        val remainingMs = when {
            undoOfferExpiresAtEpochMs != null ->
                (undoOfferExpiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            else -> LAYOUT_UNDO_OFFER_DURATION_MS
        }
        if (remainingMs <= 0L) {
            onUndoExpired(candidate.token)
            return@LaunchedEffect
        }
        val message = when (candidate.kind) {
            LayoutUndoKind.ClearSlot -> "已清空常用槽"
            LayoutUndoKind.MoveToLocalDeleted -> "已移入本机已删除"
        }
        // Indefinite + deadline dismiss keeps a stable wall-clock expiry across
        // configuration recreation instead of re-filling SnackbarDuration.Short.
        val expiryJob = launch {
            delay(remainingMs)
            undoSnackbarHostState.currentSnackbarData?.dismiss()
        }
        val result = undoSnackbarHostState.showSnackbar(
            message = message,
            actionLabel = "撤销",
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        expiryJob.cancel()
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo(candidate.token)
            SnackbarResult.Dismissed -> onUndoExpired(candidate.token)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("layout_edit_mode")
            .onPreviewKeyEvent { event ->
                val token = undoCandidate?.token ?: return@onPreviewKeyEvent false
                if (!event.isCtrlPressed || event.key != Key.Z) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> true
                    KeyEventType.KeyUp -> {
                        onUndo(token)
                        true
                    }
                    else -> false
                }
            }
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                rootWindowOrigin = Offset(p.x, p.y)
            }
            .semantics { contentDescription = "编辑布局，可拖动或使用操作重新排列" },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Editor-owned chrome. Root date chrome and primary tabs are hidden.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = LeziSpacing.Touch)
                    .padding(horizontal = LeziSpacing.Page, vertical = LeziSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = LayoutEditPresentation.title,
                    style = LeziTypography.Title,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("layout_edit_title"),
                )
                TextButton(
                    onClick = onDragGuidanceHelp,
                    modifier = Modifier
                        .heightIn(min = LeziSpacing.Touch)
                        .testTag("layout_edit_guidance_help")
                        .semantics {
                            onClick(label = "查看布局拖放帮助") {
                                onDragGuidanceHelp()
                                true
                            }
                        },
                ) {
                    Text("帮助")
                }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier
                        .heightIn(min = LeziSpacing.Touch)
                        .testTag("layout_edit_done")
                        .focusRequester(doneFocusRequester)
                        .semantics {
                            onClick(label = "完成并保存布局") {
                                onDone()
                                true
                            }
                        },
                ) {
                    Text("完成")
                }
            }
            layoutWriteAnnouncement(prefs, writeState, hasSubmittedIntent)?.let { announcement ->
                Text(
                    text = announcement,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = LeziSpacing.Page)
                        .testTag("layout_edit_save_feedback")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (dragGuidance.visibility != LayoutDragGuidanceVisibility.Hidden) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = com.lezi.babylog.designsystem.LeziThemeExt.controlShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LeziSpacing.Page, vertical = LeziSpacing.Xxs)
                        .testTag("layout_edit_guidance"),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = LeziSpacing.Md,
                            end = LeziSpacing.Xs,
                            top = LeziSpacing.Xs,
                            bottom = LeziSpacing.Xs,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "长按卡片拖到常用槽；拖出槽位可清空。",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("layout_edit_guidance_text"),
                        )
                        TextButton(
                            onClick = onDragGuidanceClose,
                            modifier = Modifier
                                .heightIn(min = LeziSpacing.Touch)
                                .testTag("layout_edit_guidance_close")
                                .semantics {
                                    onClick(label = "关闭布局拖放帮助") {
                                        onDragGuidanceClose()
                                        true
                                    }
                                },
                        ) {
                            Text("关闭")
                        }
                    }
                }
            }

            LayoutEditCatalogSurface(
                prefs = prefs,
                known = known,
                labels = labels,
                visualByKey = visualByKey,
                slots = slots,
                sections = sections,
                fullCategoryOrder = fullCategoryOrder,
                deleted = deleted,
                catalogScrollState = catalogScrollState,
                localDeletedScrollState = localDeletedScrollState,
                drag = drag,
                targetRegistry = targetRegistry,
                categoryFocusRequesters = categoryFocusRequesters,
                itemFocusRequesters = itemFocusRequesters,
                onCatalogViewportBoundsChanged = { catalogViewportWindowBounds = it },
                onTargetRegistryChanged = ::notifyTargetRegistryChanged,
                onDragStart = { source, key, windowPos, labelOverride ->
                    beginDrag(source, key, windowPos, labelOverride)
                },
                onDrag = ::updateDrag,
                onDragEnd = ::finishDrag,
                onDragCancel = ::cancelActiveDrag,
                onIntent = onIntent,
                onOpenCustomManage = onOpenCustomManage,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            Spacer(Modifier.height(LeziSpacing.Xxs))

            // Bottom dock — drop targets for quick slots (Android dock metaphor)
            LauncherEditDock(
                slots = slots,
                labels = labels,
                visualByKey = visualByKey,
                currentTarget = drag?.currentTarget,
                dragKey = drag?.catalogKey,
                dragFromSlot = (drag?.source as? LayoutDragSource.BoundSlot)?.slotIndex,
                targetRegistry = targetRegistry,
                onTargetRegistryChanged = ::notifyTargetRegistryChanged,
                onSlotDragStart = { index, key, pos ->
                    beginDrag(
                        source = LayoutDragSource.BoundSlot(index, key),
                        key = key,
                        windowPos = pos,
                    )
                },
                onSlotDrag = ::updateDrag,
                onSlotDragEnd = ::finishDrag,
                onSlotDragCancel = { token ->
                    cancelActiveDrag(LayoutDragCancelReason.Dispose, token)
                },
                onIntent = onIntent,
            )
        }

        val activePulse = feedbackPulse
        val pulseWindow = feedbackPulseWindow
        if (activePulse != null && pulseWindow != null && feedbackProgress.value > 0f) {
            val pulseSize = 48.dp
            val pulseRadiusPx = with(density) { pulseSize.toPx() / 2f }
            Box(
                Modifier
                    .zIndex(19f)
                    .offset {
                        IntOffset(
                            (pulseWindow.x - rootWindowOrigin.x - pulseRadiusPx).roundToInt(),
                            (pulseWindow.y - rootWindowOrigin.y - pulseRadiusPx).roundToInt(),
                        )
                    }
                    .size(pulseSize)
                    .scale(0.82f + feedbackProgress.value * 0.18f)
                    .alpha(feedbackProgress.value)
                    .border(
                        width = 2.dp,
                        color = if (activePulse.kind == LayoutDragVisualPulseKind.Drop) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shape = CircleShape,
                    )
                    .testTag("layout_drag_feedback_pulse")
                    .semantics { invisibleToUser() },
            )
        }

        // Floating card under finger; static and dragged items keep one visual language.
        drag?.let { d ->
            val localX = d.pointerWindow.x - rootWindowOrigin.x
            val localY = d.pointerWindow.y - rootWindowOrigin.y
            val headingDrag = d.source is LayoutDragSource.CategoryHeading
            val avatarWidth = if (headingDrag) 160.dp else 80.dp
            val widthPx = with(density) { avatarWidth.toPx() }
            val avatarModifier = Modifier
                .zIndex(20f)
                .offset {
                    IntOffset(
                        (localX - widthPx / 2f).roundToInt(),
                        (localY - with(density) { LeziSpacing.Touch.toPx() }).roundToInt(),
                    )
                }
                .width(avatarWidth)
                .scale(
                    if (
                        activePulse?.kind == LayoutDragVisualPulseKind.Pickup &&
                        activePulse.token == d.token
                    ) {
                        1.04f + feedbackProgress.value * 0.04f
                    } else {
                        1.08f
                    },
                )
                .testTag("layout_edit_drag_avatar")
                .semantics { invisibleToUser() }
            if (headingDrag) {
                Surface(
                    modifier = avatarModifier.heightIn(min = LeziSpacing.Touch),
                    shape = com.lezi.babylog.designsystem.LeziThemeExt.controlShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                    ),
                    shadowElevation = 6.dp,
                ) {
                    Box(
                        Modifier.padding(horizontal = LeziSpacing.Sm),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        RecordCatalogSectionHeading(d.label)
                    }
                }
            } else {
                RecordCatalogCard(
                    label = d.label,
                    recordType = d.recordType,
                    customIconSlot = d.customIconSlot,
                    colorRole = d.colorRole ?: LeziRecordColorRole.Care,
                    contentDescription = "正在拖动${d.label}",
                    modifier = avatarModifier,
                )
            }
        }

        SnackbarHost(
            hostState = undoSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = LeziSpacing.Page,
                    end = LeziSpacing.Page,
                    bottom = 96.dp,
                )
                .zIndex(30f)
                .testTag("layout_edit_undo_snackbar"),
        )
    }
}
