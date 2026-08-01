package com.lezi.babylog.feature.family

import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.PendingMemberLoginRequest
import com.lezi.babylog.sync.PendingMemberRenameRequest

/**
 * Screen-level family projection composed from [AccountOverviewUi] + [MembersDevicesUi].
 * Hosts own their call-flow slices; the navigation shell may merge them for shared visuals.
 */
data class FamilyUi(
    val displayName: String = LOCAL_FAMILY_DISPLAY_NAME,
    val status: SyncStatus = SyncStatus.Disabled,
    val enabled: Boolean = false,
    val hasLocalBaby: Boolean = false,
    val familyId: String = "1",
    val membershipId: String = "",
    val current: Baby? = null,
    val babies: List<Baby> = emptyList(),
    /** Local pre-join profiles kept only as merge sources while this device is a member. */
    val localOrphanBabies: List<Baby> = emptyList(),
    val baseUrl: String = "",
    val serverHost: String = "",
    val serverPort: Int = com.lezi.babylog.sync.DEFAULT_SERVER_PORT,
    val serverScheme: String = com.lezi.babylog.sync.DEFAULT_SERVER_SCHEME,
    val role: FamilyRole = FamilyRole.None,
    val lastSuccessAt: Long? = null,
    /** Raw shared family name from session cache; null when empty/unknown. */
    val familyName: String? = null,
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
    /** Resolved label when the current optional family name is empty. */
    val familyNameLabel: String
        get() = displayFamilyName(familyName, current?.nickname)
}

/** Merge overview + members hosts for shell composables that still take one projection. */
internal fun familyUiFromHosts(
    overview: AccountOverviewUi,
    members: MembersDevicesUi,
): FamilyUi = FamilyUi(
    displayName = overview.displayName,
    status = overview.status,
    enabled = overview.enabled,
    hasLocalBaby = overview.hasLocalBaby,
    familyId = overview.familyId,
    membershipId = overview.membershipId,
    current = overview.current,
    babies = overview.babies,
    localOrphanBabies = overview.localOrphanBabies,
    baseUrl = overview.baseUrl,
    serverHost = overview.serverHost,
    serverPort = overview.serverPort,
    serverScheme = overview.serverScheme,
    role = overview.role,
    lastSuccessAt = overview.lastSuccessAt,
    familyName = overview.familyName,
    members = members.members,
    membersLoaded = members.membersLoaded,
    membersLoading = members.membersLoading,
    membersError = members.membersError,
    pendingMemberLogin = overview.pendingMemberLogin,
    pendingMemberRequests = members.pendingMemberRequests,
    pendingMemberRenameRequests = members.pendingMemberRenameRequests,
    optionalAppUpdate = overview.optionalAppUpdate,
)
