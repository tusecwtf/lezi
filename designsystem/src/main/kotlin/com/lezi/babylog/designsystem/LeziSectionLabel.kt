package com.lezi.babylog.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared max height for bottom-sheet scroll content, so the record composer and the
 * nursing completion sheet keep one viewport size (previously hand-picked 620dp / 530dp).
 */
val LeziSheetContentMax: Dp = 560.dp

/**
 * Single-tier section label inside sheets: one level below [SectionHeading]
 * (eyebrow + TitleSm title), rendered as a quiet Label in onSurfaceVariant.
 */
@Composable
fun LeziSectionLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = LeziTypography.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
