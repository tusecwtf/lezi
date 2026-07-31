package com.lezi.babylog.sync

data class SyncEntity(
    val type: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val rev: Long = 0,
)

data class PullResult(
    val entities: List<SyncEntity>,
    val cursor: Long,
    val generation: String,
    val hasMore: Boolean,
    /** Current wire always contains family_name; null explicitly clears it. */
    val familyName: String? = null,
)

/** Server-owned Record author returned after an accepted atomic commit. */
data class CanonicalRecordAuthor(
    val clientUuid: String,
    val createdByMembershipId: String,
)

data class SessionBootstrapResult(
    val familyId: String,
    /** Short-lived access credential; process memory only. */
    val accessToken: String,
    /** Long-lived rotating credential; Android secure storage only. */
    val refreshToken: String = "",
    val accessExpiresAtEpochSeconds: Long = 0,
    /** Canonical server-minted device identity. */
    val deviceId: String = "",
    val role: FamilyRole,
    /** Current session bootstrap does not carry an entity page. */
    val entities: List<SyncEntity> = emptyList(),
    /** Current create/login/claim bootstrap starts from cursor zero. */
    val cursor: Long = 0,
    /** Required by every current session bootstrap response. */
    val generation: String,
    /** Shared family name from create/login/claim; null means the server stores no name. */
    val familyName: String? = null,
    /** Server-minted immutable membership identity. */
    val membershipId: String,
    /**
     * Create-only: true when the server reclaimed the existing one-stack owner
     * membership instead of minting a new family.
     */
    val reclaimed: Boolean = false,
) {
    override fun toString(): String =
        "SessionBootstrapResult(familyId=$familyId, deviceId=$deviceId, role=$role, " +
            "membershipId=$membershipId, credentials=<redacted>)"
}

data class SessionRefreshResult(
    val familyId: String,
    val membershipId: String,
    val deviceId: String,
    val role: FamilyRole,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochSeconds: Long,
    val generation: String,
    val familyName: String?,
) {
    override fun toString(): String =
        "SessionRefreshResult(familyId=$familyId, deviceId=$deviceId, role=$role, " +
            "membershipId=$membershipId, credentials=<redacted>)"
}

data class MemberLoginReceipt(
    val requestId: String,
    val pendingSecret: String,
    val expiresAtEpochSeconds: Long,
)

enum class MemberLoginStatus { Pending, Approved, Rejected, Cancelled, Expired, Claimed }

data class PendingMemberLoginRequest(
    val requestId: String,
    val displayName: String,
    val deviceName: String,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

data class PendingMemberRenameRequest(
    val requestId: String,
    val membershipId: String,
    val currentDisplayName: String,
    val requestedDisplayName: String,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(requestId.isNotBlank()) { "改名申请 ID 不能为空" }
        require(membershipId.isNotBlank()) { "改名申请成员 ID 不能为空" }
        require(currentDisplayName.isNotBlank()) { "当前家庭称呼不能为空" }
        require(requestedDisplayName.isNotBlank()) { "申请家庭称呼不能为空" }
        require(createdAtEpochSeconds >= 0) { "改名申请时间无效" }
        require(expiresAtEpochSeconds > createdAtEpochSeconds) { "改名申请有效期无效" }
    }
}

sealed interface DisplayNameUpdateResult {
    data class Updated(val displayName: String) : DisplayNameUpdateResult
    data class Pending(val request: PendingMemberRenameRequest) : DisplayNameUpdateResult
}

data class MemberLoginGrant(
    val grant: String,
    val familyName: String?,
    val memberDisplayName: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "MemberLoginGrant(familyName=$familyName, memberDisplayName=$memberDisplayName, " +
            "expiresAtEpochSeconds=$expiresAtEpochSeconds, grant=<redacted>)"
}

/** Client-generated atomic package for record/care_plan + full media manifest. */
data class AtomicBundleDraft(
    val bundleId: String,
    val root: SyncEntity,
    val media: List<SyncEntity> = emptyList(),
)

data class BundleStageStatus(
    val bundleId: String,
    val status: String,
    val missingMedia: List<String> = emptyList(),
    val stagedMedia: List<String> = emptyList(),
) {
    val isCommitted: Boolean get() = status == "committed"
}

data class BundleCommitResult(
    val bundleId: String,
    val status: String,
    val applied: Int,
    val cursor: Long,
    val recordAuthors: List<CanonicalRecordAuthor> = emptyList(),
)

