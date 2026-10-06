package com.lezi.babylog.core.database.causal

/**
 * Local causal mutation epoch for one mutable atomic root.
 *
 * [contentEpoch] is the local content revision (`updatedAt`) frozen with [mutationId].
 * [baseVersion] is the last acknowledged server stable `version_id` (opaque); never equal to
 * local content revision semantics.
 *
 * Pending vs unresolved conflict (must stay distinct):
 * - Ordinary dirty: [syncDirty]=true, [openConflictId]=null
 * - After `branched`: [syncDirty]=false, [openConflictId] set — durable on server, no blind resend
 * - After accepted/merged: clean, [mutationId]=null, [baseVersion] advanced
 * - Re-edit while conflict is open: new [mutationId] + [syncDirty]=true, but conflict linkage
 *   remains until explicit resolution / pull-reconcile clears it
 */
data class CausalRootMutationState(
    val baseVersion: String?,
    val mutationId: String?,
    val contentEpoch: Long,
    val syncDirty: Boolean,
    val openConflictId: String?,
    val localBranchVersionId: String?,
)

/** Columns every causal root entity must apply after freeze/ack/branch. */
data class AppliedCausalMutationColumns(
    val baseVersion: String?,
    val mutationId: String?,
    val syncDirty: Boolean,
    val openConflictId: String?,
    val localBranchVersionId: String?,
)

/** Whether a durable commit-first terminal settled the frozen fact or a later local edit. */
enum class CommitFirstSettlementEpoch {
    CurrentEpoch,
    SupersededEpoch,
}

data class CommitFirstSettlement(
    val state: CausalRootMutationState,
    val epoch: CommitFirstSettlementEpoch,
)

fun CausalRootMutationState.toAppliedColumns(): AppliedCausalMutationColumns =
    AppliedCausalMutationColumns(
        baseVersion = baseVersion,
        mutationId = mutationId,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        localBranchVersionId = localBranchVersionId,
    )

/**
 * Freeze (or re-freeze) a dirty content epoch for reconcile/commit.
 *
 * Same [contentEpoch] reuses the existing [CausalRootMutationState.mutationId] so retries are
 * idempotent. After a durable `branched` receipt, a same-epoch freeze is a pure no-op (must not
 * re-queue push or mint a new mutation). A true user re-edit (different epoch) mints
 * [newMutationId] while keeping the last acknowledged [CausalRootMutationState.baseVersion]
 * and preserving any open conflict linkage until explicit resolution.
 */
fun freezeDirtyEpoch(
    current: CausalRootMutationState,
    contentEpoch: Long,
    newMutationId: String,
): CausalRootMutationState? {
    // contentEpoch was captured before this transaction. A later local edit
    // owns the row now; never move its epoch backwards or freeze mixed content.
    if (current.contentEpoch != contentEpoch) return null
    // Branched / open conflict with unchanged content: durable, no infinite resend.
    if (current.openConflictId != null && !current.syncDirty && current.contentEpoch == contentEpoch) {
        return current
    }
    val reuseOrdinary = current.openConflictId == null &&
        current.syncDirty &&
        current.mutationId != null &&
        current.contentEpoch == contentEpoch
    if (reuseOrdinary) {
        return current
    }
    // True user re-edit: new mutation. Preserve unresolved conflict linkage when present so
    // freeze cannot silently reclassify conflict as ordinary dirty without resolution.
    val preserveConflict = current.openConflictId != null
    return CausalRootMutationState(
        baseVersion = current.baseVersion,
        mutationId = newMutationId,
        contentEpoch = contentEpoch,
        syncDirty = true,
        openConflictId = if (preserveConflict) current.openConflictId else null,
        localBranchVersionId = if (preserveConflict) current.localBranchVersionId else null,
    )
}

/** Assign the one immutable-envelope identity at the captured fact epoch. */
fun freezeCommitFirstEpoch(
    current: CausalRootMutationState,
    contentEpoch: Long,
    newMutationId: String,
): CausalRootMutationState? {
    if (!current.syncDirty || current.contentEpoch != contentEpoch) return null
    if (newMutationId.isBlank()) return null
    return current.copy(mutationId = newMutationId)
}

