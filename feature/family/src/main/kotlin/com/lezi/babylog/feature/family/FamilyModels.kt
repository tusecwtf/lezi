package com.lezi.babylog.feature.family

import com.lezi.babylog.feature.family.components.LOCAL_FAMILY_DISPLAY_NAME
import com.lezi.babylog.sync.session.FamilyRole
/**
 * Shared session-identity fields for account surfaces.
 * Overview and members hosts each project this once; the shell does not re-merge
 * identity from two places when a single host slice is enough.
 */
data class FamilyIdentityUi(
    val displayName: String = LOCAL_FAMILY_DISPLAY_NAME,
    val enabled: Boolean = false,
    val familyId: String = "1",
    val membershipId: String = "",
    val role: FamilyRole = FamilyRole.None,
    val familyName: String? = null,
)
