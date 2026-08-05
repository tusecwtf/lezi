package com.lezi.babylog.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/** Product fixed content dwell after a user pull-to-refresh finishes (0.3.10). */
const val TransientShallowSyncContentMillis: Long = 5_000L

/**
 * Unified shallow-sync chrome for log / summary / growth.
 *
 * - While [isUserRefreshing]: content row is visible (spinner is owned by PullToRefresh).
 * - After a user pull ends: result stays in content ≈5s then collapses (content hidden).
 * - Silent / non-pull sync never forces a permanent content row.
 * - [onCollapsedChange] publishes text for root top-bar secondary placement when collapsed.
 */
@Composable
fun TransientShallowSyncStatus(
    text: String,
    isError: Boolean,
    isUserRefreshing: Boolean,
    contentTestTag: String,
    modifier: Modifier = Modifier,
    contentDwellMillis: Long = TransientShallowSyncContentMillis,
    onCollapsedChange: (text: String?, isError: Boolean) -> Unit = { _, _ -> },
) {
    var contentVisible by remember { mutableStateOf(false) }
    var pullGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(isUserRefreshing) {
        if (isUserRefreshing) {
            contentVisible = true
            onCollapsedChange(null, false)
            pullGeneration += 1
        } else if (contentVisible) {
            val gen = pullGeneration
            delay(contentDwellMillis)
            if (gen == pullGeneration && !isUserRefreshing) {
                contentVisible = false
                onCollapsedChange(text, isError)
            }
        }
    }

    // Keep collapsed top-bar text fresh while content is hidden.
    LaunchedEffect(text, isError, contentVisible, isUserRefreshing) {
        if (!contentVisible && !isUserRefreshing) {
            onCollapsedChange(text, isError)
        }
    }

    val enterMs = leziMotionMillis(LeziMotion.Fast)
    val exitMs = leziMotionMillis(LeziMotion.Fast)
    AnimatedVisibility(
        visible = contentVisible || isUserRefreshing,
        enter = fadeIn(animationSpec = tween(durationMillis = enterMs)),
        exit = fadeOut(animationSpec = tween(durationMillis = exitMs)),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = LeziTypography.Meta,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .testTag(contentTestTag)
                .padding(bottom = LeziSpacing.Sm)
                .semantics { contentDescription = text },
        )
    }
}