/**
 * Settle accepted/merged without letting an old response overwrite a later local edit.
 * A superseding fact keeps its product content and pending state while advancing its
 * causal base to the durable server terminal.
 */
fun settleCommitFirstAcceptedOrMerged(
    current: CausalRootMutationState,
    expectedMutationId: String,
    expectedContentEpoch: Long,
    newBaseVersion: String,
): CommitFirstSettlement? {
    if (current.contentEpoch == expectedContentEpoch) {
        val settled = acknowledgeAcceptedOrMerged(
            current = current,
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            newBaseVersion = newBaseVersion,
        ) ?: return null
        return CommitFirstSettlement(settled, CommitFirstSettlementEpoch.CurrentEpoch)
    }
    if (!current.syncDirty || current.contentEpoch < expectedContentEpoch) return null
    if (current.mutationId != null && current.mutationId != expectedMutationId) return null
    return CommitFirstSettlement(
        state = current.copy(baseVersion = newBaseVersion, mutationId = null),
        epoch = CommitFirstSettlementEpoch.SupersededEpoch,
    )
}

/** Same epoch policy as [settleCommitFirstAcceptedOrMerged] for a durable branch. */
fun settleCommitFirstBranched(
    current: CausalRootMutationState,
    expectedMutationId: String,
    expectedContentEpoch: Long,
    conflictId: String,
    branchVersionId: String,
    stableBaseVersion: String,
): CommitFirstSettlement? {
    if (current.contentEpoch == expectedContentEpoch) {
        val settled = acknowledgeBranched(
            current = current,
            expectedMutationId = expectedMutationId,
            expectedContentEpoch = expectedContentEpoch,
            conflictId = conflictId,
            branchVersionId = branchVersionId,
            stableBaseVersion = stableBaseVersion,
        ) ?: return null
        return CommitFirstSettlement(settled, CommitFirstSettlementEpoch.CurrentEpoch)
    }
    if (!current.syncDirty || current.contentEpoch < expectedContentEpoch) return null
    if (current.mutationId != null && current.mutationId != expectedMutationId) return null
    return CommitFirstSettlement(
        state = current.copy(
            baseVersion = stableBaseVersion,
            mutationId = null,
            syncDirty = true,
            openConflictId = conflictId,
            localBranchVersionId = branchVersionId,
        ),
        epoch = CommitFirstSettlementEpoch.SupersededEpoch,
    )
}

/**
 * Exact CAS ack for accepted/merged: only the frozen mutation+epoch may advance base and clear
 * pending. Mismatch returns null (caller leaves row unchanged).
 */
fun acknowledgeAcceptedOrMerged(
    current: CausalRootMutationState,
    expectedMutationId: String,
    expectedContentEpoch: Long,
    newBaseVersion: String,
): CausalRootMutationState? {
    if (current.mutationId != expectedMutationId) return null
    if (current.contentEpoch != expectedContentEpoch) return null
    if (!current.syncDirty && current.openConflictId == null) {
        // Already settled for this mutation: idempotent only when base already matches.
        if (current.baseVersion == newBaseVersion) {
            return current.copy(mutationId = null)
        }
        return null
    }
    return CausalRootMutationState(
        baseVersion = newBaseVersion,
        mutationId = null,
        contentEpoch = current.contentEpoch,
        syncDirty = false,
        openConflictId = null,
        localBranchVersionId = null,
    )
}

/**
 * `branched` is a successful lossless publication: convert pending → unresolved conflict.
 * Does not advance [baseVersion] to the branch; does not leave [syncDirty] true (no infinite resend).
 */
fun acknowledgeBranched(
    current: CausalRootMutationState,
    expectedMutationId: String,
    expectedContentEpoch: Long,
    conflictId: String,
    branchVersionId: String,
    stableBaseVersion: String,
): CausalRootMutationState? {
    if (current.mutationId != expectedMutationId) return null
    if (current.contentEpoch != expectedContentEpoch) return null
    if (conflictId.isBlank() || branchVersionId.isBlank()) return null
    return CausalRootMutationState(
        baseVersion = stableBaseVersion,
        mutationId = expectedMutationId,
        contentEpoch = current.contentEpoch,
        syncDirty = false,
        openConflictId = conflictId,
        localBranchVersionId = branchVersionId,
    )
}
