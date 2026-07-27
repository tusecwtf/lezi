package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PendingReplicaCleanupStoreTest {
    @Test
    fun stageAndLoadRoundTripArbitraryMediaPaths() = runBlocking {
        val store = RoomPendingReplicaCleanupStore(FakePendingReplicaCleanupDao())
        val pending = PendingReplicaCleanup(
            scope = PendingReplicaCleanupScope.RECORDS_ONLY,
            familyId = "family-a",
            pullGeneration = "generation-a",
            mediaClientUuids = setOf("media-b", "media-a"),
            localMediaPaths = setOf(
                "photos/逗号,照片.jpg",
                "photos/line\nbreak.jpg",
            ),
        )

        store.stage(pending)

        assertThat(store.load()).isEqualTo(pending)
    }

    @Test
    fun stageUsesStableJsonAndDeleteRetiresTheMarker() = runBlocking {
        val dao = FakePendingReplicaCleanupDao()
        val store = RoomPendingReplicaCleanupStore(dao)

        store.stage(
            PendingReplicaCleanup(
                scope = PendingReplicaCleanupScope.ALL_LOCAL,
                familyId = "family-a",
                pullGeneration = "generation-a",
                mediaClientUuids = setOf("media-z", "media-a"),
                localMediaPaths = setOf("photos/z.jpg", "photos/a.jpg"),
            ),
        )

        assertThat(dao.requirePending().mediaClientUuidsJson)
            .isEqualTo("[\"media-a\",\"media-z\"]")
        assertThat(dao.requirePending().localMediaPathsJson)
            .isEqualTo("[\"photos/a.jpg\",\"photos/z.jpg\"]")

        store.delete()

        assertThat(store.load()).isNull()
    }

    @Test
    fun corruptScopeAndJsonFailClosedWithoutDeletingTheMarker() = runBlocking {
        val dao = FakePendingReplicaCleanupDao()
        val store = RoomPendingReplicaCleanupStore(dao)
        dao.seed(scope = "future_scope")

        assertThat(runCatching { store.load() }.exceptionOrNull())
            .isInstanceOf(CorruptPendingReplicaCleanupException::class.java)
        assertThat(dao.requirePending().scope).isEqualTo("future_scope")

        dao.seed(scope = "records_only", mediaClientUuidsJson = "[1]")
        assertThat(runCatching { store.load() }.exceptionOrNull())
            .isInstanceOf(CorruptPendingReplicaCleanupException::class.java)
        assertThat(dao.requirePending().mediaClientUuidsJson).isEqualTo("[1]")
    }

    @Test
    fun blankIdsAndPathsAreRejectedBeforeRoomWrite() = runBlocking {
        val dao = FakePendingReplicaCleanupDao()
        val store = RoomPendingReplicaCleanupStore(dao)

        val blankIdFailure = runCatching {
            store.stage(
                PendingReplicaCleanup(
                    scope = PendingReplicaCleanupScope.RECORDS_ONLY,
                    familyId = "family-a",
                    pullGeneration = "",
                    mediaClientUuids = setOf(" "),
                    localMediaPaths = emptySet(),
                ),
            )
        }.exceptionOrNull()
        val blankPathFailure = runCatching {
            store.stage(
                PendingReplicaCleanup(
                    scope = PendingReplicaCleanupScope.RECORDS_ONLY,
                    familyId = "family-a",
                    pullGeneration = "",
                    mediaClientUuids = emptySet(),
                    localMediaPaths = setOf(""),
                ),
            )
        }.exceptionOrNull()

        assertThat(blankIdFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(blankPathFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(dao.pending).isNull()
    }
}

private class FakePendingReplicaCleanupDao : PendingReplicaCleanupDao {
    var pending: PendingReplicaCleanupEntity? = null
        private set

    fun requirePending(): PendingReplicaCleanupEntity = checkNotNull(pending)

    fun seed(
        scope: String,
        mediaClientUuidsJson: String = "[]",
        localMediaPathsJson: String = "[]",
    ) {
        pending = PendingReplicaCleanupEntity(
            operation = "local_replica_clear",
            scope = scope,
            familyId = "family-a",
            pullGeneration = "generation-a",
            mediaClientUuidsJson = mediaClientUuidsJson,
            localMediaPathsJson = localMediaPathsJson,
        )
    }

    override suspend fun get(operation: String): PendingReplicaCleanupEntity? =
        pending?.takeIf { it.operation == operation }

    override suspend fun insert(pending: PendingReplicaCleanupEntity) {
        check(this.pending == null)
        this.pending = pending
    }

    override suspend fun delete(operation: String) {
        if (pending?.operation == operation) pending = null
    }
}
