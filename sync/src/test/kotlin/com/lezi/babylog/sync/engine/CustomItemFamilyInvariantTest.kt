package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.sync.backend.SyncEntity
import org.junit.Test

class CustomItemFamilyInvariantTest {
    @Test
    fun `eleventh live definition is deferred, not thrown`() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(remoteCustomItem("item-10", updatedAt = 11L))

        assertThat(customItemCapacityDeferredUuids(existing, incoming))
            .containsExactly("item-10")
    }

    @Test
    fun tombstoneAndReplacementInOnePageKeepsCapacityValid() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(
            remoteCustomItem("item-0", updatedAt = 20L, deletedAt = 20L),
            remoteCustomItem("replacement", updatedAt = 21L),
        )

        assertThat(customItemCapacityDeferredUuids(existing, incoming)).isEmpty()
    }

    @Test
    fun `overflow withholds newest additive rows first and is redelivery-stable`() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(
            // A same-page deletion frees one slot, so only one additive row
            // overflows and the newest one must be the withheld one.
            remoteCustomItem("item-0", updatedAt = 29L, deletedAt = 29L),
            remoteCustomItem("older-new", updatedAt = 30L),
            remoteCustomItem("newest-new", updatedAt = 31L),
        )

        val deferred = customItemCapacityDeferredUuids(existing, incoming)
        assertThat(deferred).containsExactly("newest-new")

        // Redelivery sees the same local state (the deferred row never applied)
        // and must make the same deterministic choice.
        assertThat(customItemCapacityDeferredUuids(existing, incoming)).isEqualTo(deferred)
    }

    @Test
    fun `count-neutral update to a live row is never withheld`() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val incoming = listOf(
            remoteCustomItem("item-5", updatedAt = 99L),
            remoteCustomItem("extra", updatedAt = 100L),
        )

        assertThat(customItemCapacityDeferredUuids(existing, incoming))
            .containsExactly("extra")
    }

    @Test
    fun `deletion on another page frees capacity for the withheld row`() {
        val existing = (0 until 10).map { index -> customItem("item-$index", index.toLong()) }
        val withheld = listOf(remoteCustomItem("item-10", updatedAt = 11L))
        assertThat(customItemCapacityDeferredUuids(existing, withheld))
            .containsExactly("item-10")

        val afterDeletion = existing.drop(1) // item-0 deleted elsewhere
        assertThat(customItemCapacityDeferredUuids(afterDeletion, withheld)).isEmpty()
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
