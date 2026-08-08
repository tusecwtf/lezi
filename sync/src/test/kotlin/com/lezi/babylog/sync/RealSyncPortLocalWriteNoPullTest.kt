package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [SyncPort]/[RealSyncPort] LocalWrite no-pull under causal wire —
 * operation order, cursor independence, background/lease gates, and later full pull.
 * Does not assert Channel/Job/mutex private coordinator structure.
 */
class RealSyncPortLocalWriteNoPullTest {

    @Test
    fun localWriteCausalOrderIsReconcileThenCommitWithNoPullAndUnchangedCursor() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 12)
        val rig = SyncRig(session = session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-facade-fast",
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-peer-should-wait",
                    updatedAt = 900,
                    versionId = "v-peer",
                ),
            ),
            cursor = 99,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("pull:") }).isEmpty()
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly("causal_reconcile:1", "causal_commit:1")
            .inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(12)
        assertThat(rig.records.getByClientUuid("record-peer-should-wait")).isNull()
        val settled = requireNotNull(rig.records.getByClientUuid("record-facade-fast"))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun localWriteNoPullThenForegroundPullReceivesUnrelatedPeer() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 0)
        val rig = SyncRig(session = session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-mine-facade",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 50,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)

        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-peer-facade",
                    versionId = "v-peer-2",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val full = rig.port.sync(SyncTrigger.Foreground)
        assertThat(full.exceptionOrNull()).isNull()
        assertThat(full.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(3)
        val peer = requireNotNull(rig.records.getByClientUuid("record-peer-facade"))
        assertThat(peer.syncDirty).isFalse()
        assertThat(peer.baseVersion).isEqualTo("v-peer-2")
    }

    @Test
    fun backgroundLocalWriteDoesNotCallCausalSettleAndKeepsDirty() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 6)
        val rig = SyncRig(session = session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-background",
                schemaVersion = 2,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.foreground.setForeground(false)

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
        assertThat(requireNotNull(rig.records.getByClientUuid("record-background")).syncDirty)
            .isTrue()
    }

    @Test
    fun unhealthyLeaseLocalWriteDoesNotCallCausalSettleAndKeepsDirty() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 9)
        val rig = SyncRig(
            session = session,
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-lease",
                schemaVersion = 2,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        // Force availability failure before any sync work.
        rig.backend.anonymousHealthFailure = java.io.IOException("lease expired / unreachable")

        val result = rig.port.syncWhenAvailable(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
        assertThat(requireNotNull(rig.records.getByClientUuid("record-lease")).syncDirty).isTrue()
        val availability = rig.port.availability().first()
        // Probe may leave Unavailable or the failure may be the currently-unavailable wrapper.
        assertThat(
            availability is FamilyServerAvailability.Unavailable ||
                availability is FamilyServerAvailability.Disabled ||
                availability is FamilyServerAvailability.Checking,
        ).isTrue()
    }

    @Test
    fun localWriteWithoutCausalCapabilityStillPullsOnFacade() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 2)
        val rig = SyncRig(session = session)
        // enableCausal defaults false → no-pull must not apply.
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = true))
        rig.records.seed(localRecord(babyId).copy(syncDirty = true))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.syncOrder.first()).isEqualTo("pull:2")
        assertThat(rig.backend.causalReconciledUnits).isEmpty()
    }
}
