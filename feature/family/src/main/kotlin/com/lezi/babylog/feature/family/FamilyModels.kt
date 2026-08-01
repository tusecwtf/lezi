package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.feature.family.components.LOCAL_FAMILY_DISPLAY_NAME
import com.lezi.babylog.feature.family.components.displayFamilyName
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
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

/**
 * Optional shell merge of overview + members for rare cross-flow visuals.
 * Prefer typing UI against [AccountOverviewUi] / [MembersDevicesUi] directly.
 */
data class FamilyUi(
    val identity: FamilyIdentityUi = FamilyIdentityUi(),
    val status: SyncStatus = SyncStatus.Disabled,
    val hasLocalBaby: Boolean = false,
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    /** Local pre-join profiles kept only as merge sources while this device is a member. */
    val localOrphanBabies: List<Baby> = emptyList(),
    val lastSuccessAt: Long? = null,
    val members: List<FamilyMember> = emptyList(),
    val membersLoaded: Boolean = false,
    val membersLoading: Boolean = false,
    val membersError: String? = null,
    val pendingMemberLogin: PendingMemberLogin? = null,
    val pendingMemberRequests: List<PendingMemberLoginRequest> = emptyList(),
    val pendingMemberRenameRequests: List<PendingMemberRenameRequest> = emptyList(),
    /**
     * Optional self-hosted app update from handshake/sync discovery.
     * Null when none, not joined, up-to-date, or dismissed for this process session.
     */
    val optionalAppUpdate: AppUpdateMetadata? = null,
) {
    val displayName: String get() = identity.displayName
    val enabled: Boolean get() = identity.enabled
    val familyId: String get() = identity.familyId
    val membershipId: String get() = identity.membershipId
    val role: FamilyRole get() = identity.role
    val familyName: String? get() = identity.familyName

    /** Resolved label when the current optional family name is empty. */
    val familyNameLabel: String
        get() = displayFamilyName(familyName, current?.nickname)
}

/** Merge overview + members for shell visuals that truly need both slices. */
internal fun familyUiFromHosts(
    overview: AccountOverviewUi,
    members: MembersDevicesUi,
): FamilyUi = FamilyUi(
    // Identity: prefer overview session projection (authoritative for account shell).
    identity = overview.identity,
    status = overview.status,
    hasLocalBaby = overview.hasLocalBaby,
    current = overview.current,
    babies = overview.babies,
    localOrphanBabies = overview.localOrphanBabies,
    lastSuccessAt = overview.lastSuccessAt,
    members = members.members,
    membersLoaded = members.membersLoaded,
    membersLoading = members.membersLoading,
    membersError = members.membersError,
    pendingMemberLogin = overview.pendingMemberLogin,
    pendingMemberRequests = members.pendingMemberRequests,
    pendingMemberRenameRequests = members.pendingMemberRenameRequests,
    optionalAppUpdate = overview.optionalAppUpdate,
)
