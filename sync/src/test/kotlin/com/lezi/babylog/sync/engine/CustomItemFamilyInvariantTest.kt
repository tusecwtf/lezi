package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.sync.backend.SyncEntity
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomItemFamilyInvariantTest {
    @Test
    fun incomingPageCannotLeaveMoreThanTenLiveDefinitions() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(remoteCustomItem("item-10", updatedAt = 11L))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            requireCustomItemCapacityAfterApply(existing, incoming)
        }

        assertThat(failure).hasMessageThat().contains("最多 10")
    }

    @Test
    fun tombstoneAndReplacementInOnePageKeepsCapacityValid() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(
            remoteCustomItem("item-0", updatedAt = 20L, deletedAt = 20L),
            remoteCustomItem("replacement", updatedAt = 21L),
        )

        requireCustomItemCapacityAfterApply(existing, incoming)
    }

    private fun customItem(clientUuid: String, updatedAt: Long) = CustomItemEntity(
        clientUuid = clientUuid,
        familyId = 1L,
        name = clientUuid,
        iconSlot = 0,
        updatedAt = updatedAt,
        deletedAt = null,
        syncDirty = false,
    )

    private fun remoteCustomItem(
        clientUuid: String,
        updatedAt: Long,
        deletedAt: Long? = null,
    ) = SyncEntity(
        type = "custom_item",
        clientUuid = clientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        payloadJson = """{"name":"$clientUuid","icon_slot":0,"created_by_membership_id":"member"}""",
    )
}
