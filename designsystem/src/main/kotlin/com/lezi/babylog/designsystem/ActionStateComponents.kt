package com.lezi.babylog.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp

@Composable
fun QuickRecordButton(
    title: String,
    subtitle: String,
    tone: LeziTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
) {
    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "quickScale")
    Surface(
        modifier = modifier
            .heightIn(min = 74.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        // Journal more-grid tiles keep a light border + 8dp (not full section cards).
        shape = if (journal) LeziShapes.JournalButton else LeziThemeExt.cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        ),
        shadowElevation = if (journal) LeziElevation.None else LeziThemeExt.cardElevation,
    ) {
        if (journal) Column(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(LeziShapes.JournalButton)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(title, style = LeziTypography.Label, maxLines = 1)
            Text(
                subtitle,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else Row(
            Modifier.padding(LeziSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = LeziTypography.BodyStrong)
                Text(subtitle, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

enum class StateKind { Loading, Empty, Error, Recording, Success }

@Composable
fun StateContainer(
    kind: StateKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // Use LeziCard (not banded panel) so nested empty states inside chart panels stay flat.
    LeziCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = LeziSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (kind) {
                    StateKind.Loading -> "…"
                    StateKind.Empty -> "○"
                    StateKind.Error -> "!"
                    StateKind.Recording -> "●"
                    StateKind.Success -> "✓"
                },
                style = LeziTypography.Display,
                color = when (kind) {
                    StateKind.Error -> LocalLeziColors.current.danger
                    StateKind.Success -> LocalLeziColors.current.success
                    StateKind.Recording -> LocalLeziColors.current.fab
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(title, style = LeziTypography.TitleSm)
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                LeziPrimaryButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/**
 * Visual / interactive mode for [LeziPrimaryButton].
 *
 * [ExplainedDisabled] keeps the normal shape and remains clickable so the caller
 * can show a concrete reason; it is not a Material-disabled control.
 */
enum class LeziPrimaryButtonMode {
    Enabled,
    ExplainedDisabled,
    Disabled,
}

@Composable
fun LeziPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mode: LeziPrimaryButtonMode = if (enabled) {
        LeziPrimaryButtonMode.Enabled
    } else {
        LeziPrimaryButtonMode.Disabled
    },
) {
    val journal = LeziThemeExt.isJournal
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickable = mode != LeziPrimaryButtonMode.Disabled
    val shape = LeziThemeExt.buttonShape
    val fillColor = when (mode) {
        LeziPrimaryButtonMode.Enabled -> MaterialTheme.colorScheme.primary
        LeziPrimaryButtonMode.ExplainedDisabled ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        LeziPrimaryButtonMode.Disabled ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val labelColor = when (mode) {
        LeziPrimaryButtonMode.Enabled -> MaterialTheme.colorScheme.onPrimary
        LeziPrimaryButtonMode.ExplainedDisabled ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        LeziPrimaryButtonMode.Disabled ->
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val hardEdge = journal && mode == LeziPrimaryButtonMode.Enabled
    val elevation = when {
        mode != LeziPrimaryButtonMode.Enabled -> LeziElevation.None
        hardEdge -> LeziElevation.None
        pressed -> LeziElevation.None
        else -> LeziElevation.ButtonWarm
    }
    val hardShadow = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f).let { base ->
        // Darken primary for the journal hard edge (template-v2 accent-strong).
        Color(
            red = (base.red * 0.72f).coerceIn(0f, 1f),
            green = (base.green * 0.72f).coerceIn(0f, 1f),
            blue = (base.blue * 0.72f).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }
    val edge = LeziElevation.JournalHardEdge
    Box(
        modifier = modifier
            .heightIn(min = LeziSpacing.Touch + if (hardEdge && !pressed) edge else 0.dp)
            .then(
                if (mode == LeziPrimaryButtonMode.ExplainedDisabled) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        if (hardEdge && !pressed) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(y = edge)
                    .clip(shape)
                    .background(hardShadow),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LeziSpacing.Touch)
                .offset(y = if (hardEdge && pressed) edge else 0.dp)
                .clickable(
                    enabled = clickable,
                    interactionSource = interaction,
                    indication = if (clickable) ripple() else null,
                    onClick = onClick,
                ),
            shape = shape,
            color = fillColor,
            contentColor = labelColor,
            shadowElevation = elevation,
        ) {
            Box(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = LeziTypography.Label, maxLines = 1)
            }
        }
    }
}

@Composable
fun LeziSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = LeziThemeExt.buttonShape
    Surface(
        modifier = modifier
            .heightIn(min = LeziSpacing.Touch)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = if (enabled) 1f else 0.45f,
                ),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (enabled) 1f else 0.38f,
        ),
    ) {
        Box(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = LeziTypography.Label, maxLines = 1)
        }
    }
}

@Composable
fun SectionHeading(
    eyebrow: String? = null,
    title: String,
    meta: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = LeziTypography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = LeziTypography.TitleSm)
        }
        if (meta != null) {
            Text(meta, style = LeziTypography.Meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Row(content = trailing)
        }
    }
}
