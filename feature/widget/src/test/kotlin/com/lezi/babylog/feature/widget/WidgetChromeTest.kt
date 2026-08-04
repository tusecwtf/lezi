package com.lezi.babylog.feature.widget

import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 11 — widget chrome spacing/type align with Lezi tokens within Glance limits.
 *
 * Source-level CareWidget/config consumption is owned by designsystem
 * [com.lezi.babylog.designsystem.WeakSurfacesContractTest] so product tests do not
 * reimplement [com.lezi.babylog.designsystem.DesignsystemSourceFixtures] path walk.
 */
class WidgetChromeTest {

    @Test
    fun padsAndTypeMapToSpacingAndTypographySteps() {
        assertEquals(LeziSpacing.Sm, WidgetChrome.padHorizontal)
        assertEquals(LeziSpacing.Xs, WidgetChrome.padVertical)
        assertEquals(LeziSpacing.Xxs, WidgetChrome.stackGap)
        assertEquals(LeziSpacing.Xxs, WidgetChrome.actionGap)
        assertEquals(LeziSpacing.Sm, WidgetChrome.actionPadHorizontal)
        assertEquals(LeziSpacing.Md, WidgetChrome.actionPadVertical)
        assertEquals(LeziSpacing.Touch, WidgetChrome.configRowMinHeight)
        assertEquals(LeziTypography.Label.fontSize, WidgetChrome.titleFontSize)
        assertEquals(LeziTypography.Meta.fontSize, WidgetChrome.bodyFontSize)
        // 4dp grid for every structural pad.
        val grid = LeziSpacing.Xxs.value
        for (value in listOf(
            WidgetChrome.padHorizontal,
            WidgetChrome.padVertical,
            WidgetChrome.stackGap,
            WidgetChrome.actionGap,
            WidgetChrome.actionPadHorizontal,
            WidgetChrome.actionPadVertical,
            WidgetChrome.configRowMinHeight,
        )) {
            assertEquals(0f, value.value % grid, 0.001f)
        }
    }
}
