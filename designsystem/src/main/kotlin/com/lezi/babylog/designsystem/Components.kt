package com.lezi.babylog.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

enum class LeziTone { Blue, Yellow, Cream, Neutral }

@Composable
fun toneBg(tone: LeziTone): Color {
    val ext = LocalLeziColors.current
    return when (tone) {
        LeziTone.Blue -> ext.skySoft
        LeziTone.Yellow -> ext.sunSoft
        LeziTone.Cream -> ext.creamDeep
        LeziTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    }
}

/**
 * Warm: soft floating card (8dp radius + light elevation).
 * Journal: flat panel shell (0 radius, no elevation). Prefer [LeziSurfacePanel]
 * for full-bleed grid sections; this remains the shared chrome for mixed call sites.
 */
@Composable
fun LeziCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedPad = contentPadding ?: PaddingValues(
        if (LeziThemeExt.isElder) LeziThemeExt.density.cardPad else LeziSpacing.CardPad,
    )

    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.98f else 1f, label = "cardScale")
    Surface(
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        shape = LeziThemeExt.cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = if (journal) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
            )
        },
        shadowElevation = LeziThemeExt.cardElevation,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(resolvedPad), content = content)

    }
}

/**
 * Full-bleed section surface.
 * Journal: grid/panel with optional bottom band (template 8px bg stripe).
 * Warm: delegates to [LeziCard].
 *
 * Journal hairline ownership: the panel's 1dp bottom hairline closes generic
 * content. When the panel hosts [RecordRow]-style rows (each row already draws
 * its own bottom hairline), pass [bottomDivider] = false so the row hairlines
 * stay the single source and the nested edge does not double-draw.
 */
@Composable
fun LeziSurfacePanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    bottomBand: Boolean = false,
    bottomDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedPad = contentPadding ?: PaddingValues(
        if (LeziThemeExt.isElder) LeziThemeExt.density.cardPad else LeziSpacing.CardPad,
    )
    val journal = LeziThemeExt.isJournal
    if (!journal) {
        LeziCard(
            modifier = modifier,
            onClick = onClick,
            contentPadding = resolvedPad,
            content = content,
        )
        return
    }
    val interaction = remember { MutableInteractionSource() }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = ripple(bounded = true),
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            shape = LeziShapes.JournalFlat,
            color = MaterialTheme.colorScheme.surface,
            border = null,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(resolvedPad), content = content)

        }
        if (bottomBand) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.background),
            )
        } else if (bottomDivider) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(leziHairlineColor()),
            )
        }
    }
}


/** Off keeps the historical 18.dp clip box; elder follows ChipMetric line height. */
@Composable
fun leziChipMetricWellModifier(): Modifier {
    val line = LeziThemeExt.typography.ChipMetric.lineHeight
    return if (LeziThemeExt.isElder) {
        Modifier.heightIn(min = with(LocalDensity.current) { line.toDp() })
    } else {
        Modifier.height(18.dp)
    }
}

/**
 * Single tappable metric cell with a tone-tinted icon chip, value, and label.
 *
 * Division of labor vs `RecordSummaryStrip` (core/ui): this is the warm/log-home
 * **card** language (tinted background, per-cell border, 82dp min height) used as
 * standalone summary cards; `RecordSummaryStrip` is the flat four-cell **strip**
 * language (dividers between cells, record-type icons, filter semantics) embedded
 * in record panels. Keep both; new record-type filter strips belong to the strip.
 */
@Composable
fun SummaryMetric(
    value: String,
    label: String,
    tone: LeziTone = LeziTone.Neutral,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    selectionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val bg = toneBg(tone)
    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics {
                            role = Role.Button
                            this.selected = selected
                            contentDescription = selectionLabel ?: label
                        }
                } else {
                    Modifier
                },
            ),
        shape = LeziThemeExt.cardShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            bg
        },
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            },
        ),
        shadowElevation = if (selected) LeziThemeExt.cardElevation else LeziElevation.None,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                Modifier
                    .size(LeziIconSize.Chip)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) icon() else LeziPlaceholderDot()
            }
            Spacer(Modifier.height(LeziSpacing.Xxs))
            val chipMetric = LeziThemeExt.typography.ChipMetric
            val structure = LeziThemeExt.structure
            Box(
                leziChipMetricWellModifier(),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    value,
                    style = chipMetric,
                    maxLines = structure.chipMetricMaxLines,
                    overflow = if (structure.chipMetricSoftWrap) {
                        TextOverflow.Clip
                    } else {
                        TextOverflow.Ellipsis
                    },
                    softWrap = structure.chipMetricSoftWrap,
                )
            }
            Text(
                label,
                style = if (LeziThemeExt.isElder) {
                    LeziThemeExt.typography.Meta
                } else {
                    LeziTypography.Micro
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (LeziThemeExt.isElder) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
