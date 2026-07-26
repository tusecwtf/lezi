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
    fun parseRejectsRetiredGenericKeysAndAcceptsConcreteOnes() {
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
    fun retiredKeysAreInvalidNewEntryReferences() {
        listOf("memo", "other", "custom").forEach { key ->
            assertTrue(key, RecordItemIdentity.isRetiredGenericCatalogKey(key))
            assertTrue(key, RecordItemIdentity.isInvalidNewEntryReference(key))
        }
        assertFalse(RecordItemIdentity.isInvalidNewEntryReference("pee"))
        assertFalse(RecordItemIdentity.isInvalidNewEntryReference("custom:3"))
        assertFalse(RecordItemIdentity.isInvalidNewEntryReference("diary"))
    }

    @Test
    fun memoOtherCustomAreNotAvailableForNewEntryButRemainAsTypes() {
        assertFalse(RecordType.MEMO.isAvailableForNewEntry)
        assertFalse(RecordType.OTHER.isAvailableForNewEntry)
        assertFalse(RecordType.CUSTOM.isAvailableForNewEntry)
        assertTrue(RecordType.DIARY.isAvailableForNewEntry)
        assertTrue(RecordType.PEE.isAvailableForNewEntry)

        val available = RecordType.availableForNewEntry()
        assertFalse(available.contains(RecordType.MEMO))
        assertFalse(available.contains(RecordType.OTHER))
        assertFalse(available.contains(RecordType.CUSTOM))
        assertEquals(
            RecordType.entries.size - 3,
            available.size,
        )
    }

    @Test
    fun displayLabelPrefersCustomAndOtherSnapshots() {
        val custom = Record(
            id = 1,
            clientUuid = "c1",
            babyId = 1,
            type = RecordType.CUSTOM,
            timestamp = 1_000,
            createdByUserId = 1,
            payloadJson = """{"title":"抚触","detail":"晚间","custom_item_id":9,"icon_slot":2}""",
            updatedAt = 1_000,
        )
        val other = Record(
            id = 2,
            clientUuid = "o1",
            babyId = 1,
            type = RecordType.OTHER,
            timestamp = 1_000,
            createdByUserId = 1,
            payloadJson = """{"title":"旧其他","detail":"x"}""",
            updatedAt = 1_000,
        )
        val pee = Record(
            id = 3,
            clientUuid = "p1",
            babyId = 1,
            type = RecordType.PEE,
            timestamp = 1_000,
            createdByUserId = 1,
            payloadJson = """{"pee_amount":2}""",
            updatedAt = 1_000,
        )
        val bareCustom = Record(
            id = 4,
            clientUuid = "c2",
            babyId = 1,
            type = RecordType.CUSTOM,
            timestamp = 1_000,
            createdByUserId = 1,
            payloadJson = """{"title":"仅标题"}""",
            updatedAt = 1_000,
        )

        assertEquals("抚触", custom.displayLabel())
        assertEquals("旧其他", other.displayLabel())
        assertEquals("尿尿", pee.displayLabel())
        assertEquals(
            RecordItemIdentity.custom(9L),
            custom.itemIdentity(),
        )
        assertNull(bareCustom.itemIdentity())
        assertEquals(RecordItemIdentity.builtIn(RecordType.OTHER), other.itemIdentity())
    }
}
