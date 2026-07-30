package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PendingReminderCleanupStoreTest {
    @Test
    fun upsertMergesCurrentCarePlanIdsAndPromotesRetention() = runBlocking {
        val dao = FakePendingReminderCleanupDao(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                carePlanIds = "5,2,5",
                familyServerRetained = false,
            ),
        )
        val store = RoomPendingReminderCleanupStore(dao)

        store.upsert(
            PendingReminderCleanup(
                scope = LocalDataClearScope.RecordsOnly,
                carePlanIds = setOf(3L, 2L),
                systemCalendarProjections = linkedMapOf(
                    "plan-3" to "evt,3",
                    "plan-2" to "evt-2",
                ),
                familyServerRetained = true,
            ),
        )

        assertThat(dao.pending).isEqualTo(
            PendingReminderCleanupEntity(
                operation = "records_clear",
                carePlanIds = "2,3,5",
                systemCalendarProjectionsJson =
                    "{\"plan-2\":\"evt-2\",\"plan-3\":\"evt,3\"}",
                familyServerRetained = true,
            ),
        )
    }

    @Test
    fun emptyCurrentSnapshotRemainsRecoverableUntilCompletion() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val scope = LocalDataClearScope.RecordsOnly

        store.upsert(
            PendingReminderCleanup(
                scope = scope,
                familyServerRetained = true,
            ),
        )

        assertThat(store.load(scope)?.carePlanIds).isEmpty()
        assertThat(store.load(scope)?.systemCalendarProjections).isEmpty()

        store.delete(scope)

        assertThat(store.load(scope)).isNull()
    }

    @Test
    fun currentProjectionSnapshotRoundTrips() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val pending = PendingReminderCleanup(
            scope = LocalDataClearScope.RecordsOnly,
            carePlanIds = setOf(8L),
            systemCalendarProjections = linkedMapOf(
                "plan-with-id" to "evt-41",
                "plan-needs-uid-lookup" to null,
            ),
            familyServerRetained = false,
        )

        store.upsert(pending)

        assertThat(store.load(LocalDataClearScope.RecordsOnly)).isEqualTo(pending)
    }

    @Test
    fun allLocalCleanupUsesIndependentOperationKey() = runBlocking {
        val dao = FakePendingReminderCleanupDao()
        val store = RoomPendingReminderCleanupStore(dao)
        val scope = LocalDataClearScope.AllLocalData

        store.upsert(
            PendingReminderCleanup(
                scope = scope,
                carePlanIds = setOf(8L),
                familyServerRetained = false,
            ),
        )

        assertThat(dao.pending?.operation).isEqualTo("all_local_data_clear")
        assertThat(store.load(scope)?.scope).isEqualTo(scope)
    }

    @Test
    fun corruptCarePlanIdsFailClosedAndKeepPendingRow() = runBlocking {
        val original = PendingReminderCleanupEntity(
            operation = "records_clear",
            carePlanIds = "7,not-a-plan,11",
            familyServerRetained = true,
        )
        val dao = FakePendingReminderCleanupDao(original)
        val store = RoomPendingReminderCleanupStore(dao)

        val failure = runCatching {
            store.load(LocalDataClearScope.RecordsOnly)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CorruptPendingReminderCleanupException::class.java)
        assertThat(failure).hasMessageThat().contains("care-plan")
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
