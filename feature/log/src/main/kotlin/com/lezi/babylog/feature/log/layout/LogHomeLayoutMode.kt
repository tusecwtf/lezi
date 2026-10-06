package com.lezi.babylog.feature.log.layout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Home ↔ layout-edit switcher. [targetState] is the retained presentation so the
 * outgoing edit branch keeps its `prefs`/`session` while the exit transition plays.
 * [contentKey] is only null vs non-null: prefs updates stay in-place (no transition).
 */
@Composable
internal fun LogHomeLayoutMode(
    layoutPresentation: RetainedLayoutEditPresentation?,
    transitionMillis: Int,
    onRequestExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (RetainedLayoutEditPresentation?) -> Unit,
) {
    BackHandler(enabled = layoutPresentation != null, onBack = onRequestExit)
    AnimatedContent(
        targetState = layoutPresentation,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(animationSpec = tween(transitionMillis)) +
                    slideInVertically(animationSpec = tween(transitionMillis)) { it / 24 }
                ).togetherWith(
                fadeOut(animationSpec = tween(transitionMillis)) +
                    slideOutVertically(animationSpec = tween(transitionMillis)) { it / 24 },
            )
        },
        contentKey = { it != null },
        label = "logLayoutEditMode",
    ) { presentation ->
        content(presentation)
    }
}
