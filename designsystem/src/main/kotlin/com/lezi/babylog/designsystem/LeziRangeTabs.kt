package com.lezi.babylog.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared segmented switcher (summary 日/周/月, growth 体重/身长, …).
 *
 * The selected pill slides between equal-width slots while tab text color
 * animates, so switching reads as one continuous control instead of a snap.
 */
@Composable
fun <T> LeziRangeTabs(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "LeziRangeTabs needs at least one item" }
    val trackShape = LeziThemeExt.controlShape
    val tabShape = if (LeziThemeExt.isJournal) LeziShapes.JournalSm else LeziShapes.Sm
    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
    val gap = LeziSpacing.Xxs
    Box(
        modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(gap),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tabWidth = (maxWidth - gap * (items.size - 1)) / items.size
            val indicatorOffset by animateDpAsState(
                targetValue = (tabWidth + gap) * selectedIndex,
                label = "range_tab_indicator",
            )
            Box(
                Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(tabShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        tabShape,
                    ),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items.forEach { item ->
                    val on = item == selected
                    val textColor by animateColorAsState(
                        targetValue = if (on) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "range_tab_text",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(tabShape)
                            .heightIn(min = LeziSpacing.Touch)
                            .selectable(
                                selected = on,
                                role = Role.Tab,
                                onClick = { onSelect(item) },
                            )
                            .padding(vertical = LeziSpacing.Sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(item),
                            style = LeziTypography.Label,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
