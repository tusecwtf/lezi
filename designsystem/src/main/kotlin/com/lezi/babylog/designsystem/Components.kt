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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun LeziCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(LeziSpacing.CardPad),
    content: @Composable ColumnScope.() -> Unit,
) {
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
        shape = if (journal) LeziShapes.JournalCard else LeziShapes.Md,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = if (journal) 0.95f else 0.65f),
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

data class JournalSummaryValue(
    val value: String,
    val label: String,
    val tone: LeziTone,
)

/** Five-column glance strip used by the compact record-book template. */
@Composable
fun JournalSummaryStrip(
    values: List<JournalSummaryValue>,
    modifier: Modifier = Modifier,
) {
    LeziCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val visibleValues = values.take(5)
            visibleValues.forEachIndexed { index, item ->
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 3.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(toneBg(item.tone)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        )
                    }
                    Text(item.value, style = LeziTypography.Mono, maxLines = 1)
                    Text(
                        item.label,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (index < visibleValues.lastIndex) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(58.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryMetric(
    value: String,
    label: String,
    tone: LeziTone = LeziTone.Neutral,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val bg = toneBg(tone)
    Surface(
        modifier = modifier.heightIn(min = 82.dp),
        shape = LeziShapes.Md,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) icon() else Text("·", style = LeziTypography.Label)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = LeziTypography.Mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = LeziTypography.Meta.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
