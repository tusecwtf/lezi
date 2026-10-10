package com.lezi.babylog.feature.log.dock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign

import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.core.ui.orderedRecordSections
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.core.ui.sortCatalogByLocalOrder
import com.lezi.babylog.designsystem.LeziCustomItemGlyphIcon
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/**
 * Always renders four configurable slots plus fixed "更多" (absolute LTR order).
 * Hidden / deleted / invalid refs blank a cell rather than removing it.
 * Long-press enters 布局编辑态.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OneHandQuickDock(
    storedSlots: List<String>,
    hiddenTypeKeys: Set<String>,
    customItems: List<CustomRecordItem>,
    sleepRunning: Boolean,
    onBound: (RecordItemIdentity) -> Unit,
    onEmpty: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
) {
    val resolved = remember(storedSlots, hiddenTypeKeys, customItems) {
        resolveQuickSlots(storedSlots, hiddenTypeKeys, customItems)
    }
    val cells = remember(resolved) {
        fixedQuickDockOrder(resolved)
    }
    QuickDockContainer(
        modifier = modifier,
        surfaceModifier = Modifier.testTag("one_hand_quick_dock_fixed"),
    ) {
        cells.forEachIndexed { index, cell ->
            val presentation = quickDockPresentation(cell, sleepRunning)
            val tint = when (cell) {
                is QuickDockCell.Bound ->
                    if (cell.recordType == RecordType.CUSTOM) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        leziRecordColor(cell.recordType.presentation.colorRole)
                    }
                QuickDockCell.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
                QuickDockCell.More -> MaterialTheme.colorScheme.primary
            }
            val tag = when (cell) {
                is QuickDockCell.Bound -> "one_hand_action_${cell.catalogKey}"
                QuickDockCell.Empty -> "one_hand_action_empty_$index"
                QuickDockCell.More -> "one_hand_action_more"
            }
            val cellInteraction = when (cell) {
                is QuickDockCell.Bound -> Modifier
                    .combinedClickable(
                        onClick = { onBound(cell.identity) },
                        onLongClick = onLongPress,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = presentation.contentDescription
                    }
                QuickDockCell.Empty -> Modifier
                    .pointerInput(onEmpty, onLongPress) {
                        detectTapGestures(
                            onTap = { onEmpty() },
                            onLongPress = { onLongPress() },
                        )
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                            onLongPress()
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .semantics(mergeDescendants = true) {
                        contentDescription = presentation.contentDescription
                        stateDescription = "短按无操作"
                        customActions = listOf(
                            CustomAccessibilityAction("编辑常用布局") {
                                onLongPress()
                                true
                            },
                        )
                    }
                QuickDockCell.More -> Modifier
                    .clickable(onClick = onMore)
                    .semantics(mergeDescendants = true) {
                        contentDescription = presentation.contentDescription
                    }
            }
            QuickDockCell(
                modifier = Modifier
                    .testTag(tag)
                    .then(cellInteraction),
                // Idle slots are not emphasized (ADR-0002/0006 do not ask for a
                // selected idle dock). Opaque surface keeps the press ripple visible.
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = QuickDockVisualSpec.rowVertical),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    QuickDockIconDisc(tint = tint) {
                        when (cell) {
                            is QuickDockCell.Bound -> {
                                if (cell.recordType == RecordType.CUSTOM) {
                                    LeziCustomItemGlyphIcon(
                                        slot = cell.customIconSlot ?: 0,
                                        size = RecordCatalogVisualSpec.innerIconSize,
                                        tint = tint,
                                    )
                                } else {
                                    RecordTypeIcon(cell.recordType, tint = tint)
                                }
                            }
                            QuickDockCell.Empty -> {
                                QuickDockAddPlaceholder(tint)
                            }
                            QuickDockCell.More -> {
                                LeziRecordGlyphIcon(
                                    glyph = LeziRecordGlyph.Other,
                                    tint = tint,
                                )
                            }
                        }
                    }
                    Text(
                        presentation.visualLabel,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (LeziThemeExt.isElder) 2 else 1,
                        overflow = if (LeziThemeExt.isElder) {
                            TextOverflow.Clip
                        } else {
                            TextOverflow.Ellipsis
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Catalog entry for the more sheet: built-in type or a concrete custom definition. */
internal sealed class MoreCatalogEntry {
    abstract val identity: RecordItemIdentity
    abstract val label: String
    abstract val section: RecordSection

    data class BuiltIn(
        val type: RecordType,
    ) : MoreCatalogEntry() {
        override val identity: RecordItemIdentity = RecordItemIdentity.builtIn(type)
        override val label: String get() = type.presentation.label
        override val section: RecordSection get() = type.presentation.section
    }

    data class Custom(
        val item: CustomRecordItem,
    ) : MoreCatalogEntry() {
        override val identity: RecordItemIdentity =
            RecordItemIdentity.custom(item.id, item.clientUuid)
        override val label: String get() = item.name
        override val section: RecordSection = RecordSection.Custom
    }
}

