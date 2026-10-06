package com.lezi.babylog.sync.engine

import com.lezi.babylog.sync.backend.SyncEntity

/**
 * Named verdict for one pull-apply gate decision. Boolean `false` cannot
 * express a pull deferral anymore: every unresolved entity names its gate,
 * the missing reference, and the local state observed at the gate, so a
 * durable `reference_unready` receipt is self-explanatory. Compiler-enforced:
 * any new gate in the pull apply tree must return [Deferred] with a reason.
 */
internal sealed interface ApplyVerdict {
    /** Content accepted — including LWW skip and same-version acknowledgement. */
    data object Applied : ApplyVerdict

    /** Reference gate not ready; the entity stays unapplied for this round. */
    data class Deferred(val reason: DeferredReason) : ApplyVerdict
}

/**
 * Why one pull entity could not apply: which [gate], which referenced entity
 * is missing, and the local Room state at the gate. [localSnapshot] is a short
 * debug string (e.g. `baby=absent`) and never carries family content.
 */
internal data class DeferredReason(
    val gate: DeferredGate,
    val missingEntityType: String,
    val missingClientUuid: String,
    val localSnapshot: String,
)

/** Exhaustive pull reference gates. Receipts persist [DeferredGate.name]. */
internal enum class DeferredGate {
    BabyMissing,
    CustomItemMissing,
    FamilyRowMissing,
    SleepTypeMismatch,
    WakeRetarget,
    PlanBabyMissing,
    PlanCustomItemMissing,
    PlanFulfilledRecordMissing,
    PlanFulfilledRecordBabyMismatch,
    FulfillmentPlanMissing,
    FulfillmentRecordMissing,
    MediaRecordMissing,
    MediaCarePlanMissing,
    MediaBabyMissing,
    MediaWakeMissing,
    MediaEditGuard,
    MediaBytesUnstaged,
    BabyLocalDirty,
    /** Family holds >10 live definitions (only the per-device create gate
     *  enforces the cap); overflow rows wait for a visible deletion. */
    CustomItemCapacityExceeded,
}

/** Deferred-verdict factory keeping gate call sites one-liners. */
internal fun applyDeferred(
    gate: DeferredGate,
    missingEntityType: String,
    missingClientUuid: String,
    localSnapshot: String,
): ApplyVerdict.Deferred = ApplyVerdict.Deferred(
    DeferredReason(
        gate = gate,
        missingEntityType = missingEntityType,
        missingClientUuid = missingClientUuid,
        localSnapshot = localSnapshot,
    ),
)

/** Pull entity deferred by a reference gate, carrying its named reason. */
internal data class UnresolvedPull(
    val entity: SyncEntity,
    val reason: DeferredReason,
)
