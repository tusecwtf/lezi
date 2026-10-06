package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.LIVE_CENSUS_MISMATCH_CODE
import com.lezi.babylog.sync.session.LIVE_CENSUS_ENTITY_TYPE
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] for 0.5 ticket 03 (W2 census
 * predicate reuse). A fully quiet pull round — every page carried zero
 * entities, no unresolved reference survived, the checkpoint did not move,
 * no pending publish units, no pending generation resync — cannot have
 * changed the local live set, so the census comparison reuses the previous
 * (generation, census) instead of re-hashing all seven tables. Any other
 * round recomputes exactly once (current semantics). Any cursor-0 rewalk —
 * census-mismatch repair, 409 full resync — ends with an unconditional cache
 * drop plus one fresh rebuild, so a drifted cache degrades to at most ONE
 * extra rewalk, never to a permanent false diagnostic.
 *
 * Observability discipline: recompute-vs-reuse is observed through the
 * externally visible round shape (pull cursors, rewalk presence, durable
 * diagnostics), never through internals: with a local live row the server
 * never saw, a recomputed comparison mismatches (exactly one cursor-0
 * rewalk), while a reused comparison matches (no rewalk).
 */
class ReplicaSyncEngineCensusReuseTest {

    @Test
    fun quietRoundReusesPreviousCensusWithoutRecompute() = runTest {
        val rig = riggedRoundOne()

        // A local live row the server never saw: a fresh comparison would
        // mismatch and trigger exactly one cursor-0 rewalk. Reuse must not
        // even look — zero-application round, cached census matches the
        // server claim, so no rewalk and no diagnostic.
        seedRogueRecord(rig)
        rig.backend.nextPull = emptyHeadPage()

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.pullCursors).containsExactly(7L).inOrder()
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
    }

    @Test
    fun nonEmptyPullPageBreaksTheQuietPredicateAndRecomputes() = runTest {
        val rig = riggedRoundOne()
        seedRogueRecord(rig)
        // An idempotent re-emission still counts as an applied page.
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteSleepRecord(SLEEP_UUID)),
            cursor = 7,
            generation = GENERATION,
            hasMore = false,
            liveCensus = matchedCensus(),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")
    }

    @Test
    fun advancedCursorBreaksTheQuietPredicateAndRecomputes() = runTest {
        val rig = riggedRoundOne()
        seedRogueRecord(rig)
        rig.backend.nextPull = emptyHeadPage(cursor = 9)

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")
    }

    @Test
    fun deferredUnresolvedEntityBreaksTheQuietPredicateAndRecomputes() = runTest {
        val rig = riggedRoundOne()
        seedRogueRecord(rig)
        // The wake's parent never arrives, so the deferred set stays open.
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            cursor = 7,
            generation = GENERATION,
            hasMore = false,
            liveCensus = matchedCensus(),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        // Main pull plus exactly one bounded cursor-0 rewalk — no storm.
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        val censusDiagnostics = rig.conflictDetails.listPullDiagnostics()
            .filter { it.code == LIVE_CENSUS_MISMATCH_CODE }
        assertThat(censusDiagnostics).hasSize(1)
        assertThat(censusDiagnostics.single().localSnapshot).isEqualTo("record=2/1")
    }

    @Test
    fun pendingPublishUnitsSuppressTheComparisonExactlyAsBefore() = runTest {
        val rig = riggedRoundOne()
        // Unpublished local intent lands mid-pull (before the head
        // comparison): the comparison is skipped wholesale (pre-existing
        // behavior) — never a reuse, never a rewalk — and the ordinary push
        // phase settles the row afterwards.
        rig.backend.beforePullReturn = { seedRogueRecord(rig, syncDirty = true) }
        rig.backend.nextPull = emptyHeadPage()

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.pullCursors).containsExactly(7L).inOrder()
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
    }

    @Test
    fun pendingGenerationResyncBreaksTheQuietPredicateAndRecomputes() = runTest {
        val rig = riggedRoundOne()
        seedRogueRecord(rig)
        rig.preferences.markPendingGenerationResync()
        rig.backend.nextPull = emptyHeadPage()

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")
        // The cycle-end convergence gate still clears the flag as before.
        assertThat(rig.preferences.hasPendingGenerationResync()).isFalse()
    }

    @Test
    fun reuseEntryIsInvalidatedWhenThePullGenerationChanges() = runTest {
        val rig = riggedRoundOne()
        seedRogueRecord(rig)
        // A generation switch makes every cached entry stale by key.
        rig.preferences.updatePullCheckpoint(
            cursor = 0,
            generation = "generation-b",
            familyName = null,
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = "generation-b",
            hasMore = false,
            liveCensus = matchedCensus(),
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        // Recomputed against the fresh generation: the rogue row surfaces a
        // mismatch and the bounded rewalk follows (a reused cache would have
        // silently matched).
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(0L, 0L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")
    }

    @Test
    fun rewalkEndsForcingAFreshCensusRebuildForTheNextRound() = runTest {
        val rig = riggedRoundOne()
        // Repairable divergence: the server claims one more live record and
        // the mismatch rewalk's page delivers it. The main round consumes the
        // queued head page; nextPull (the rewalk's own page) delivers the row.
        val repaired = liveCensusOf(
            "baby" to listOf("baby-local"),
            "record" to listOf(SLEEP_UUID, EXTRA_RECORD_UUID),
        )
        rig.backend.pullResults += emptyHeadPage(liveCensus = repaired)
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteReplicaRecord(EXTRA_RECORD_UUID)),
            cursor = 2,
            generation = GENERATION,
            hasMore = false,
            liveCensus = repaired,
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()

        // The NEXT quiet round must not rewalk: only a post-rewalk rebuilt
        // cache matches the repaired census; a stale pre-rewalk entry would
        // mismatch again and burn another cursor-0 pass.
        rig.backend.pullResults.clear()
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 2,
            generation = GENERATION,
            hasMore = false,
            liveCensus = repaired,
        )
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L, 2L).inOrder()
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
    }

    @Test
    fun localWriteDuringThePullCausesAtMostOneRewalkPerCycleWithoutDiagnosticStorm() = runTest {
        val rig = ReplicaEngineRig(joinedReplicaSession().copy(pullCursor = 7))
            .also { it.backend.enableCausal = true }
        seedSleep(rig)
        // The rogue row commits mid-pull (published shape, invisible to the
        // pending-units veto): the cold comparison recomputes, sees it, and
        // false-mismatches exactly once.
        rig.backend.beforePullReturn = { seedRogueRecord(rig) }
        rig.backend.nextPull = emptyHeadPage()

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.backend.pullCount).isEqualTo(2)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")

        // The next identical quiet round keeps the durable diagnostic and
        // does not burn another cursor-0 history walk for the same snapshot.
        rig.backend.nextPull = emptyHeadPage()
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.backend.pullCount).isEqualTo(3)
        assertThat(rig.backend.pullCursors).containsExactly(7L, 0L, 7L).inOrder()
        assertSingleCensusMismatchDiagnostic(rig, "record=2/1")
    }

    // --- fixtures ----------------------------------------------------------------

    private companion object {
        const val GENERATION = "generation-a"
        const val SLEEP_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val WAKE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        const val EXTRA_RECORD_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee03"
        const val ROGUE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee04"
    }

    /** Round one: cold engine, matched census — builds the reuse entry. */
    private suspend fun riggedRoundOne() = ReplicaEngineRig(
        joinedReplicaSession().copy(pullCursor = 7),
    ).also { rig ->
        rig.backend.enableCausal = true
        seedSleep(rig)
        rig.backend.nextPull = emptyHeadPage()
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.conflictDetails.listPullDiagnostics()).isEmpty()
        // Per-round accounting below: reset the counters round one consumed.
        rig.backend.pullCount = 0
        rig.backend.pullCursors.clear()
    }

    private fun emptyHeadPage(
        cursor: Long = 7,
        hasMore: Boolean = false,
        liveCensus: com.lezi.babylog.sync.backend.LiveCensus = matchedCensus(),
    ) = PullResult(
        entities = emptyList(),
        cursor = cursor,
        generation = GENERATION,
        hasMore = hasMore,
        liveCensus = liveCensus,
    )

    private fun seedSleep(rig: ReplicaEngineRig) {
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
                clientUuid = SLEEP_UUID,
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

    private fun seedRogueRecord(
        rig: ReplicaEngineRig,
        syncDirty: Boolean = false,
    ) {
        rig.records.seed(
            RecordEntity(
                clientUuid = ROGUE_UUID,
                babyId = 1,
                type = "sleep",
                timestamp = 2_000,
                endTimestamp = null,
                payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 260,
                syncDirty = syncDirty,
                familyPublishedUpdatedAt = 260,
                baseVersion = "v-rogue",
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

    /** Census matching the seeded local set. */
    private fun matchedCensus() = liveCensusOf(
        "baby" to listOf("baby-local"),
        "record" to listOf(SLEEP_UUID),
    )

    private fun liveCensusOf(vararg types: Pair<String, List<String>>): com.lezi.babylog.sync.backend.LiveCensus =
        com.lezi.babylog.sync.backend.LiveCensus(
            entries = types.associate { (entityType, keys) ->
                entityType to com.lezi.babylog.sync.backend.LiveCensusEntry(
                    count = keys.size.toLong(),
                    keyDigest = com.lezi.babylog.sync.backend.liveCensusKeyDigest(keys),
                )
            },
        )

    private suspend fun assertSingleCensusMismatchDiagnostic(
        rig: ReplicaEngineRig,
        localSnapshot: String,
    ) {
        val diagnostics = rig.conflictDetails.listPullDiagnostics()
        assertThat(diagnostics).hasSize(1)
        val diagnostic = diagnostics.single()
        assertThat(diagnostic.entityType).isEqualTo(LIVE_CENSUS_ENTITY_TYPE)
        assertThat(diagnostic.code).isEqualTo(LIVE_CENSUS_MISMATCH_CODE)
        assertThat(diagnostic.localSnapshot).isEqualTo(localSnapshot)
    }
}
