package com.lezi.babylog.feature.log

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.catalogSectionForKey
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.orderedKeysInSection
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CustomRecordItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private val CustomGlyphs = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

private data class LayoutDragState(
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

private data class LayoutItemVisual(
    val label: String,
    val recordType: RecordType?,
    val customIconSlot: Int?,
    val colorRole: LeziRecordColorRole,
)

private data class LayoutAlternativeAction(
    val label: String,
    val intent: LayoutEditIntent,
)

/** One dispatch seam shared by TalkBack custom actions and hardware-key chords. */
private fun Modifier.layoutAlternativeInput(
    actions: List<LayoutAlternativeAction>,
    keyIntent: (KeyEvent) -> LayoutEditIntent?,
    onIntent: (LayoutEditIntent) -> Unit,
): Modifier = this
    .onPreviewKeyEvent { event ->
        val intent = keyIntent(event) ?: return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> {
                onIntent(intent)
                true
            }
            else -> false
        }
    }
    .focusable()
    .semantics(mergeDescendants = true) {
        customActions = actions.map { action ->
            CustomAccessibilityAction(action.label) {
                onIntent(action.intent)
                true
            }
        }
    }

private fun catalogActions(
    catalogKey: String,
    visibleIndex: Int,
    visibleCount: Int,
): List<LayoutAlternativeAction> = buildList {
    repeat(QuickDockVisualSpec.configurableSlotCount) { slotIndex ->
        add(
            LayoutAlternativeAction(
                label = "设为常用槽${slotIndex + 1}",
                intent = LayoutEditIntent.AssignToSlot(slotIndex, catalogKey),
            ),
        )
    }
    if (visibleIndex > 0) {
        add(
            LayoutAlternativeAction(
                "在本类别前移",
                LayoutEditIntent.MoveItemInSection(catalogKey, -1),
            ),
        )
    }
    if (visibleIndex in 0 until (visibleCount - 1)) {
        add(
            LayoutAlternativeAction(
                "在本类别后移",
                LayoutEditIntent.MoveItemInSection(catalogKey, 1),
            ),
        )
    }
    add(
        LayoutAlternativeAction(
            "仅在本机隐藏",
            LayoutEditIntent.MoveToLocalDeleted(catalogKey),
        ),
    )
}

private fun catalogKeyIntent(
    catalogKey: String,
    visibleIndex: Int,
    visibleCount: Int,
    event: KeyEvent,
): LayoutEditIntent? {
    if (event.key == Key.Delete) return LayoutEditIntent.MoveToLocalDeleted(catalogKey)
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> if (visibleIndex > 0) {
            LayoutEditIntent.MoveItemInSection(catalogKey, -1)
        } else {
            null
        }
        Key.DirectionRight -> if (visibleIndex in 0 until (visibleCount - 1)) {
            LayoutEditIntent.MoveItemInSection(catalogKey, 1)
        } else {
            null
        }
        Key.One, Key.NumPad1 -> LayoutEditIntent.AssignToSlot(0, catalogKey)
        Key.Two, Key.NumPad2 -> LayoutEditIntent.AssignToSlot(1, catalogKey)
        Key.Three, Key.NumPad3 -> LayoutEditIntent.AssignToSlot(2, catalogKey)
        Key.Four, Key.NumPad4 -> LayoutEditIntent.AssignToSlot(3, catalogKey)
        else -> null
    }
}

private fun categoryActions(
    section: RecordSection,
    index: Int,
    count: Int,
): List<LayoutAlternativeAction> = buildList {
    if (index > 0) {
        add(LayoutAlternativeAction("分类前移", LayoutEditIntent.MoveCategory(section, -1)))
    }
    if (index in 0 until (count - 1)) {
        add(LayoutAlternativeAction("分类后移", LayoutEditIntent.MoveCategory(section, 1)))
    }
}

private fun categoryKeyIntent(
    section: RecordSection,
    index: Int,
    count: Int,
    event: KeyEvent,
): LayoutEditIntent? {
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionUp -> if (index > 0) {
            LayoutEditIntent.MoveCategory(section, -1)
        } else {
            null
        }
        Key.DirectionDown -> if (index in 0 until (count - 1)) {
            LayoutEditIntent.MoveCategory(section, 1)
        } else {
            null
        }
        else -> null
    }
}

