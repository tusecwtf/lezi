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
    val generation: String = "",
    /** Null means a legacy response omitted the additive `has_more` field. */
    val hasMore: Boolean? = null,
)

data class JoinResult(
    val familyId: String,
    val token: String,
    val role: FamilyRole,
    val entities: List<SyncEntity> = emptyList(),
    val cursor: Long = 0,
    val generation: String = "",
    /** Shared family name from create/join; null when empty or legacy NAS omits it. */
    val familyName: String? = null,
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

    suspend fun push(session: SyncSession, entities: List<SyncEntity>): Int
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
    suspend fun deleteFamily(session: SyncSession)
    suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    )

    suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray
}
