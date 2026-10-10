package com.lezi.babylog.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Absolute-screen swipe secondary actions for timeline cells.
 *
 * Gesture progress drives an **in-card fill** (content stays put):
 * - Finger moves **left** (negative progress) → green **edit** grows from the right.
 * - Finger moves **right** (positive progress) → red **delete** grows from the left.
 *
 * Directions never mirror with preferred-hand layout.
 */
enum class SwipeEditDeleteSettle {
    SettledClosed,
    RevealedEdit,
    RevealedDelete,
    CommitEdit,
    CommitDelete,
}

/** Half-row reveal target as a fraction of row width (design ≈28%, ±5% ok). */
const val SWIPE_REVEAL_RATIO = 0.28f

/** Full-travel commit threshold as a fraction of row width (design ≈55%, ±5% ok). */
const val SWIPE_COMMIT_RATIO = 0.55f

/**
 * Snap / rebound duration — aliases [LeziMotion.Base] so the swipe settle stays
 * on the motion token tiers (motion-polish ticket 03). Resolve per composition
 * with [leziMotionMillis] (reduce-motion collapses to instant); the constant
 * name remains the call-site anchor.
 */
val SWIPE_SETTLE_MS: Int = LeziMotion.Base

/**
 * Pure settle from swipe progress ratio (signedPx / width).
 * Positive ratio = delete fill from the left; negative = edit fill from the right.
 */
fun settleSwipeEditDelete(
    offsetRatio: Float,
    revealRatio: Float = SWIPE_REVEAL_RATIO,
    commitRatio: Float = SWIPE_COMMIT_RATIO,
    editEnabled: Boolean = true,
    deleteEnabled: Boolean = true,
): SwipeEditDeleteSettle {
    val absRatio = abs(offsetRatio)
    return when {
        offsetRatio > 0f && deleteEnabled && absRatio >= commitRatio ->
            SwipeEditDeleteSettle.CommitDelete
        offsetRatio < 0f && editEnabled && absRatio >= commitRatio ->
            SwipeEditDeleteSettle.CommitEdit
        offsetRatio > 0f && deleteEnabled && absRatio >= revealRatio ->
            SwipeEditDeleteSettle.RevealedDelete
        offsetRatio < 0f && editEnabled && absRatio >= revealRatio ->
            SwipeEditDeleteSettle.RevealedEdit
        else -> SwipeEditDeleteSettle.SettledClosed
    }
}

/** Settled progress ratio for a non-commit settle result (drives in-card fill width). */
fun targetOffsetRatioForSettle(
    settle: SwipeEditDeleteSettle,
    revealRatio: Float = SWIPE_REVEAL_RATIO,
): Float = when (settle) {
    SwipeEditDeleteSettle.RevealedDelete -> revealRatio
    SwipeEditDeleteSettle.RevealedEdit -> -revealRatio
    SwipeEditDeleteSettle.SettledClosed,
    SwipeEditDeleteSettle.CommitDelete,
    SwipeEditDeleteSettle.CommitEdit,
    -> 0f
}

/** Show action label once the fill is wide enough to host icon + copy. */
internal const val SWIPE_LABEL_MIN_RATIO = 0.16f