private fun slotActions(index: Int, key: String): List<LayoutAlternativeAction> = buildList {
    if (index > 0) {
        add(LayoutAlternativeAction("向左移动", LayoutEditIntent.SwapSlots(index, index - 1)))
    }
    if (index < QuickDockVisualSpec.configurableSlotCount - 1) {
        add(LayoutAlternativeAction("向右移动", LayoutEditIntent.SwapSlots(index, index + 1)))
    }
    add(LayoutAlternativeAction("清空常用槽", LayoutEditIntent.ClearSlot(index)))
    add(
        LayoutAlternativeAction(
            "仅在本机隐藏该项目",
            LayoutEditIntent.MoveToLocalDeleted(key),
        ),
    )
}

private fun slotKeyIntent(index: Int, event: KeyEvent): LayoutEditIntent? {
    if (event.key == Key.Delete) return LayoutEditIntent.ClearSlot(index)
    if (!event.isCtrlPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> (index - 1).takeIf { it >= 0 }
            ?.let { LayoutEditIntent.SwapSlots(index, it) }
        Key.DirectionRight -> (index + 1)
            .takeIf { it < QuickDockVisualSpec.configurableSlotCount }
            ?.let { LayoutEditIntent.SwapSlots(index, it) }
        else -> null
    }
}

