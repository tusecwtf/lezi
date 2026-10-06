package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.backend.SyncHttpException

// 0.5.4 ticket 02 (S2): a baby merge must abandon pre-merge frozen envelopes
// (they embed the merged-away baby's clientUuid) so they never publish, while
// the dirty re-bound rows re-freeze under the target baby (ADR-0022).
class RealSyncPortBabyMergeEnvelopeAbandonTest {
    @Test
    fun abandonedSourceEnvelopesEscapePublicationAndReboundRowsRefreezeUnderTargetBaby() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val sourceBabyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "11111111-1111-3111-8111-111111111111",
                updatedAt = 50,
                syncDirty = false,
            ),
        )
        val targetBabyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "22222222-2222-3222-8222-222222222222",
                updatedAt = 60,
                syncDirty = false,
            ),
        )
        val recordUuid = "44444444-4444-3444-8444-444444444444"
        val planUuid = "33333333-3333-3333-8333-333333333333"
        rig.records.seed(
            localRecord(sourceBabyId).copy(
                clientUuid = recordUuid,
                updatedAt = 200,
                syncDirty = true,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(sourceBabyId).copy(
                clientUuid = planUuid,
                updatedAt = 210,
                syncDirty = true,
            ),
        )

        // Loss-of-response freeze: both envelopes freeze with the SOURCE baby identity
        // and the commit never lands.
        rig.backend.onCausalCommit = { throw SyncHttpException(503, "temporary") }
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isFailure).isTrue()
        val frozenRecord = rig.conflictDetails.getFrozenMutation("record", recordUuid)
        assertThat(frozenRecord).isNotNull()
        assertThat(frozenRecord!!.payloadJson).contains("11111111-1111-3111-8111-111111111111")
        val frozenPlan = rig.conflictDetails.getFrozenMutation("care_plan", planUuid)
        assertThat(frozenPlan).isNotNull()
        assertThat(frozenPlan!!.payloadJson).contains("11111111-1111-3111-8111-111111111111")

        // Merge seam: abandon the pre-merge envelopes before the rows are re-bound.
        assertThat(
            rig.port.abandonPendingLocalMutations(
                listOf(
                    PendingLocalMutationRef("record", recordUuid),
                    PendingLocalMutationRef("care_plan", planUuid),
                ),
            ).isSuccess,
        ).isTrue()
        assertThat(rig.conflictDetails.getFrozenMutation("record", recordUuid)).isNull()
        assertThat(rig.conflictDetails.getFrozenMutation("care_plan", planUuid)).isNull()
        val recordReceipt = rig.conflictDetails.getTerminalReceipt("record", recordUuid)
        assertThat(recordReceipt!!.abandoned).isTrue()
        val planReceipt = rig.conflictDetails.getTerminalReceipt("care_plan", planUuid)
        assertThat(planReceipt!!.abandoned).isTrue()
        val abandonedRecord = rig.records.getByClientUuid(recordUuid)!!
        assertThat(abandonedRecord.syncDirty).isFalse()
        assertThat(abandonedRecord.mutationId).isNull()
        val abandonedPlan = rig.carePlans.getByClientUuid(planUuid)!!
        assertThat(abandonedPlan.syncDirty).isFalse()
        assertThat(abandonedPlan.mutationId).isNull()

        // Simulate the merge re-bind: rows move to the target baby and turn dirty
        // with a strictly advanced content epoch.
        rig.records.update(
            abandonedRecord.copy(
                babyId = targetBabyId,
                updatedAt = abandonedRecord.updatedAt + 1,
                syncDirty = true,
            ),
        )
        rig.carePlans.update(
            abandonedPlan.copy(
                babyId = targetBabyId,
                updatedAt = abandonedPlan.updatedAt + 1,
                syncDirty = true,
            ),
        )

        rig.backend.onCausalCommit = null
        // Drop the failed loss-of-response attempt from the commit record so the
        // remaining entries are exactly the envelopes the family server accepted.
        rig.backend.causalCommittedUnits.clear()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        // The old envelope never committed: exactly one commit per uuid, and it
        // carries the TARGET baby identity.
        val targetUuid = "22222222-2222-3222-8222-222222222222"
        val recordCommits = rig.backend.causalCommittedUnits.flatten()
            .filter { it.clientUuid == recordUuid }
        assertThat(recordCommits).hasSize(1)
        assertThat(recordCommits.single().rootJson).contains(targetUuid)
        assertThat(recordCommits.single().rootJson)
            .doesNotContain("11111111-1111-3111-8111-111111111111")
        val planCommits = rig.backend.causalCommittedUnits.flatten()
            .filter { it.clientUuid == planUuid }
        assertThat(planCommits).hasSize(1)
        assertThat(planCommits.single().rootJson).contains(targetUuid)
        assertThat(planCommits.single().rootJson)
            .doesNotContain("11111111-1111-3111-8111-111111111111")
        assertThat(rig.records.getByClientUuid(recordUuid)!!.syncDirty).isFalse()
        assertThat(rig.carePlans.getByClientUuid(planUuid)!!.syncDirty).isFalse()
        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
    }

    @Test
    fun unfrozenDirtySourceRowAbandonStillRefreezesUnderTargetIdentity() = runTest {
        // A dirty row whose envelope was not frozen yet also survives the abandon
        // step: the abandoned receipt (old epoch) is superseded by the re-bind's
        // advanced epoch, so the row re-freezes instead of being suppressed.
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val sourceBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-source", updatedAt = 50, syncDirty = false),
        )
        val targetBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-target", updatedAt = 60, syncDirty = false),
        )
        val recordUuid = "merge-unfrozen-record"
        rig.records.seed(
            localRecord(sourceBabyId).copy(
                clientUuid = recordUuid,
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        assertThat(
            rig.port.abandonPendingLocalMutations(
                listOf(PendingLocalMutationRef("record", recordUuid)),
            ).isSuccess,
        ).isTrue()
        val row = rig.records.getByClientUuid(recordUuid)!!
        rig.records.update(
            row.copy(babyId = targetBabyId, updatedAt = row.updatedAt + 1, syncDirty = true),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val commits = rig.backend.causalCommittedUnits.flatten()
            .filter { it.clientUuid == recordUuid }
        assertThat(commits).hasSize(1)
        assertThat(commits.single().rootJson).contains("baby-target")
        assertThat(commits.single().rootJson).doesNotContain("baby-source")
        assertThat(rig.conflictDetails.getTerminalReceipt("record", recordUuid)).isNull()
        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
    }
}
