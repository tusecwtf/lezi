package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room regressions for conditional media prepare/receipt writes.
 * Full-row @Update must not be the atomic-bundle commit path.
 */
@RunWith(AndroidJUnit4::class)
class MediaAssetCasRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var dao: MediaAssetDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        dao = database.mediaAssetDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun preparedMetadataAndReceiptOnlyApplyAtMatchingRevisionPathAndDeleteState() = runBlocking {
        dao.upsert(
            MediaAssetEntity(
                id = 0,
                recordId = 1,
                clientUuid = "media-cas",
                localUri = "/local/original.jpg",
                remoteUri = null,
                mime = "image/png",
                width = 10,
                height = 10,
                byteSize = 4,
                createdAt = 1,
                updatedAt = 20,
                deletedAt = null,
                syncDirty = true,
            ),
        )

        assertEquals(
            1,
            dao.mergePreparedMetadata(
                clientUuid = "media-cas",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                mime = "image/jpeg",
                width = 40,
                height = 30,
                byteSize = 99,
            ),
        )
        val afterPrepare = requireNotNull(dao.getByClientUuid("media-cas"))
        assertEquals("image/jpeg", afterPrepare.mime)
        assertEquals(40, afterPrepare.width)
        assertEquals(30, afterPrepare.height)
        assertEquals(99L, afterPrepare.byteSize)
        assertEquals(true, afterPrepare.syncDirty)
        assertNull(afterPrepare.remoteUri)

        assertEquals(
            1,
            dao.writeCommitReceipt(
                clientUuid = "media-cas",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                remoteUri = "sync://family/media-cas",
            ),
        )
        assertEquals(
            "sync://family/media-cas",
            dao.getByClientUuid("media-cas")?.remoteUri,
        )

        // Concurrent domain edit: higher revision, new path, still dirty.
        dao.update(
            requireNotNull(dao.getByClientUuid("media-cas")).copy(
                updatedAt = 50,
                localUri = "/local/replaced.jpg",
                mime = "image/webp",
                width = 1,
                height = 1,
                byteSize = 1,
                remoteUri = null,
                syncDirty = true,
            ),
        )

        assertEquals(
            0,
            dao.mergePreparedMetadata(
                clientUuid = "media-cas",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                mime = "image/gif",
                width = 9,
                height = 9,
                byteSize = 9,
            ),
        )
        assertEquals(
            0,
            dao.writeCommitReceipt(
                clientUuid = "media-cas",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                remoteUri = "sync://family/stale",
            ),
        )