/** Full-screen layout editor continuing the 添加记录 categorized-card visual language. */
@Composable
internal fun LayoutEditCanvas(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
    writeState: DeviceLayoutWriteState = DeviceLayoutWriteState.Saved(),
    hasSubmittedIntent: Boolean = false,
    cancelDragSignal: Long = 0L,
    undoCandidate: LayoutUndoCandidate? = null,
    onUndo: (Long) -> Unit = {},
    onUndoExpired: (Long) -> Unit = {},
    initialCatalogScroll: LayoutCatalogScrollPosition = LayoutCatalogScrollPosition(),
    onCatalogScrollChanged: (LayoutCatalogScrollPosition) -> Unit = {},
    configurationSessionKey: Any? = null,
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
        normalizeStoredQuickSlots(prefs.quickRecordSlots)
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
        if (intent != null) onIntent(intent)
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
    LaunchedEffect(undoCandidate?.token) {
        val candidate = undoCandidate
        undoSnackbarHostState.currentSnackbarData?.dismiss()
        if (candidate == null) return@LaunchedEffect
        val message = when (candidate.kind) {
            LayoutUndoKind.ClearSlot -> "已清空常用槽"
            LayoutUndoKind.MoveToLocalDeleted -> "已移入本机已删除"
        }
        when (
            undoSnackbarHostState.showSnackbar(
                message = message,
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        ) {
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

            // Catalog and local-deleted are sibling scroll regions. Their shared viewport is
            // measured after the fixed toolbar and dock, so neither scroll surface can cover it.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val headingLineHeight = with(density) {
                    LeziTypography.Label.lineHeight.toDp()
                }
                val helperTwoLineHeight = with(density) {
                    LeziTypography.Meta.lineHeight.toDp() * 2
                }
                val fontScale = density.fontScale.coerceAtLeast(1f)
                val deletedChromeHeight =
                    headingLineHeight + helperTwoLineHeight +
                        LeziSpacing.Xxs + LeziSpacing.Xs * 3
                val deletedRowHeight =
                    RecordCatalogVisualSpec.cardMinHeight * fontScale +
                        RecordCatalogVisualSpec.rowSpacing
                val preferredDeletedHeight = deletedChromeHeight + deletedRowHeight * 2
                val minimumCatalogViewport =
                    LeziSpacing.SectionGap + maxOf(LeziSpacing.Touch, headingLineHeight) +
                        RecordCatalogVisualSpec.rowSpacing
                val localDeletedMaxHeight = minOf(
                    preferredDeletedHeight,
                    (maxHeight - minimumCatalogViewport).coerceAtLeast(0.dp),
                )

                Column(Modifier.fillMaxSize()) {
                    // Same categorized four-column card catalog as 添加记录.
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                catalogViewportWindowBounds = coordinates.boundsInWindow()
                            },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .verticalScroll(catalogScrollState)
                                .padding(horizontal = LeziSpacing.Page),
                        ) {
                            sections.forEach { section ->
                                val keys = layoutEditVisibleKeys(prefs, section, known)
                                val fullSectionOrder = orderedKeysInSection(
                                    section = section,
                                    itemOrderJson = prefs.itemOrderJson,
                                    knownKeysForSection = known,
                                )
                                if (keys.isNotEmpty() || section == RecordSection.Custom) {
                                    Spacer(Modifier.height(LeziSpacing.SectionGap))
                                }
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("layout_edit_section_${section.storageKey}"),
                                ) {
                                    val categoryIndex = fullCategoryOrder.indexOf(section)
                                    val categorySource = LayoutDragSource.CategoryHeading(section)
                                    val categoryDragging = drag?.source == categorySource
                                    val categoryTarget = LayoutDropTarget.CategoryHeading(
                                        section = section,
                                        toIndex = categoryIndex,
                                    )
                                    val categoryHot = drag?.currentTarget == categoryTarget
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = LeziSpacing.Touch)
                                            .layoutTargetRegistration(
                                                node = LayoutTargetNode.CategoryHeading(
                                                    section = section,
                                                    toIndex = categoryIndex,
                                                ),
                                                registry = targetRegistry,
                                                onRegistryChanged = ::notifyTargetRegistryChanged,
                                            )
                                            .draggableLayoutSource(
                                                dragKey = "category:${section.storageKey}",
                                                onDragStart = { pos ->
                                                    beginDrag(
                                                        source = categorySource,
                                                        key = "category:${section.storageKey}",
                                                        windowPos = pos,
                                                        labelOverride = section.title,
                                                    )
                                                },
                                                onDrag = ::updateDrag,
                                                onDragEnd = ::finishDrag,
                                                onDragCancel = { token ->
                                                    cancelActiveDrag(
                                                        LayoutDragCancelReason.Dispose,
                                                        token,
                                                    )
                                                },
                                            )
                                            .focusRequester(
                                                checkNotNull(categoryFocusRequesters[section]),
                                            )
                                            .focusProperties {
                                                keys.firstOrNull()
                                                    ?.let(itemFocusRequesters::get)
                                                    ?.let { down = it }
                                            }
                                            .layoutAlternativeInput(
                                                actions = categoryActions(
                                                    section = section,
                                                    index = categoryIndex,
                                                    count = fullCategoryOrder.size,
                                                ),
                                                keyIntent = {
                                                    categoryKeyIntent(
                                                        section = section,
                                                        index = categoryIndex,
                                                        count = fullCategoryOrder.size,
                                                        event = it,
                                                    )
                                                },
                                                onIntent = onIntent,
                                            )
                                            .testTag("layout_edit_category_${section.storageKey}")
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = "${section.title}分类"
                                                stateDescription =
                                                    "第${categoryIndex + 1}个分类，共${fullCategoryOrder.size}个"
                                            }
                                            .then(
                                                if (categoryHot) {
                                                    Modifier
                                                        .clip(
                                                            com.lezi.babylog.designsystem.LeziThemeExt
                                                                .controlShape,
                                                        )
                                                        .background(
                                                            MaterialTheme.colorScheme.primaryContainer.copy(
                                                                alpha = 0.72f,
                                                            ),
                                                        )
                                                        .border(
                                                            2.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            com.lezi.babylog.designsystem.LeziThemeExt
                                                                .controlShape,
                                                        )
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .then(
                                                if (categoryDragging) Modifier.alpha(0.3f) else Modifier,
                                            )
                                            .padding(horizontal = LeziSpacing.Xs),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        RecordCatalogSectionHeading(section.title)
                                    }
                                    Spacer(Modifier.height(LeziSpacing.Xs))
                                    val showAdd = section == RecordSection.Custom
                                    val dropBorderColor = MaterialTheme.colorScheme.primary
                                    val dropBorderShape =
                                        com.lezi.babylog.designsystem.LeziThemeExt.cardShape
                                    LayoutCatalogGrid(
                                        keys = keys,
                                        labels = labels,
                                        visualByKey = visualByKey,
                                        showAddCell = showAdd,
                                        onAddClick = onOpenCustomManage,
                                        itemModifier = { key ->
                                            val targetIndex = fullSectionOrder.indexOf(key)
                                            val visibleIndex = keys.indexOf(key)
                                            val boundSlot = slots.indexOf(key)
                                            val targetNode = LayoutTargetNode.CatalogItem(
                                                catalogKey = key,
                                                section = section,
                                                toIndex = targetIndex,
                                            )
                                            val dragging = drag?.source == LayoutDragSource.CatalogItem(
                                                catalogKey = key,
                                                section = section,
                                            )
                                            val hot = drag?.currentTarget == LayoutDropTarget.CatalogItem(
                                                catalogKey = key,
                                                toIndex = targetIndex,
                                            )
                                            Modifier
                                                .layoutTargetRegistration(
                                                    node = targetNode,
                                                    registry = targetRegistry,
                                                    onRegistryChanged = ::notifyTargetRegistryChanged,
                                                )
                                                .draggableLayoutSource(
                                                    dragKey = key,
                                                    onDragStart = { pos ->
                                                        beginDrag(
                                                            source = LayoutDragSource.CatalogItem(key, section),
                                                            key = key,
                                                            windowPos = pos,
                                                        )
                                                    },
                                                    onDrag = ::updateDrag,
                                                    onDragEnd = ::finishDrag,
                                                    onDragCancel = { token ->
                                                        cancelActiveDrag(
                                                            LayoutDragCancelReason.Dispose,
                                                            token,
                                                        )
                                                    },
                                                )
                                                .focusRequester(
                                                    checkNotNull(itemFocusRequesters[key]),
                                                )
                                                .layoutAlternativeInput(
                                                    actions = catalogActions(
                                                        catalogKey = key,
                                                        visibleIndex = visibleIndex,
                                                        visibleCount = keys.size,
                                                    ),
                                                    keyIntent = {
                                                        catalogKeyIntent(
                                                            catalogKey = key,
                                                            visibleIndex = visibleIndex,
                                                            visibleCount = keys.size,
                                                            event = it,
                                                        )
                                                    },
                                                    onIntent = onIntent,
                                                )
                                                .testTag("layout_edit_item_$key")
                                                .semantics(mergeDescendants = true) {
                                                    stateDescription = buildString {
                                                        append(section.title)
                                                        append("分类，第${visibleIndex + 1}项，共${keys.size}项")
                                                        if (boundSlot >= 0) {
                                                            append("，当前在常用槽${boundSlot + 1}")
                                                        }
                                                    }
                                                }
                                                .then(
                                                    if (hot) {
                                                        Modifier.border(
                                                            2.dp,
                                                            dropBorderColor,
                                                            dropBorderShape,
                                                        )
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                                .then(
                                                    if (dragging) Modifier.alpha(0.25f) else Modifier,
                                                )
                                        },
                                    )
                                }
                        }
                            Spacer(Modifier.height(LeziSpacing.Md))
                        }
                    }

                    // Local-only deleted section remains a clear, bounded drop zone.
                    val trashHot = drag?.currentTarget == LayoutDropTarget.LocalDeleted
                    val localDeletedDescription =
                        "本机已删除，${deleted.size} 项。这里只隐藏本机入口，不删除历史记录或自定义项目。" +
                            "可对项目使用恢复操作或长按拖出，恢复到所属类别末尾；不会自动回填常用槽。"
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LeziSpacing.Page)
                            .heightIn(max = localDeletedMaxHeight)
                            .layoutTargetRegistration(
                                node = LayoutTargetNode.LocalDeleted,
                                registry = targetRegistry,
                                onRegistryChanged = ::notifyTargetRegistryChanged,
                            )
                            .clip(com.lezi.babylog.designsystem.LeziThemeExt.cardShape)
                            .background(
                                if (trashHot) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .then(
                                if (trashHot) {
                                    Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.error,
                                        com.lezi.babylog.designsystem.LeziThemeExt.cardShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = LeziSpacing.Xs, vertical = LeziSpacing.Xs)
                            .testTag("layout_edit_local_deleted")
                            .semantics {
                                contentDescription = localDeletedDescription
                                stateDescription = if (trashHot) {
                                    "松开后仅在本机隐藏，并清空常用槽引用"
                                } else {
                                    "可逆的本机隐藏区"
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RecordCatalogSectionHeading(
                            title = "本机已删除 · ${deleted.size} 项",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("layout_edit_local_deleted_heading"),
                        )
                        Spacer(Modifier.height(LeziSpacing.Xxs))
                        Text(
                            text = if (trashHot) {
                                "松开后仅在本机隐藏，并清空常用槽引用"
                            } else {
                                "仅在本机隐藏 · 使用操作或拖出恢复"
                            },
                            style = LeziTypography.Meta,
                            color = if (trashHot) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(LeziSpacing.Xs))
                        if (deleted.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = LeziSpacing.Touch),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "暂无已隐藏项目",
                                    style = LeziTypography.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(localDeletedScrollState),
                            ) {
                                LayoutCatalogGrid(
                                    keys = deleted,
                                    labels = labels,
                                    visualByKey = visualByKey,
                                    showAddCell = false,
                                    onAddClick = {},
                                    contentDescriptionForKey = { key ->
                                        "${labels[key] ?: key}，本机已隐藏"
                                    },
                                    itemModifier = { key ->
                                        val dragging = drag?.source == LayoutDragSource.LocalDeleted(key)
                                        val sectionTitle = catalogSectionForKey(key)?.title ?: "所属类别"
                                        Modifier
                                            .draggableLayoutSource(
                                                dragKey = key,
                                                onDragStart = { pos ->
                                                    beginDrag(
                                                        source = LayoutDragSource.LocalDeleted(key),
                                                        key = key,
                                                        windowPos = pos,
                                                    )
                                                },
                                                onDrag = ::updateDrag,
                                                onDragEnd = ::finishDrag,
                                                onDragCancel = { token ->
                                                    cancelActiveDrag(
                                                        LayoutDragCancelReason.Dispose,
                                                        token,
                                                    )
                                                },
                                            )
                                            .layoutAlternativeInput(
                                                actions = listOf(
                                                    LayoutAlternativeAction(
                                                        "恢复到${sectionTitle}末尾",
                                                        LayoutEditIntent.RestoreFromLocalDeleted(key),
                                                    ),
                                                ),
                                                keyIntent = { event ->
                                                    if (event.key == Key.Enter) {
                                                        LayoutEditIntent.RestoreFromLocalDeleted(key)
                                                    } else {
                                                        null
                                                    }
                                                },
                                                onIntent = onIntent,
                                            )
                                            .testTag("layout_edit_deleted_$key")
                                            .semantics(mergeDescendants = true) {
                                                stateDescription =
                                                    "本机已隐藏，可恢复到${sectionTitle}末尾"
                                            }
                                            .then(
                                                if (dragging) Modifier.alpha(0.25f) else Modifier,
                                            )
                                    },
                                )
                            }
                        }
                    }
                }
            }

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

