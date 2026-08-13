package com.lezi.babylog.designsystem

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warm/journal density table policy. Product source-string scans removed
 * (test-redundancy Wave 1 / StructureTest ban).
 */
class RecordDensityEmptyContractTest {

    @Test
    fun `warm density remains strictly more open than journal on every role`() {
        // Product literals — same policy as MotionDensityTokensTest / ticket 01.
        assertTrue(LeziDensity.Warm.cardPad > LeziDensity.Journal.cardPad)
        assertTrue(LeziDensity.Warm.topBarHorizontal > LeziDensity.Journal.topBarHorizontal)
        assertTrue(LeziDensity.Warm.sectionGap > LeziDensity.Journal.sectionGap)
        assertTrue(LeziDensity.Warm.panelContent > LeziDensity.Journal.panelContent)
        assertTrue(LeziDensity.Warm.dockOuterHorizontal > LeziDensity.Journal.dockOuterHorizontal)
        assertTrue(LeziSpacing.Touch.value >= 48f)
    }
}
