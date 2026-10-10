package com.lezi.babylog.sync.session

import com.lezi.babylog.sync.FamilyMember

/**
 * Token-free local audience from one identity-owner revision. The producer only includes a
 * directory committed for [identityEpoch]; consumers must never stamp a separate roster with
 * the current identity. Null epoch is the fail-closed legacy adapter (no trusted roster).
 */
data class FamilyReadSnapshot(
    val session: SyncSessionPresentation,
    val identityEpoch: Long?,
    val members: List<FamilyMember> = emptyList(),
    val directoryIdentityEpoch: Long? = null,
    val directoryGeneration: String = "",
) {
    val hasCurrentDirectory: Boolean
        get() = identityEpoch != null && directoryIdentityEpoch == identityEpoch
}
