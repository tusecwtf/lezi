package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CustomItemClientUuidTest {
    @Test
    fun nonCustomRecordHasNoDefinitionUuid() = runTest {
        val uuid = resolveRecordCustomItemClientUuid(
            record = record(type = "diary", payloadJson = "{}"),
            customItemDao = MemoryCustomItemDao(),
        )

        assertThat(uuid).isNull()
    }

    @Test
    fun customRecordResolvesDefinitionUuidFromLocalPayloadId() = runTest {
        val customItems = MemoryCustomItemDao()
        val localId = customItems.seed(customItem(clientUuid = "custom-family-1"))

        val uuid = resolveRecordCustomItemClientUuid(
            record = record(payloadJson = "{\"custom_item_id\":$localId}"),
            customItemDao = customItems,
        )

        assertThat(uuid).isEqualTo("custom-family-1")
    }

    @Test
    fun customRecordRejectsMissingOrNonPositiveLocalDefinitionId() = runTest {
        val customItems = MemoryCustomItemDao()

        listOf(
            "{}",
            "{\"custom_item_id\":0}",
            "{\"custom_item_id\":\"not-a-number\"}",
            "not-json",
        ).forEach { payload ->
            val failure = runCatching {
                resolveRecordCustomItemClientUuid(record(payloadJson = payload), customItems)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat()
                .isEqualTo("custom record 缺少本地 custom_item_id")
        }
    }

    @Test
    fun customRecordRejectsMissingDefinitionAndBlankDefinitionUuid() = runTest {
        val missingFailure = runCatching {
            resolveRecordCustomItemClientUuid(
                record(payloadJson = "{\"custom_item_id\":99}"),
                MemoryCustomItemDao(),
            )
        }.exceptionOrNull()
        val blankItems = MemoryCustomItemDao()
        val blankId = blankItems.seed(customItem(clientUuid = ""))
        val blankFailure = runCatching {
            resolveRecordCustomItemClientUuid(
                record(payloadJson = "{\"custom_item_id\":$blankId}"),
                blankItems,
            )
        }.exceptionOrNull()

        assertThat(missingFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(missingFailure).hasMessageThat()
            .isEqualTo("custom record 引用的本地定义不存在")
        assertThat(blankFailure).isInstanceOf(IllegalStateException::class.java)
        assertThat(blankFailure).hasMessageThat()
            .isEqualTo("custom record 引用的定义缺少 client_uuid")
    }

    @Test
    fun customRecordRequiresCurrentPayloadSchema() = runTest {
        val failure = runCatching {
            resolveRecordCustomItemClientUuid(
                record(
                    payloadJson = "{\"custom_item_id\":1}",
                    schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION - 1,
                ),
                MemoryCustomItemDao(),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(
            "custom record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION",
        )
    }

    private companion object {
        fun record(
            type: String = "custom",
            payloadJson: String,
            schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        ) = RecordEntity(
            id = 1,
            clientUuid = "record-1",
            babyId = 1,
            type = type,
            timestamp = 1,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
            updatedAt = 1,
        )

        fun customItem(clientUuid: String) = CustomItemEntity(
            clientUuid = clientUuid,
            familyId = 1,
            name = "抚触",
            iconSlot = 1,
            updatedAt = 1,
        )
    }
}
