package com.lezi.babylog.core.database.causal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Public pure-state seams for local causal epochs (ticket 04).
 * Expected literals come from the freeze/ack/branch contract — not recomputed from SUT helpers.
 */
class CausalMutationStateTest {
    @Test
    fun freezeDirtyEpochReusesMutationIdWhenContentEpochUnchanged() {
        val existing = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = "mut-existing",
            contentEpoch = 100L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )
        val next = requireNotNull(freezeDirtyEpoch(
            current = existing,
            contentEpoch = 100L,
            newMutationId = "mut-should-not-use",
        ))
        assertThat(next.mutationId).isEqualTo("mut-existing")
        assertThat(next.baseVersion).isEqualTo("v-base-1")
        assertThat(next.syncDirty).isTrue()
        assertThat(next.contentEpoch).isEqualTo(100L)
    }

    @Test
    fun freezeDirtyEpochMintsNewMutationIdOnUserEditAndKeepsAcknowledgedBase() {
        val existing = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = null,
            contentEpoch = 200L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )
        val next = requireNotNull(freezeDirtyEpoch(
            current = existing,
            contentEpoch = 200L,
            newMutationId = "mut-new",
        ))
        assertThat(next.mutationId).isEqualTo("mut-new")
        assertThat(next.baseVersion).isEqualTo("v-base-1")
        assertThat(next.contentEpoch).isEqualTo(200L)
        assertThat(next.syncDirty).isTrue()
        assertThat(next.openConflictId).isNull()
        assertThat(next.localBranchVersionId).isNull()
    }

    @Test
    fun freezeDirtyEpochRejectsStaleCapturedEpoch() {
        val concurrentlyEdited = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = null,
            contentEpoch = 200L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )

        val next = freezeDirtyEpoch(
            current = concurrentlyEdited,
            contentEpoch = 100L,
            newMutationId = "mut-stale",
        )

        assertThat(next).isNull()
    }

    @Test
    fun exactCasAckAdvancesBaseClearsPendingForMatchingMutation() {
        val pending = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = "mut-1",
            contentEpoch = 50L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )
        val next = acknowledgeAcceptedOrMerged(
            current = pending,
            expectedMutationId = "mut-1",
            expectedContentEpoch = 50L,
            newBaseVersion = "v-stable-2",
        )
        assertThat(next).isNotNull()
        assertThat(next!!.baseVersion).isEqualTo("v-stable-2")
        assertThat(next.mutationId).isNull()
        assertThat(next.syncDirty).isFalse()
        assertThat(next.openConflictId).isNull()
        assertThat(next.localBranchVersionId).isNull()
    }

    @Test
    fun exactCasAckRejectsMismatchedMutationOrEpoch() {
        val pending = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = "mut-1",
            contentEpoch = 50L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )
        assertThat(
            acknowledgeAcceptedOrMerged(
                current = pending,
                expectedMutationId = "mut-other",
                expectedContentEpoch = 50L,
                newBaseVersion = "v-stable-2",
            ),
        ).isNull()
        assertThat(
            acknowledgeAcceptedOrMerged(
                current = pending,
                expectedMutationId = "mut-1",
                expectedContentEpoch = 99L,
                newBaseVersion = "v-stable-2",
            ),
        ).isNull()
    }

    @Test
    fun branchedReceiptConvertsPendingToUnresolvedConflictWithoutClearingAsSynced() {
        val pending = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = "mut-1",
            contentEpoch = 50L,
            syncDirty = true,
            openConflictId = null,
            localBranchVersionId = null,
        )
        val next = acknowledgeBranched(
            current = pending,
            expectedMutationId = "mut-1",
            expectedContentEpoch = 50L,
            conflictId = "conflict-9",
            branchVersionId = "branch-v3",
            stableBaseVersion = "v-base-1",
        )
        assertThat(next).isNotNull()
        assertThat(next!!.syncDirty).isFalse()
        assertThat(next.openConflictId).isEqualTo("conflict-9")
        assertThat(next.localBranchVersionId).isEqualTo("branch-v3")
        assertThat(next.mutationId).isEqualTo("mut-1")
        assertThat(next.baseVersion).isEqualTo("v-base-1")
        // Not fully consistent: conflict still open; base not advanced to branch.
        assertThat(next.baseVersion).isNotEqualTo(next.localBranchVersionId)
    }

    @Test
    fun freezeAfterBranchedSameEpochIsNoOpAndDoesNotRequeuePush() {
        val branched = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = "mut-1",
            contentEpoch = 50L,
            syncDirty = false,
            openConflictId = "conflict-9",
            localBranchVersionId = "branch-v3",
        )
        val next = requireNotNull(freezeDirtyEpoch(
            current = branched,
            contentEpoch = 50L,
            newMutationId = "mut-should-not-mint",
        ))
        assertThat(next).isEqualTo(branched)
        assertThat(next.syncDirty).isFalse()
        assertThat(next.mutationId).isEqualTo("mut-1")
        assertThat(next.openConflictId).isEqualTo("conflict-9")
        assertThat(next.localBranchVersionId).isEqualTo("branch-v3")
    }

    @Test
    fun freezeAfterBranchedOnNewEditMintsMutationButPreservesConflictLinkage() {
        val branched = CausalRootMutationState(
            baseVersion = "v-base-1",
            mutationId = null,
            contentEpoch = 80L,
            syncDirty = true,
            openConflictId = "conflict-9",
            localBranchVersionId = "branch-v3",
        )
        val next = requireNotNull(freezeDirtyEpoch(
            current = branched,
            contentEpoch = 80L,
            newMutationId = "mut-reedit",
        ))
        assertThat(next.mutationId).isEqualTo("mut-reedit")
        assertThat(next.contentEpoch).isEqualTo(80L)
        assertThat(next.syncDirty).isTrue()
        assertThat(next.baseVersion).isEqualTo("v-base-1")
        // Conflict is not silently resolved by freeze alone.
        assertThat(next.openConflictId).isEqualTo("conflict-9")
        assertThat(next.localBranchVersionId).isEqualTo("branch-v3")
    }
}
