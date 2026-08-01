package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

private val CustomGlyphs = com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS

@Composable
internal fun LauncherEditDock(
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
