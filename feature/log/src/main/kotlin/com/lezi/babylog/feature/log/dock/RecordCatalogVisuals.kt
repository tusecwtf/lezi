package com.lezi.babylog.feature.log.dock
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
import com.lezi.babylog.core.ui.RecordSection
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.designsystem.LeziCard
import com.lezi.babylog.designsystem.LeziCustomItemGlyphIcon
import com.lezi.babylog.designsystem.LeziRecordColorRole
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziRecordColor
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

/** Shared authority for the four-column catalog used by 添加记录 and 编辑布局. */
internal object RecordCatalogVisualSpec {
    const val columnCount: Int = 4
    val columnSpacing: Dp = LeziSpacing.Xs
    val rowSpacing: Dp = LeziSpacing.Xs
    val cardMinHeight: Dp = 64.dp
    // Two icon tiers across dock + catalog: 32dp disc, 18dp glyph inside a disc.
    val iconSize: Dp = 32.dp
    val innerIconSize: Dp = 18.dp
    val iconShape: RoundedCornerShape = LeziShapes.JournalCard
    val contentPadding: PaddingValues = PaddingValues(
        horizontal = LeziSpacing.Xxs,
        vertical = LeziSpacing.Xxs,
    )
}

/** Shared geometry for the everyday and edit-mode five-cell quick dock. */
internal object QuickDockVisualSpec {
    const val configurableSlotCount: Int = 4
    val outerHorizontalWarm: Dp = LeziSpacing.Xs
    val outerVertical: Dp = LeziSpacing.Xxs
    val rowHorizontal: Dp = LeziSpacing.Xxs
    val rowVertical: Dp = LeziSpacing.Xxs
    val cellSpacing: Dp = LeziSpacing.Xxs
    val cellMinHeight: Dp = 64.dp
    val iconSize: Dp = RecordCatalogVisualSpec.iconSize
    val occupiedHeight: Dp = outerVertical + outerVertical +
        rowVertical + rowVertical + cellMinHeight
    val snackbarSafetySpacing: Dp = LeziSpacing.Xs
    val snackbarBottomInset: Dp = occupiedHeight + snackbarSafetySpacing
}

/** Root Snackbar clearance while the everyday fixed quick dock is visible. */
val quickDockSnackbarBottomInset: Dp = QuickDockVisualSpec.snackbarBottomInset

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
                    .background(color.copy(alpha = QuickDockIconDiscAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                if (isAdd) {
                    QuickDockAddPlaceholder(tint = color)
                } else if (recordType == RecordType.CUSTOM || customIconSlot != null) {
                    LeziCustomItemGlyphIcon(
                        slot = customIconSlot ?: 0,
                        size = RecordCatalogVisualSpec.innerIconSize,
                        tint = color,
                    )
                } else if (recordType != null) {
                    RecordTypeIcon(
                        recordType,
                        size = RecordCatalogVisualSpec.innerIconSize,
                        tint = color,
                    )
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