/**
 * Pure catalog builder: built-ins available for new entry + concrete custom items.
 * Memo / other / bare custom never appear. Order uses shared local category/item policy.
 */
internal fun moreSheetCatalog(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
): List<MoreCatalogEntry> {
    val builtIns = RecordType.availableForNewEntry()
        .filter { it.key !in settings.hiddenItems }
        .map { MoreCatalogEntry.BuiltIn(it) }
    val customs = customItems
        .filter {
            RecordItemIdentity.custom(it.id, it.clientUuid).catalogKey !in settings.hiddenItems
        }
        .map { MoreCatalogEntry.Custom(it) }
    return sortCatalogByLocalOrder(
        entries = builtIns + customs,
        sectionOf = { it.section },
        catalogKeyOf = { it.identity.catalogKey },
        categoryOrderJson = settings.categoryOrderJson,
        itemOrderJson = settings.itemOrderJson,
    )
}

internal fun moreSheetQuickSuggestions(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
): List<MoreCatalogEntry> {
    val preferred = listOf(
        RecordType.POOP,
        RecordType.TEMPERATURE,
        RecordType.WEIGHT,
        RecordType.DIARY,
    )
    val catalog = moreSheetCatalog(settings, customItems)
    val preferredEntries = preferred.mapNotNull { type ->
        catalog.firstOrNull { it is MoreCatalogEntry.BuiltIn && it.type == type }
    }
    return preferredEntries.ifEmpty { catalog.take(4) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MoreSheet(
    settings: SettingsLocal,
    customItems: List<CustomRecordItem>,
    onPick: (RecordItemIdentity) -> Unit,
    onLongPressItem: () -> Unit = {},
) {
    val catalog = remember(
        settings.itemOrderJson,
        settings.categoryOrderJson,
        settings.hiddenItems,
        customItems,
    ) {
        moreSheetCatalog(settings, customItems)
    }
    val suggestions = remember(
        settings.itemOrderJson,
        settings.categoryOrderJson,
        settings.hiddenItems,
        customItems,
    ) {
        moreSheetQuickSuggestions(settings, customItems)
    }
    // Everyday more: only non-empty sections with visible items; pure tap-to-log.
    val groups = orderedRecordSections(settings.categoryOrderJson).map { section ->
        section to catalog.filter { it.section == section }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("more_sheet_catalog"),
        contentPadding = PaddingValues(
            start = LeziSpacing.Md,
            top = LeziSpacing.Md,
            end = LeziSpacing.Md,
            bottom = LeziSpacing.Xxl,
        ),
    ) {
        item {
            Text("添加记录", style = LeziThemeExt.typography.Title)
            Spacer(Modifier.height(LeziSpacing.Sm))
            Text(
                "常用补充",
                style = LeziTypography.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    RecordCatalogVisualSpec.columnSpacing,
                ),
            ) {
                suggestions.forEach { entry ->
                    MoreCatalogCard(
                        entry = entry,
                        onClick = { onPick(entry.identity) },
                        onLongClick = onLongPressItem,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(
                    (RecordCatalogVisualSpec.columnCount - suggestions.size).coerceAtLeast(0),
                ) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(LeziSpacing.Md))
        }
        groups.forEach { (section, items) ->
            if (items.isEmpty()) return@forEach
            item {
                Column {
                    RecordCatalogSectionHeading(section.title)
                    Spacer(Modifier.height(LeziSpacing.Xs))
                    recordCatalogRows(items).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                RecordCatalogVisualSpec.columnSpacing,
                            ),
                        ) {
                            rowItems.forEach { entry ->
                                MoreCatalogCard(
                                    entry = entry,
                                    onClick = { onPick(entry.identity) },
                                    onLongClick = onLongPressItem,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(RecordCatalogVisualSpec.columnCount - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(RecordCatalogVisualSpec.rowSpacing))
                    }
                    Spacer(Modifier.height(LeziSpacing.Xxs))
                }
            }
        }
    }
}

@Composable
private fun MoreCatalogCard(
    entry: MoreCatalogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
) {
    val recordType = when (entry) {
        is MoreCatalogEntry.BuiltIn -> entry.type
        is MoreCatalogEntry.Custom -> RecordType.CUSTOM
    }
    val customIconSlot = (entry as? MoreCatalogEntry.Custom)?.item?.iconSlot
    val colorRole = when (entry) {
        is MoreCatalogEntry.BuiltIn -> entry.type.presentation.colorRole
        is MoreCatalogEntry.Custom -> RecordType.CUSTOM.presentation.colorRole
    }
    RecordCatalogCard(
        label = entry.label,
        recordType = recordType,
        customIconSlot = customIconSlot,
        colorRole = colorRole,
        contentDescription = moreRecordContentDescription(entry),
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
