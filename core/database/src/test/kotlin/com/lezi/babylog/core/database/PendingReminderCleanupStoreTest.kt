package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PendingReminderCleanupStoreTest {
    @Test
    fun loadDecodesLegacyCsvIntoTypedSnapshot() = runBlocking {
        val dao = FakePendingReminderCleanupDao(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "9, 3,9",
                familyServerRetained = false,
            ),
        )
        val store = RoomPendingReminderCleanupStore(dao)

        val pending = store.load(PendingReminderCleanupOperation.RECORDS_CLEAR)

        assertThat(pending).isEqualTo(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(3L, 9L),
                familyServerRetained = false,
            ),
        )
    }

    @Test
    fun upsertMergesIdsInStableOrderAndOnlyPromotesFamilyServerRetained() = runBlocking {
        val dao = FakePendingReminderCleanupDao(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "7,2,7",
                familyServerRetained = false,
            ),
        )
        val store = RoomPendingReminderCleanupStore(dao)

        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(3L, 2L),
                familyServerRetained = false,
            ),
        )
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(1L),
                familyServerRetained = true,
            ),
        )
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = emptySet(),
                familyServerRetained = false,
            ),
        )

        assertThat(dao.pending).isEqualTo(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "1,2,3,7",
                familyServerRetained = true,
            ),
        )
    }

    @Test
    fun emptyIdSetRemainsRecoverableUntilExplicitCompletion() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val operation = PendingReminderCleanupOperation.RECORDS_CLEAR

        store.upsert(
            PendingReminderCleanup(
                operation = operation,
                calendarEventIds = emptySet(),
                familyServerRetained = true,
            ),
        )

        assertThat(store.load(operation)?.calendarEventIds).isEmpty()
        assertThat(dao.pending?.calendarEventIds).isEmpty()

        store.delete(operation)

        assertThat(store.load(operation)).isNull()
    }

    @Test
    fun corruptLegacyCsvFailsClosedAndKeepsPendingRow() = runBlocking {
        val original = PendingReminderCleanupEntity(
            operation = "records_clear",
            calendarEventIds = "3,not-an-id,9",
            familyServerRetained = true,
        )
        val dao = FakePendingReminderCleanupDao(original)
        val store = RoomPendingReminderCleanupStore(dao)

        val failure = runCatching {
            store.load(PendingReminderCleanupOperation.RECORDS_CLEAR)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CorruptPendingReminderCleanupException::class.java)
        assertThat(failure).hasMessageThat().contains("not-an-id")
        assertThat(dao.pending).isEqualTo(original)
        assertThat(dao.deleteCount).isEqualTo(0)
    }
}

private class FakePendingReminderCleanupDao(
    var pending: PendingReminderCleanupEntity? = null,
) : PendingReminderCleanupDao {
    var deleteCount: Int = 0

    override suspend fun get(operation: String): PendingReminderCleanupEntity? =
        pending?.takeIf { it.operation == operation }

    override suspend fun upsert(pending: PendingReminderCleanupEntity) {
        this.pending = pending
    }

    override suspend fun delete(operation: String) {
        deleteCount += 1
        if (pending?.operation == operation) pending = null
    }
}
