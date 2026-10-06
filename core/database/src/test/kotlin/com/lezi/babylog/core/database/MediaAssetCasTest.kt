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

    @Test
    fun sha256MustBeNullOrSixtyFourLowercaseHex() {
        assertThat(media(sha256 = null).sha256).isNull()
        assertThat(
            media(sha256 = KNOWN_BYTES_1234_SHA256).sha256,
        ).isEqualTo(KNOWN_BYTES_1234_SHA256)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            media(sha256 = "not-a-digest")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            media(sha256 = KNOWN_BYTES_1234_SHA256.uppercase())
        }
    }

    private fun media(
        clientUuid: String = "m1",
        localUri: String = "/a.jpg",
        updatedAt: Long = 10,
        deletedAt: Long? = null,
        sha256: String? = null,
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
        sha256 = sha256,
    )

    companion object {
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    }
}
