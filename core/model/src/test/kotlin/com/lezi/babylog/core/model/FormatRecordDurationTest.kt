package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatRecordDurationTest {
    @Test
    fun formatsCompactDurationsWithoutWhitespace() {
        assertEquals("0m", formatRecordDuration(0))
        assertEquals("0m", formatRecordDuration(-3))
        assertEquals("45m", formatRecordDuration(45))
        assertEquals("2h", formatRecordDuration(120))
        assertEquals("1h5m", formatRecordDuration(65))
        assertEquals("2h10m", formatRecordDuration(130))
    }
}
