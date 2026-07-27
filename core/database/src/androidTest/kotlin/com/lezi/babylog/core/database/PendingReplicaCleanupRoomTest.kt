package com.lezi.babylog.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReplicaCleanupRoomTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: LeziDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun stagedMarkerSurvivesDatabaseReopenUntilExplicitlyDeleted() = runBlocking {
        val pending = PendingReplicaCleanup(
            scope = PendingReplicaCleanupScope.RECORDS_ONLY,
            familyId = "family-a",
            pullGeneration = "generation-a",
            mediaClientUuids = setOf("media-b", "media-a"),
            localMediaPaths = setOf("photos/逗号,照片.jpg", "photos/line\nbreak.jpg"),
        )
        openStore().stage(pending)
        database?.close()
        database = null

        val reopened = openStore()

        assertEquals(pending, reopened.load())
        reopened.delete()
        assertNull(reopened.load())
    }

    @Test
    fun nestedDomainDeleteAndMarkerRollBackTogether() = runBlocking {
        val db = openDatabase()
        db.recordDao().upsert(
            RecordEntity(
                clientUuid = "record-before-clear",
                babyId = 1,
                type = "formula",
                timestamp = 100,
                payloadJson = "{}",
                updatedAt = 100,
            ),
        )
        val transactions = RoomDatabaseTransactionRunner(db)
        val store = RoomPendingReplicaCleanupStore(db.pendingReplicaCleanupDao())

        val failure = runCatching {
            transactions.run {
                store.stage(
                    PendingReplicaCleanup(
                        scope = PendingReplicaCleanupScope.RECORDS_ONLY,
                        familyId = "family-a",
                        pullGeneration = "generation-a",
                        mediaClientUuids = emptySet(),
                        localMediaPaths = emptySet(),
                    ),
                )
                transactions.run {
                    db.recordDao().deleteAll()
                }
                error("abort outer clear")
            }
        }.exceptionOrNull()

        assertEquals("abort outer clear", failure?.message)
        assertEquals(
            "record-before-clear",
            db.recordDao().getByClientUuid("record-before-clear")?.clientUuid,
        )
        assertNull(store.load())
    }

    private fun openStore(): PendingReplicaCleanupStore {
        val db = openDatabase()
        return RoomPendingReplicaCleanupStore(db.pendingReplicaCleanupDao())
    }

    private fun openDatabase(): LeziDatabase {
        val db = Room.databaseBuilder(context, LeziDatabase::class.java, DATABASE_NAME).build()
        database = db
        return db
    }

    private companion object {
        const val DATABASE_NAME = "pending-replica-cleanup-room-test"
    }
}
