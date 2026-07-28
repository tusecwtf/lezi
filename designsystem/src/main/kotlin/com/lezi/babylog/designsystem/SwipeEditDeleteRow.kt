package com.lezi.babylog.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Absolute-screen swipe secondary actions for timeline cells.
 *
 * - Content moves **left** (negative offset) → green **edit** on the right.
 * - Content moves **right** (positive offset) → red **delete** on the left.
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

/** Snap / rebound duration. */
const val SWIPE_SETTLE_MS = 200

/**
 * Pure settle from content offset ratio (offsetX / width).
 * Positive ratio = content shifted right (delete side).
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

/** Settled content offset ratio for a non-commit settle result. */
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
    var crossedCommit by remember { mutableStateOf(false) }
    val success = LocalLeziColors.current.success
    val danger = LocalLeziColors.current.danger
    val onAction = MaterialTheme.colorScheme.onError

    val displayOffset = if (dragging) dragOffset else animOffset.value

    fun widthOrDefault(): Float = widthPx.takeIf { it > 0f } ?: 1f

    fun maxDeleteOffset(): Float =
        if (deleteEnabled) widthOrDefault() * SWIPE_COMMIT_RATIO else 0f

    fun maxEditOffset(): Float =
        if (editEnabled) -widthOrDefault() * SWIPE_COMMIT_RATIO else 0f

    fun emitOpen(openNow: Boolean) {
        if (open != openNow) onOpenChange(openNow)
    }

    fun settleFromDrag(current: Float) {
        scope.launch {
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
                    animOffset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_MS))
                    emitOpen(false)
                    onEdit()
                }
                SwipeEditDeleteSettle.CommitDelete -> {
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_MS))
                    emitOpen(false)
                    onDelete()
                }
                SwipeEditDeleteSettle.RevealedEdit,
                SwipeEditDeleteSettle.RevealedDelete,
                -> {
                    val target = targetOffsetRatioForSettle(settle) * widthOrDefault()
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(target, animationSpec = tween(SWIPE_SETTLE_MS))
                    emitOpen(true)
                }
                SwipeEditDeleteSettle.SettledClosed -> {
                    animOffset.snapTo(current)
                    dragging = false
                    animOffset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_MS))
                    emitOpen(false)
                }
            }
            crossedCommit = false
        }
    }

    fun closeAnimated(then: (() -> Unit)? = null) {
        scope.launch {
            dragging = false
            animOffset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_MS))
            dragOffset = 0f
            crossedCommit = false
            emitOpen(false)
            then?.invoke()
        }
    }

    // Parent closed this row (another row opened, scroll, dialog).
    LaunchedEffect(open) {
        if (!open && (animOffset.value != 0f || dragOffset != 0f || dragging)) {
            dragging = false
            animOffset.animateTo(0f, animationSpec = tween(SWIPE_SETTLE_MS))
            dragOffset = 0f
            crossedCommit = false
        }
    }

    val canSwipe = editEnabled || deleteEnabled

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        // Full-height action layer (half red / half green).
        Row(
            Modifier
                .matchParentSize()
                .fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(danger)
                    .then(
                        if (deleteTestTag != null) Modifier.testTag(deleteTestTag) else Modifier,
                    )
                    .semantics {
                        contentDescription = "删除"
                        role = Role.Button
                    }
                    .clickable(enabled = deleteEnabled && displayOffset > 0f) {
                        closeAnimated(onDelete)
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                SwipeActionLabel(
                    icon = {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = onAction)
                    },
                    label = "删除",
                    contentColor = onAction,
                    padStart = true,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(success)
                    .then(
                        if (editTestTag != null) Modifier.testTag(editTestTag) else Modifier,
                    )
                    .semantics {
                        contentDescription = "编辑"
                        role = Role.Button
                    }
                    .clickable(enabled = editEnabled && displayOffset < 0f) {
                        closeAnimated(onEdit)
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                SwipeActionLabel(
                    icon = {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = onAction)
                    },
                    label = "编辑",
                    contentColor = onAction,
                    padStart = false,
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(displayOffset.roundToInt(), 0) }
                .fillMaxWidth()
                .then(
                    if (canSwipe) {
                        Modifier.pointerInput(editEnabled, deleteEnabled) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val touchSlop = viewConfiguration.touchSlop
                                var totalX = 0f
                                var totalY = 0f
                                var pastSlop = false
                                var isHorizontal = false
                                val pointerId = down.id
                                val startOffset = if (dragging) dragOffset else animOffset.value
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId }
                                            ?: break
                                        if (!change.pressed) break
                                        val delta = change.positionChange()
                                        if (!pastSlop) {
                                            totalX += delta.x
                                            totalY += delta.y
                                            val distSq = totalX * totalX + totalY * totalY
                                            if (distSq >= touchSlop * touchSlop) {
                                                pastSlop = true
                                                isHorizontal = abs(totalX) >= abs(totalY)
                                                if (isHorizontal) {
                                                    change.consume()
                                                    // Claim exclusive open for this row.
                                                    onOpenChange(true)
                                                    dragging = true
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
                                            }
                                        } else if (isHorizontal) {
                                            totalX += delta.x
                                            change.consume()
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
                                    }
                                } finally {
                                    if (isHorizontal) {
                                        settleFromDrag(dragOffset)
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
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