interface SyncBackend {
    suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String? = null,
    ): SessionBootstrapResult

    suspend fun refresh(baseUrl: String, refreshToken: String): SessionRefreshResult =
        throw UnsupportedOperationException("Session refresh is not implemented")

    suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
    ): SessionRefreshResult = refresh(endpoint.origin, refreshToken)

    /** Authenticates a new Owner Device; takeover revokes older Owner Devices. */
    suspend fun ownerLogin(
        baseUrl: String,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult = throw UnsupportedOperationException("Owner login is not implemented")

    suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt = throw UnsupportedOperationException("Member login request is not implemented")

    suspend fun memberLoginStatus(baseUrl: String, pendingSecret: String): MemberLoginStatus =
        throw UnsupportedOperationException("Member login status is not implemented")

    suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        throw UnsupportedOperationException("Member login cancellation is not implemented")
    }

    suspend fun claimMemberLogin(baseUrl: String, pendingSecret: String): SessionBootstrapResult =
        throw UnsupportedOperationException("Member login claim is not implemented")

    suspend fun pendingMemberLogins(session: SyncSession): List<PendingMemberLoginRequest> =
        throw UnsupportedOperationException("Pending member login list is not implemented")

    suspend fun approveNewMemberLogin(session: SyncSession, requestId: String) {
        throw UnsupportedOperationException("Member login approval is not implemented")
    }

    suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) {
        throw UnsupportedOperationException("Existing member binding is not implemented")
    }

    suspend fun rejectMemberLogin(session: SyncSession, requestId: String) {
        throw UnsupportedOperationException("Member login rejection is not implemented")
    }

    suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant = throw UnsupportedOperationException("Member login grant is not implemented")

    suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult = throw UnsupportedOperationException("Member login grant claim is not implemented")

    suspend fun pull(session: SyncSession): PullResult
    suspend fun members(session: SyncSession): List<FamilyMember>
    /** Owner updates immediately; ordinary Member receives a pending approval request. */
    suspend fun updateMyDisplayName(
        session: SyncSession,
        displayName: String,
    ): DisplayNameUpdateResult
    suspend fun pendingMemberRenameRequests(
        session: SyncSession,
    ): List<PendingMemberRenameRequest> =
        throw UnsupportedOperationException("Pending member rename list is not implemented")
    suspend fun approveMemberRename(session: SyncSession, requestId: String) {
        throw UnsupportedOperationException("Member rename approval is not implemented")
    }
    suspend fun rejectMemberRename(session: SyncSession, requestId: String) {
        throw UnsupportedOperationException("Member rename rejection is not implemented")
    }
    suspend fun cancelMyMemberRename(session: SyncSession) {
        throw UnsupportedOperationException("Member rename cancellation is not implemented")
    }
    suspend fun addFamilyMember(session: SyncSession, displayName: String): FamilyMember =
        throw UnsupportedOperationException("Owner member creation is not implemented")
    suspend fun renameFamilyMember(
        session: SyncSession,
        membershipId: String,
        displayName: String,
    ) {
        throw UnsupportedOperationException("Owner member rename is not implemented")
    }
    suspend fun renameFamilyDevice(
        session: SyncSession,
        deviceId: String,
        deviceName: String,
    ) {
        throw UnsupportedOperationException("Family device rename is not implemented")
    }
    suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) {
        throw UnsupportedOperationException("Family device revoke is not implemented")
    }
    suspend fun logoutCurrentDevice(session: SyncSession) {
        throw UnsupportedOperationException("Current device logout is not implemented")
    }
    /** Owner-only; current wire requires a non-empty shared family name. */
    suspend fun renameFamily(session: SyncSession, familyName: String?)
    suspend fun leave(session: SyncSession)
    /**
     * Owner removes another active member by [membershipId].
     * Does not clear local replica; only ends the target membership's access.
     */
    suspend fun removeMember(session: SyncSession, membershipId: String)
    /** Owner-only destructive request; [rootPassword] is request-scoped and must not be stored. */
    suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    )
    suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray

    /**
     * Stage root entity + media metadata for an atomic package.
     * Nothing is visible on family pull until [commitBundle].
     */
    suspend fun stageBundle(session: SyncSession, draft: AtomicBundleDraft): BundleStageStatus

    /** Upload one media blob into a staged bundle manifest slot. */
    suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus

    /** Publish a complete package in one server transaction (idempotent). */
    suspend fun commitBundle(session: SyncSession, bundleId: String): BundleCommitResult
}
