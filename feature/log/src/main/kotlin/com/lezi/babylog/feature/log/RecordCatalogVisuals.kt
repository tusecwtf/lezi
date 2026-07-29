package com.lezi.babylog.feature.log

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.ui.CUSTOM_ITEM_ICON_GLYPHS
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor

/** Shared authority for the four-column catalog used by 添加记录 and 编辑布局. */
internal object RecordCatalogVisualSpec {
    const val columnCount: Int = 4
    val columnSpacing: Dp = LeziSpacing.Xs
    val rowSpacing: Dp = LeziSpacing.Xs
    val cardMinHeight: Dp = 64.dp
    val iconSize: Dp = 32.dp
    val iconShape: RoundedCornerShape = LeziShapes.JournalCard
    val contentPadding: PaddingValues = PaddingValues(horizontal = 3.dp, vertical = 5.dp)
}

/** Shared geometry for the everyday and edit-mode five-cell quick dock. */
internal object QuickDockVisualSpec {
    const val configurableSlotCount: Int = 4
    val outerHorizontalWarm: Dp = LeziSpacing.Xs
    val outerVertical: Dp = LeziSpacing.Xxs
    val rowHorizontal: Dp = LeziSpacing.Xxs
    val rowVertical: Dp = 5.dp
    val cellSpacing: Dp = 2.dp
    val cellMinHeight: Dp = 64.dp
    val iconSize: Dp = 30.dp
}

internal fun <T> recordCatalogRows(items: List<T>): List<List<T>> =
    items.chunked(RecordCatalogVisualSpec.columnCount)

internal sealed interface LayoutEditDockCell {
    data class Configurable(val index: Int) : LayoutEditDockCell
    data object LockedMore : LayoutEditDockCell
}

internal data class LayoutEditCatalogSection(
    val key: String,
    val title: String,
)

/** Structural presentation contract consumed by the editor and asserted without pixels. */
internal object LayoutEditPresentation {
    const val title: String = "编辑布局"
    const val showsDateChrome: Boolean = false
    const val showsPrimaryNavigation: Boolean = false
    const val showsCommonSupplement: Boolean = false
    const val showsLocalDeletedHeading: Boolean = true

    val catalogSections: List<LayoutEditCatalogSection> = RecordSection.entries.map { section ->
        LayoutEditCatalogSection(section.name, section.title)
    }
    val dockCells: List<LayoutEditDockCell> =
        List(QuickDockVisualSpec.configurableSlotCount) { LayoutEditDockCell.Configurable(it) } +
            LayoutEditDockCell.LockedMore
}

@Composable
internal fun RecordCatalogSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = LeziTypography.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("record_catalog_heading_$title"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordCatalogCard(
    label: String,
    recordType: RecordType?,
    customIconSlot: Int?,
    colorRole: LeziRecordColorRole,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isAdd: Boolean = false,
) {
    val color = leziRecordColor(colorRole)
    val interactionModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    LeziCard(
        modifier = modifier
            .heightIn(min = RecordCatalogVisualSpec.cardMinHeight)
            .then(interactionModifier),
        onClick = null,
        contentPadding = RecordCatalogVisualSpec.contentPadding,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    this.contentDescription = contentDescription
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(RecordCatalogVisualSpec.iconSize)
                    .clip(RecordCatalogVisualSpec.iconShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isAdd) {
                    Text(
                        "＋",
                        style = LeziTypography.BodyStrong,
                        color = color,
                    )
                } else if (recordType == RecordType.CUSTOM || customIconSlot != null) {
                    Text(
                        CUSTOM_ITEM_ICON_GLYPHS[(customIconSlot ?: 0).coerceIn(0, 7)],
                        style = LeziTypography.BodyStrong,
                        color = color,
                    )
                } else if (recordType != null) {
                    RecordTypeIcon(recordType, size = 18.dp, tint = color)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = LeziTypography.Label.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
