package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.UnresolvedLocalKind
import com.lezi.babylog.sync.backend.LiveCensus
import com.lezi.babylog.sync.backend.LiveCensusEntry
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.liveCensusKeyDigest
import com.lezi.babylog.sync.session.LIVE_CENSUS_LOCAL_EXTRA_CODE
import com.lezi.babylog.sync.session.LIVE_CENSUS_MISMATCH_CODE
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 0.5.4 ticket 01 (S1): 只从这台手机去掉 must hold on this device. The
 * dismissal writes a [dismissedEntityCacheKey] journal entry in the same
 * transaction as the local tombstone; the pull apply gate then skips later
 * LIVE family versions for the journaled entity (a family edit can no longer
 * resurrect it) while an incoming family TOMBSTONE still applies. The live-set
 * census projection strips journaled uuids from the server keys side so the
 * dismissed entity never wedges reconciliation as 家庭活集与服务器不一致.
 */
class ReplicaSyncEngineDismissDurabilityTest {

    @Test
    fun dismissedRecordKeepsTombstoneAndJournalWhenFamilyLaterEditsIt() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 2)
        val rig = ReplicaEngineRig(session)
        seedRecord(rig, RECORD_UUID)
        rig.engine.dismissUnresolvedLocally("record", RECORD_UUID, UnresolvedLocalKind.Rejected)
        val dismissed = requireNotNull(rig.records.getByClientUuid(RECORD_UUID))
        assertThat(dismissed.deletedAt).isNotNull()
        assertThat(dismissed.syncDirty).isFalse()
        assertThat(dismissed.mutationId).isNull()
        // Journal key landed durably next to the local tombstone.
        assertThat(rig.conflictDetails.getDismissedEntityJournal("record", RECORD_UUID))
            .isNotNull()

        // The family edits the record afterwards: server rev advances, still live.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(RECORD_UUID).copy(
                    updatedAt = 260,
                    versionId = "v-record-2",
                ),
            ),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = liveCensusOf(
                "baby" to listOf("baby-local"),
                "record" to listOf(RECORD_UUID),
            ),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        val after = requireNotNull(rig.records.getByClientUuid(RECORD_UUID))
        // The live family version never resurrected the dismissed row.
        assertThat(after.deletedAt).isEqualTo(dismissed.deletedAt)
        assertThat(after.updatedAt).isEqualTo(dismissed.updatedAt)
        assertThat(after.syncDirty).isFalse()
        assertThat(after.baseVersion).isEqualTo("v-record")
        // Census claims the dismissed record family-live: suppressed, no rewalk.
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(
            rig.conflictDetails.listPullDiagnostics().none {
                it.code == LIVE_CENSUS_MISMATCH_CODE || it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
            },
        ).isTrue()
    }

    @Test
    fun familyTombstoneStillAppliesToDismissedRecord() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 2)
        val rig = ReplicaEngineRig(session)
        seedRecord(rig, RECORD_UUID)
        rig.engine.dismissUnresolvedLocally("record", RECORD_UUID, UnresolvedLocalKind.Rejected)

        // 家庭删除照常收敛: the family's own tombstone applies over the dismissed row.
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteReplicaRecord(RECORD_UUID).copy(
                    updatedAt = 300,
                    deletedAt = 300,
                    versionId = "v-record-tomb",
                ),
            ),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = liveCensusOf("baby" to listOf("baby-local")),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        val after = requireNotNull(rig.records.getByClientUuid(RECORD_UUID))
        assertThat(after.deletedAt).isEqualTo(300)
        assertThat(after.baseVersion).isEqualTo("v-record-tomb")
        assertThat(after.syncDirty).isFalse()
        assertThat(after.mutationId).isNull()
        // The journal entry persists: the dismissal also holds if the family
        // ever restores the record live again.
        assertThat(rig.conflictDetails.getDismissedEntityJournal("record", RECORD_UUID))
            .isNotNull()
        assertThat(
            rig.conflictDetails.listPullDiagnostics().none {
                it.code == LIVE_CENSUS_MISMATCH_CODE || it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
            },
        ).isTrue()
    }

    @Test
    fun censusWithLiveKeysStripsDismissedUuidFromServerSide() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 2)
        val rig = ReplicaEngineRig(session)
        seedRecord(rig, RECORD_UUID)
        rig.engine.dismissUnresolvedLocally("record", RECORD_UUID, UnresolvedLocalKind.Rejected)

        // Census carrying live keys (server rewalk shape): the record type
        // claims only the dismissed uuid. Stripping it must leave the local live
        // set equal (both empty), so no mismatch, no rewalk, no diagnostic.
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = LiveCensus(
                entries = mapOf(
                    "baby" to LiveCensusEntry(
                        count = 1,
                        keyDigest = liveCensusKeyDigest(listOf("baby-local")),
                    ),
                    "record" to LiveCensusEntry(
                        count = 1,
                        keyDigest = liveCensusKeyDigest(listOf(RECORD_UUID)),
                        keys = listOf(RECORD_UUID),
                    ),
                ),
            ),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.pullCursors).containsExactly(2L).inOrder()
        assertThat(
            rig.conflictDetails.listPullDiagnostics().none {
                it.code == LIVE_CENSUS_MISMATCH_CODE || it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
            },
        ).isTrue()
    }

    @Test
    fun recordWithoutDismissJournalStillResurrectsThroughNormalApply() = runTest {
        // Guardrail: the apply gate only engages for journaled entities. The
        // plain live pull apply of a never-dismissed record is untouched.
        val session = joinedReplicaSession().copy(pullCursor = 2)
        val rig = ReplicaEngineRig(session)
        seedRecord(rig, RECORD_UUID)
        rig.engine.dismissUnresolvedLocally("record", RECORD_UUID, UnresolvedLocalKind.Rejected)
        // A different, non-dismissed record still applies as usual.
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord(OTHER_RECORD_UUID)),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.records.getByClientUuid(OTHER_RECORD_UUID)).isNotNull()
        val dismissed = requireNotNull(rig.records.getByClientUuid(RECORD_UUID))
        assertThat(dismissed.deletedAt).isNotNull()
    }

    private fun seedRecord(rig: ReplicaEngineRig, recordUuid: String) {
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-local",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 1_000,
                endTimestamp = null,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = false,
                familyPublishedUpdatedAt = 200,
                baseVersion = "v-record",
            ),
        )
    }

    private fun liveCensusOf(vararg types: Pair<String, List<String>>): LiveCensus =
        LiveCensus(
            entries = types.associate { (entityType, keys) ->
                entityType to LiveCensusEntry(
                    count = keys.size.toLong(),
                    keyDigest = liveCensusKeyDigest(keys),
                )
            },
        )

    private companion object {
        const val RECORD_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val OTHER_RECORD_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
    }
}