        val current = requireNotNull(dao.getByClientUuid("media-cas"))
        assertEquals(50L, current.updatedAt)
        assertEquals("/local/replaced.jpg", current.localUri)
        assertEquals("image/webp", current.mime)
        assertEquals(1, current.width)
        assertEquals(1, current.height)
        assertEquals(1L, current.byteSize)
        assertNull(current.remoteUri)
        assertEquals(true, current.syncDirty)
    }

    @Test
    fun tombstoneBlocksStalePrepareAndReceiptWithoutClearingDeleteState() = runBlocking {
        dao.upsert(
            MediaAssetEntity(
                id = 0,
                recordId = 1,
                clientUuid = "media-tombstone",
                localUri = "/local/photo.jpg",
                remoteUri = null,
                mime = "image/png",
                width = 2,
                height = 2,
                byteSize = 2,
                createdAt = 1,
                updatedAt = 10,
                deletedAt = null,
                syncDirty = true,
            ),
        )
        dao.update(
            requireNotNull(dao.getByClientUuid("media-tombstone")).copy(
                deletedAt = 11,
                updatedAt = 11,
                syncDirty = true,
            ),
        )

        assertEquals(
            0,
            dao.mergePreparedMetadata(
                clientUuid = "media-tombstone",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/local/photo.jpg",
                expectedDeletedAt = null,
                mime = "image/jpeg",
                width = 40,
                height = 30,
                byteSize = 50,
            ),
        )
        assertEquals(
            0,
            dao.writeCommitReceipt(
                clientUuid = "media-tombstone",
                expectedUpdatedAt = 10,
                expectedLocalUri = "/local/photo.jpg",
                expectedDeletedAt = null,
                remoteUri = "sync://family/stale",
            ),
        )

        val tombstone = requireNotNull(dao.getByClientUuid("media-tombstone"))
        assertEquals(11L, tombstone.deletedAt)
        assertEquals(11L, tombstone.updatedAt)
        assertEquals("image/png", tombstone.mime)
        assertEquals(2L, tombstone.byteSize)
        assertNull(tombstone.remoteUri)
        assertEquals(true, tombstone.syncDirty)
    }

    /**
     * Each CAS key must independently force a miss on **both** prepare and receipt.
     * Co-mutating updatedAt with localUri/deletedAt would hide a WHERE drift that
     * dropped one of those columns.
     */
    @Test
    fun eachCasKeyAloneCausesMissOnPrepareAndReceipt() = runBlocking {
        suspend fun seed(
            clientUuid: String,
            updatedAt: Long = 20,
            localUri: String = "/local/original.jpg",
            deletedAt: Long? = null,
        ) {
            dao.upsert(
                MediaAssetEntity(
                    id = 0,
                    recordId = 1,
                    clientUuid = clientUuid,
                    localUri = localUri,
                    remoteUri = null,
                    mime = "image/png",
                    width = 10,
                    height = 10,
                    byteSize = 4,
                    createdAt = 1,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt,
                    syncDirty = true,
                ),
            )
        }

        suspend fun assertBothMiss(
            clientUuid: String,
            expectedUpdatedAt: Long,
            expectedLocalUri: String,
            expectedDeletedAt: Long?,
        ) {
            assertEquals(
                0,
                dao.mergePreparedMetadata(
                    clientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                    mime = "image/gif",
                    width = 1,
                    height = 1,
                    byteSize = 1,
                ),
            )
            assertEquals(
                0,
                dao.writeCommitReceipt(
                    clientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                    remoteUri = "sync://family/stale",
                ),
            )
            val row = requireNotNull(dao.getByClientUuid(clientUuid))
            assertEquals("image/png", row.mime)
            assertEquals(4L, row.byteSize)
            assertNull(row.remoteUri)
            assertEquals(true, row.syncDirty)
        }

        // Same updatedAt + same deletedAt, different localUri only.
        seed("media-localuri")
        dao.update(
            requireNotNull(dao.getByClientUuid("media-localuri")).copy(
                localUri = "/local/other.jpg",
            ),
        )
        assertBothMiss(
            clientUuid = "media-localuri",
            expectedUpdatedAt = 20,
            expectedLocalUri = "/local/original.jpg",
            expectedDeletedAt = null,
        )

        // Same updatedAt + same localUri, different deletedAt only.
        seed("media-deletedat")
        dao.update(
            requireNotNull(dao.getByClientUuid("media-deletedat")).copy(
                deletedAt = 99,
            ),
        )
        assertBothMiss(
            clientUuid = "media-deletedat",
            expectedUpdatedAt = 20,
            expectedLocalUri = "/local/original.jpg",
            expectedDeletedAt = null,
        )

        // Same localUri + same deletedAt, different updatedAt only.
        seed("media-updatedat")
        dao.update(
            requireNotNull(dao.getByClientUuid("media-updatedat")).copy(
                updatedAt = 50,
            ),
        )
        assertBothMiss(
            clientUuid = "media-updatedat",
            expectedUpdatedAt = 20,
            expectedLocalUri = "/local/original.jpg",
            expectedDeletedAt = null,
        )

        // Full match still applies on both APIs (shared WHERE matrix).
        seed("media-match")
        assertEquals(
            1,
            dao.mergePreparedMetadata(
                clientUuid = "media-match",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                mime = "image/jpeg",
                width = 40,
                height = 30,
                byteSize = 99,
            ),
        )
        assertEquals(
            1,
            dao.writeCommitReceipt(
                clientUuid = "media-match",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
                remoteUri = "sync://family/media-match",
            ),
        )
        val matched = requireNotNull(dao.getByClientUuid("media-match"))
        assertEquals("image/jpeg", matched.mime)
        assertEquals(99L, matched.byteSize)
        assertEquals("sync://family/media-match", matched.remoteUri)
    }

    @Test
    fun orphanCleanupDeletesOnlyTheExactCapturedRevision() = runBlocking {
        dao.upsert(
            MediaAssetEntity(
                id = 0,
                recordId = 99,
                clientUuid = "media-orphan-cas",
                localUri = "/local/original.jpg",
                createdAt = 1,
                updatedAt = 20,
                syncDirty = true,
            ),
        )
        dao.update(
            requireNotNull(dao.getByClientUuid("media-orphan-cas")).copy(
                localUri = "/local/concurrent.jpg",
                updatedAt = 21,
            ),
        )

        assertEquals(
            0,
            dao.deleteExactRevision(
                clientUuid = "media-orphan-cas",
                expectedUpdatedAt = 20,
                expectedLocalUri = "/local/original.jpg",
                expectedDeletedAt = null,
            ),
        )
        assertEquals("/local/concurrent.jpg", dao.getByClientUuid("media-orphan-cas")?.localUri)
        assertEquals(
            1,
            dao.deleteExactRevision(
                clientUuid = "media-orphan-cas",
                expectedUpdatedAt = 21,
                expectedLocalUri = "/local/concurrent.jpg",
                expectedDeletedAt = null,
            ),
        )
        assertNull(dao.getByClientUuid("media-orphan-cas"))
    }

    @Test
    fun persistSha256IfAbsentFillsNullOnceWithoutTouchingRevision() = runBlocking {
        dao.upsert(
            MediaAssetEntity(
                id = 0,
                recordId = 1,
                clientUuid = "media-sha-persist",
                localUri = "/local/original.jpg",
                createdAt = 1,
                updatedAt = 20,
                syncDirty = true,
            ),
        )

        assertEquals(
            1,
            dao.persistSha256IfAbsent("media-sha-persist", KNOWN_BYTES_1234_SHA256),
        )
        val filled = requireNotNull(dao.getByClientUuid("media-sha-persist"))
        assertEquals(KNOWN_BYTES_1234_SHA256, filled.sha256)
        assertEquals(20L, filled.updatedAt)
        assertEquals(true, filled.syncDirty)

        assertEquals(
            0,
            dao.persistSha256IfAbsent(
                "media-sha-persist",
                "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
            ),
        )
        val again = requireNotNull(dao.getByClientUuid("media-sha-persist"))
        assertEquals(KNOWN_BYTES_1234_SHA256, again.sha256)
        assertEquals(20L, again.updatedAt)
        assertEquals(true, again.syncDirty)
    }

    companion object {
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    }
}
