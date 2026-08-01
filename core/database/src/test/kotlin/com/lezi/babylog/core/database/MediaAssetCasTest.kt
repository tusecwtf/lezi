package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure predicate regressions for [matchesPublishedRevision].
 * Room SQL authority remains [MediaAssetCasRoomTest].
 */
class MediaAssetCasTest {
    @Test
    fun matchesOnlyWhenEveryCasKeyAgreesIncludingNullDeletedAt() {
        val row = media(
            clientUuid = "m1",
            localUri = "/a.jpg",
            updatedAt = 10,
            deletedAt = null,
        )
        assertThat(
            row.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = null,
            ),
        ).isTrue()
        assertThat(
            row.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/b.jpg",
                expectedDeletedAt = null,
            ),
        ).isFalse()
        assertThat(
            row.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 11,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = null,
            ),
        ).isFalse()
        assertThat(
            row.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = 1,
            ),
        ).isFalse()
        assertThat(
            row.matchesPublishedRevision(
                expectedClientUuid = "other",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = null,
            ),
        ).isFalse()
    }

    @Test
    fun tombstoneDeletedAtMustMatchExactly() {
        val tombstone = media(
            clientUuid = "m1",
            localUri = "/a.jpg",
            updatedAt = 11,
            deletedAt = 11,
        )
        assertThat(
            tombstone.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 11,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = 11,
            ),
        ).isTrue()
        assertThat(
            tombstone.matchesPublishedRevision(
                expectedClientUuid = "m1",
                expectedUpdatedAt = 11,
                expectedLocalUri = "/a.jpg",
                expectedDeletedAt = null,
            ),
        ).isFalse()
    }

    private fun media(
        clientUuid: String,
        localUri: String,
        updatedAt: Long,
        deletedAt: Long?,
    ) = MediaAssetEntity(
        id = 1,
        recordId = 1,
        clientUuid = clientUuid,
        localUri = localUri,
        remoteUri = null,
        mime = null,
        width = null,
        height = null,
        byteSize = 0,
        createdAt = 1,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = true,
    )
}
