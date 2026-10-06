package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] for ticket 04 — the pull-stall
 * ledger lives and dies with the cursor generation. A cursor reset to 0
 * (reinstall / rejoin / device re-add / generation full resync) must clear the
 * ledger so previously skipped keys retry under a fresh
 * MAX_PULL_STALL_ATTEMPTS budget instead of staying permanently silent.
 */
class ReplicaSyncEngineStallLedgerSelfHealTest {

    @Test
    fun cursorZeroStartClearsSeededStallLedgerSoCeilingRestarts() = runTest {
        // Rejoin / device re-add on an existing install: the session cursor
        // was reset to 0 while the Room journal still carries the previous
        // generation's counts.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.conflictDetails.putTransportJournal(
            PULL_STALL_JOURNAL_KEY,
            """{"$STALL_KEY":3}""",
            contentEpoch = 0L,
        )
        rig.backend.nextPull = unreadyWakePage(session)

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        // Fresh generation: the wake counts one attempt (not four), so the
        // checkpoint still holds instead of skipping past the ceiling.
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0L)
        assertThat(
            rig.conflictDetails.getTransportJournal(PULL_STALL_JOURNAL_KEY)?.payloadJson,
        ).isEqualTo("""{"$STALL_KEY":1}""")
    }

    @Test
    fun rejoinAfterSkipRedeliversSkippedKeyWithRestartedAttempts() = runTest {
        // Previous generation: exhaust the ceiling at cursor 2 — the wake is
        // skipped past the ceiling and the ledger keeps its 4-count entry.
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        val unreadyPage = unreadyWakePage(session)
        repeat(4) {
            rig.backend.nextPull = unreadyPage
            rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7L)
        assertThat(
            rig.conflictDetails.getTransportJournal(PULL_STALL_JOURNAL_KEY)?.payloadJson,
        ).isEqualTo("""{"$STALL_KEY":4}""")

        // Rejoin: the session cursor is reset to 0 and the full rewalk
        // re-delivers the same still-unready key. Its count restarts instead
        // of accumulating, so the key is retried (and the checkpoint holds)
        // rather than being instantly skipped for good.
        rig.preferences.updateCursor(0L, generation = session.pullGeneration)
        rig.backend.nextPull = unreadyPage

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(
            rig.conflictDetails.getTransportJournal(PULL_STALL_JOURNAL_KEY)?.payloadJson,
        ).isEqualTo("""{"$STALL_KEY":1}""")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0L)
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

    private fun unreadyWakePage(session: SyncSession) = PullResult(
        entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
        cursor = 7,
        generation = session.pullGeneration,
        hasMore = false,
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

    private companion object {
        const val SLEEP_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val WAKE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        const val PULL_STALL_JOURNAL_KEY = "pull-stall-state"
        const val STALL_KEY = "wake_observation:$WAKE_UUID"
    }
}