@Composable
fun SwipeEditDeleteRow(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    editEnabled: Boolean = true,
    deleteEnabled: Boolean = true,
    editTestTag: String? = null,
    deleteTestTag: String? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val animOffset = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var crossedCommit by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var previousPermissions by remember { mutableStateOf(editEnabled to deleteEnabled) }
    val success = LocalLeziColors.current.success
    val danger = LocalLeziColors.current.danger
    val density = LocalDensity.current
    // One reduce-motion-aware resolution shared by every animateTo below.
    val swipeSettleMs = leziMotionMillis(SWIPE_SETTLE_MS)

    fun widthOrDefault(): Float = widthPx.takeIf { it > 0f } ?: 1f

    fun maxDeleteOffset(): Float =
        if (deleteEnabled) widthOrDefault() * SWIPE_COMMIT_RATIO else 0f

    fun maxEditOffset(): Float =
        if (editEnabled) -widthOrDefault() * SWIPE_COMMIT_RATIO else 0f

    fun emitOpen(openNow: Boolean) {
        if (open != openNow) onOpenChange(openNow)
    }

    fun settleFromDrag(current: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val ratio = current / widthOrDefault()
            val settle = settleSwipeEditDelete(
                offsetRatio = ratio,
                editEnabled = editEnabled,
                deleteEnabled = deleteEnabled,
            )
            when (settle) {
                SwipeEditDeleteSettle.CommitEdit -> {
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(0f, animationSpec = tween(swipeSettleMs))
                    emitOpen(false)
                    onEdit()
                }
                SwipeEditDeleteSettle.CommitDelete -> {
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(0f, animationSpec = tween(swipeSettleMs))
                    emitOpen(false)
                    onDelete()
                }
                SwipeEditDeleteSettle.RevealedEdit,
                SwipeEditDeleteSettle.RevealedDelete,
                -> {
                    val target = targetOffsetRatioForSettle(settle) * widthOrDefault()
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(target, animationSpec = tween(swipeSettleMs))
                    emitOpen(true)
                }
                SwipeEditDeleteSettle.SettledClosed -> {
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(0f, animationSpec = tween(swipeSettleMs))
                    emitOpen(false)
                }
            }
            crossedCommit = false
        }
    }

    fun closeAnimated(then: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = scope.launch {
            dragging = false
            animOffset.animateTo(0f, animationSpec = tween(swipeSettleMs))
            dragOffset = 0f
            crossedCommit = false
            emitOpen(false)
            then?.invoke()
        }
    }

    // Parent closed this row (another row opened, scroll, dialog).
    LaunchedEffect(open) {
        if (!open && (animOffset.value != 0f || dragOffset != 0f || dragging)) {
            settleJob?.cancel()
            dragging = false
            animOffset.animateTo(0f, animationSpec = tween(swipeSettleMs))
            dragOffset = 0f
            crossedCommit = false
        }
    }

    LaunchedEffect(editEnabled, deleteEnabled) {
        val permissions = editEnabled to deleteEnabled
        if (permissions != previousPermissions) {
            previousPermissions = permissions
            settleJob?.cancel()
            dragging = false
            dragOffset = 0f
            crossedCommit = false
            animOffset.snapTo(0f)
            emitOpen(false)
        }
    }

    val canSwipe = editEnabled || deleteEnabled

    @Composable
    fun BoxScope.SwipeActionStrip() {
        val displayOffset = if (dragging) dragOffset else animOffset.value
        val revealEdit = displayOffset < 0f
        val revealDelete = displayOffset > 0f
        val fillFraction = (abs(displayOffset) / widthOrDefault()).coerceIn(0f, 1f)
        val actionColor = when {
            revealEdit -> success
            revealDelete -> danger
            else -> Color.Transparent
        }
        // Foreground reads against the actual fill (green edit / red delete), not onError.
        val onAction = readableContentColor(actionColor)
        val showActionLabel = fillFraction >= SWIPE_LABEL_MIN_RATIO
        // Match row outer radius: journal flat list = 0; warm cards = 8.
        val corner: Dp = LeziThemeExt.swipeActionCorner
        val stripShape = if (revealEdit) {
            RoundedCornerShape(topStart = 0.dp, topEnd = corner, bottomEnd = corner, bottomStart = 0.dp)
        } else {
            RoundedCornerShape(topStart = corner, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = corner)
        }
        // Only the filled strip is composed (not a full-size overlay), so taps on the
        // unfilled part of the card still reach content (collapse / fulfill / edit).
        if ((revealEdit || revealDelete) && fillFraction > 0f && heightPx > 0f) {
            val activeTag = if (revealEdit) editTestTag else deleteTestTag
            val stripWidth = with(density) {
                (widthOrDefault() * fillFraction).roundToInt().toDp()
            }
            val stripHeight = with(density) { heightPx.roundToInt().toDp() }
            Box(
                Modifier
                    .align(
                        if (revealEdit) Alignment.CenterEnd else Alignment.CenterStart,
                    )
                    .size(width = stripWidth, height = stripHeight)
                    .clip(stripShape)
                    .background(actionColor)
                    .then(if (activeTag != null) Modifier.testTag(activeTag) else Modifier)
                    .semantics {
                        contentDescription = if (revealEdit) "编辑" else "删除"
                        role = Role.Button
                    }
                    .clickable(
                        enabled = (revealEdit && editEnabled) || (revealDelete && deleteEnabled),
                    ) {
                        if (revealEdit) closeAnimated(onEdit) else closeAnimated(onDelete)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (showActionLabel) {
                    if (revealEdit) {
                        SwipeActionLabel(
                            icon = {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = null,
                                    tint = onAction,
                                )
                            },
                            label = "编辑",
                            contentColor = onAction,
                            padStart = false,
                        )
                    } else {
                        SwipeActionLabel(
                            icon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = onAction,
                                )
                            },
                            label = "删除",
                            contentColor = onAction,
                            padStart = true,
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged {
                widthPx = it.width.toFloat()
                heightPx = it.height.toFloat()
            }
            .then(
                if (canSwipe) {
                    Modifier.pointerInput(editEnabled, deleteEnabled) {
                        var startOffset = 0f
                        fun applyDrag(totalX: Float) {
                            val next = (startOffset + totalX)
                                .coerceIn(maxEditOffset(), maxDeleteOffset())
                            dragOffset = next
                            maybeHaptic(
                                next,
                                widthOrDefault(),
                                crossedCommit,
                            ) { crossed ->
                                crossedCommit = crossed
                                if (crossed) {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress,
                                    )
                                }
                            }
                        }
                        val settleGesture: (Boolean) -> Unit = { wasHorizontal ->
                            if (wasHorizontal) {
                                settleFromDrag(dragOffset)
                            }
                        }
                        trackSlopHorizontalGesture(
                            onGestureStart = {
                                startOffset = if (dragging) dragOffset else animOffset.value
                            },
                            onHorizontalStart = {
                                // Claim exclusive open for this row.
                                onOpenChange(true)
                                dragging = true
                            },
                            onHorizontalDrag = { totalX -> applyDrag(totalX) },
                            onGestureEnd = settleGesture,
                            onGestureCancel = { wasHorizontal ->
                                // Cancellation is never a release, even past the commit threshold.
                                if (wasHorizontal) closeAnimated()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // Card stays put; color grows over it with swipe progress.
        // Offset is read only inside the strip so drag frames do not restart content().
        content()
        SwipeActionStrip()
    }
}

@Composable
private fun SwipeActionLabel(
    icon: @Composable () -> Unit,
    label: String,
    contentColor: Color,
    padStart: Boolean,
) {
    Column(
        Modifier.padding(
            start = if (padStart) LeziSpacing.Md else 0.dp,
            end = if (padStart) 0.dp else LeziSpacing.Md,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = contentColor, style = LeziTypography.Meta)
    }
}

/**
 * Fire a one-shot haptic when absolute offset first crosses the commit threshold.
 * [alreadyCrossed] is prior state; [onCrossedChange] receives the updated flag.
 */
internal fun maybeHaptic(
    offsetPx: Float,
    widthPx: Float,
    alreadyCrossed: Boolean,
    onCrossedChange: (Boolean) -> Unit,
) {
    val ratio = abs(offsetPx / widthPx.coerceAtLeast(1f))
    val crossed = ratio >= SWIPE_COMMIT_RATIO
    if (crossed && !alreadyCrossed) {
        onCrossedChange(true)
    } else if (!crossed && alreadyCrossed) {
        onCrossedChange(false)
    }
}
