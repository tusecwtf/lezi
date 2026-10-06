package com.lezi.babylog

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.lezi.babylog.designsystem.LeziHaptics

/**
 * Bottom primary nav gesture split:
 * - **Short-press navigation** is owned exclusively by
 *   [androidx.compose.material3.NavigationBarItem] `onClick` (must be non-empty).
 * - **Long-press** (e.g. cycle baby) may only register [detectTapGestures] `onLongPress`
 *   — never `onTap` — so a short press cannot double-fire or starve Material `onClick`.
 *
 * Regression: empty `onClick = {}` + combined short-press gesture broke tab navigation.
 */
internal fun Modifier.bottomNavLongPressOnly(
    onLongClick: (() -> Unit)?,
    restartKey: Any? = onLongClick != null,
): Modifier =
    if (onLongClick == null) {
        this
    } else {
        pointerInput(restartKey) {
            detectTapGestures(onLongPress = { onLongClick() })
        }
    }

/**
 * Long-press baby-cycle action (0.5.4 ticket 15, spec §M3): one confirm haptic riding the
 * actual switch — never a buzz on a no-op (single baby) long-press, and short-press stays
 * haptic-free. The gate mirrors the header affordance's `babies.size > 1` policy, under
 * which [nextSiblingId] can never return null.
 */
internal fun bottomNavBabyCycleClick(
    canCycle: Boolean,
    haptics: LeziHaptics,
    cycleBaby: () -> Unit,
): () -> Unit = {
    if (canCycle) {
        haptics.confirm()
        cycleBaby()
    }
}
