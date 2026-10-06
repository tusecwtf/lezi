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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
            Text(
                title,
                style = LeziTypography.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    // Density-backed card pad so empty/loading chrome follows warm-open / journal-compact.
    LeziCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(LeziThemeExt.density.cardPad),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = LeziSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Empty uses a quiet ring (onSurfaceVariant); loading alone uses primary spinner.
            val markColor = when (kind) {
                StateKind.Error -> LocalLeziColors.current.danger
                StateKind.Success -> LocalLeziColors.current.success
                StateKind.Recording -> LocalLeziColors.current.fab
                StateKind.Empty -> MaterialTheme.colorScheme.onSurfaceVariant
                StateKind.Loading -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.size(LeziIconSize.State), contentAlignment = Alignment.Center) {
                if (kind == StateKind.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(LeziSpacing.Xl),
                        strokeWidth = 2.5.dp,
                        color = markColor,
                    )
                } else {
                    LeziStateMark(kind = kind, color = markColor)
                }
            }
            Spacer(Modifier.height(LeziSpacing.Xs))
            Text(title, style = LeziTypography.TitleSm)
            Spacer(Modifier.height(LeziSpacing.Xxs))
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

/**
 * Primary CTA: flat filled surface (warm-card language — no bottom hard-edge
 * strip, no floating shadow). Matches secondary/destructive flatness; only the
 * primary fill differs. Press feedback is ripple, not elevation change.
 */
@Composable
fun LeziPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    mode: LeziPrimaryButtonMode = if (enabled && !busy) {
        LeziPrimaryButtonMode.Enabled
    } else if (!enabled) {
        LeziPrimaryButtonMode.Disabled
    } else {
        LeziPrimaryButtonMode.Enabled
    },
) {
    val interaction = remember { MutableInteractionSource() }
    val clickable = mode != LeziPrimaryButtonMode.Disabled && !busy
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LeziThemeExt.touchTarget.primary)
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
            )
            .clickable(
                enabled = clickable,
                interactionSource = interaction,
                indication = if (clickable) ripple() else null,
                onClick = onClick,
            ),
        shape = shape,
        color = fillColor,
        contentColor = labelColor,
        shadowElevation = LeziElevation.None,
        tonalElevation = LeziElevation.None,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = labelColor,
                )
                Spacer(Modifier.width(LeziSpacing.Xs))
            }
            Text(label, style = LeziTypography.Label, maxLines = 1)
        }
    }
}

@Composable
fun LeziSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = LeziThemeExt.buttonShape
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.38f,
    )
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
            .clickable(enabled = enabled && !busy, onClick = onClick),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = contentColor,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
                Spacer(Modifier.width(LeziSpacing.Xs))
            } else if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(LeziSpacing.Xs))
            }
            Text(label, style = LeziTypography.Label, maxLines = 1)
        }
    }
}

/**
 * Icon-only control with [LeziSpacing.Touch] min size (month chevrons, overflow, …).
 *
 * Provides themed [LocalContentColor] (onSurface, disabled alpha) so migrations off
 * Material IconButton do not inherit ambient black and vanish in dark mode.
 * Explicit Icon `tint = …` at call sites still wins over the ambient default.
 */
@Composable
fun LeziIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else LeziAlphas.Disabled,
    )
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = LeziSpacing.Touch, minHeight = LeziSpacing.Touch)
            .clip(LeziThemeExt.buttonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Outlined secondary shape with error content color — the single product
 * language for destructive actions outside dialogs (delete family, leave, …).
 */
@Composable
fun LeziDestructiveButton(
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
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.error.copy(
            alpha = if (enabled) 1f else LeziAlphas.Disabled,
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

/** Tone for compact text actions (dialog slots, inline links). */
enum class LeziTextButtonTone {
    /** onSurface / default dialog action. */
    Neutral,

    /** Primary-colored affirmative (confirm, continue). */
    Primary,

    /** Error-colored destructive confirm. */
    Destructive,

    /**
     * White label for chrome over dark media scrims (photo preview close).
     * Keeps dismiss readable on light photos without forking button geometry.
     */
    OnMedia,
}

/**
 * Compact text action used in dialog button slots and quiet inline rows.
 * Always meets [LeziSpacing.Touch] min width and height; shape/typeface from tokens.
 */
@Composable
fun LeziTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: LeziTextButtonTone = LeziTextButtonTone.Neutral,
) {
    val color = when (tone) {
        LeziTextButtonTone.Neutral -> MaterialTheme.colorScheme.onSurface
        LeziTextButtonTone.Primary -> MaterialTheme.colorScheme.primary
        LeziTextButtonTone.Destructive -> MaterialTheme.colorScheme.error
        LeziTextButtonTone.OnMedia -> Color.White
    }.copy(alpha = if (enabled) 1f else LeziAlphas.Disabled)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = LeziSpacing.Touch, minHeight = LeziSpacing.Touch)
            .clip(LeziThemeExt.buttonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = LeziSpacing.Sm, vertical = LeziSpacing.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LeziTypography.Label,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Single-select chip for settings/onboarding choices. Enforces [LeziSpacing.Touch]
 * min height so FilterChip defaults (~32dp) never ship as product touch targets.
 */
@Composable
fun LeziFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LeziFilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label, style = LeziTypography.Label, maxLines = 1) },
    )
}

/** Composable-label overload for glyph / icon chips (custom item icon picker). */
@Composable
fun LeziFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = label,
        modifier = modifier.heightIn(min = LeziSpacing.Touch),
        shape = LeziThemeExt.controlShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Page / panel section title (TitleSm, optional eyebrow + meta + trailing).
 * Sheet-local quiet labels use [LeziSectionLabel] instead — do not invent a third tier.
 */
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
                Text(eyebrow, style = LeziThemeExt.typography.Eyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
