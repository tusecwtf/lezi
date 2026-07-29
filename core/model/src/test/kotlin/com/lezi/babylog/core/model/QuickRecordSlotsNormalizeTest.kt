package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickRecordSlotsNormalizeTest {
    @Test
    fun padsAndTruncatesToFourSlots() {
        assertEquals(
            listOf("pee", "sleep", "", ""),
            normalizeQuickRecordSlots(listOf("pee", "sleep")),
        )
        assertEquals(
            listOf("a", "b", "c", "d"),
            normalizeQuickRecordSlots(listOf("a", "b", "c", "d", "e", "f")),
        )
    }

    @Test
    fun trimsKeysAndKeepsIntentionalBlanks() {
        assertEquals(
            listOf("pee", "", "sleep", ""),
            normalizeQuickRecordSlots(listOf("  pee ", "", " sleep ")),
        )
    }
}
