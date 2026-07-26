package com.lezi.babylog.feature.settings

import com.lezi.babylog.domain.encodeSystemCalendarEventMap
import com.lezi.babylog.domain.parseSystemCalendarEventMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarEventMapTest {
    @Test
    fun roundTripMapPreservesPlanUuidToEventId() {
        val original = mapOf(
            "plan-uuid-1" to "evt-9",
            "plan-uuid-2" to "evt-10",
        )
        val encoded = encodeSystemCalendarEventMap(original)
        val parsed = parseSystemCalendarEventMap(encoded)
        assertEquals(original, parsed)
    }

    @Test
    fun emptyAndMalformedMapsAreEmpty() {
        assertTrue(parseSystemCalendarEventMap("{}").isEmpty())
        assertTrue(parseSystemCalendarEventMap("").isEmpty())
        assertTrue(parseSystemCalendarEventMap("not-json").isEmpty())
    }
}
