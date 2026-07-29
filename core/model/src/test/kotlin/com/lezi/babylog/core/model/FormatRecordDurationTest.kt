package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatRecordDurationTest {
    @Test
    fun formatsCompactChineseDurations() {
        assertEquals("不足1分", formatRecordDuration(0))
        assertEquals("不足1分", formatRecordDuration(-3))
        assertEquals("45分", formatRecordDuration(45))
        assertEquals("2小时", formatRecordDuration(120))
        assertEquals("1小时5分", formatRecordDuration(65))
    }
}
