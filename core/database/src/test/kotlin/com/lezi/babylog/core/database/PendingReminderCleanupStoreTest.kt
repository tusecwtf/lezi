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
                settingsSnapshotCaptured = false,
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
                carePlanIds = "5,2,5",
                familyServerRetained = false,
            ),
        )
        val store = RoomPendingReminderCleanupStore(dao)

        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(3L, 2L),
                carePlanIds = setOf(3L, 2L),
                systemCalendarProjections = linkedMapOf(
                    "plan-3" to "evt,3",
                    "plan-2" to "evt-2",
                ),
                familyServerRetained = false,
            ),
        )
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = setOf(1L),
                carePlanIds = setOf(1L),
                systemCalendarProjections = mapOf("plan-1" to "evt-1"),
                familyServerRetained = true,
            ),
        )
        store.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = emptySet(),
                carePlanIds = emptySet(),
                systemCalendarProjections = emptyMap(),
                familyServerRetained = false,
            ),
        )

        assertThat(dao.pending).isEqualTo(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                calendarEventIds = "1,2,3,7",
                carePlanIds = "1,2,3,5",
                systemCalendarProjectionsJson =
                    "{\"plan-1\":\"evt-1\",\"plan-2\":\"evt-2\",\"plan-3\":\"evt,3\"}",
                settingsSnapshotCaptured = true,
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
        assertThat(store.load(operation)?.carePlanIds).isEmpty()
        assertThat(store.load(operation)?.systemCalendarProjections).isEmpty()
        assertThat(store.load(operation)?.settingsSnapshotCaptured).isTrue()
        assertThat(dao.pending?.calendarEventIds).isEmpty()
        assertThat(dao.pending?.carePlanIds).isEmpty()
        assertThat(dao.pending?.systemCalendarProjectionsJson).isEqualTo("{}")

        store.delete(operation)

        assertThat(store.load(operation)).isNull()
    }

    @Test
    fun nextFeedEpochRoundTripsThroughTheDurableMarker() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val pending = PendingReminderCleanup(
            operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
            calendarEventIds = emptySet(),
            nextFeedAt = 8L,
            nextFeedEpoch = "feed-epoch-1",
            familyServerRetained = false,
        )

        store.upsert(pending)

        assertThat(store.load(PendingReminderCleanupOperation.RECORDS_CLEAR)).isEqualTo(pending)
        assertThat(dao.pending?.nextFeedEpoch).isEqualTo("feed-epoch-1")
    }

    @Test
    fun systemCalendarProjectionPairsAndUidOnlyHandoffsRoundTrip() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val pending = PendingReminderCleanup(
            operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
            calendarEventIds = emptySet(),
            systemCalendarProjections = linkedMapOf(
                "plan-with-id" to "evt-41",
                "plan-needs-uid-lookup" to null,
            ),
            familyServerRetained = false,
        )

        store.upsert(pending)

        assertThat(store.load(PendingReminderCleanupOperation.RECORDS_CLEAR))
            .isEqualTo(pending)
        assertThat(dao.pending?.systemCalendarProjectionsJson)
            .isEqualTo("{\"plan-needs-uid-lookup\":null,\"plan-with-id\":\"evt-41\"}")
    }

    @Test
    fun allLocalCleanupUsesAnIndependentDurableOperationKey() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val operation = PendingReminderCleanupOperation.ALL_LOCAL_DATA_CLEAR

        store.upsert(
            PendingReminderCleanup(
                operation = operation,
                calendarEventIds = setOf(7L),
                carePlanIds = setOf(8L),
                familyServerRetained = false,
            ),
        )

        assertThat(dao.pending?.operation).isEqualTo("all_local_data_clear")
        assertThat(store.load(operation)?.operation).isEqualTo(operation)
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

    @Test
    fun corruptCarePlanIdsFailClosedAndKeepPendingRow() = runBlocking {
        val original = PendingReminderCleanupEntity(
            operation = "records_clear",
            calendarEventIds = "3,9",
            carePlanIds = "7,not-a-plan,11",
            familyServerRetained = true,
        )
        val dao = FakePendingReminderCleanupDao(original)
        val store = RoomPendingReminderCleanupStore(dao)

        val failure = runCatching {
            store.load(PendingReminderCleanupOperation.RECORDS_CLEAR)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CorruptPendingReminderCleanupException::class.java)
        assertThat(failure).hasMessageThat().contains("care-plan")
        assertThat(failure).hasMessageThat().contains("not-a-plan")
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
