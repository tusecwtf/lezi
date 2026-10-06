package com.lezi.babylog.feature.family

import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession

/**
 * Identity reloads when the joined session identity changes, or when a local
 * display-name write bumps domain LocalFamilyIdentityInvalidations. Token, cursor,
 * and last-success churn do not re-read the two identity DAOs.
 */
internal data class LocalFamilyIdentityReloadKey(
    val familyId: String,
    val deviceId: String,
    val role: FamilyRole,
    val membershipId: String,
    val reauthRequired: Boolean,
)

internal fun localFamilyIdentityReloadKey(session: SyncSession): LocalFamilyIdentityReloadKey =
    LocalFamilyIdentityReloadKey(
        familyId = session.familyId,
        deviceId = session.deviceId,
        role = session.role,
        membershipId = session.membershipId,
        reauthRequired = session.reauthRequired,
    )
