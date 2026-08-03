package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomRecordDefinitionInvariantTest {
    @Test
    fun tombstonedDefinitionCannotCreateANewCustomFact() {
        val document = RecordPayloadDocument(
            type = RecordType.CUSTOM,
            payload = CustomPayload(
                titleSnapshot = "抚触",
                customItemId = 7L,
                iconSlot = 1,
            ),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        val tombstone = CustomItemEntity(
            id = 7L,
            clientUuid = "123e4567-e89b-12d3-a456-426614174000",
            familyId = 1L,
            name = "抚触",
            iconSlot = 1,
            updatedAt = 2L,
            deletedAt = 2L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            requireLiveCustomDefinition(document, tombstone)
        }
    }
}
