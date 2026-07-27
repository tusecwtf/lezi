package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordItemIdentityTest {
    @Test
    fun catalogKeysEncodeBuiltInAndCustomDistinctly() {
        assertEquals("pee", RecordItemIdentity.builtIn(RecordType.PEE).catalogKey)
        assertEquals("custom:12", RecordItemIdentity.custom(12L).catalogKey)
        assertEquals(RecordType.PEE, RecordItemIdentity.builtIn(RecordType.PEE).recordType)
        assertEquals(RecordType.CUSTOM, RecordItemIdentity.custom(12L).recordType)
    }

    @Test
    fun parseRejectsRemovedAndNonConcreteKeysAndAcceptsConcreteOnes() {
        assertNull(RecordType.fromKey("memo"))
        assertNull(RecordType.fromKey("other"))
        assertNull(RecordItemIdentity.parseCatalogKey("memo"))
        assertNull(RecordItemIdentity.parseCatalogKey("other"))
        assertNull(RecordItemIdentity.parseCatalogKey("custom"))
        assertNull(RecordItemIdentity.parseCatalogKey("custom:0"))
        assertNull(RecordItemIdentity.parseCatalogKey("custom:abc"))
        assertNull(RecordItemIdentity.parseCatalogKey("not_a_type"))

        assertEquals(
            RecordItemIdentity.builtIn(RecordType.DIARY),
            RecordItemIdentity.parseCatalogKey("diary"),
        )
        assertEquals(
            RecordItemIdentity.custom(7L),
            RecordItemIdentity.parseCatalogKey("custom:7"),
        )
    }

    @Test
    fun onlyConcreteCustomIdentityIsAvailableForNewEntry() {
        assertFalse(RecordType.CUSTOM.isAvailableForNewEntry)
        assertTrue(RecordType.DIARY.isAvailableForNewEntry)
        assertTrue(RecordType.PEE.isAvailableForNewEntry)

        val available = RecordType.availableForNewEntry()
        assertFalse(available.contains(RecordType.CUSTOM))
        assertEquals(
            RecordType.entries.size - 1,
            available.size,
        )
    }

    @Test
    fun displayLabelPrefersConcreteCustomSnapshot() {
        val custom = Record(
            id = 1,
            clientUuid = "c1",
            babyId = 1,
            type = RecordType.CUSTOM,
            timestamp = 1_000,
            payloadJson = """{"title":"抚触","detail":"晚间","custom_item_id":9,"icon_slot":2}""",
            updatedAt = 1_000,
        )
        val pee = Record(
            id = 2,
            clientUuid = "p1",
            babyId = 1,
            type = RecordType.PEE,
            timestamp = 1_000,
            payloadJson = """{"pee_amount":2}""",
            updatedAt = 1_000,
        )
        assertEquals("抚触", custom.displayLabel())
        assertEquals("尿尿", pee.displayLabel())
        assertEquals(
            RecordItemIdentity.custom(9L),
            custom.itemIdentity(),
        )
    }
}
