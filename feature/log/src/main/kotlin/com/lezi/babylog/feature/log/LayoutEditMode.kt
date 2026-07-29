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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.catalogSectionForKey
import com.lezi.babylog.core.ui.knownCatalogKeys
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CustomRecordItem
import kotlin.math.abs
import kotlin.math.roundToInt

private val CustomGlyphs = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

private data class LayoutDragState(
    val catalogKey: String,
    val label: String,
    val pointerWindow: Offset,
    val sourceSlotIndex: Int?,
    val sourceIsDeleted: Boolean,
    val colorRole: LeziRecordColorRole?,
    val recordType: RecordType?,
    val customIconSlot: Int?,
)

private data class LayoutItemVisual(
    val label: String,
    val recordType: RecordType?,
    val customIconSlot: Int?,
    val colorRole: LeziRecordColorRole,
)

/**
 * Full-screen Android home-screen style layout editor:
 * icon grid + floating drag avatar + bottom dock slots.
 * No text lists / ↑↓ rows — only icons (with short labels under).
 */
@Composable
internal fun LayoutEditCanvas(
    prefs: DeviceLayoutPrefs,
    customItems: List<CustomRecordItem>,
    onIntent: (LayoutEditIntent) -> Unit,
    onDone: () -> Unit,
    onOpenCustomManage: () -> Unit,
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

    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    val catalogItemBounds = remember { mutableStateMapOf<String, Rect>() }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var rootWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf<LayoutDragState?>(null) }
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

    fun dropAt(
        windowPos: Offset,
        sourceKey: String,
        sourceIsDeleted: Boolean,
        sourceSlotIndex: Int? = null,
    ) {
        val intent = resolveLayoutDrop(
            pointerWindow = windowPos,
            slotBounds = slotBounds.toMap(),
            trashBounds = trashBounds,
            sourceKey = sourceKey,
            sourceIsDeleted = sourceIsDeleted,
            sourceSlotIndex = sourceSlotIndex,
            catalogItemBounds = catalogItemBounds.toMap(),
            itemOrderJson = prefs.itemOrderJson,
            knownKeys = known,
            hiddenItems = prefs.hiddenItems,
        )
        if (intent != null) onIntent(intent)
    }

    fun beginDrag(
        key: String,
        windowPos: Offset,
        sourceSlotIndex: Int? = null,
        sourceIsDeleted: Boolean = false,
    ) {
        val visual = visualByKey[key]
        drag = LayoutDragState(
            catalogKey = key,
            label = labels[key] ?: key,
            pointerWindow = windowPos,
            sourceSlotIndex = sourceSlotIndex,
            sourceIsDeleted = sourceIsDeleted,
            colorRole = visual?.colorRole,
            recordType = visual?.recordType,
            customIconSlot = visual?.customIconSlot,
        )
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
            // Minimal chrome — only Done (launcher style)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.testTag("layout_edit_done"),
                ) {
                    Text("完成")
                }
            }

            // Icon wall (catalog)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                sections.forEach { section ->
                    val keys = layoutEditVisibleKeys(prefs, section, known)
                    // Subtle section pin — not a settings list header
                    if (keys.isNotEmpty() || section == RecordSection.Custom) {
                        Spacer(Modifier.height(10.dp))
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .testTag("layout_edit_section_${section.storageKey}"),
                    ) {
                        val showAdd = section == RecordSection.Custom
                        IconGrid(
                            keys = keys,
                            labels = labels,
                            visualByKey = visualByKey,
                            wiggleDegrees = iconWiggle,
                            showAddCell = showAdd,
                            onAddClick = onOpenCustomManage,
                            itemModifier = { key ->
                                val dragging = drag?.catalogKey == key &&
                                    drag?.sourceSlotIndex == null &&
                                    drag?.sourceIsDeleted != true
                                Modifier
                                    .onGloballyPositioned { coords ->
                                        catalogItemBounds[key] = coords.boundsInWindow()
                                    }
                                    .draggableCatalogKey(
                                        catalogKey = key,
                                        onDragStart = { beginDrag(key, it) },
                                        onDrag = { pos ->
                                            drag = drag?.copy(pointerWindow = pos)
                                        },
                                        onDragEnd = { pos ->
                                            dropAt(pos, key, sourceIsDeleted = false)
                                            drag = null
                                        },
                                        onDragCancel = { drag = null },
                                    )
                                    .testTag("layout_edit_item_$key")
                                    .then(
                                        if (dragging) Modifier.alpha(0.25f) else Modifier,
                                    )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Remove / 本机已删除 tray (icon drop zone, not a text form)
            val trashHot = drag != null &&
                trashBounds?.contains(drag!!.pointerWindow) == true
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .heightIn(min = 72.dp)
                    .onGloballyPositioned { trashBounds = it.boundsInWindow() }
                    .clip(RoundedCornerShape(16.dp))
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
                                RoundedCornerShape(16.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("layout_edit_local_deleted"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (deleted.isEmpty()) {
                    // Pure glyph placeholder — no long copy
                    Box(
                        Modifier
                            .size(40.dp)
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
                    IconGrid(
                        keys = deleted,
                        labels = labels,
                        visualByKey = visualByKey,
                        wiggleDegrees = iconWiggle,
                        showAddCell = false,
                        onAddClick = {},
                        itemModifier = { key ->
                            val dragging = drag?.catalogKey == key &&
                                drag?.sourceIsDeleted == true
                            Modifier
                                .draggableCatalogKey(
                                    catalogKey = key,
                                    onDragStart = {
                                        beginDrag(key, it, sourceIsDeleted = true)
                                    },
                                    onDrag = { pos ->
                                        drag = drag?.copy(pointerWindow = pos)
                                    },
                                    onDragEnd = { pos ->
                                        dropAt(pos, key, sourceIsDeleted = true)
                                        drag = null
                                    },
                                    onDragCancel = { drag = null },
                                )
                                .testTag("layout_edit_deleted_$key")
                                .then(
                                    if (dragging) Modifier.alpha(0.25f) else Modifier,
                                )
                        },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Bottom dock — drop targets for quick slots (Android dock metaphor)
            LauncherEditDock(
                slots = slots,
                labels = labels,
                visualByKey = visualByKey,
                dragPointer = drag?.pointerWindow,
                dragKey = drag?.catalogKey,
                dragFromSlot = drag?.sourceSlotIndex,
                wiggleDegrees = iconWiggle,
                slotBounds = slotBounds,
                onSlotBounds = { index, rect -> slotBounds[index] = rect },
                onSlotDragStart = { index, key, pos ->
                    beginDrag(key, pos, sourceSlotIndex = index)
                },
                onSlotDrag = { pos -> drag = drag?.copy(pointerWindow = pos) },
                onSlotDragEnd = { index, key, pos ->
                    dropAt(pos, key, sourceIsDeleted = false, sourceSlotIndex = index)
                    drag = null
                },
                onSlotDragCancel = { drag = null },
            )
        }

        // Floating launcher icon under finger
        drag?.let { d ->
            val localX = d.pointerWindow.x - rootWindowOrigin.x
            val localY = d.pointerWindow.y - rootWindowOrigin.y
            val sizePx = with(density) { 72.dp.toPx() }
            val tint = d.colorRole?.let { leziRecordColor(it) }
                ?: MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .zIndex(20f)
                    .offset {
                        IntOffset(
                            (localX - sizePx / 2f).roundToInt(),
                            (localY - sizePx / 2f - with(density) { 8.dp.toPx() }).roundToInt(),
                        )
                    }
                    .size(72.dp)
                    .scale(1.12f)
                    .testTag("layout_edit_drag_avatar"),
                contentAlignment = Alignment.Center,
            ) {
                LauncherIconBadge(
                    recordType = d.recordType,
                    customIconSlot = d.customIconSlot,
                    tint = tint,
                    badgeSize = 56.dp,
                    elevated = true,
                )
            }
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
private fun IconGrid(
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
    cells.chunked(4).forEach { row ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.forEach { cell ->
                if (cell.isAdd) {
                    LauncherIconCell(
                        label = "添加",
                        recordType = null,
                        customIconSlot = null,
                        colorRole = null,
                        dimmed = false,
                        wiggleDegrees = 0f,
                        emptyPlus = true,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onAddClick)
                            .testTag("layout_edit_custom_manage"),
                    )
                } else {
                    val key = cell.key!!
                    val visual = visualByKey[key]
                    LauncherIconCell(
                        label = labels[key] ?: key,
                        recordType = visual?.recordType,
                        customIconSlot = visual?.customIconSlot,
                        colorRole = visual?.colorRole,
                        dimmed = false,
                        wiggleDegrees = wiggleDegrees,
                        modifier = Modifier
                            .weight(1f)
                            .then(itemModifier(key)),
                    )
                }
            }
            repeat(4 - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun LauncherIconCell(
    label: String,
    recordType: RecordType?,
    customIconSlot: Int?,
    colorRole: LeziRecordColorRole?,
    dimmed: Boolean,
    wiggleDegrees: Float,
    emptyPlus: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        emptyPlus -> MaterialTheme.colorScheme.onSurfaceVariant
        colorRole != null -> leziRecordColor(colorRole)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier
            .heightIn(min = 84.dp)
            .padding(vertical = 4.dp)
            .alpha(if (dimmed) 0.3f else 1f)
            .rotate(wiggleDegrees)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        LauncherIconBadge(
            recordType = if (emptyPlus) null else recordType,
            customIconSlot = customIconSlot,
            tint = tint,
            badgeSize = 52.dp,
            emptyPlus = emptyPlus,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = LeziTypography.Meta.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun LauncherIconBadge(
    recordType: RecordType?,
    customIconSlot: Int?,
    tint: Color,
    badgeSize: Dp,
    elevated: Boolean = false,
    emptyPlus: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(badgeSize)
            .then(
                if (elevated) {
                    Modifier
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(tint.copy(alpha = if (emptyPlus) 0.10f else 0.18f))
            .then(
                if (elevated) {
                    Modifier.border(1.5.dp, tint.copy(alpha = 0.55f), shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            emptyPlus -> Text(
                "＋",
                style = LeziTypography.TitleSm,
                color = tint,
            )
            recordType == RecordType.CUSTOM ||
                (recordType == null && customIconSlot != null) -> {
                Text(
                    CustomGlyphs[(customIconSlot ?: 0).coerceIn(0, 7)],
                    style = LeziTypography.TitleSm,
                    color = tint,
                )
            }
            recordType != null -> RecordTypeIcon(
                recordType,
                size = badgeSize * 0.48f,
                tint = tint,
            )
            else -> Text("＋", style = LeziTypography.TitleSm, color = tint)
        }
    }
}

@Composable
private fun LauncherEditDock(
    slots: List<String>,
    labels: Map<String, String>,
    visualByKey: Map<String, LayoutItemVisual>,
    dragPointer: Offset?,
    dragKey: String?,
    dragFromSlot: Int?,
    wiggleDegrees: Float,
    slotBounds: Map<Int, Rect>,
    onSlotBounds: (Int, Rect) -> Unit,
    onSlotDragStart: (Int, String, Offset) -> Unit,
    onSlotDrag: (Offset) -> Unit,
    onSlotDragEnd: (Int, String, Offset) -> Unit,
    onSlotDragCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("layout_edit_dock"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.forEachIndexed { index, key ->
                val hot = dragPointer != null &&
                    slotBounds[index]?.contains(dragPointer) == true
                val visual = if (key.isNotBlank()) visualByKey[key] else null
                val label = when {
                    key.isBlank() -> "空"
                    else -> labels[key] ?: key
                }
                val dimmed = dragKey == key && dragFromSlot == index
                Column(
                    Modifier
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            onSlotBounds(index, coords.boundsInWindow())
                        }
                        .then(
                            if (hot) {
                                Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    )
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(16.dp),
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = 4.dp)
                        .testTag("layout_edit_slot_$index")
                        .semantics {
                            contentDescription = if (key.isBlank()) {
                                "空槽${index + 1}，拖入图标设为常用"
                            } else {
                                "常用${index + 1}，$label，长按拖动替换"
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bodyMod = if (key.isNotBlank()) {
                        Modifier
                            .draggableCatalogKey(
                                catalogKey = key,
                                onDragStart = { onSlotDragStart(index, key, it) },
                                onDrag = onSlotDrag,
                                onDragEnd = { onSlotDragEnd(index, key, it) },
                                onDragCancel = onSlotDragCancel,
                            )
                            .alpha(if (dimmed) 0.25f else 1f)
                            .rotate(if (dimmed) 0f else wiggleDegrees)
                    } else {
                        Modifier
                    }
                    Column(
                        bodyMod,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (key.isBlank()) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "＋",
                                    style = LeziTypography.TitleSm,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                " ",
                                style = LeziTypography.Meta.copy(fontSize = 12.sp),
                            )
                        } else {
                            val tint = visual?.let { leziRecordColor(it.colorRole) }
                                ?: MaterialTheme.colorScheme.primary
                            LauncherIconBadge(
                                recordType = visual?.recordType,
                                customIconSlot = visual?.customIconSlot,
                                tint = tint,
                                badgeSize = 48.dp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label,
                                style = LeziTypography.Meta.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                }
            }
            // Locked 更多 — not a drop target
            Column(
                Modifier
                    .weight(1f)
                    .alpha(0.45f)
                    .padding(vertical = 4.dp)
                    .testTag("layout_edit_more_locked"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LeziRecordGlyphIcon(
                        glyph = LeziRecordGlyph.Other,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 22.dp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "更多",
                    style = LeziTypography.Meta.copy(fontSize = 12.sp),
                )
            }
        }
    }
}

private fun Modifier.draggableCatalogKey(
    catalogKey: String,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
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
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    lastWindow = Offset(originHolder[0], originHolder[1]) + local
                    onDragStart(lastWindow)
                },
                onDrag = { change, _ ->
                    change.consume()
                    lastWindow = Offset(originHolder[0], originHolder[1]) + change.position
                    onDrag(lastWindow)
                },
                onDragEnd = { onDragEnd(lastWindow) },
                onDragCancel = onDragCancel,
            )
        }
}

/**
 * Resolve drop using window coordinates.
 *
 * - Catalog → slot: [LayoutEditIntent.AssignToSlot]
 * - Bound slot → other slot: [LayoutEditIntent.SwapSlots]
 * - Any non-deleted → trash: [LayoutEditIntent.MoveToLocalDeleted]
 * - Deleted → outside trash: [LayoutEditIntent.RestoreFromLocalDeleted]
 * - Catalog → same-section catalog cell: [LayoutEditIntent.ReorderItemInSection]
 * - Bound slot drag-off: [LayoutEditIntent.ClearSlot]
 */
internal fun resolveLayoutDrop(
    pointerWindow: Offset?,
    slotBounds: Map<Int, Rect>,
    trashBounds: Rect?,
    sourceKey: String,
    sourceIsDeleted: Boolean,
    sourceSlotIndex: Int? = null,
    catalogItemBounds: Map<String, Rect> = emptyMap(),
    itemOrderJson: String = "[]",
    knownKeys: Collection<String> = emptyList(),
    @Suppress("UNUSED_PARAMETER") hiddenItems: Set<String> = emptySet(),
): LayoutEditIntent? {
    val pos = pointerWindow ?: return null
    if (trashBounds?.contains(pos) == true && !sourceIsDeleted) {
        return LayoutEditIntent.MoveToLocalDeleted(sourceKey)
    }
    if (sourceIsDeleted && trashBounds?.contains(pos) != true) {
        return LayoutEditIntent.RestoreFromLocalDeleted(sourceKey)
    }
    val hit = slotBounds.entries
        .filter { it.value.contains(pos) }
        .minByOrNull { abs(it.value.center.x - pos.x) }
        ?.key
    if (hit != null && !sourceIsDeleted) {
        if (sourceSlotIndex != null) {
            if (hit == sourceSlotIndex) return null
            return LayoutEditIntent.SwapSlots(sourceSlotIndex, hit)
        }
        return LayoutEditIntent.AssignToSlot(hit, sourceKey)
    }
    if (!sourceIsDeleted && sourceSlotIndex == null && catalogItemBounds.isNotEmpty()) {
        val hitKey = catalogItemBounds.entries
            .filter { it.value.contains(pos) }
            .minByOrNull {
                abs(it.value.center.x - pos.x) + abs(it.value.center.y - pos.y)
            }
            ?.key
        if (hitKey != null && hitKey != sourceKey) {
            val sourceSection = catalogSectionForKey(sourceKey)
            val targetSection = catalogSectionForKey(hitKey)
            if (sourceSection != null && sourceSection == targetSection) {
                val fullIndex = com.lezi.babylog.core.ui.orderedKeysInSection(
                    sourceSection,
                    itemOrderJson,
                    knownKeys,
                ).indexOf(hitKey)
                if (fullIndex >= 0) {
                    return LayoutEditIntent.ReorderItemInSection(sourceKey, fullIndex)
                }
            }
        }
    }
    if (sourceSlotIndex != null && !sourceIsDeleted) {
        return LayoutEditIntent.ClearSlot(sourceSlotIndex)
    }
    return null
}
