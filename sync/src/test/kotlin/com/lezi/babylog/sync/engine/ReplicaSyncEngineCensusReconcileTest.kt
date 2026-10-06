package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.LiveCensus
import com.lezi.babylog.sync.backend.LiveCensusEntry
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.liveCensusKeyDigest
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.LIVE_CENSUS_ENTITY_TYPE
import com.lezi.babylog.sync.session.LIVE_CENSUS_LOCAL_EXTRA_CODE
import com.lezi.babylog.sync.session.LIVE_CENSUS_MISMATCH_CODE
import com.lezi.babylog.sync.session.toSkippedPullItem
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] for ticket 07 — after a pull
 * round reaches head, the server's live-set census (count + key digest per
 * type) is compared against the local live Room rows. A mismatch triggers at
 * most ONE automatic cursor-0 full rewalk per cycle (ticket 04 clears the
 * stall ledger there), so a silently skipped entity is re-delivered and can
 * repair. If the sets still diverge, a durable census-mismatch diagnostic
 * surfaces through the skip-visibility warning without failing the cycle and
 * without a third-pull storm. Servers without the census field are skipped
 * gracefully.
 */
class ReplicaSyncEngineCensusReconcileTest {

    @Test
    fun censusDigestMatchesServerAlgorithmIncludingEmptySet() {
        // SHA-256 of the empty byte string.
        assertThat(liveCensusKeyDigest(emptyList()))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        // Keys are sorted and joined by \n with no trailing newline.
        assertThat(liveCensusKeyDigest(listOf("record-b", "record-a")))
            .isEqualTo("c841d2d543d87df81547a351a1644e22c079b460d322aad8656ecf744f36755c")
        assertThat(liveCensusKeyDigest(listOf("record-a", "record-b")))
            .isEqualTo(liveCensusKeyDigest(listOf("record-b", "record-a")))
        assertThat(liveCensusKeyDigest(listOf("baby-local")))
            .isEqualTo("369b41542f37e20d4f716a1fbc5829d1ed241ac58bfe333886952832bfb7c4a2")
    }

    @Test
    fun pullWithoutCensusFieldSkipsReconcileWithoutExtraPulls() = runTest {
        val session = joinedReplicaSession().copy(pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        // No liveCensus: pre-0.4.7 server shape.
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.pullCursors).containsExactly(2L).inOrder()
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
    }

    @Test
    fun censusMismatchTriggersOneCursorZeroRewalkThatRepairsSkippedEntity() = runTest {
        // Phase 1 — exhaust the stall ceiling while the census agrees with the
        // local set (the wake is missing on both sides): no reconcile interferes.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        val unreadyPage = PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = matchedCensus(),
        )
        repeat(4) {
            rig.backend.nextPull = unreadyPage
            rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7L)
        assertThat(rig.conflictDetails.getTransportJournal(PULL_STALL_JOURNAL_KEY)?.payloadJson)
            .isEqualTo("""{"$STALL_KEY":4}""")

