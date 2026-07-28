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

/** Server-owned Record author returned after an accepted push or atomic commit. */
data class CanonicalRecordAuthor(
    val clientUuid: String,
    val createdByMembershipId: String,
)

data class PushResult(
    val applied: Int,
    val recordAuthors: List<CanonicalRecordAuthor> = emptyList(),
)

data class JoinResult(
    val familyId: String,
    val token: String,
    val role: FamilyRole,
    /** Create has no bootstrap entity page, while join may return one. */
    val entities: List<SyncEntity> = emptyList(),
    /** Create starts from cursor zero; join publishes its explicit bootstrap cursor. */
    val cursor: Long = 0,
    /** Required by both create and join in the current protocol. */
    val generation: String,
    /** Shared family name from create/join; null means the current server stores no name. */
    val familyName: String? = null,
    /** Server-minted immutable membership identity. */
    val membershipId: String,
)

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
    ): JoinResult

    suspend fun push(session: SyncSession, entities: List<SyncEntity>): PushResult
    suspend fun pull(session: SyncSession): PullResult
    suspend fun invite(session: SyncSession): Invite

    suspend fun join(
        baseUrl: String,
        code: String,
        deviceId: String,
        displayName: String?,
    ): JoinResult

    suspend fun members(session: SyncSession): List<FamilyMember>
    /** Self-only; updates the caller's membership 家庭称呼. */
    suspend fun updateMyDisplayName(session: SyncSession, displayName: String)
    /** Owner-only; null/blank clears the shared family name. */
    suspend fun renameFamily(session: SyncSession, familyName: String?)
    suspend fun leave(session: SyncSession)
    /**
     * Owner removes another active member by [membershipId].
     * Does not clear local replica; only ends the target membership's access.
     */
    suspend fun removeMember(session: SyncSession, membershipId: String)
    suspend fun deleteFamily(session: SyncSession)
    suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    )

    suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray

    /**
     * Stage root entity + media metadata for an atomic package.
     * Nothing is visible on ordinary pull until [commitBundle].
     */
    suspend fun stageBundle(session: SyncSession, draft: AtomicBundleDraft): BundleStageStatus

    /** Upload one media blob into a staged bundle manifest slot. */
    suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ): BundleStageStatus

    /** Publish a complete package in one server transaction (idempotent). */
    suspend fun commitBundle(session: SyncSession, bundleId: String): BundleCommitResult
}
