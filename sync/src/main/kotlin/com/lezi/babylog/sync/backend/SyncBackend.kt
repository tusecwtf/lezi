package com.lezi.babylog.sync.backend

import java.io.OutputStream
import java.security.MessageDigest
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_SYNC_V2
import com.lezi.babylog.sync.session.CAPABILITY_NURSING_PLAN_INTENT_V1
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile

data class SyncEntity(
    val type: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val rev: Long = 0,
    /**
     * Opaque stable `version_id` for causal roots (wire §7). Null for media /
     * fulfillment_candidate and for pre-causal projections without a head.
     */
    val versionId: String? = null,
    /** Bounded open-conflict summary from pull (wire §7). */
    val conflictSummary: PullConflictSummary? = null,
    /** Wire §12.3 optional source-relation summary on record roots. */
    val sourceRelationSummary: PullSourceRelationSummary? = null,
    /**
     * Existing optional causal-root manifest. Kept for already trusted legacy
     * pages; independent media use the separately negotiated [mediaIdentity].
     */
    val media: List<CausalMediaItem> = emptyList(),
    /** Pull-only four-key byte identity, negotiated by X-Lezi-Media-Identity: v1. */
    val mediaIdentity: PullMediaIdentity? = null,
)

/** Deliberately independent of canonical/conflict media metadata (legacy MIME can be null). */
data class PullMediaIdentity(
    val mediaUuid: String,
    val role: String,
    val sha256: String,
    val byteSize: Long,
)

/** Wire §7 conflict_summary closed keys on ordinary pull entities. */
data class PullConflictSummary(
    val conflictId: String,
    val entityType: String,
    val clientUuid: String,
    val stableVersionId: String,
    val branchVersionIds: List<String>,
)

/** Unresolved sync conflict requires a non-empty open branch set. */
fun PullConflictSummary.takeIfOpenBranches(): PullConflictSummary? =
    takeIf { branchVersionIds.isNotEmpty() }

/** Wire §12.3 `source_relation_summary` on stable pull entities. */
data class PullSourceRelationSummary(
    val relationId: String,
    /** display | source */
    val role: String,
    val peerIds: List<String>,
    /** Additive wire §12.3; old APKs ignore the key. */
    val autoAligned: Boolean = false,
)

/** Wire §12.1 author declare request. */
data class SourceRelationDeclareRequest(
    val mutationId: String,
    val recordClientUuid: String,
    val equivalentToClientUuid: String,
    val expectedRecordVersion: String,
    val expectedOtherVersion: String,
)

/** Wire §12.2 Owner group resolve request. */
data class SourceRelationResolveGroupRequest(
    val mutationId: String,
    val memberClientUuids: List<String>,
    val displayClientUuid: String,
    val expectedVersions: Map<String, String>,
)

/** Wire §12 declare / resolve-group receipt. */
data class SourceRelationResult(
    val status: String,
    val relationId: String? = null,
    val displayClientUuid: String? = null,
    val sourceClientUuids: List<String> = emptyList(),
    val mediaRetained: Boolean? = null,
    val code: String? = null,
    val latestVersions: Map<String, String> = emptyMap(),
    /** Local settlement projection; never part of the immutable mutation receipt wire body. */
    val currentProjection: CurrentSourceRelationsSnapshot? = null,
)

/**
 * Wire §1.4 additive live-set census for one entity type: live row count and
 * the digest of the sorted live `client_uuid` keys at the server's head.
 */
data class LiveCensusEntry(
    val count: Long,
    val keyDigest: String,
    /** Present only when the pull asked for `include_live_keys` on this type. */
    val keys: List<String>? = null,
)

/** Full-family live-set census at head (tombstones excluded), keyed by entity type. */
data class LiveCensus(
    val entries: Map<String, LiveCensusEntry>,
)

/**
 * Shared census digest, matching the server's `live_key_digest` exactly:
 * lowercase hex SHA-256 over the UTF-8 bytes of the keys sorted
 * lexicographically and joined by `\n` (no trailing newline). The empty set
 * digests the empty byte string (`e3b0c442…b855`).
 */
