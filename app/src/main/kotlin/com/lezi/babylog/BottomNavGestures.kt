package com.lezi.babylog

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Bottom primary nav gesture split:
 * - **Short-press navigation** is owned exclusively by
 *   [androidx.compose.material3.NavigationBarItem] `onClick` (must be non-empty).
 * - **Long-press** (e.g. cycle baby) may only register [detectTapGestures] `onLongPress`
 *   — never `onTap` — so a short press cannot double-fire or starve Material `onClick`.
 *
 * Regression: empty `onClick = {}` + combined short-press gesture broke tab navigation.
 */
internal fun Modifier.bottomNavLongPressOnly(onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) {
        this
    } else {
        pointerInput(onLongClick) {
            detectTapGestures(onLongPress = { onLongClick() })
        }
    }
