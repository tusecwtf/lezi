package com.lezi.babylog.core.database

/**
 * Pure CAS decision for acknowledging a synthetic elevated root publication.
 *
 * Used by [RecordDao] / [CarePlanDao] (receipt-bearing roots) and [BabyDao]
 * (updatedAt-only watermark) so concurrent branches cannot twin-drift.
 *
 * Rules:
 * - Advance + clear dirty only when [currentUpdatedAt] == [expectedLocalUpdatedAt]
 *   (true content-epoch CAS).
 * - When [currentUpdatedAt] == [publishedUpdatedAt] but expected was left behind:
 *   idempotent success only if already clean; if [currentSyncDirty], treat as a
 *   concurrent edit that landed on the elevated clock — merge receipt only,
 *   keep dirty, do not confirm.
 * - Newer concurrent content: never overwrite body; merge receipt when
 *   [publishedUpdatedAt] ≤ current (monotonic); keep dirty.
 */
internal data class SyntheticRootAckDecision(
    val confirmed: Boolean,
    val write: SyntheticRootAckWrite?,
)

/** Fields to persist when the ack path mutates the root row. */
internal data class SyntheticRootAckWrite(
    val updatedAt: Long,
    val familyPublishedUpdatedAt: Long?,
    val syncDirty: Boolean,
)

/**
 * Receipt-bearing roots (Record / CarePlan): CAS content epoch, merge
 * [familyPublishedUpdatedAt] monotonically, never clear dirty on concurrent
 * content that happens to share the published LWW clock.
 */
internal fun decideSyntheticRootPublicationWithReceipt(
    currentUpdatedAt: Long,
    currentFamilyPublishedUpdatedAt: Long?,
    currentSyncDirty: Boolean,
    expectedLocalUpdatedAt: Long,
    publishedUpdatedAt: Long,
): SyntheticRootAckDecision {
    if (publishedUpdatedAt <= 0L || expectedLocalUpdatedAt <= 0L) {
        return SyntheticRootAckDecision(confirmed = false, write = null)
    }
    if (publishedUpdatedAt < expectedLocalUpdatedAt) {
        return SyntheticRootAckDecision(confirmed = false, write = null)
    }
    return when {
        // True content-epoch CAS: advance revision, set receipt, clear dirty.
        currentUpdatedAt == expectedLocalUpdatedAt -> {
            val validExisting = currentFamilyPublishedUpdatedAt
                ?.takeIf { it > 0L && it <= publishedUpdatedAt }
            val mergedReceipt = maxOf(validExisting ?: 0L, publishedUpdatedAt)
            val needsWrite =
                currentUpdatedAt != publishedUpdatedAt ||
                    currentFamilyPublishedUpdatedAt != mergedReceipt ||
                    currentSyncDirty
            SyntheticRootAckDecision(
                confirmed = true,
                write = if (needsWrite) {
                    SyntheticRootAckWrite(
                        updatedAt = publishedUpdatedAt,
                        familyPublishedUpdatedAt = mergedReceipt,
                        syncDirty = false,
                    )
                } else {
                    null
                },
            )
        }
        // At published clock with expected left behind (or re-ack after partial).
        currentUpdatedAt == publishedUpdatedAt -> {
            if (currentSyncDirty) {
                // Concurrent edit landed exactly on the elevated revision: keep
                // body + dirty so capture rebuilds outbox; merge receipt only.
                concurrentReceiptOnly(
                    currentUpdatedAt = currentUpdatedAt,
                    currentFamilyPublishedUpdatedAt = currentFamilyPublishedUpdatedAt,
                    currentSyncDirty = currentSyncDirty,
                    publishedUpdatedAt = publishedUpdatedAt,
                )
            } else {
                // Idempotent retry of a prior successful confirm.
                val validExisting = currentFamilyPublishedUpdatedAt
                    ?.takeIf { it > 0L && it <= publishedUpdatedAt }
                val mergedReceipt = maxOf(validExisting ?: 0L, publishedUpdatedAt)
                SyntheticRootAckDecision(
                    confirmed = true,
                    write = if (currentFamilyPublishedUpdatedAt != mergedReceipt) {
                        SyntheticRootAckWrite(
                            updatedAt = currentUpdatedAt,
                            familyPublishedUpdatedAt = mergedReceipt,
                            syncDirty = false,
                        )
                    } else {
                        null
                    },
                )
            }
        }
        currentUpdatedAt > expectedLocalUpdatedAt -> {
            if (publishedUpdatedAt > currentUpdatedAt) {
                SyntheticRootAckDecision(confirmed = false, write = null)
            } else {
                concurrentReceiptOnly(
                    currentUpdatedAt = currentUpdatedAt,
                    currentFamilyPublishedUpdatedAt = currentFamilyPublishedUpdatedAt,
                    currentSyncDirty = currentSyncDirty,
                    publishedUpdatedAt = publishedUpdatedAt,
                )
            }
        }
        else -> SyntheticRootAckDecision(confirmed = false, write = null)
    }
}

/**
 * Baby root: no separate receipt column; elevated [updatedAt] is the watermark.
 * Never clear dirty unless content epoch still matches [expectedLocalUpdatedAt].
 */
internal fun decideSyntheticRootPublicationBaby(
    currentUpdatedAt: Long,
    currentSyncDirty: Boolean,
    expectedLocalUpdatedAt: Long,
    publishedUpdatedAt: Long,
): SyntheticRootAckDecision {
    if (publishedUpdatedAt <= 0L || expectedLocalUpdatedAt <= 0L) {
        return SyntheticRootAckDecision(confirmed = false, write = null)
    }
    if (publishedUpdatedAt < expectedLocalUpdatedAt) {
        return SyntheticRootAckDecision(confirmed = false, write = null)
    }
    return when {
        currentUpdatedAt == expectedLocalUpdatedAt -> {
            val needsWrite =
                currentUpdatedAt != publishedUpdatedAt || currentSyncDirty
            SyntheticRootAckDecision(
                confirmed = true,
                write = if (needsWrite) {
                    SyntheticRootAckWrite(
                        updatedAt = publishedUpdatedAt,
                        familyPublishedUpdatedAt = null,
                        syncDirty = false,
                    )
                } else {
                    null
                },
            )
        }
        // Idempotent only when already clean; concurrent dirty at published stays dirty.
        currentUpdatedAt == publishedUpdatedAt -> {
            SyntheticRootAckDecision(
                confirmed = !currentSyncDirty,
                write = null,
            )
        }
        else -> SyntheticRootAckDecision(confirmed = false, write = null)
    }
}

private fun concurrentReceiptOnly(
    currentUpdatedAt: Long,
    currentFamilyPublishedUpdatedAt: Long?,
    currentSyncDirty: Boolean,
    publishedUpdatedAt: Long,
): SyntheticRootAckDecision {
    val validExisting = currentFamilyPublishedUpdatedAt
        ?.takeIf { it > 0L && it <= currentUpdatedAt }
    val mergedReceipt = maxOf(validExisting ?: 0L, publishedUpdatedAt)
    return SyntheticRootAckDecision(
        confirmed = false,
        write = if (validExisting != mergedReceipt) {
            SyntheticRootAckWrite(
                updatedAt = currentUpdatedAt,
                familyPublishedUpdatedAt = mergedReceipt,
                syncDirty = currentSyncDirty,
            )
        } else {
            null
        },
    )
}