internal fun liveCensusKeyDigest(keys: Collection<String>): String {
    val canonical = keys.sorted().joinToString(separator = "\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

data class PullResult(
    val entities: List<SyncEntity>,
    val cursor: Long,
    val generation: String,
    val hasMore: Boolean,
    /** Zero-based continuation index echoed by the ordinary pull page. */
    val pageIndex: Int = 0,
    /** Current wire always contains family_name; null explicitly clears it. */
    val familyName: String? = null,
    /**
     * Wire §1.4 additive full-family live-set census; present only when the
     * pull requested it and the server is 0.4.7+.
     */
    val liveCensus: LiveCensus? = null,
)

enum class PullResponseEncoding(val wireName: String) {
    Gzip("gzip"),
    Identity("identity"),
}

data class PullPageBudget(
    val maxEntities: Int,
    val maxEncodedBytes: Int,
    val maxDecodedBytes: Int,
    val maxPages: Int,
)

internal val FROZEN_PULL_PAGE_BUDGET = PullPageBudget(
    maxEntities = 200,
    maxEncodedBytes = 9 * 1024 * 1024,
    maxDecodedBytes = 8 * 1024 * 1024,
    maxPages = 500,
)

data class PullPageRequest(
    val pageIndex: Int,
    val encoding: PullResponseEncoding,
    val budget: PullPageBudget,
    /** Opt-in live keys for these census types; empty keeps the 0.4.7 envelope. */
    val liveKeyTypes: Set<String> = emptySet(),
)

internal fun PullResult.requireValidPage(request: PullPageRequest): PullResult {
    require(pageIndex == request.pageIndex) {
        "家庭服务器返回了跳页或重复的 pull page_index"
    }
    require(entities.size <= request.budget.maxEntities) {
        "家庭服务器 pull 页超过 item 上限"
    }
    require(entities.map { it.type to it.clientUuid }.distinct().size == entities.size) {
        "家庭服务器 pull 页包含重复实体"
    }
    return this
}

data class PullTransportContract(
    val encoding: PullResponseEncoding,
    val budget: PullPageBudget,
    val liveKeyTypes: Set<String> = emptySet(),
) {
    fun page(index: Int): PullPageRequest =
        PullPageRequest(index, encoding, budget, liveKeyTypes)

    fun withLiveKeyTypes(types: Set<String>): PullTransportContract =
        copy(liveKeyTypes = types)
}

/** Wire §4.6 causal media manifest item (no bytes). */
data class CausalMediaItem(
    val mediaUuid: String,
    val role: String,
    val sha256: String,
    val byteSize: Long,
    val mime: String?,
    val width: Long? = null,
    val height: Long? = null,
)

/** Durable server receipt for one exact causal media preimage (wire §4.6.1). */
data class CausalMediaPreimageReceipt(
    val mediaUuid: String,
    /** staged | consumed */
    val status: String,
    val byteSize: Long,
    val sha256: String,
    val expiresAtEpochSeconds: Long,
)

/**
 * Wire §3.1 frozen mutation unit for `/v1/causal/commit`.
 * [rootJson] is the closed-key root object (includes `updated_at`).
 */
data class CausalMutationUnit(
    val mutationId: String,
    val baseVersion: String?,
    val entityType: String,
    val clientUuid: String,
    val rootJson: String,
    val media: List<CausalMediaItem> = emptyList(),
    val deleted: Boolean = false,
)

/** Wire §3.2 contracted commit unit; no cursor, generation, or rejection payload. */
data class CausalCommitUnitResult(
    val status: String,
    val mutationId: String,
    val requestHash: String,
    val replay: Boolean = false,
    val stableVersionId: String,
    val stableRootJson: String,
    val stableMedia: List<CausalMediaItem> = emptyList(),
    val stableDeleted: Boolean = false,
    val stableDeletedAt: Long? = null,
    /** Adapter evidence: required stable projection members were present on the wire. */
    val stableRootPresent: Boolean = true,
    val stableMediaPresent: Boolean = true,
    val branchVersionId: String? = null,
    val conflictId: String? = null,
)

data class CausalCommitBatchResult(
    val generation: String,
    val results: List<CausalCommitUnitResult>,
)

class CausalCommitRejectedException(
    val mutationId: String?,
    val code: String,
) : IllegalStateException("因果 commit 被服务器拒绝：$code")

/** One opaque choice in the wire §8.2 resolution command. */
data class ConflictResolutionChoice(
    val path: String,
    val choiceId: String,
)

/** Wire §8.2 choice-only resolve request. */
data class ConflictResolveRequest(
    val snapshotToken: String,
    val resolutionMutationId: String,
    val choices: List<ConflictResolutionChoice>,
)

/** Wire §8.3 withdraw own open conflict branches. */
data class ConflictWithdrawRequest(
    val withdrawalMutationId: String,
    val expectedStableVersionId: String,
    val expectedBranchVersionIds: List<String>,
)

sealed class ConflictWithdrawResult {
    data class Accepted(
        val stableVersionId: String,
        val withdrawalMutationId: String,
        val conflictStatus: String,
        val remainingBranchVersionIds: List<String>,
        val replay: Boolean,
    ) : ConflictWithdrawResult()

    data class Rejected(
        val code: String,
        val withdrawalMutationId: String?,
        val retryable: Boolean,
    ) : ConflictWithdrawResult()
}

sealed class ConflictResolveResult {
    /**
     * Wire §8.2 accepted terminal. Root/media are validated against the H05 typed
     * decoder, but local settlement waits for pull because this envelope omits
     * authoritative deleted state.
     */
    data class Accepted(
        val stableVersionId: String,
        val resolutionMutationId: String,
        val stableRootJson: String = "{}",
        val stableMedia: List<CausalMediaItem> = emptyList(),
        val replay: Boolean,
    ) : ConflictResolveResult()

    data class Rejected(
        val code: String,
        val resolutionMutationId: String?,
        val retryable: Boolean,
    ) : ConflictResolveResult()
}

/** Wire §9.5 closed rejection codes shared by conflict detail and resolution. */
internal val CONFLICT_TERMINAL_REJECTION_CODES = setOf(
    "unknown_field",
    "missing_field",
    "wrong_type",
    "non_canonical_value",
    "invalid_domain",
    "content_drift",
    "unauthenticated",
    "forbidden",
    "capability_mismatch",
    "invalid_snapshot_token",
    "snapshot_expired",
    "snapshot_stale",
    "invalid_choice",
    "duplicate_choice",
    "incomplete_choices",
    "missing_restore_base",
    "incomplete_restore_base",
    "missing_restore_media",
    "cas_mismatch",
)

/** Closed causal commit statuses (wire §6). */
object CausalCommitStatus {
    const val ACCEPTED = "accepted"
    const val MERGED = "merged"
    const val BRANCHED = "branched"
}

/** A syntactically successful response that cannot prove every frozen authority key. */
class AuthorityProofException(
    val serverGeneration: String,
    cause: IllegalArgumentException,
) : IllegalStateException("家庭服务器权威裁决证明无效，必须全量重建", cause)

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
) {
    override fun toString(): String =
        "MemberLoginReceipt(requestId=$requestId, " +
            "expiresAtEpochSeconds=$expiresAtEpochSeconds, pendingSecret=<redacted>)"
}

enum class MemberLoginStatus { Pending, Approved, Rejected, Cancelled, Expired, Claimed }

/**
 * Legacy compatibility name for an Owner-visible open login request.
 * The open view contains only Pending or Approved-but-unclaimed rows; callers must inspect status.
 */
data class PendingMemberLoginRequest(
    val requestId: String,
    val displayName: String,
    val deviceName: String,
    val createdAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    /** Owner list includes both undecided and approved-but-unclaimed requests. */
    val status: MemberLoginStatus = MemberLoginStatus.Pending,
) {
    init {
        require(status == MemberLoginStatus.Pending || status == MemberLoginStatus.Approved) {
            "管理员设备申请列表状态无效"
        }
    }
}

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
    val landingUrl: String? = null,
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

/**
 * Media clientUuids still required before commit for an already-staged package.
 * Committed stages need no upload; open stages must partition [liveMediaUuids]
 * into missing vs already staged without overlap or drift.
 */
internal fun BundleStageStatus.mediaUuidsToUpload(liveMediaUuids: Set<String>): Set<String> =
    if (isCommitted) {
        emptySet()
    } else {
        val missing = missingMedia.toSet()
        val staged = stagedMedia.toSet()
        require(missing.intersect(staged).isEmpty() && missing + staged == liveMediaUuids) {
            "家庭服务器返回了无效的原子同步包媒体状态"
        }
        missing
    }

data class BundleCommitResult(
    val bundleId: String,
    val status: String,
    val applied: Int,
    val cursor: Long,
    val recordAuthors: List<CanonicalRecordAuthor> = emptyList(),
)

data class AnonymousHealth(
    val version: String,
    val capabilities: Set<String>,
)

data class AnonymousReadiness(
    val version: String,
)

/**
 * Wire §1.5 closed three-key snapshot returned by the 0.4.8 foreground
 * heartbeat probe `GET /v1/sync/heartbeat`: per-install `generation`, the
 * family watermark `head_rev`, and the family directory digest
 * `directory_generation`. Read-only; carries no entity payload.
 */
data class SyncHeartbeat(
    val generation: String,
    val headRev: Long,
    val directoryGeneration: String,
)

data class DisasterRestoreBatch(
    val batchId: String,
    /** High-entropy, short-lived capability; process/secure storage only. */
    val recoveryToken: String,
    val status: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "DisasterRestoreBatch(batchId=$batchId, status=$status, " +
            "expiresAtEpochSeconds=$expiresAtEpochSeconds, recoveryToken=<redacted>)"
}

data class DisasterRestoreMediaSpec(
    val clientUuid: String,
    val byteSize: Long,
    val sha256: String,
)

/** Closed membership and historical display intent, adopted by the restored Owner. */
data class DisasterRestoreSourceRelation(
    val relationId: String,
    val displayClientUuid: String,
    val sourceClientUuids: List<String>,
    val autoAligned: Boolean,
)

data class DisasterRestoreStatus(
    val batchId: String,
    val status: String,
    val expiresAtEpochSeconds: Long,
) {
    val readyToCommit: Boolean get() = status == "ready_to_commit"
    val committed: Boolean get() = status == "committed"
}

internal const val AUTHENTICATED_SYNC_PROTOCOL_VERSION = 1

/** Paired0.5.5 value-domain revision; SQL shape remains13. */
internal val REQUIRED_CAUSAL_WIRE_CAPABILITIES = setOf(
    CAPABILITY_CAUSAL_SYNC_V2,
    CAPABILITY_NURSING_PLAN_INTENT_V1,
)

data class SyncHandshakePrincipal(
    val membershipId: String,
    val deviceId: String,
    val role: FamilyRole,
)

data class SyncHandshakeLimits(
    val pullPageMaxEntities: Int,
    val pullPageMaxEncodedBytes: Int,
    val pullPageMaxDecodedBytes: Int,
    val pullMaxPages: Int,
    val commitBatchMaxUnits: Int,
    val mediaMaxBytes: Long,
) {
    fun pullBudget(): PullPageBudget = PullPageBudget(
        maxEntities = pullPageMaxEntities,
        maxEncodedBytes = pullPageMaxEncodedBytes,
        maxDecodedBytes = pullPageMaxDecodedBytes,
        maxPages = pullMaxPages,
    )
}

data class SyncHandshakeCompression(val pullResponse: Set<String>)

data class SyncHandshakeRetryHints(val retryAfter: Boolean)

data class AuthenticatedSyncHandshake(
    val protocolVersion: Int,
    val serverVersion: String,
    val ready: Boolean,
    val capabilities: Set<String>,
    val principal: SyncHandshakePrincipal,
    val directoryGeneration: String,
    val limits: SyncHandshakeLimits,
    val compression: SyncHandshakeCompression,
    val retryHints: SyncHandshakeRetryHints,
) {
    fun pullTransport(): PullTransportContract {
        require(compression.pullResponse == setOf("gzip", "identity")) {
            "普通 pull compression 合同不兼容"
        }
        return PullTransportContract(
            encoding = PullResponseEncoding.Gzip,
            budget = limits.pullBudget(),
        )
    }
}

data class FamilyMemberDirectorySnapshot(
    val generation: String,
    val members: List<FamilyMember>,
)

class SyncHandshakeRejectedException(val code: String) :
    IllegalStateException("同步握手被服务器拒绝：$code")

/**
 * Streaming receipt of one app-update APK download: the incremental sha256
 * (64 lowercase hex, same format as app-update metadata) over exactly the
 * bytes written to the caller's sink, and the total byte count.
 */
data class AppUpdateApkDownload(
    val sha256: String,
    val byteCount: Long,
)

interface SyncBackend {
    /** Trusted TLS only; never sends family credentials or client data. */
    suspend fun anonymousHealth(endpoint: TrustedEndpointProfile): AnonymousHealth =
        throw UnsupportedOperationException("Anonymous health is not implemented")

    /** Trusted TLS only; never sends family credentials or client data. */
    suspend fun anonymousReady(endpoint: TrustedEndpointProfile): AnonymousReadiness =
        throw UnsupportedOperationException("Anonymous readiness is not implemented")

    /** Empty-server Owner recovery start. Root password is request-scoped and never returned. */
    suspend fun startDisasterRestore(
        endpoint: TrustedEndpointProfile,
        requestId: String,
        familyId: String,
        familyName: String,
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ): DisasterRestoreBatch =
        throw UnsupportedOperationException("Disaster restore is not implemented")

    suspend fun putDisasterRestoreManifest(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        entities: List<SyncEntity>,
        media: List<DisasterRestoreMediaSpec>,
        sourceRelations: List<DisasterRestoreSourceRelation>,
    ): DisasterRestoreStatus =
        throw UnsupportedOperationException("Disaster restore is not implemented")

    suspend fun putDisasterRestoreMedia(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): DisasterRestoreStatus =
        throw UnsupportedOperationException("Disaster restore is not implemented")

    suspend fun disasterRestoreStatus(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus =
        throw UnsupportedOperationException("Disaster restore is not implemented")

    suspend fun commitDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        rootPassword: String,
    ): SessionBootstrapResult =
        throw UnsupportedOperationException("Disaster restore is not implemented")

    suspend fun cancelDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus =
        throw UnsupportedOperationException("Disaster restore is not implemented")

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
        baseUrl: String,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult = refresh(baseUrl, refreshToken)

    suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
    ): SessionRefreshResult = refresh(endpoint.origin, refreshToken)

    suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult = refresh(endpoint, refreshToken)

    /** Authenticates a new Owner Device; takeover revokes older Owner Devices. */
    suspend fun ownerLogin(
        baseUrl: String,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult = throw UnsupportedOperationException("Owner login is not implemented")

    /** Candidate transport identity is explicit so an active-session resolver cannot leak creds. */
    suspend fun ownerLogin(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult = ownerLogin(
        endpoint.origin,
        deviceName,
        loginRequestId,
        rootPassword,
        takeover,
    )

    suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt = throw UnsupportedOperationException("Member login request is not implemented")

    suspend fun requestMemberLogin(
        endpoint: TrustedEndpointProfile,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt = requestMemberLogin(endpoint.origin, displayName, deviceName)

    suspend fun memberLoginStatus(baseUrl: String, pendingSecret: String): MemberLoginStatus =
        throw UnsupportedOperationException("Member login status is not implemented")

    suspend fun memberLoginStatus(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): MemberLoginStatus = memberLoginStatus(endpoint.origin, pendingSecret)

    suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        throw UnsupportedOperationException("Member login cancellation is not implemented")
    }

    suspend fun cancelMemberLogin(endpoint: TrustedEndpointProfile, pendingSecret: String) =
        cancelMemberLogin(endpoint.origin, pendingSecret)

    suspend fun claimMemberLogin(baseUrl: String, pendingSecret: String): SessionBootstrapResult =
        throw UnsupportedOperationException("Member login claim is not implemented")

    suspend fun claimMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): SessionBootstrapResult = claimMemberLogin(endpoint.origin, pendingSecret)

    /** Legacy name: returns the negotiated Owner open view, not necessarily only Pending rows. */
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

    /** H16 ordinary-pull transport seam; wrappers must preserve this exact immutable request. */
    suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult

    suspend fun authenticatedHandshake(session: SyncSession): AuthenticatedSyncHandshake

    /**
     * 0.4.8 foreground heartbeat probe (wire §1.5). One authenticated read-only
     * GET returning the closed [SyncHeartbeat] three-key snapshot so the client
     * can detect server-side change — including disaster-recovery watermark
     * regression, compared with `!=`, never `>` — without a pull round.
     * Must use the Probe budget (single attempt, seconds-level timeouts), never
     * the Handshake retry table. 401 terminal codes map exactly like every
     * other authenticated call.
     */
    suspend fun heartbeat(session: SyncSession): SyncHeartbeat =
        throw UnsupportedOperationException("Sync heartbeat is not implemented")

    /**
     * Causal atomic commit (wire §6). Successful units are accepted / merged / branched;
     * semantic rejection is a batch-level terminal envelope and never a partial result.
     */
    suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalCommitBatchResult = throw UnsupportedOperationException("Causal commit is not implemented")

    /**
     * Stage media bytes into the family authority media store before causal commit.
     * Mutation envelopes carry only the manifest; bytes must already be present for
     * accept/branch. Idempotent when the same sha256/size is already stored.
     */
    suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
        sha256: String,
    ): CausalMediaPreimageReceipt =
        throw UnsupportedOperationException("Causal media preimage upload is not implemented")

    /**
     * On-demand conflict detail (wire §8.1). Not included in ordinary pull pages.
     */
    suspend fun fetchConflictSnapshotPage(
        session: SyncSession,
        conflictId: String,
        request: ConflictSnapshotPageRequest,
    ): FetchedConflictSnapshotPage =
        throw UnsupportedOperationException("Conflict detail paging is not implemented")

    /** CAS conflict resolution (wire §8.2): receipt token + canonical opaque choices only. */
    suspend fun resolveConflict(
        session: SyncSession,
        conflictId: String,
        request: ConflictResolveRequest,
    ): ConflictResolveResult = throw UnsupportedOperationException("Conflict resolve is not implemented")

    /** Withdraw own open conflict branches (wire §8.3). */
    suspend fun withdrawConflictBranches(
        session: SyncSession,
        conflictId: String,
        request: ConflictWithdrawRequest,
    ): ConflictWithdrawResult = throw UnsupportedOperationException("Conflict withdraw is not implemented")

    /**
     * Author equivalence declare (wire §12.1).
     */
    suspend fun declareSourceRelation(
        session: SyncSession,
        request: SourceRelationDeclareRequest,
    ): SourceRelationResult = throw UnsupportedOperationException("Source relation declare is not implemented")

    /**
     * Owner full-group resolve (wire §12.2).
     */
    suspend fun resolveSourceRelationGroup(
        session: SyncSession,
        request: SourceRelationResolveGroupRequest,
    ): SourceRelationResult = throw UnsupportedOperationException("Source relation resolve-group is not implemented")

    /** Authenticated read of complete current groups; does not advance ordinary pull. */
    suspend fun readCurrentSourceRelations(
        session: SyncSession,
        request: CurrentSourceRelationsRequest,
    ): CurrentSourceRelationsSnapshot =
        throw UnsupportedOperationException("Current source relation projection is not supported")

    suspend fun memberDirectory(session: SyncSession): FamilyMemberDirectorySnapshot
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

    /** Publish a complete package in one server transaction (idempotent). */
    suspend fun commitBundle(session: SyncSession, bundleId: String): BundleCommitResult

    /**
     * Authenticated GET of self-hosted app-update metadata.
     * Requires a usable device session; no anonymous access.
     */
    suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata =
        throw UnsupportedOperationException("App update metadata is not implemented")

    /**
     * Authenticated GET of the release APK advertised by app-update metadata,
     * streamed chunk-by-chunk into [target] (never buffered whole in memory)
     * while the sha256 is computed incrementally over exactly the bytes handed
     * to [target] and the APK byte cap is enforced on the cumulative bytes
     * actually received — never on the declared Content-Length. The receipt is
     * returned only after the full body reached EOF; a failed download leaves
     * [target] with partial bytes for the caller to clean up. Requires a usable
     * device session; no anonymous access.
     */
    suspend fun downloadAppUpdateApk(
        session: SyncSession,
        target: OutputStream,
    ): AppUpdateApkDownload =
        throw UnsupportedOperationException("App update APK download is not implemented")

    /**
     * Immediate teardown of undetached 2xx handles (out-of-round authenticated
     * exchanges such as the conflict detail/resolve paths). Default is a no-op.
     */
    fun releaseForegroundKeepAlive() = Unit

    /**
     * End of a foreground sync cycle (0.5 W3). HTTP adapters may keep undetached
     * 2xx handles connected for reuse by the next round and disconnect them only
     * after a short idle TTL; origin+SPKI identity changes must still evict
     * immediately. Default is a no-op.
     */
    fun releaseForegroundKeepAliveToIdleTtl() = Unit
}

/** Positive transport evidence that no member application body was offered to the socket. */
class MemberLoginRequestNotSentException(cause: Throwable) : java.io.IOException(
    "加入申请尚未发送，可以重试", cause,
)
