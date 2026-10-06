package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the shipped [leziTextFieldLineMode] policy used by [LeziTextField].
 * Product call-site source scans removed (test-redundancy Wave 1 / StructureTest ban).
 */
class LeziTextFieldLineModeTest {
    @Test
    fun `default single-line form row stays single line`() {
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 1, maxLines = 1)
        assertTrue(mode.singleLine)
        assertEquals(1, mode.minLines)
        assertEquals(1, mode.maxLines)
    }

    @Test
    fun `minLines greater than one forces multi-line even if singleLine default true`() {
        // Migration hazard: callers pass minLines=2,maxLines=4 without singleLine=false.
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 2, maxLines = 4)
        assertFalse(mode.singleLine)
        assertEquals(2, mode.minLines)
        assertEquals(4, mode.maxLines)
    }

    @Test
    fun `maxLines greater than one forces multi-line`() {
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 1, maxLines = 4)
        assertFalse(mode.singleLine)
        assertEquals(1, mode.minLines)
        assertEquals(4, mode.maxLines)
    }

    @Test
    fun `explicit multi-line is preserved`() {
        val mode = leziTextFieldLineMode(singleLine = false, minLines = 4, maxLines = 7)
        assertFalse(mode.singleLine)
        assertEquals(4, mode.minLines)
        assertEquals(7, mode.maxLines)
    }

    @Test
    fun `maxLines is raised to minLines when inconsistent`() {
        val mode = leziTextFieldLineMode(singleLine = false, minLines = 4, maxLines = 2)
        assertFalse(mode.singleLine)
        assertEquals(4, mode.minLines)
        assertEquals(4, mode.maxLines)
    }
}
