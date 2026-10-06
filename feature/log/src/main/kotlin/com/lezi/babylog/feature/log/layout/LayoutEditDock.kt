package com.lezi.babylog.feature.log.layout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziCustomItemGlyphIcon
import com.lezi.babylog.designsystem.LeziRecordGlyph
import com.lezi.babylog.designsystem.LeziRecordGlyphIcon
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

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
    QuickDockContainer(
        surfaceModifier = Modifier
            .layoutTargetRegistration(
                node = LayoutTargetNode.Dock,
                registry = targetRegistry,
                onRegistryChanged = onTargetRegistryChanged,
            )
            .testTag("layout_edit_dock"),
        rowModifier = Modifier.focusGroup(),
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
            QuickDockCell(
                modifier = Modifier
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
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = if (hot) QuickDockDropTargetAlpha else LeziAlphas.Emphasis,
                ),
                border = if (hot) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
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
                    QuickDockIconDisc(tint = tint) {
                        when {
                            key.isBlank() -> QuickDockAddPlaceholder(tint)
                            visual?.recordType == RecordType.CUSTOM -> LeziCustomItemGlyphIcon(
                                slot = visual.customIconSlot ?: 0,
                                size = RecordCatalogVisualSpec.innerIconSize,
                                tint = tint,
                            )
                            visual?.recordType != null -> RecordTypeIcon(
                                visual.recordType,
                                tint = tint,
                            )
                        }
                    }
                    if (key.isBlank()) {
                        // Fixed-height placeholder matching the occupied label line.
                        Spacer(
                            Modifier.height(
                                with(LocalDensity.current) {
                                    LeziTypography.Meta.lineHeight.toDp()
                                },
                            ),
                        )
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
        QuickDockCell(
            modifier = Modifier
                .alpha(LeziAlphas.Disabled)
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
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                alpha = LeziAlphas.Emphasis,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = QuickDockVisualSpec.rowVertical),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                QuickDockIconDisc(tint = MaterialTheme.colorScheme.primary) {
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
