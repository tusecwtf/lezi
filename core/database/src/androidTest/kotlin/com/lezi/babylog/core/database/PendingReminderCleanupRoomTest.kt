package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReminderCleanupRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var dao: PendingReminderCleanupDao
    private lateinit var store: PendingReminderCleanupStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        dao = database.pendingReminderCleanupDao()
        store = RoomPendingReminderCleanupStore(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomAdapterStrictlyLoadsAndMergesLegacyRow() = runBlocking {
        dao.upsert(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "9,3,9",
                carePlanIds = "8,4,8",
                familyServerRetained = false,
            ),
        )

        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(2L, 3L),
                carePlanIds = setOf(2L, 4L),
                familyServerRetained = true,
            ),
        )

        assertEquals(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(2L, 3L, 9L),
                carePlanIds = setOf(2L, 4L, 8L),
                familyServerRetained = true,
            ),
            store.load(PendingReminderCleanupOperation.RECORDS_CLEAR),
        )
        assertEquals(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "2,3,9",
                carePlanIds = "2,4,8",
                familyServerRetained = true,
            ),
            dao.get("records_clear"),
        )
    }

    @Test
    fun corruptLegacyRowRemainsPendingAfterLoadFailure() = runBlocking {
        val original = PendingReminderCleanupEntity(
            operation = "records_clear",
            calendarEventIds = "3,broken,9",
            familyServerRetained = true,
        )
        dao.upsert(original)

        val failure = runCatching {
            store.load(PendingReminderCleanupOperation.RECORDS_CLEAR)
        }.exceptionOrNull()

        assertTrue(failure is CorruptPendingReminderCleanupException)
        assertEquals(original, dao.get("records_clear"))
    }
}
