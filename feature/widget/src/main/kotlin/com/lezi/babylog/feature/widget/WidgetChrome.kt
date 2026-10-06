package com.lezi.babylog.feature.widget

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography

/**
 * Glance + configuration spacing/type map for the home widget (ticket 11).
 *
 * Glance cannot host Compose [androidx.compose.ui.text.TextStyle]; call sites
 * use the token **sizes** only. All structural pads land on [LeziSpacing] steps.
 */
@Immutable
object WidgetChrome {
    /** Outer horizontal pad on the Glance surface. */
    val padHorizontal: Dp = LeziSpacing.Sm

    /** Outer vertical pad on the Glance surface. */
    val padVertical: Dp = LeziSpacing.Xs

    /** Gap between title stack and quick-action row. */
    val stackGap: Dp = LeziSpacing.Xxs

    /** Horizontal gap between quick-action chips. */
    val actionGap: Dp = LeziSpacing.Xxs

    /** Quick-action chip horizontal padding. */
    val actionPadHorizontal: Dp = LeziSpacing.Sm

    /** Quick-action chip vertical padding (touch-friendly under Glance limits). */
    val actionPadVertical: Dp = LeziSpacing.Md

    /** Title line size — [LeziTypography.Label] (13sp). */
    val titleFontSize: TextUnit = LeziTypography.Label.fontSize

    /** Summary lines size — [LeziTypography.Meta] (12sp). */
    val bodyFontSize: TextUnit = LeziTypography.Meta.fontSize

    /** Minimum height for widget-config selectable rows. */
    val configRowMinHeight: Dp = LeziSpacing.Touch
}
