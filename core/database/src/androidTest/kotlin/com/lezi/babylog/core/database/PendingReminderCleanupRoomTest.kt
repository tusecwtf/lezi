package com.lezi.babylog.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReminderCleanupRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var store: PendingReminderCleanupStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        store = RoomPendingReminderCleanupStore(database.pendingReminderCleanupDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomAdapterRoundTripsAndMergesCurrentReminderSnapshot() = runBlocking {
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                carePlanIds = setOf(8L, 4L),
                familyServerRetained = false,
            ),
        )
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                carePlanIds = setOf(2L, 4L),
                nextFeedAt = 8L,
                nextFeedEpoch = "feed-epoch-1",
                familyServerRetained = true,
            ),
        )

        assertEquals(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                carePlanIds = setOf(2L, 4L, 8L),
                nextFeedAt = 8L,
                nextFeedEpoch = "feed-epoch-1",
                familyServerRetained = true,
            ),
            store.load(PendingReminderCleanupOperation.RECORDS_CLEAR),
        )
    }
}
