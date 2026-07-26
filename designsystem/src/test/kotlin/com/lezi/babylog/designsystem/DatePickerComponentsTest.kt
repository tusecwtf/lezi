package com.lezi.babylog.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class DatePickerComponentsTest {
    @Test
    fun `large fonts use full-scale accessible input instead of clamped calendar`() {
        assertEquals(false, useAccessibleDateInputMode(0.85f))
        assertEquals(false, useAccessibleDateInputMode(1f))
        assertEquals(true, useAccessibleDateInputMode(1.05f))
        assertEquals(true, useAccessibleDateInputMode(1.3f))
        assertEquals(true, useAccessibleDateInputMode(2f))
    }
}
