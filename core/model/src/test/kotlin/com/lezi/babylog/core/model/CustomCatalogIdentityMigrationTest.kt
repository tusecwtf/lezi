package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CustomCatalogIdentityMigrationTest {
    @Test
    fun localIdsBecomeStableFamilyIdsWithoutAllowingUnknownRebinding() {
        val firstUuid = "123e4567-e89b-12d3-a456-426614174000"
        val secondUuid = "223e4567-e89b-12d3-a456-426614174000"
        val snapshot = DeviceLayoutSnapshot(
            quickRecordSlots = listOf("custom:7", "custom:999", "pee", "custom:8"),
            hiddenItems = setOf("custom:7", "custom:999", "sleep"),
            itemOrderJson = """["custom:8","custom:999","pee","custom:7"]""",
        )

        val migrated = stabilizeCustomLayoutSnapshot(
            snapshot = snapshot,
            clientUuidByLocalId = mapOf(7L to firstUuid, 8L to secondUuid),
        )

        assertEquals(
            listOf("custom:$firstUuid", "", "pee", "custom:$secondUuid"),
            migrated.quickRecordSlots,
        )
        assertEquals(setOf("custom:$firstUuid", "sleep"), migrated.hiddenItems)
        assertEquals(
            """["custom:$secondUuid","pee","custom:$firstUuid"]""",
            migrated.itemOrderJson,
        )
        assertFalse(hasLegacyLocalCustomCatalogKeys(migrated))
    }
}
