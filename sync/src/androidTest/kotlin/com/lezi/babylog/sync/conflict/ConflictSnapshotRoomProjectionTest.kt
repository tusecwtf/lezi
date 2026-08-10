package com.lezi.babylog.sync.conflict

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictSnapshotRoomProjectionTest {
    @Test
    fun productionProjection_roundTripsReplacesClearsReopensAndRollsBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "conflict-projection-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, LeziDatabase::class.java, name).build()
        try {
            database.recordDao().upsert(
                RecordEntity(
                    clientUuid = ROOT_UUID,
                    babyId = 1,
                    type = "formula",
                    timestamp = 100,
                    updatedAt = 100,
                ),
            )
            val first = snapshot(CONFLICT_ONE, "v1", "stable-one")
            projection(database).replaceComplete(first)

            database.close()
            database = Room.databaseBuilder(context, LeziDatabase::class.java, name).build()
            val reopened = projection(database)
            assertThat(reopened.read(CONFLICT_ONE)).isEqualTo(first)
            assertThat(database.recordDao().getByClientUuid(ROOT_UUID)).isNotNull()

            val second = snapshot(CONFLICT_TWO, "v2", "stable-two")
            reopened.replaceComplete(second)
            assertThat(reopened.read(CONFLICT_ONE)).isNull()
            assertThat(reopened.read(CONFLICT_TWO)).isEqualTo(second)

            val failing = ConflictSnapshotProjection(
                database.conflictSummaryDao(),
                FailingAfterWriteDao(database.conflictSnapshotCacheDao()),
                DatabaseModule.transactionRunner(database),
            )
            assertThat(
                runCatching {
                    failing.replaceComplete(snapshot(CONFLICT_THREE, "v3", "must-rollback"))
                }.exceptionOrNull(),
            ).isNotNull()
            assertThat(reopened.read(CONFLICT_TWO)).isEqualTo(second)
            assertThat(reopened.read(CONFLICT_THREE)).isNull()
            assertThat(database.conflictSummaryDao().get(CONFLICT_TWO)).isNotNull()

            reopened.clearRoot(ConflictRootType.Record, ROOT_UUID)
            assertThat(reopened.read(CONFLICT_TWO)).isNull()
            assertThat(database.conflictSummaryDao().listForRoot("record", ROOT_UUID)).isEmpty()
            assertThat(database.recordDao().getByClientUuid(ROOT_UUID)).isNotNull()
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun projection(database: LeziDatabase) = ConflictSnapshotProjection(
        database.conflictSummaryDao(),
        database.conflictSnapshotCacheDao(),
        DatabaseModule.transactionRunner(database),
    )

    private fun snapshot(conflictId: String, version: String, note: String): ConflictSnapshot =
        ConflictSnapshotCodec.decode(
            """{"contract":"conflict_snapshot_v2","conflict_id":"$conflictId","entity_type":"record","client_uuid":"$ROOT_UUID","snapshot_token":"${"a".repeat(43)}","expires_at":2000000,"stable":{"version_id":"$version","base_version":"base-$version","root":{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"$note","payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"},"media":[{"media_uuid":"$MEDIA_ONE","role":"log","sha256":"${"a".repeat(64)}","byte_size":12,"mime":"image/jpeg","width":640,"height":480}],"deleted":false,"mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100},"branches":[{"version_id":"branch-$version","base_version":"$version","root":{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":60},"schema_version":2,"updated_at":110,"created_by_membership_id":"member-b"},"media":[{"media_uuid":"$MEDIA_TWO","role":"log","sha256":"${"b".repeat(64)}","byte_size":34,"mime":"image/png","width":320,"height":240}],"deleted":true,"mutation_id":"$BRANCH_MUTATION_UUID","actor_id":"member-b","device_id":"device-b","received_at":110}],"conflicting":[{"path":"/note","candidates":[{"choice_id":"choice-aaaaaaaaa","outcome":{"op":"set","value":null},"sources":[{"version_id":"$version","mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100}]},{"choice_id":"choice-bbbbbbbbb","outcome":{"op":"set","value":"branch"},"sources":[{"version_id":"branch-$version","mutation_id":"$BRANCH_MUTATION_UUID","actor_id":"member-b","device_id":"device-b","received_at":110}]}]},{"path":"/media/$MEDIA_TWO","candidates":[{"choice_id":"choice-remove-med","outcome":{"op":"remove"},"sources":[{"version_id":"branch-$version","mutation_id":"$BRANCH_MUTATION_UUID","actor_id":"member-b","device_id":"device-b","received_at":110}]},{"choice_id":"choice-keep-media","outcome":{"op":"set","value":{"sha256":"${"b".repeat(64)}","dimensions":{"width":320,"height":240}}},"sources":[{"version_id":"$version","mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100}]}]}],"auto_merged":[{"path":"/payload_json/amount_ml","outcome":{"op":"set","value":60},"sources":[{"version_id":"$version","mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100}]}],"page_index":0,"continuation":null,"complete":true}""",
        )

    private class FailingAfterWriteDao(
        private val delegate: ConflictSnapshotCacheDao,
    ) : ConflictSnapshotCacheDao by delegate {
        override suspend fun upsert(entity: ConflictSnapshotCacheEntity) {
            delegate.upsert(entity)
            error("injected projection failure")
        }
    }

    private companion object {
        const val ROOT_UUID = "00000000-0000-0000-0000-000000000011"
        const val BABY_UUID = "00000000-0000-0000-0000-000000000012"
        const val MUTATION_UUID = "00000000-0000-0000-0000-000000000013"
        const val BRANCH_MUTATION_UUID = "00000000-0000-0000-0000-000000000014"
        const val MEDIA_ONE = "00000000-0000-0000-0000-000000000015"
        const val MEDIA_TWO = "00000000-0000-0000-0000-000000000016"
        const val CONFLICT_ONE = "00000000-0000-0000-0000-000000000021"
        const val CONFLICT_TWO = "00000000-0000-0000-0000-000000000022"
        const val CONFLICT_THREE = "00000000-0000-0000-0000-000000000023"
    }
}
