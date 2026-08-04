package com.lezi.babylog.feature.log.dock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography

/** Dock shell stays near-opaque so content scrolling behind it never bleeds through. */
internal const val QuickDockContainerAlpha = 0.98f

/** Drop-target fill: one notch brighter than the regular emphasized cell. */
internal const val QuickDockDropTargetAlpha = 0.92f

/** Light wash of the record tint behind dock/catalog icons. */
internal const val QuickDockIconDiscAlpha = 0.14f

/**
 * Shared outer chrome of the five-cell quick dock (everyday + layout-edit):
 * surface, outline border, elevation, and the evenly spaced cell row.
 * [surfaceModifier] is applied after the outer padding (tags, drop-target registration).
 */
@Composable
internal fun QuickDockContainer(
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                // Structural outer inset: journal full-bleed; warm uses density top-bar role.
                horizontal = if (LeziThemeExt.isJournal) {
                    0.dp
                } else {
                    LeziThemeExt.density.topBarHorizontal
                },
                vertical = QuickDockVisualSpec.outerVertical,
            )
            .then(surfaceModifier),
        shape = LeziThemeExt.dockShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = QuickDockContainerAlpha),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = LeziAlphas.Emphasis),
        ),
        shadowElevation = LeziThemeExt.dockElevation,
    ) {
        Row(
            rowModifier
                .fillMaxWidth()
                .padding(
                    horizontal = QuickDockVisualSpec.rowHorizontal,
                    vertical = QuickDockVisualSpec.rowVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(QuickDockVisualSpec.cellSpacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** One weighted dock cell: control shape, min height, optional drop-target border. */
@Composable
internal fun RowScope.QuickDockCell(
    modifier: Modifier = Modifier,
    containerColor: Color,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = QuickDockVisualSpec.cellMinHeight)
            .then(modifier),
        shape = LeziThemeExt.controlShape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border,
    ) {
        content()
    }
}

/** Tinted circular disc carrying the record glyph inside a dock cell or catalog card. */
@Composable
internal fun QuickDockIconDisc(
    tint: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .size(QuickDockVisualSpec.iconSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = QuickDockIconDiscAlpha)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Shared "＋" placeholder for empty dock slots and the catalog add card. */
@Composable
internal fun QuickDockAddPlaceholder(tint: Color) {
    Text("＋", style = LeziTypography.BodyStrong, color = tint)
}
