package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BabyProfileConstraintsTest {
    @Test
    fun birthWeightIsOptionalButNeverSilentlyDroppedOutsideProductRange() {
        assertNull(birthWeightValidationError(null))
        assertNull(birthWeightValidationError(500))
        assertNull(birthWeightValidationError(9_000))
        assertEquals("出生体重需在 500–9000 克之间", birthWeightValidationError(499))
        assertEquals("出生体重需在 500–9000 克之间", birthWeightValidationError(9_001))
    }
}