/** @deprecated Prefer [LayoutEditCanvas]. */
@Composable
internal fun LayoutEditModeDialog(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
) {
    LayoutEditCanvas(
        prefs = prefs,
        customItems = customItems,
        onIntent = onIntent,
        onDone = onDone,
        onOpenCustomManage = onOpenCustomManage,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun LayoutCatalogGrid(
    keys: List<String>,
    labels: Map<String, String>,
    visualByKey: Map<String, LayoutItemVisual>,
    showAddCell: Boolean,
    onAddClick: () -> Unit,
    contentDescriptionForKey: (String) -> String = { key -> labels[key] ?: key },
    itemModifier: (String) -> Modifier,
) {
    data class Cell(val key: String?, val isAdd: Boolean = false)
    val cells = keys.map { Cell(it) } +
        if (showAddCell) listOf(Cell(key = null, isAdd = true)) else emptyList()
    recordCatalogRows(cells).forEach { row ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                RecordCatalogVisualSpec.columnSpacing,
            ),
        ) {
            row.forEach { cell ->
                if (cell.isAdd) {
                    RecordCatalogCard(
                        label = "添加",
                        recordType = null,
                        customIconSlot = null,
                        colorRole = LeziRecordColorRole.Care,
                        contentDescription = "管理自定义记录项目",
                        isAdd = true,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onAddClick)
                            .testTag("layout_edit_custom_manage"),
                    )
                } else {
                    val key = cell.key!!
                    val visual = visualByKey[key]
                    RecordCatalogCard(
                        label = labels[key] ?: key,
                        recordType = visual?.recordType,
                        customIconSlot = visual?.customIconSlot,
                        colorRole = visual?.colorRole ?: LeziRecordColorRole.Care,
                        contentDescription = contentDescriptionForKey(key),
                        modifier = Modifier
                            .weight(1f)
                            .then(itemModifier(key)),
                    )
                }
            }
            repeat(RecordCatalogVisualSpec.columnCount - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(RecordCatalogVisualSpec.rowSpacing))
    }
}

