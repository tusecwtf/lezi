package com.lezi.babylog.feature.log

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.orderedKeysInSection
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CustomRecordItem
import kotlin.math.roundToInt

private val CustomGlyphs = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

private data class LayoutDragState(
    val token: Long,
    val source: LayoutDragSource,
    val catalogKey: String,
    val label: String,
    val pointerWindow: Offset,
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
)

private data class LayoutItemVisual(
    val label: String,
    val recordType: RecordType?,
    val customIconSlot: Int?,
    val colorRole: LeziRecordColorRole,
)

/** Full-screen layout editor continuing the 添加记录 categorized-card visual language. */
@Composable
internal fun LayoutEditCanvas(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
    cancelDragSignal: Long = 0L,
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
    val deleted = remember(prefs, known) { layoutEditDeletedKeys(prefs, known) }

    val targetRegistry = remember { LayoutVisibleTargetRegistry() }
    var targetRegistryEpoch by remember { mutableLongStateOf(0L) }
    var rootWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf<LayoutDragState?>(null) }
    var activeSession by remember { mutableStateOf<LayoutDragSession?>(null) }
    var nextDragToken by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val isDragging = drag != null

    // Mild jiggle like Android home edit (paused while actively dragging).
    val jiggle = rememberInfiniteTransition(label = "layout_jiggle")
    val jiggleAngle by jiggle.animateFloat(
        initialValue = -2.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(140, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggle_angle",
    )
    val iconWiggle = if (isDragging) 0f else jiggleAngle

    fun beginDrag(
        source: LayoutDragSource,
        key: String,
        windowPos: Offset,
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
            label = labels[key] ?: key,
            pointerWindow = windowPos,
            currentTarget = resolution.currentTarget,
            colorRole = visual?.colorRole,
            recordType = visual?.recordType,
            customIconSlot = visual?.customIconSlot,
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
                currentTarget = resolution.currentTarget,
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
        activeSession = null
        drag = null
        if (intent != null) onIntent(intent)
    }

    fun cancelActiveDrag(reason: LayoutDragCancelReason, token: Long? = null) {
        val current = drag ?: return
        if (token != null && token != current.token) return
        activeSession?.cancel(reason)
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

    val configuration = LocalConfiguration.current
    val configurationKey = LayoutConfigurationKey(
        orientation = configuration.orientation,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        uiMode = configuration.uiMode,
        fontScale = configuration.fontScale,
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

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("layout_edit_mode")
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                rootWindowOrigin = Offset(p.x, p.y)
            }
            .semantics { contentDescription = "编辑布局，拖动图标替换常用" },
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
                    modifier = Modifier.testTag("layout_edit_done"),
                ) {
                    Text("完成")
                }
            }

            // Same categorized four-column card catalog as 添加记录.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                        RecordCatalogSectionHeading(section.title)
                        Spacer(Modifier.height(LeziSpacing.Xs))
                        val showAdd = section == RecordSection.Custom
                        val dropBorderColor = MaterialTheme.colorScheme.primary
                        val dropBorderShape =
                            com.lezi.babylog.designsystem.LeziThemeExt.cardShape
                        LayoutCatalogGrid(
                            keys = keys,
                            labels = labels,
                            visualByKey = visualByKey,
                            wiggleDegrees = iconWiggle,
                            showAddCell = showAdd,
                            onAddClick = onOpenCustomManage,
                            itemModifier = { key ->
                                val targetIndex = fullSectionOrder.indexOf(key)
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
                                    .draggableCatalogKey(
                                        catalogKey = key,
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
                                    .testTag("layout_edit_item_$key")
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

            // Local-only deleted section remains a clear, bounded drop zone.
            val trashHot = drag?.currentTarget == LayoutDropTarget.LocalDeleted
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LeziSpacing.Page)
                    .heightIn(min = 72.dp)
                    .layoutTargetRegistration(
                        node = LayoutTargetNode.LocalDeleted,
                        registry = targetRegistry,
                        onRegistryChanged = ::notifyTargetRegistryChanged,
                    )
                    .clip(com.lezi.babylog.designsystem.LeziThemeExt.cardShape)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(
                            alpha = if (trashHot) 0.65f else 0.28f,
                        ),
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
                    .testTag("layout_edit_local_deleted"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordCatalogSectionHeading(
                    title = "本机已删除",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("layout_edit_local_deleted_heading"),
                )
                Spacer(Modifier.height(LeziSpacing.Xs))
                if (deleted.isEmpty()) {
                    Box(
                        Modifier
                            .size(LeziSpacing.Touch)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "⌫",
                            style = LeziTypography.TitleSm,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                        )
                    }
                } else {
                    LayoutCatalogGrid(
                        keys = deleted,
                        labels = labels,
                        visualByKey = visualByKey,
                        wiggleDegrees = iconWiggle,
                        showAddCell = false,
                        onAddClick = {},
                        itemModifier = { key ->
                            val dragging = drag?.source == LayoutDragSource.LocalDeleted(key)
                            Modifier
                                .draggableCatalogKey(
                                    catalogKey = key,
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
                                .testTag("layout_edit_deleted_$key")
                                .then(
                                    if (dragging) Modifier.alpha(0.25f) else Modifier,
                                )
                        },
                    )
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
                wiggleDegrees = iconWiggle,
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
            )
        }

        // Floating card under finger; static and dragged items keep one visual language.
        drag?.let { d ->
            val localX = d.pointerWindow.x - rootWindowOrigin.x
            val localY = d.pointerWindow.y - rootWindowOrigin.y
            val widthPx = with(density) { 80.dp.toPx() }
            RecordCatalogCard(
                label = d.label,
                recordType = d.recordType,
                customIconSlot = d.customIconSlot,
                colorRole = d.colorRole ?: LeziRecordColorRole.Care,
                contentDescription = "正在拖动${d.label}",
                modifier = Modifier
                    .zIndex(20f)
                    .offset {
                        IntOffset(
                            (localX - widthPx / 2f).roundToInt(),
                            (localY - widthPx / 2f - with(density) { LeziSpacing.Xs.toPx() })
                                .roundToInt(),
                        )
                    }
                    .width(80.dp)
                    .scale(1.08f)
                    .testTag("layout_edit_drag_avatar"),
            )
        }
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
    wiggleDegrees: Float,
    showAddCell: Boolean,
    onAddClick: () -> Unit,
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
                        contentDescription = labels[key] ?: key,
                        modifier = Modifier
                            .weight(1f)
                            .rotate(wiggleDegrees)
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
    wiggleDegrees: Float,
    targetRegistry: LayoutVisibleTargetRegistry,
    onTargetRegistryChanged: () -> Unit,
    onSlotDragStart: (Int, String, Offset) -> Long,
    onSlotDrag: (Long, Offset) -> Unit,
    onSlotDragEnd: (Long, Offset) -> Unit,
    onSlotDragCancel: (Long) -> Unit,
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
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = QuickDockVisualSpec.cellMinHeight)
                        .layoutTargetRegistration(
                            node = LayoutTargetNode.QuickSlot(index),
                            registry = targetRegistry,
                            onRegistryChanged = onTargetRegistryChanged,
                        )
                        .testTag("layout_edit_slot_$index")
                        .semantics {
                            contentDescription = if (key.isBlank()) {
                                "空槽${index + 1}，拖入图标设为常用"
                            } else {
                                "常用${index + 1}，$label，长按拖动替换"
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
                            .draggableCatalogKey(
                                catalogKey = key,
                                onDragStart = { onSlotDragStart(index, key, it) },
                                onDrag = onSlotDrag,
                                onDragEnd = onSlotDragEnd,
                                onDragCancel = onSlotDragCancel,
                            )
                            .alpha(if (dimmed) 0.25f else 1f)
                            .rotate(if (dimmed) 0f else wiggleDegrees)
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
                    .semantics { contentDescription = "更多，编辑布局时已锁定" },
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

private fun Modifier.draggableCatalogKey(
    catalogKey: String,
    onDragStart: (Offset) -> Long,
    onDrag: (Long, Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: (Long) -> Unit,
): Modifier {
    val originHolder = floatArrayOf(0f, 0f)
    return this
        .onGloballyPositioned { coords ->
            val p = coords.positionInWindow()
            originHolder[0] = p.x
            originHolder[1] = p.y
        }
        .pointerInput(catalogKey) {
            var lastWindow = Offset.Zero
            var activeToken: Long? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    lastWindow = Offset(originHolder[0], originHolder[1]) + local
                    activeToken = onDragStart(lastWindow)
                },
                onDrag = { change, _ ->
                    change.consume()
                    lastWindow = Offset(originHolder[0], originHolder[1]) + change.position
                    activeToken?.let { onDrag(it, lastWindow) }
                },
                onDragEnd = {
                    activeToken?.let { onDragEnd(it, lastWindow) }
                    activeToken = null
                },
                onDragCancel = {
                    activeToken?.let(onDragCancel)
                    activeToken = null
                },
            )
        }
}