        // Phase 2 — the census now claims the wake is live server-side while it
        // is locally skipped: mismatch triggers exactly one cursor-0 rewalk,
        // and its second page finally delivers the missing parent so the wake
        // applies and the local live set matches the census.
        rig.backend.pullResults.clear()
        rig.backend.nextPull = null
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = censusClaimingRepairedFamily(),
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = true,
            liveCensus = censusClaimingRepairedFamily(),
        )
        rig.backend.pullResults += PullResult(
            entities = listOf(remoteSleepRecord("missing-sleep")),
            cursor = 9,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = censusClaimingRepairedFamily(),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9L)
        assertThat(rig.backend.pullCursors)
            .containsExactly(2L, 2L, 2L, 2L, 7L, 0L, 7L)
            .inOrder()
        // The rewalk started at cursor 0, so the stall ledger was cleared and
        // the resolved wake left no entry behind.
        assertThat(rig.conflictDetails.getTransportJournal(PULL_STALL_JOURNAL_KEY)).isNull()
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
    }

    @Test
    fun persistentCensusMismatchSurfacesDiagnosticWithoutThirdPull() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 7)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        // The census claims a record the rewalk can never deliver (the server
        // keeps returning the same head page): the repair stays incomplete.
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = censusClaimingGhostRecord(),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        // Main pull + the single bounded cursor-0 rewalk. No third pull.
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        val diagnostics = rig.conflictDetails.listPullDiagnostics()
        assertThat(diagnostics).hasSize(1)
        val diagnostic = diagnostics.single()
        assertThat(diagnostic.entityType).isEqualTo(LIVE_CENSUS_ENTITY_TYPE)
        assertThat(diagnostic.code).isEqualTo(LIVE_CENSUS_MISMATCH_CODE)
        assertThat(diagnostic.localSnapshot).isEqualTo("record=1/2")
        // Visible 人话 warning through the skip-visibility surface.
        val item = diagnostic.toSkippedPullItem()
        assertThat(item.reasonGateDisplay).isEqualTo("家庭活集与服务器不一致")

        // Same durable snapshot must not pay another history rewalk. A later
        // cycle only retries when the local/server counts actually change.
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L, 7L).inOrder()
        assertThat(rig.conflictDetails.listPullDiagnostics()).hasSize(1)
    }

    @Test
    fun persistentWakeExtraProjectsClickableIdentityAndDismissAlignsCensus() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 7)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        val publishedWake = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee11"
        val extraWake = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee12"
        rig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = publishedWake,
                sleepRecordClientUuid = SLEEP_UUID,
                wakeTimestamp = 1_500,
                observerMembershipId = "membership-a",
                updatedAt = 300,
                syncDirty = false,
                familyPublishedUpdatedAt = 300,
                baseVersion = "v-wake",
            ),
        )
        rig.wakeObservations.seed(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                clientUuid = extraWake,
                sleepRecordClientUuid = SLEEP_UUID,
                wakeTimestamp = 1_600,
                observerMembershipId = "membership-a",
                updatedAt = 400,
                syncDirty = false,
                familyPublishedUpdatedAt = null,
                baseVersion = null,
            ),
        )
        val census = liveCensusOf(
            "baby" to listOf("baby-local"),
            "record" to listOf(SLEEP_UUID),
            "wake_observation" to listOf(publishedWake),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = census,
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        val extras = rig.conflictDetails.listPullDiagnostics()
            .filter { it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE }
        assertThat(extras).hasSize(1)
        assertThat(extras.single().entityType).isEqualTo("wake_observation")
        assertThat(extras.single().clientUuid).isEqualTo(extraWake)
        assertThat(extras.single().toSkippedPullItem().reasonGateDisplay)
            .isEqualTo("本机有、家里没有")
        assertThat(rig.backend.pullPageRequests.any { "wake_observation" in it.liveKeyTypes })
            .isTrue()

        rig.engine.dismissUnresolvedLocally(
            "wake_observation",
            extraWake,
            com.lezi.babylog.sync.UnresolvedLocalKind.LocalExtra,
        )
        val gone = requireNotNull(rig.wakeObservations.getByClientUuid(extraWake))
        assertThat(gone.deletedAt).isNotNull()
        assertThat(gone.syncDirty).isFalse()
        assertThat(rig.conflictDetails.getTerminalReceipt("wake_observation", extraWake)?.abandoned)
            .isTrue()

        rig.backend.causalCommittedUnits.clear()
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.LocalWrite)
        assertThat(rig.backend.causalCommittedUnits.flatten()).isEmpty()

        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
            liveCensus = census,
        )
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(
            rig.conflictDetails.listPullDiagnostics().none {
                it.code == LIVE_CENSUS_MISMATCH_CODE || it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
            },
        ).isTrue()
    }

    private fun seedSleep(rig: ReplicaEngineRig, sleepUuid: String) {
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
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 1_000,
                endTimestamp = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = false,
                familyPublishedUpdatedAt = 200,
                baseVersion = "v-sleep",
            ),
        )
    }

    private fun remoteSleepRecord(clientUuid: String) = SyncEntity(
        type = "record",
        clientUuid = clientUuid,
        payloadJson = """
            {
              "baby_client_uuid":"baby-local",
              "created_by_membership_id":"membership-b",
              "type":"sleep",
              "custom_item_client_uuid":null,
              "timestamp":1400,
              "note":null,
              "payload_json":{"is_nap":false,"anomaly_flag":false},
              "schema_version":2,
              "effective_wake_observation_client_uuid":null
            }
        """.trimIndent(),
        updatedAt = 250,
    )

    private fun remoteWakeObservation(clientUuid: String, sleepUuid: String) = SyncEntity(
        type = "wake_observation",
        clientUuid = clientUuid,
        payloadJson = """
            {
              "sleep_record_client_uuid":"$sleepUuid",
              "wake_timestamp":1500,
              "note":null,
              "withdrawn":false,
              "observer_membership_id":"membership-b"
            }
        """.trimIndent(),
        updatedAt = 300,
    )

    /** Census matching the seeded local set (no wake claimed yet). */
    private fun matchedCensus() = liveCensusOf(
        "baby" to listOf("baby-local"),
        "record" to listOf(SLEEP_UUID),
    )

    /** Census of the family after the repair pages land. */
    private fun censusClaimingRepairedFamily() = liveCensusOf(
        "baby" to listOf("baby-local"),
        "record" to listOf(SLEEP_UUID, "missing-sleep"),
        "wake_observation" to listOf(WAKE_UUID),
    )

    /** Census claiming a record the client can never receive. */
    private fun censusClaimingGhostRecord() = liveCensusOf(
        "baby" to listOf("baby-local"),
        "record" to listOf(SLEEP_UUID, "ghost-record"),
    )

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
        const val SLEEP_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val WAKE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        const val PULL_STALL_JOURNAL_KEY = "pull-stall-state"
        const val STALL_KEY = "wake_observation:$WAKE_UUID"
    }
}