@Composable
private fun LauncherEditDock(
    slots: List<String>,
    labels: Map<String, String>,
    visualByKey: Map<String, LayoutItemVisual>,
    currentTarget: LayoutDropTarget?,
    dragKey: String?,
    dragFromSlot: Int?,
    targetRegistry: LayoutVisibleTargetRegistry,
    onTargetRegistryChanged: () -> Unit,
    onSlotDragStart: (Int, String, Offset) -> Long,
    onSlotDrag: (Long, Offset) -> Unit,
    onSlotDragEnd: (Long, Offset) -> Unit,
    onSlotDragCancel: (Long) -> Unit,
    onIntent: (LayoutEditIntent) -> Unit,
) {
    val journal = com.lezi.babylog.designsystem.LeziThemeExt.isJournal
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (journal) 0.dp else QuickDockVisualSpec.outerHorizontalWarm,
                vertical = QuickDockVisualSpec.outerVertical,
            )
            .layoutTargetRegistration(
                node = LayoutTargetNode.Dock,
                registry = targetRegistry,
                onRegistryChanged = onTargetRegistryChanged,
            )
            .testTag("layout_edit_dock"),
        shape = com.lezi.babylog.designsystem.LeziThemeExt.dockShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        ),
        shadowElevation = com.lezi.babylog.designsystem.LeziThemeExt.dockElevation,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusGroup()
                .padding(
                    horizontal = QuickDockVisualSpec.rowHorizontal,
                    vertical = QuickDockVisualSpec.rowVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(QuickDockVisualSpec.cellSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEachIndexed { index, key ->
                val hot = currentTarget == LayoutDropTarget.QuickSlot(index)
                val visual = if (key.isNotBlank()) visualByKey[key] else null
                val label = when {
                    key.isBlank() -> "空"
                    else -> labels[key] ?: key
                }
                val dimmed = dragKey == key && dragFromSlot == index
                val slotInputModifier = if (key.isNotBlank()) {
                    Modifier.layoutAlternativeInput(
                        actions = slotActions(index, key),
                        keyIntent = { slotKeyIntent(index, it) },
                        onIntent = onIntent,
                    )
                } else {
                    Modifier
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = QuickDockVisualSpec.cellMinHeight)
                        .layoutTargetRegistration(
                            node = LayoutTargetNode.QuickSlot(index),
                            registry = targetRegistry,
                            onRegistryChanged = onTargetRegistryChanged,
                        )
                        .then(slotInputModifier)
                        .testTag("layout_edit_slot_$index")
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (key.isBlank()) {
                                "常用槽${index + 1}，空，可从记录项目的操作中指派"
                            } else {
                                "常用槽${index + 1}，$label"
                            }
                            stateDescription = if (key.isBlank()) {
                                "空槽"
                            } else {
                                "第${index + 1}槽，共${QuickDockVisualSpec.configurableSlotCount}槽"
                            }
                        },
                    shape = com.lezi.babylog.designsystem.LeziThemeExt.controlShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = if (hot) 0.92f else 0.72f,
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (hot) {
                        androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        null
                    },
                ) {
                    val bodyMod = if (key.isNotBlank()) {
                        Modifier
                            .draggableLayoutSource(
                                dragKey = key,
                                onDragStart = { onSlotDragStart(index, key, it) },
                                onDrag = onSlotDrag,
                                onDragEnd = onSlotDragEnd,
                                onDragCancel = onSlotDragCancel,
                            )
                            .alpha(if (dimmed) 0.25f else 1f)
                    } else {
                        Modifier
                    }
                    Column(
                        bodyMod
                            .fillMaxWidth()
                            .padding(vertical = QuickDockVisualSpec.rowVertical),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = visual?.let { leziRecordColor(it.colorRole) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            Modifier
                                .size(QuickDockVisualSpec.iconSize)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                key.isBlank() -> Text(
                                    "＋",
                                    style = LeziTypography.BodyStrong,
                                    color = tint,
                                )
                                visual?.recordType == RecordType.CUSTOM -> Text(
                                    CustomGlyphs[(visual.customIconSlot ?: 0).coerceIn(0, 7)],
                                    style = LeziTypography.Meta,
                                    color = tint,
                                )
                                visual?.recordType != null -> RecordTypeIcon(
                                    visual.recordType,
                                    tint = tint,
                                )
                            }
                        }
                        if (key.isBlank()) {
                            Text(" ", style = LeziTypography.Meta)
                        } else {
                            Text(
                                label,
                                style = LeziTypography.Meta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // Locked 更多 — not a drop target
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = QuickDockVisualSpec.cellMinHeight)
                    .alpha(0.55f)
                    .layoutTargetRegistration(
                        node = LayoutTargetNode.LockedMore,
                        registry = targetRegistry,
                        onRegistryChanged = onTargetRegistryChanged,
                    )
                    .testTag("layout_edit_more_locked")
                    .semantics(mergeDescendants = true) {
                        contentDescription = "更多，固定在末位，编辑布局时已锁定"
                        stateDescription = "固定且锁定，无可用编辑动作"
                    },
                shape = com.lezi.babylog.designsystem.LeziThemeExt.controlShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = QuickDockVisualSpec.rowVertical),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(QuickDockVisualSpec.iconSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        LeziRecordGlyphIcon(
                            glyph = LeziRecordGlyph.Other,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "更多·锁",
                        style = LeziTypography.Meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun Modifier.layoutTargetRegistration(
    node: LayoutTargetNode,
    registry: LayoutVisibleTargetRegistry,
    onRegistryChanged: () -> Unit,
): Modifier = composed {
    val registrationOwner = remember(registry, node) { Any() }
    DisposableEffect(registry, node) {
        onDispose {
            if (registry.unregister(node, registrationOwner)) onRegistryChanged()
        }
    }
    this@layoutTargetRegistration.onGloballyPositioned { coordinates ->
        if (registry.register(node, coordinates.boundsInWindow(), registrationOwner)) {
            onRegistryChanged()
        }
    }
}

private fun Modifier.draggableLayoutSource(
    dragKey: String,
    onDragStart: (Offset) -> Long,
    onDrag: (Long, Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: (Long) -> Unit,
): Modifier {
    var coordinates: LayoutCoordinates? = null
    return this
        .onGloballyPositioned { coords ->
            coordinates = coords
        }
        .pointerInput(dragKey) {
            var lastWindow = Offset.Zero
            var activeToken: Long? = null
            detectDragGesturesAfterLongPress(
                onDragStart = start@{ local ->
                    val currentCoordinates = coordinates
                        ?.takeIf(LayoutCoordinates::isAttached)
                        ?: return@start
                    lastWindow = currentCoordinates.localToWindow(local)
                    activeToken = onDragStart(lastWindow)
                },
                onDrag = drag@{ change, _ ->
                    change.consume()
                    val currentCoordinates = coordinates
                        ?.takeIf(LayoutCoordinates::isAttached)
                    if (currentCoordinates == null) {
                        activeToken?.let(onDragCancel)
                        activeToken = null
                        return@drag
                    }
                    lastWindow = currentCoordinates.localToWindow(change.position)
                    activeToken?.let { onDrag(it, lastWindow) }
                },
                onDragEnd = {
                    activeToken?.let { token ->
                        if (coordinates?.isAttached == true) {
                            onDragEnd(token, lastWindow)
                        } else {
                            onDragCancel(token)
                        }
                    }
                    activeToken = null
                },
                onDragCancel = {
                    activeToken?.let(onDragCancel)
                    activeToken = null
                },
            )
        }
}
