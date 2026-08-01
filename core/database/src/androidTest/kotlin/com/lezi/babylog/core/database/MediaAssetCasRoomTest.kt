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
}
