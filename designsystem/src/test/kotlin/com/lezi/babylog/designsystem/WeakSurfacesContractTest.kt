package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 11 (ui-drawing-polish): menu icon tokens on the spacing grid.
 * Product source-string scans removed (test-redundancy Wave 1 / StructureTest ban).
 */
class WeakSurfacesContractTest {

    @Test
    fun `menu icon tokens fix well and glyph sizes on the spacing grid`() {
        // Product literals: well 40dp (10×4), glyph = LeziSpacing.Lg (20dp).
        assertEquals(40f, LeziMenuIcon.WellSize.value, 0.001f)
        assertEquals(LeziSpacing.Lg, LeziMenuIcon.GlyphSize)
        assertEquals(20f, LeziMenuIcon.GlyphSize.value, 0.001f)
        val grid = LeziSpacing.Xxs.value
        assertEquals(0f, LeziMenuIcon.WellSize.value % grid, 0.001f)
        assertEquals(0f, LeziMenuIcon.GlyphSize.value % grid, 0.001f)
    }
}
