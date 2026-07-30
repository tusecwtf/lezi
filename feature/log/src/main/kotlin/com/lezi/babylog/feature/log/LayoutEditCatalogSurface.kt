package com.lezi.babylog.feature.log

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.catalogSectionForKey
import com.lezi.babylog.core.ui.orderedKeysInSection
import com.lezi.babylog.core.ui.storageKey
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography

internal data class LayoutItemVisual(
    val label: String,
    val recordType: RecordType?,
    val customIconSlot: Int?,
    val colorRole: LeziRecordColorRole,
)

@Composable
internal fun LayoutEditCatalogSurface(
    prefs: DeviceLayoutPrefs,
    known: List<String>,
    labels: Map<String, String>,
    visualByKey: Map<String, LayoutItemVisual>,
    slots: List<String>,
    sections: List<RecordSection>,
    fullCategoryOrder: List<RecordSection>,
    deleted: List<String>,
    catalogScrollState: ScrollState,
    localDeletedScrollState: ScrollState,
    drag: LayoutDragState?,
    targetRegistry: LayoutVisibleTargetRegistry,
    categoryFocusRequesters: Map<RecordSection, FocusRequester>,
    itemFocusRequesters: Map<String, FocusRequester>,
    onCatalogViewportBoundsChanged: (Rect) -> Unit,
    onTargetRegistryChanged: () -> Unit,
    onDragStart: (LayoutDragSource, String, Offset, String?) -> Long,
    onDrag: (Long, Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: (LayoutDragCancelReason, Long?) -> Unit,
    onIntent: (LayoutEditIntent) -> Unit,
    onOpenCustomManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    fun notifyTargetRegistryChanged() = onTargetRegistryChanged()

    fun beginDrag(
        source: LayoutDragSource,
        key: String,
        windowPos: Offset,
        labelOverride: String? = null,
    ): Long = onDragStart(source, key, windowPos, labelOverride)

    fun updateDrag(token: Long, windowPos: Offset) = onDrag(token, windowPos)

    fun finishDrag(token: Long, windowPos: Offset) = onDragEnd(token, windowPos)

    fun cancelActiveDrag(reason: LayoutDragCancelReason, token: Long? = null) =
        onDragCancel(reason, token)

    // Catalog and local-deleted are sibling scroll regions. Their shared viewport is
    // measured after the fixed toolbar and dock, so neither scroll surface can cover it.
    BoxWithConstraints(modifier) {
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
                        onCatalogViewportBoundsChanged(coordinates.boundsInWindow())
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
