package com.lezi.babylog.sync

import com.lezi.babylog.sync.session.ShallowSyncLine
import com.lezi.babylog.sync.session.ShallowSyncState
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.PendingPublishDao
import com.lezi.babylog.core.database.matchesPublishedRevision
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.RootPublicationState
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import com.lezi.babylog.sync.appupdate.APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.appupdate.APP_UPDATE_PACKAGE_INVALID_MESSAGE
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.StagedApkIdentity
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.appUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.forceShellNeedsSessionRecovery
import com.lezi.babylog.sync.appupdate.lanInviteApkDownloadUrl
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.AuthorityDisposition
import com.lezi.babylog.sync.backend.AuthorityResult
import com.lezi.babylog.sync.backend.AnonymousHealth
import com.lezi.babylog.sync.backend.AnonymousReadiness
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.BundleStageStatus
import com.lezi.babylog.sync.backend.CanonicalRecordAuthor
import com.lezi.babylog.sync.backend.CausalBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalReconcileStatus
import com.lezi.babylog.sync.backend.CausalUnitResult
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.DisasterRestoreBatch
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.backend.MemberLoginGrant
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.ReconcileResult
import com.lezi.babylog.sync.backend.ReconcileUnitDraft
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.appupdate.NoOpAppUpdateInstaller
import com.lezi.babylog.sync.clear.LocalClearCommittedException
import com.lezi.babylog.sync.engine.AtomicBundleId
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.ForegroundSyncBlockedException
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.DisasterRestoreRequestIds
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.familySyncError
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.LegacyPushResult
import com.lezi.babylog.sync.backend.testPreparedMedia


// Shared harness for RealSyncPort* contract suites (extracted from kitchen sink).

internal fun localBaby() = BabyEntity(
    familyId = 1,
    nickname = "本地宝宝",
    birthdayEpochDay = 20_000,
    themeColorArgb = 0,
    clientUuid = "baby-local",
    updatedAt = 100,
)

internal fun localCarePlan(babyId: Long) = CarePlanEntity(
    clientUuid = "plan-local",
    babyId = babyId,
    type = "formula",
    scheduledAt = 9_000_000_000_000L,
    scheduledZoneId = "Asia/Shanghai",
    payloadJson = """{"amount_ml":120}""",
    status = "pending",
    createdByMembershipId = "member-local",
    updatedAt = 100,
    syncDirty = true,
)

internal fun localRecord(babyId: Long) = RecordEntity(
    clientUuid = "record-local",
    babyId = babyId,
    type = "formula",
    timestamp = 120,
    payloadJson = """{"amount_ml":120}""",
    updatedAt = 120,
)

internal fun remoteBaby() = SyncEntity(
    type = "baby",
    clientUuid = "baby-remote",
    payloadJson = """
        {
          "nickname":"远端宝宝",
          "sex":null,
          "birthday":"2024-01-01",
          "birth_weight_grams":null,
          "avatar_media_uuid":null
        }
    """.trimIndent(),
    updatedAt = 200,
)

internal fun remoteRecord() = SyncEntity(
    type = "record",
    clientUuid = "record-remote",
    payloadJson = """
        {
          "baby_client_uuid":"baby-remote",
          "created_by_membership_id":"membership-b",
          "type":"formula",
          "custom_item_client_uuid":null,
          "timestamp":210,
          "end_timestamp":null,
          "note":null,
          "payload_json":{"amount_ml":90},
          "schema_version":2
        }
    """.trimIndent(),
    updatedAt = 210,
)

internal data class PushedBatch(
    val session: SyncSession,
    val entities: List<SyncEntity>,
)

internal class RecordingSyncBackend : SyncBackend {
    val pushes = mutableListOf<PushedBatch>()
    val pushAttempts = mutableListOf<SyncSession>()
    val operationOrder = mutableListOf<String>()
    val syncOrder = mutableListOf<String>()
    val mediaUploads = mutableListOf<String>()
    var pullCount = 0
    var nextPull: PullResult? = null
    val pullResults = ArrayDeque<PullResult>()
    val pullFailures = ArrayDeque<Throwable>()
    val pushFailures = ArrayDeque<Throwable>()
    val pullCursors = mutableListOf<Long>()
    val reconciledUnits = mutableListOf<List<ReconcileUnitDraft>>()
    var nextReconcile: ReconcileResult? = null
    var onReconcile: (suspend (List<ReconcileUnitDraft>) -> Unit)? = null
    val causalReconciledUnits = mutableListOf<List<CausalMutationUnit>>()
    val causalCommittedUnits = mutableListOf<List<CausalMutationUnit>>()
    var nextCausalReconcile: CausalBatchResult? = null
    var nextCausalCommit: CausalBatchResult? = null
    var onCausalReconcile: (suspend (List<CausalMutationUnit>) -> Unit)? = null
    var onCausalCommit: (suspend (List<CausalMutationUnit>) -> Unit)? = null
    /**
     * Opt-in causal path for ReplicaSyncEngine tests. When false and no causal
     * hooks/results are prepared, methods throw [UnsupportedOperationException]
     * so the engine falls back to the legacy authority reconcile path.
     */
    var enableCausal: Boolean = false
    /** When true, default causal reconcile returns confirmed instead of publish. */
    var causalReconcileConfirmed: Boolean = false
    var afterPush: (() -> Unit)? = null
    var afterCommit: (suspend () -> Unit)? = null
    var pullStarted: CompletableDeferred<Unit>? = null
    var releasePull: CompletableDeferred<Unit>? = null
    var createStarted: CompletableDeferred<Unit>? = null
    var releaseCreate: CompletableDeferred<Unit>? = null
    var leaveFailure: Throwable? = null
    var deviceRevokeFailure: Throwable? = null
    var deviceLogoutFailure: Throwable? = null
    val revokedDeviceIds = mutableListOf<String>()
    var deviceLogoutCalls = 0
    var deleteFailure: Throwable? = null
    var onLeave: suspend () -> Unit = {}
    var onDeleteFamily: suspend () -> Unit = {}
    var deleteFamilyCalls = 0
    val deletedFamilyConfirmations = mutableListOf<Pair<String, String>>()
    var createFailure: Throwable? = null
    var membersFailure: Throwable? = null
    var nextMembers: List<FamilyMember>? = null
    var rejectMemberAvatarPointers = false
    var enforceBundleReferences = false
    var beforeGetMediaReturn: (suspend () -> Unit)? = null
    var beforePullReturn: (suspend () -> Unit)? = null
    var getMediaFailure: Throwable? = null
    var mediaBytes: ByteArray = byteArrayOf(1)
    val createRequestIds = mutableListOf<String>()
    val createDisplayNames = mutableListOf<String?>()
    val createFamilyNames = mutableListOf<String?>()
    val createBootstrapSecrets = mutableListOf<String?>()
    val ownerLoginRequestIds = mutableListOf<String>()
    val ownerLoginDeviceNames = mutableListOf<String>()
    val ownerLoginRootPasswords = mutableListOf<String>()
    val ownerLoginTakeovers = mutableListOf<Boolean>()
    val ownerLoginCandidateEndpoints = mutableListOf<TrustedEndpointProfile>()
    var ownerLoginFailure: Throwable? = null
    var nextOwnerLoginFamilyId = "family-owner-login"
    val memberLoginRequests = mutableListOf<Triple<String, String, String>>()
    val memberLoginCandidateEndpoints = mutableListOf<TrustedEndpointProfile>()
    var nextMemberLoginReceipt = MemberLoginReceipt(
        requestId = "99999999-9999-9999-9999-999999999999",
        pendingSecret = "pending-secret-000000000000000000000001",
        expiresAtEpochSeconds = 1_753_504_800,
    )
    val memberLoginStatuses = ArrayDeque<MemberLoginStatus>()
    var memberLoginClaimCalls = 0
    var memberLoginClaimFailure: Throwable? = null
    var nextMemberLoginClaim = SessionBootstrapResult(
        familyId = "family-member-approved",
        accessToken = "member-approved-access",
        refreshToken = "member-approved-refresh",
        accessExpiresAtEpochSeconds = 1_753_419_300,
        deviceId = "device-member-approved",
        role = FamilyRole.Member,
        generation = "current-generation",
        familyName = "乐乐一家",
        membershipId = "membership-member-approved",
    )
    var cancelMemberLoginCalls = 0
    var cancelMemberLoginFailure: Throwable? = null
    var beforeCancelMemberLoginReturn: suspend () -> Unit = {}
    var nextPendingMemberLogins: List<PendingMemberLoginRequest> = emptyList()
    val approvedMemberLoginRequestIds = mutableListOf<String>()
    val boundMemberLoginRequests = mutableListOf<Pair<String, String>>()
    val rejectedMemberLoginRequestIds = mutableListOf<String>()
    var nextMemberLoginGrant = MemberLoginGrant(
        grant = "grant-0000000000000000000000000000000000000",
        familyName = "乐乐一家",
        memberDisplayName = "妈妈",
        expiresAtEpochSeconds = 1_753_419_000,
    )
    val memberLoginGrantTargets =
        mutableListOf<Triple<String, TrustedEndpointProfile, String>>()
    val memberLoginGrantClaims =
        mutableListOf<Triple<TrustedEndpointProfile, String, String>>()
    val updatedDisplayNames = mutableListOf<String>()
    val renamedFamilyNames = mutableListOf<String?>()
    var renameFamilyFailure: Throwable? = null
    var nextPushRecordAuthors: List<CanonicalRecordAuthor>? = null
    var nextCreateFamilyName: String? = null
    var nextCreateEntities: List<SyncEntity> = emptyList()
    var nextCreateReclaimed: Boolean = false
    var memberCalls = 0
    var anonymousHealthCalls = 0
    var anonymousReadyCalls = 0
    val anonymousHealthEndpoints = mutableListOf<TrustedEndpointProfile>()
    val anonymousReadyEndpoints = mutableListOf<TrustedEndpointProfile>()
    var anonymousHealthResult = AnonymousHealth(
        version = "0.3.3",
        capabilities = setOf(
            "atomic_bundle",
            "record_membership_author",
            "device_disaster_restore_v1",
            "authoritative_reconcile_v1",
            "validated_deferred_fulfillment_v1",
            "causal_versions",
            "wake_observation",
            "source_relations",
        ),
    )
    var anonymousReadyResult = AnonymousReadiness(version = "0.3.3")
    var anonymousHealthFailure: Throwable? = null
    var anonymousReadyFailure: Throwable? = null
    var anonymousHealthGate: CompletableDeferred<Unit>? = null
    var anonymousHealthStarted: CompletableDeferred<Unit>? = null
    var anonymousReadyGate: CompletableDeferred<Unit>? = null
    val disasterRestoreStartEndpoints = mutableListOf<TrustedEndpointProfile>()
    val disasterRestoreStartRequestIds = mutableListOf<String>()
    val disasterRestoreStartFamilyIds = mutableListOf<String>()
    val disasterRestoreStartRootPasswords = mutableListOf<String>()
    val disasterRestoreManifestEntities = mutableListOf<List<SyncEntity>>()
    val disasterRestoreManifestMedia = mutableListOf<List<DisasterRestoreMediaSpec>>()
    val disasterRestoreMediaUuids = mutableListOf<String>()
    val disasterRestoreCommitRootPasswords = mutableListOf<String>()
    var disasterRestoreStartFailure: Throwable? = null
    var disasterRestoreStatus = DisasterRestoreStatus(
        batchId = "restore-batch-a",
        status = "ready_to_commit",
        expiresAtEpochSeconds = 1_753_591_200,
    )
    var nextDisasterRestoreCommit = SessionBootstrapResult(
        familyId = "family-a",
        accessToken = "restored-owner-access",
        refreshToken = "restored-owner-refresh",
        accessExpiresAtEpochSeconds = 1_753_419_300,
        deviceId = "restored-owner-device",
        role = FamilyRole.Owner,
        generation = "restored-generation",
        familyName = "乐乐一家",
        membershipId = "restored-owner-membership",
    )
    private val knownEntities = mutableSetOf<Pair<String, String>>()

    fun remember(type: String, clientUuid: String) {
        knownEntities += type to clientUuid
    }

    override suspend fun anonymousHealth(endpoint: TrustedEndpointProfile): AnonymousHealth {
        anonymousHealthCalls++
        anonymousHealthEndpoints += endpoint
        val gate = anonymousHealthGate
        anonymousHealthStarted?.complete(Unit)
        gate?.await()
        anonymousHealthFailure?.let { throw it }
        return anonymousHealthResult
    }

    override suspend fun anonymousReady(endpoint: TrustedEndpointProfile): AnonymousReadiness {
        anonymousReadyCalls++
        anonymousReadyEndpoints += endpoint
        anonymousReadyGate?.await()
        anonymousReadyFailure?.let { throw it }
        return anonymousReadyResult
    }

    override suspend fun startDisasterRestore(
        endpoint: TrustedEndpointProfile,
        requestId: String,
        familyId: String,
        familyName: String,
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ): DisasterRestoreBatch {
        disasterRestoreStartEndpoints += endpoint
        disasterRestoreStartRequestIds += requestId
        disasterRestoreStartFamilyIds += familyId
        disasterRestoreStartRootPasswords += rootPassword
        disasterRestoreStartFailure?.let { throw it }
        return DisasterRestoreBatch(
            batchId = disasterRestoreStatus.batchId,
            recoveryToken = "recovery-token-secret",
            status = "started",
            expiresAtEpochSeconds = disasterRestoreStatus.expiresAtEpochSeconds,
        )
    }

    override suspend fun putDisasterRestoreManifest(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        entities: List<SyncEntity>,
        media: List<DisasterRestoreMediaSpec>,
    ): DisasterRestoreStatus {
        require(recoveryToken == "recovery-token-secret")
        disasterRestoreManifestEntities += entities
        disasterRestoreManifestMedia += media
        return disasterRestoreStatus.copy(status = "manifest_staged")
    }

    override suspend fun putDisasterRestoreMedia(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): DisasterRestoreStatus {
        require(recoveryToken == "recovery-token-secret")
        require(source.contentLength > 0)
        disasterRestoreMediaUuids += clientUuid
        return disasterRestoreStatus
    }

    override suspend fun disasterRestoreStatus(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus {
        require(recoveryToken == "recovery-token-secret")
        return disasterRestoreStatus
    }

    override suspend fun commitDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        rootPassword: String,
    ): SessionBootstrapResult {
        require(recoveryToken == "recovery-token-secret")
        disasterRestoreCommitRootPasswords += rootPassword
        return nextDisasterRestoreCommit
    }

    override suspend fun cancelDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus {
        require(recoveryToken == "recovery-token-secret")
        return disasterRestoreStatus.copy(status = "cancelled")
    }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ): SessionBootstrapResult {
        createRequestIds += createRequestId
        createDisplayNames += displayName
        createFamilyNames += familyName
        createBootstrapSecrets += bootstrapSecret
        createStarted?.complete(Unit)
        releaseCreate?.await()
        createFailure?.let { throw it }
        return SessionBootstrapResult(
            familyId = "family-created",
            accessToken = if (nextCreateReclaimed) "owner-token-reclaimed" else "owner-token",
            refreshToken = "owner-refresh-token",
            accessExpiresAtEpochSeconds = 1_753_419_300,
            deviceId = "device-created",
            role = FamilyRole.Owner,
            generation = "current-generation",
            entities = nextCreateEntities,
            familyName = nextCreateFamilyName ?: familyName,
            membershipId = "membership-created",
            reclaimed = nextCreateReclaimed,
        )
    }

    override suspend fun ownerLogin(
        baseUrl: String,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult {
        ownerLoginRequestIds += loginRequestId
        ownerLoginDeviceNames += deviceName
        ownerLoginRootPasswords += rootPassword
        ownerLoginTakeovers += takeover
        ownerLoginFailure?.let { throw it }
        return SessionBootstrapResult(
            familyId = nextOwnerLoginFamilyId,
            accessToken = "owner-login-access",
            refreshToken = "owner-login-refresh",
            accessExpiresAtEpochSeconds = 1_753_419_300,
            deviceId = "device-owner-login",
            role = FamilyRole.Owner,
            generation = "current-generation",
            familyName = "乐乐一家",
            membershipId = "membership-owner",
        )
    }

    override suspend fun ownerLogin(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult {
        ownerLoginCandidateEndpoints += endpoint
        return ownerLogin(endpoint.origin, deviceName, loginRequestId, rootPassword, takeover)
    }

    override suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        memberLoginRequests += Triple(baseUrl, displayName, deviceName)
        return nextMemberLoginReceipt
    }

    override suspend fun requestMemberLogin(
        endpoint: TrustedEndpointProfile,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        memberLoginCandidateEndpoints += endpoint
        return requestMemberLogin(endpoint.origin, displayName, deviceName)
    }

    override suspend fun memberLoginStatus(
        baseUrl: String,
        pendingSecret: String,
    ): MemberLoginStatus = memberLoginStatuses.removeFirstOrNull() ?: MemberLoginStatus.Pending

    override suspend fun memberLoginStatus(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): MemberLoginStatus = memberLoginStatus(endpoint.origin, pendingSecret)

    override suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        cancelMemberLoginCalls++
        beforeCancelMemberLoginReturn()
        cancelMemberLoginFailure?.let { throw it }
    }

    override suspend fun cancelMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ) = cancelMemberLogin(endpoint.origin, pendingSecret)

    override suspend fun claimMemberLogin(baseUrl: String, pendingSecret: String): SessionBootstrapResult {
        memberLoginClaimCalls++
        memberLoginClaimFailure?.let { throw it }
        return nextMemberLoginClaim
    }

    override suspend fun claimMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): SessionBootstrapResult = claimMemberLogin(endpoint.origin, pendingSecret)

    override suspend fun pendingMemberLogins(
        session: SyncSession,
    ): List<PendingMemberLoginRequest> = nextPendingMemberLogins

    override suspend fun approveNewMemberLogin(session: SyncSession, requestId: String) {
        approvedMemberLoginRequestIds += requestId
    }

    override suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) {
        boundMemberLoginRequests += requestId to membershipId
    }

    override suspend fun rejectMemberLogin(session: SyncSession, requestId: String) {
        rejectedMemberLoginRequestIds += requestId
    }

    override suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant {
        memberLoginGrantTargets += Triple(session.accessToken, endpoint, membershipId)
        return nextMemberLoginGrant
    }

    override suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult {
        memberLoginGrantClaims += Triple(endpoint, grant, deviceName)
        return nextMemberLoginClaim
    }

    suspend fun push(session: SyncSession, entities: List<SyncEntity>): LegacyPushResult {
        pushAttempts += session
        pushFailures.removeFirstOrNull()?.let { throw it }
        val available = knownEntities + entities.map { it.type to it.clientUuid }
        entities.forEach { entity ->
            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
            when (entity.type) {
                "baby" -> payload["avatar_media_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeUnless { it == "null" }
                    ?.let {
                        if (rejectMemberAvatarPointers && session.role == FamilyRole.Member) {
                            throw SyncHttpException(403)
                        }
                        require("media" to it in available)
                    }
                "record" -> payload["baby_client_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { require("baby" to it in available) }
                "media" -> when (payload["kind"]?.jsonPrimitive?.contentOrNull) {
                    "avatar" -> payload["baby_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("baby" to it in available) }
                    "log" -> payload["record_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("record" to it in available) }
                }
            }
        }
        val pushOperation = "push:${entities.joinToString(",") { it.type }}"
        operationOrder += pushOperation
        syncOrder += pushOperation
        pushes += PushedBatch(session, entities)
        knownEntities += entities.map { it.type to it.clientUuid }
        afterPush?.invoke()
        return LegacyPushResult(
            applied = entities.size,
            recordAuthors = nextPushRecordAuthors ?: entities
                .filter { it.type == "record" }
                .map { entity ->
                    val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                    CanonicalRecordAuthor(
                        clientUuid = entity.clientUuid,
                        createdByMembershipId = payload["created_by_membership_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: session.membershipId,
                    )
                },
        )
    }

    override suspend fun pull(session: SyncSession): PullResult {
        pullCount++
        pullCursors += session.pullCursor
        syncOrder += "pull:${session.pullCursor}"
        pullStarted?.complete(Unit)
        releasePull?.await()
        pullFailures.removeFirstOrNull()?.let { throw it }
        beforePullReturn?.also { beforePullReturn = null }?.invoke()
        return pullResults.removeFirstOrNull() ?: nextPull ?: PullResult(
            entities = emptyList(),
            cursor = session.pullCursor,
            generation = session.pullGeneration,
            hasMore = false,
        )
    }

    override suspend fun reconcile(
        session: SyncSession,
        units: List<ReconcileUnitDraft>,
    ): ReconcileResult {
        reconciledUnits += units
        syncOrder += "reconcile:${units.size}"
        onReconcile?.invoke(units)
        return nextReconcile ?: ReconcileResult(
            generation = session.pullGeneration,
            cursor = session.pullCursor,
            results = units.map { unit ->
                val isMemberLocalBaby = session.role == FamilyRole.Member &&
                    unit.root.type == "baby"
                AuthorityResult(
                    type = unit.root.type,
                    clientUuid = unit.root.clientUuid,
                    requestContentHash = unit.contentHash,
                    disposition = if (isMemberLocalBaby) {
                        AuthorityDisposition.RemoteAbsentRejected
                    } else {
                        AuthorityDisposition.Publish
                    },
                    reason = if (isMemberLocalBaby) {
                        "member_local_baby"
                    } else {
                        "authoritative_absence"
                    },
                )
            },
        )
    }

    override fun supportsCausalWire(): Boolean =
        enableCausal || onCausalReconcile != null || nextCausalReconcile != null ||
            onCausalCommit != null || nextCausalCommit != null

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
        sha256: String,
    ) {
        // Recording backend accepts preimages as no-ops; production Http stages bytes.
        syncOrder += "causal_media_preimage:$mediaUuid"
        source.openStream().use { stream ->
            var remaining = source.contentLength
            val buf = ByteArray(8_192)
            while (remaining > 0) {
                val n = stream.read(buf)
                if (n < 0) break
                remaining -= n
            }
        }
    }

    override suspend fun causalReconcile(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalBatchResult {
        if (!supportsCausalWire()) {
            throw UnsupportedOperationException("Causal reconcile is not implemented")
        }
        causalReconciledUnits += units
        syncOrder += "causal_reconcile:${units.size}"
        onCausalReconcile?.invoke(units)
        val prepared = nextCausalReconcile
        nextCausalReconcile = null
        if (prepared != null) return prepared
        return defaultCausalBatch(
            session = session,
            units = units,
            status = when {
                causalReconcileConfirmed -> CausalReconcileStatus.CONFIRMED
                else -> CausalReconcileStatus.PUBLISH
            },
            useContentHash = true,
        )
    }

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalBatchResult {
        if (!supportsCausalWire()) {
            throw UnsupportedOperationException("Causal commit is not implemented")
        }
        causalCommittedUnits += units
        syncOrder += "causal_commit:${units.size}"
        onCausalCommit?.invoke(units)
        val prepared = nextCausalCommit
        nextCausalCommit = null
        if (prepared != null) return prepared
        return defaultCausalBatch(
            session = session,
            units = units,
            status = CausalCommitStatus.ACCEPTED,
            mintStableVersion = true,
            useContentHash = true,
        )
    }

    private fun defaultCausalBatch(
        session: SyncSession,
        units: List<CausalMutationUnit>,
        status: String,
        mintStableVersion: Boolean = false,
        useContentHash: Boolean = false,
    ): CausalBatchResult = CausalBatchResult(
        generation = session.pullGeneration,
        cursor = session.pullCursor,
        results = units.map { unit ->
            val stableVersion = when {
                mintStableVersion -> "v-${unit.mutationId.take(8)}"
                unit.baseVersion != null -> unit.baseVersion
                else -> "v-confirmed-${unit.mutationId.take(8)}"
            }
            CausalUnitResult(
                status = status,
                mutationId = unit.mutationId,
                requestHash = if (useContentHash) {
                    com.lezi.babylog.sync.engine.causalMutationContentHash(unit)
                } else {
                    "hash-${unit.mutationId}"
                },
                generation = session.pullGeneration,
                stableVersionId = stableVersion,
                stableRootJson = unit.rootJson.ifBlank { "{}" },
                stableMedia = unit.media,
                branchVersionId = null,
                conflictId = null,
            )
        },
    )

    override suspend fun members(session: SyncSession): List<FamilyMember> {
        memberCalls++
        membersFailure?.let { throw it }
        return nextMembers ?: listOf(
            FamilyMember(
                "管理员",
                session.role,
                isSelf = true,
                membershipId = session.membershipId,
            ),
        )
    }

    override suspend fun updateMyDisplayName(
        session: SyncSession,
        displayName: String,
    ): DisplayNameUpdateResult {
        updatedDisplayNames += displayName
        return DisplayNameUpdateResult.Updated(displayName)
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        renameFamilyFailure?.let { throw it }
        renamedFamilyNames += familyName
    }

    override suspend fun leave(session: SyncSession) {
        leaveFailure?.let { throw it }
        onLeave()
    }

    override suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) {
        deviceRevokeFailure?.let { throw it }
        revokedDeviceIds += deviceId
    }

    override suspend fun logoutCurrentDevice(session: SyncSession) {
        deviceLogoutFailure?.let { throw it }
        deviceLogoutCalls++
    }

    val removedMembershipIds = mutableListOf<String>()
    var removeMemberFailure: Throwable? = null

    override suspend fun removeMember(session: SyncSession, membershipId: String) {
        removeMemberFailure?.let { throw it }
        removedMembershipIds += membershipId.trim()
    }

    override suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    ) {
        deleteFamilyCalls += 1
        deletedFamilyConfirmations += familyName to rootPassword
        deleteFailure?.let { throw it }
        onDeleteFamily()
    }

    suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) {
        operationOrder += "put_media:$clientUuid"
        mediaUploads += clientUuid
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray {
        beforeGetMediaReturn?.also { beforeGetMediaReturn = null }?.invoke()
        getMediaFailure?.let { throw it }
        return mediaBytes
    }

    var appUpdateMetadata: AppUpdateMetadata? = null
    var getAppUpdateMetadataFailure: Throwable? = null
    var getAppUpdateMetadataCalls = 0
    var appUpdateApkBytes: ByteArray? = null
    var downloadAppUpdateApkFailure: Throwable? = null
    var downloadAppUpdateApkCalls = 0
    var downloadAppUpdateApkStarted: CompletableDeferred<Unit>? = null
    var releaseDownloadAppUpdateApk: CompletableDeferred<Unit>? = null

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata {
        getAppUpdateMetadataCalls += 1
        getAppUpdateMetadataFailure?.let { throw it }
        return appUpdateMetadata
            ?: throw SyncHttpException(404, """{"detail":"App update metadata is not available"}""")
    }

    override suspend fun downloadAppUpdateApk(session: SyncSession): ByteArray {
        downloadAppUpdateApkCalls += 1
        downloadAppUpdateApkStarted?.complete(Unit)
        releaseDownloadAppUpdateApk?.await()
        downloadAppUpdateApkFailure?.let { throw it }
        return appUpdateApkBytes
            ?: throw SyncHttpException(404, """{"detail":"App update package is not available"}""")
    }

    val stagedBundles = mutableListOf<AtomicBundleDraft>()
    val bundleMediaUploads = mutableListOf<Pair<String, String>>()
    val committedBundles = mutableListOf<String>()
    var stageBundleFailure: Throwable? = null
    var putBundleMediaFailure: Throwable? = null
    var onPutBundleMedia: (suspend (clientUuid: String) -> Unit)? = null
    var commitBundleFailure: Throwable? = null
    var failCommitRootTypeOnce: Pair<String, Throwable>? = null
    var nextCommitRecordAuthors: List<CanonicalRecordAuthor>? = null
    var stageBundleStatus = "staging"
    var stageBundleMissingMedia: List<String>? = null
    var stageBundleStagedMedia: List<String> = emptyList()

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        stageBundleFailure?.let { throw it }
        if (enforceBundleReferences) {
            val payload = Json.parseToJsonElement(draft.root.payloadJson).jsonObject
            listOf(
                "baby_client_uuid" to "baby",
                "custom_item_client_uuid" to "custom_item",
            ).forEach { (payloadKey, entityType) ->
                payload[payloadKey]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { clientUuid ->
                        if (entityType to clientUuid !in knownEntities) {
                            throw SyncHttpException(
                                statusCode = 409,
                                responseBody =
                                    """{"detail":"${draft.root.type} $payloadKey does not exist"}""",
                            )
                        }
                    }
            }
        }
        operationOrder += "stage:${draft.root.type}"
        syncOrder += "stage:${draft.root.type}"
        stagedBundles += draft
        return BundleStageStatus(
            bundleId = draft.bundleId,
            status = stageBundleStatus,
            missingMedia = stageBundleMissingMedia
                ?: draft.media.filter { it.deletedAt == null }.map { it.clientUuid },
            stagedMedia = stageBundleStagedMedia,
        )
    }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus {
        putBundleMediaFailure?.let { throw it }
        onPutBundleMedia?.invoke(clientUuid)
        operationOrder += "put_bundle_media:$clientUuid"
        bundleMediaUploads += bundleId to clientUuid
        return BundleStageStatus(
            bundleId = bundleId,
            status = "staging",
            stagedMedia = listOf(clientUuid),
        )
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
        val stagedDraft = stagedBundles.lastOrNull { it.bundleId == bundleId }
        failCommitRootTypeOnce
            ?.takeIf { (rootType, _) -> stagedDraft?.root?.type == rootType }
            ?.let { (_, failure) ->
                failCommitRootTypeOnce = null
                throw failure
            }
        commitBundleFailure?.let { throw it }
        committedBundles += bundleId
        stagedDraft?.let { draft ->
            knownEntities += draft.root.type to draft.root.clientUuid
            draft.media.forEach { knownEntities += it.type to it.clientUuid }
        }
        afterCommit?.invoke()
        val recordAuthors = nextCommitRecordAuthors ?: stagedBundles
            .lastOrNull { it.bundleId == bundleId }
            ?.root
            ?.takeIf { it.type == "record" }
            ?.let { root ->
                val payload = Json.parseToJsonElement(root.payloadJson).jsonObject
                listOf(
                    CanonicalRecordAuthor(
                        clientUuid = root.clientUuid,
                        createdByMembershipId = payload["created_by_membership_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: session.membershipId,
                    ),
                )
            }
            .orEmpty()
        return BundleCommitResult(
            bundleId = bundleId,
            status = "committed",
            applied = 1,
            cursor = session.pullCursor,
            recordAuthors = recordAuthors,
        )
    }
}

internal class MemorySyncPreferences(
    initial: SyncSession,
    blockFirstSecretMigration: Boolean = false,
) : SyncPreferences {
    private val state = MutableStateFlow(initial)
    private val endpointState = MutableStateFlow<TrustedEndpointProfile?>(null)
    private val memberDirectoryState = MutableStateFlow<List<FamilyMember>>(emptyList())
    private val lastHealthyState = MutableStateFlow<Long?>(null)
    private val pendingMemberState = MutableStateFlow<PendingMemberLogin?>(null)
    private val disasterRestoreState = MutableStateFlow<DisasterRestoreCheckpoint?>(null)
    private var memberPendingSecret = ""
    private var disasterRestoreRecoveryToken = ""
    private var disasterRestoreRequestIds: DisasterRestoreRequestIds? = null
    private var createRequestId: String? = null
    private var ownerLoginRequestId: String? = null
    private var refreshRequestId: String? = null
    private val shouldBlockSecretMigration = AtomicBoolean(blockFirstSecretMigration)
    val secretMigrationStarted = CompletableDeferred<Unit>()
    val releaseSecretMigration = CompletableDeferred<Unit>()
    var saveSessionCalls = 0
    var failSaveSessionAttempts = 0
    var failUpdateCursorAttempts = 0
    var clearCreateRequestIdFailure: Throwable? = null
    var clearCreateRequestIdCalls = 0
    var clearOwnerLoginRequestIdCalls = 0
    var pendingDeviceRemovalClear = false
    var pendingMembershipDeletionClear = false
    var pendingFamilyDeletionClear = false
    private var pendingReplicaResetPrevious: SyncSession? = null
    private var pendingReplicaResetSession: SyncSession? = null
    val membershipDeletionClearCompleted = CompletableDeferred<Unit>()
    val familyDeletionClearCompleted = CompletableDeferred<Unit>()
    override val session: Flow<SyncSession> = state
    override val verifiedEndpoint: Flow<TrustedEndpointProfile?> = endpointState
    override val familyMemberDirectory: Flow<List<FamilyMember>> = memberDirectoryState
    override val lastServerHealthyAt: Flow<Long?> = lastHealthyState
    override val pendingMemberLogin: Flow<PendingMemberLogin?> = pendingMemberState
    override val disasterRestoreCheckpoint: Flow<DisasterRestoreCheckpoint?> =
        disasterRestoreState

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) {
        endpointState.value = endpoint
    }

    override suspend fun forgetEndpoint() {
        endpointState.value = null
    }

    override suspend fun saveFamilyMemberDirectory(members: List<FamilyMember>) {
        memberDirectoryState.value = members.map {
            it.copy(devices = null)
        }
    }

    override suspend fun clearFamilyMemberDirectory() {
        memberDirectoryState.value = emptyList()
    }

    override suspend fun saveLastServerHealthyAt(atMillis: Long) {
        lastHealthyState.value = atMillis
    }

    fun current(): SyncSession = state.value

    fun trustCurrentEndpointForTest() {
        if (endpointState.value != null) return
        val origin = state.value.baseUrl.takeIf(String::isNotBlank) ?: return
        endpointState.value = TrustedEndpointProfile.systemPki(origin)
    }

    override suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
        clearSessionIfServerChanged: Boolean,
    ) {
        val n = config.withNormalized()
        val prev = state.value
        var next = prev.copy(
            serverHost = n.host,
            serverPort = n.port,
            serverScheme = n.scheme,
        )
        if (
            clearSessionIfServerChanged &&
            prev.baseUrl.isNotBlank() &&
            prev.baseUrl != n.baseUrl &&
            n.baseUrl.isNotBlank()
        ) {
            next = next.copy(
                familyId = "",
                accessToken = "",
                refreshToken = "",
                accessExpiresAtEpochSeconds = 0,
                reauthRequired = false,
                role = FamilyRole.None,
                pullCursor = 0,
                pullGeneration = "",
                lastSuccessAt = null,
                familyName = null,
                membershipId = "",
                pendingCreatorAcknowledgements = emptySet(),
            )
            pendingMemberState.value = null
            memberPendingSecret = ""
            memberDirectoryState.value = emptyList()
        }
        state.value = next
    }

    override suspend fun saveSession(session: SyncSession) {
        saveSessionCalls += 1
        if (failSaveSessionAttempts > 0) {
            failSaveSessionAttempts--
            error("session persistence interrupted")
        }
        createRequestId = null
        ownerLoginRequestId = null
        refreshRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        val previous = state.value
        if (previous.familyId != session.familyId) {
            memberDirectoryState.value = emptyList()
        }
        state.value = session.copy(
            pendingCreatorAcknowledgements = if (previous.familyId == session.familyId) {
                previous.pendingCreatorAcknowledgements
            } else {
                emptySet()
            },
        )
    }

    override suspend fun saveReconnectedSession(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
    ) {
        saveSession(session)
        endpointState.value = endpoint
        memberDirectoryState.value = emptyList()
    }

    override suspend fun saveSessionPendingReplicaReset(
        session: SyncSession,
        previous: SyncSession,
    ) {
        saveSessionCalls += 1
        createRequestId = null
        ownerLoginRequestId = null
        refreshRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        pendingReplicaResetPrevious = previous
        pendingReplicaResetSession = session
        state.value = session.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
    }

    override suspend fun pendingReplicaResetPrevious(): SyncSession? =
        pendingReplicaResetPrevious

    override suspend fun completePendingReplicaReset() {
        val session = pendingReplicaResetSession ?: return
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        state.value = session
    }

    override suspend fun recoverPendingCredentialClear() {
        if (!shouldBlockSecretMigration.compareAndSet(true, false)) return
        secretMigrationStarted.complete(Unit)
        releaseSecretMigration.await()
        state.value = state.value.copy(accessToken = "")
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        if (failUpdateCursorAttempts > 0) {
            failUpdateCursorAttempts--
            error("cursor update failed")
        }
        state.value = state.value.copy(
            pullCursor = cursor,
            pullGeneration = generation,
        )
    }

    override suspend fun updatePullCheckpoint(
        cursor: Long,
        generation: String,
        familyName: String?,
    ) {
        val current = state.value
        state.value = current.copy(
            pullCursor = cursor,
            pullGeneration = generation,
            familyName = normalizeFamilyNameForWire(familyName),
        )
    }

    override suspend fun updateCreatorAcknowledgements(
        add: Set<CreatorAcknowledgementRef>,
        remove: Set<CreatorAcknowledgementRef>,
    ) {
        state.value = state.value.copy(
            pendingCreatorAcknowledgements =
                (state.value.pendingCreatorAcknowledgements + add) - remove,
        )
    }

    override suspend fun markSuccess(atMillis: Long) {
        state.value = state.value.copy(lastSuccessAt = atMillis)
    }

    override suspend fun ensureDeviceId(): String {
        if (state.value.deviceId.isBlank()) {
            state.value = state.value.copy(deviceId = "test-device")
        }
        return state.value.deviceId
    }

    override suspend fun ensureCreateRequestId(): String =
        createRequestId ?: "77777777-7777-7777-7777-777777777777".also {
            createRequestId = it
        }

    override suspend fun ensureOwnerLoginRequestId(): String =
        ownerLoginRequestId ?: "88888888-8888-8888-8888-888888888888".also {
            ownerLoginRequestId = it
        }

    override suspend fun clearOwnerLoginRequestId() {
        clearOwnerLoginRequestIdCalls += 1
        ownerLoginRequestId = null
    }

    override suspend fun ensureRefreshRequestId(): String =
        refreshRequestId ?: UUID.randomUUID().toString().also {
            refreshRequestId = it
        }

    override suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
    ) {
        memberPendingSecret = receipt.pendingSecret
        pendingMemberState.value = PendingMemberLogin(
            requestId = receipt.requestId,
            displayName = displayName,
            deviceName = deviceName,
            expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
        )
    }

    override suspend fun pendingMemberSecret(): String = memberPendingSecret

    fun dropPendingMemberSecretForTest() {
        memberPendingSecret = ""
    }

    override suspend fun clearPendingMemberLogin() {
        pendingMemberState.value = null
        memberPendingSecret = ""
    }

    override suspend fun saveDisasterRestoreCheckpoint(
        checkpoint: DisasterRestoreCheckpoint,
        recoveryToken: String,
    ) {
        disasterRestoreState.value = checkpoint
        disasterRestoreRecoveryToken = recoveryToken
        disasterRestoreRequestIds = DisasterRestoreRequestIds(
            start = checkpoint.startRequestId,
            manifest = checkpoint.manifestRequestId,
            commit = checkpoint.commitRequestId,
        )
    }

    override suspend fun ensureDisasterRestoreRequestIds(): DisasterRestoreRequestIds =
        disasterRestoreRequestIds ?: DisasterRestoreRequestIds(
            start = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            manifest = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            commit = "cccccccc-cccc-cccc-cccc-cccccccccccc",
        ).also { disasterRestoreRequestIds = it }

    override suspend fun disasterRestoreToken(): String = disasterRestoreRecoveryToken

    override suspend fun clearDisasterRestoreCheckpoint() {
        disasterRestoreState.value = null
        disasterRestoreRecoveryToken = ""
        disasterRestoreRequestIds = null
        refreshRequestId = null
    }

    override suspend fun clearCreateRequestId() {
        clearCreateRequestIdCalls += 1
        clearCreateRequestIdFailure?.let { throw it }
        createRequestId = null
    }

    override suspend fun clearAllLocalSyncConfig() {
        state.value = SyncSession()
        memberDirectoryState.value = emptyList()
        pendingMemberState.value = null
        memberPendingSecret = ""
        disasterRestoreState.value = null
        disasterRestoreRecoveryToken = ""
        disasterRestoreRequestIds = null
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
    }

    override suspend fun clearDeviceCredentialsForReauth() {
        refreshRequestId = null
        state.value = state.value.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
    }

    override suspend fun markPendingDeviceRemovalClear() {
        pendingDeviceRemovalClear = true
    }

    override suspend fun hasPendingDeviceRemovalClear(): Boolean = pendingDeviceRemovalClear

    override suspend fun clearPendingDeviceRemovalClear() {
        pendingDeviceRemovalClear = false
    }

    override suspend fun markPendingMembershipDeletionClear() {
        pendingMembershipDeletionClear = true
    }

    override suspend fun hasPendingMembershipDeletionClear(): Boolean =
        pendingMembershipDeletionClear

    override suspend fun clearPendingMembershipDeletionClear() {
        pendingMembershipDeletionClear = false
        membershipDeletionClearCompleted.complete(Unit)
    }

    override suspend fun markPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = true
    }

    override suspend fun hasPendingFamilyDeletionClear(): Boolean = pendingFamilyDeletionClear

    override suspend fun clearPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = false
        familyDeletionClearCompleted.complete(Unit)
    }
}

internal class MutablePolicyClock(var now: Long = 1_000) : PolicyClock {
    override fun nowMillis(): Long = now
}

internal class TestForegroundState(
    private var foreground: Boolean = true,
) : ForegroundState {
    override fun isForeground(): Boolean = foreground
    override fun setForeground(value: Boolean) {
        foreground = value
    }
}

internal class TestRemovedDeviceLocalClearGate : RemovedDeviceLocalClearGate {
    var calls = 0
    val failures = ArrayDeque<Throwable>()
    val firstCall = CompletableDeferred<Unit>()
    var release: CompletableDeferred<Unit>? = null

    override suspend fun clearAllLocalFamilyData() {
        calls++
        firstCall.complete(Unit)
        release?.await()
        failures.removeFirstOrNull()?.let { throw it }
    }
}

internal open class TestMediaFileStore : SyncMediaFileStore {
    val deleted = mutableListOf<String>()
    val existing = linkedSetOf<String>()
    val missing = linkedSetOf<String>()
    val sweepCalls = mutableListOf<Pair<LocalDataClearScope, Set<String>>>()
    val deleteFailures = ArrayDeque<Throwable>()
    var afterInspect: (suspend () -> Unit)? = null
    var afterPrepareUpload: (suspend () -> Unit)? = null
    var afterSaveDownloaded: (suspend () -> Unit)? = null

    override suspend fun inspect(localUri: String): LocalMediaInfo? {
        afterInspect?.also { afterInspect = null }?.invoke()
        if (localUri in missing) return null
        return LocalMediaInfo(byteSize = 12, mime = "image/jpeg", width = 10, height = 10)
    }

    override suspend fun prepareUpload(localUri: String): PreparedMedia {
        afterPrepareUpload?.also { afterPrepareUpload = null }?.invoke()
        return testPreparedMedia(byteArrayOf(1))
    }

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String {
        require(bytes.isNotEmpty()) { "downloaded media must not be empty" }
        afterSaveDownloaded?.also { afterSaveDownloaded = null }?.invoke()
        return "downloaded/$clientUuid"
    }

    override open suspend fun delete(localUri: String) {
        deleted += localUri
        deleteFailures.removeFirstOrNull()?.let { throw it }
        existing -= localUri
    }

    override suspend fun sweepUnreferenced(
        scope: LocalDataClearScope,
        retainedLocalUris: Set<String>,
    ) {
        sweepCalls += scope to retainedLocalUris
        val roots = when (scope) {
            LocalDataClearScope.RecordsOnly -> setOf("record-media/")
            LocalDataClearScope.AllLocalData -> setOf("record-media/", "baby_avatars/")
        }
        val reclaim = existing.filter { path ->
            roots.any(path::startsWith) && path !in retainedLocalUris
        }
        reclaim.forEach {
            deleted += it
            existing -= it
        }
    }
}

internal fun realPortClearWorkflow(
    clearRoom: suspend () -> Unit = {},
    finishCommitted: suspend () -> Unit = {},
): LocalClearWorkflow = object : LocalClearWorkflow {
    override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
    override suspend fun clearRoom() = clearRoom.invoke()
    override suspend fun finishCommitted() = finishCommitted.invoke()
}

internal class RecordingAppUpdateInstaller(
    private val canInstall: Boolean = true,
    private val installFailure: Throwable? = null,
) : AppUpdateInstaller {
    data class InstallCall(
        val expectedPackageName: String,
        val fileExistedAtCall: Boolean,
        val byteSize: Long,
    )

    val installCalls = mutableListOf<InstallCall>()

    override fun canRequestPackageInstalls(): Boolean = canInstall

    override fun installFromFile(apkFile: java.io.File, expectedPackageName: String) {
        installCalls += InstallCall(
            expectedPackageName = expectedPackageName,
            fileExistedAtCall = apkFile.isFile,
            byteSize = apkFile.length(),
        )
        installFailure?.let { throw it }
    }

    override fun createManageUnknownSourcesIntent(): android.content.Intent =
        android.content.Intent()
}

/**
 * Fake archive identity for JVM install tests (no PackageManager).
 * Defaults match com.lezi.babylog versionCode 7 with a shared test signer.
 */
internal class FakeAppUpdateApkIdentityReader(
    var packageName: String = "com.lezi.babylog",
    var versionCode: Int = 7,
    var archiveCerts: Set<String> = setOf(TEST_APP_UPDATE_CERT_SHA256),
    var installedCerts: Set<String> = setOf(TEST_APP_UPDATE_CERT_SHA256),
    var unreadable: Boolean = false,
) : AppUpdateApkIdentityReader {
    override fun readArchive(apkFile: java.io.File): StagedApkIdentity? {
        if (unreadable || !apkFile.isFile) return null
        return StagedApkIdentity(
            packageName = packageName,
            versionCode = versionCode,
            signingCertSha256 = archiveCerts,
        )
    }

    override fun installedSigningCertSha256(): Set<String> = installedCerts
}

internal const val TEST_APP_UPDATE_CERT_SHA256 =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

internal class TestPendingPublishDao(
    private val count: suspend () -> Int,
) : PendingPublishDao {
    override fun observeCount(): Flow<Int> = flow { emit(count()) }
}

internal class SyncRig(
    session: SyncSession,
    carePlanApplied: suspend (List<String>) -> Unit = {},
    syncBackend: SyncBackend? = null,
    syncPreferences: MemorySyncPreferences? = null,
    setupProbe: SetupProbe = SetupProbe { _, _ -> SetupProbeResult.Failed.Unreachable },
    removedDeviceLocalClearGate: RemovedDeviceLocalClearGate = NoOpRemovedDeviceLocalClearGate(),
    clientAppVersion: ClientAppVersion = ClientAppVersion.FALLBACK,
    appUpdateInstaller: AppUpdateInstaller = NoOpAppUpdateInstaller,
    apkIdentityReader: AppUpdateApkIdentityReader = FakeAppUpdateApkIdentityReader(),
    appUpdateCacheDir: java.io.File = createTempDir(prefix = "lezi-app-update-rig"),
    allowHistoricalMutableRootEvidence: Boolean = true,
) {
    val backend = RecordingSyncBackend()
    val preferences = (syncPreferences ?: MemorySyncPreferences(session)).also {
        it.trustCurrentEndpointForTest()
    }
    val records = MemoryRecordDao()
    val carePlans = MemoryCarePlanDao()
    val fulfillmentCandidates = MemoryFulfillmentCandidateDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val customItems = MemoryCustomItemDao()
    val pendingPublish = TestPendingPublishDao(
        count = {
            babies.listPendingSync().size +
                records.listPendingSync().size +
                carePlans.listPendingSync().size +
                fulfillmentCandidates.listPendingSync().size +
                media.listPendingSync().size +
                customItems.listPendingSync().size
        },
    )
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val fulfillmentAuthoritySettlement =
        com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement(
            carePlanDao = carePlans,
            fulfillmentCandidateDao = fulfillmentCandidates,
            transactionRunner = transactions,
        )
    val mediaFileCleanup = ReferenceAwareMediaFileCleanup(
        mediaDao = media,
        mediaReferenceDao = MemoryMediaReferenceDao(),
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
        pathGate = MediaLocalPathGate(),
    )
    val pendingReplicaCleanup = TestPendingReplicaCleanupStore()
    val pendingDomainRecovery = TestLocalClearRecoveryGate()
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    val wakeObservations = MemoryWakeObservationDao()
    val conflictSummaries = MemoryConflictSummaryDao()
    val conflictDetails = MemoryConflictSnapshotCacheDao()
    val clock = MutablePolicyClock()
    val foreground = TestForegroundState()
    val port = RealSyncPort(
        backend = syncBackend ?: backend,
        preferences = preferences,
        setupProbe = setupProbe,
        foregroundSyncGate = ForegroundSyncGate(),
        pendingPublishDao = pendingPublish,
        recordDao = records,
        carePlanDao = carePlans,
        babyDao = babies,
        mediaDao = media,
        customItemDao = customItems,
        familyDao = families,
        clock = clock,
        foregroundState = foreground,
        mediaFiles = mediaFiles,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactions,
        pendingReplicaCleanupStore = pendingReplicaCleanup,
        localClearRecoveryGate = pendingDomainRecovery,
        removedDeviceLocalClearGate = removedDeviceLocalClearGate,
        carePlanAppliedListener = CarePlanFamilyAppliedListener { carePlanApplied(it) },
        fulfillmentCandidateDao = fulfillmentCandidates,
        fulfillmentAuthoritySettlement = fulfillmentAuthoritySettlement,
        wakeObservationDao = wakeObservations,
        conflictSummaryDao = conflictSummaries,
        conflictSnapshotCacheDao = conflictDetails,
        clientAppVersion = clientAppVersion,
        appUpdateInstaller = appUpdateInstaller,
        apkIdentityReader = apkIdentityReader,
        appUpdateCacheDir = appUpdateCacheDir,
        allowHistoricalMutableRootEvidence = allowHistoricalMutableRootEvidence,
    )

    suspend fun awaitStartupRecovery() {
        pendingReplicaCleanup.firstLoad.await()
    }
}

internal class TestLocalClearRecoveryGate : LocalClearRecoveryGate {
    var calls = 0
    var resumed: LocalDataClearScope? = null
    val failures = ArrayDeque<Throwable>()

    override suspend fun recoverPendingLocalClear(): LocalDataClearScope? {
        calls += 1
        failures.removeFirstOrNull()?.let { throw it }
        return resumed
    }
}

internal class TestPendingReplicaCleanupStore :
    com.lezi.babylog.core.database.PendingReplicaCleanupStore {
    var pending: com.lezi.babylog.core.database.PendingReplicaCleanup? = null
    val loadFailures = ArrayDeque<Throwable>()
    val firstLoad = CompletableDeferred<Unit>()

    override suspend fun load(): com.lezi.babylog.core.database.PendingReplicaCleanup? {
        val failure = loadFailures.removeFirstOrNull()
        val current = pending
        firstLoad.complete(Unit)
        failure?.let { throw it }
        return current
    }

    override suspend fun stage(
        pending: com.lezi.babylog.core.database.PendingReplicaCleanup,
    ) {
        check(this.pending == null)
        this.pending = pending
    }

    override suspend fun delete() {
        pending = null
    }
}

internal class MemoryFulfillmentCandidateDao : FulfillmentCandidateDao {
    private val rows = MutableStateFlow<List<FulfillmentCandidateEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: FulfillmentCandidateEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot {
            it.id == id || it.clientUuid == entity.clientUuid
        } + entity.copy(id = id)
        return id
    }

    override suspend fun get(id: Long): FulfillmentCandidateEntity? =
        rows.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity? =
        rows.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.carePlanClientUuid == carePlanClientUuid }.sortedBy { it.id }

    override suspend fun listForRecord(recordClientUuid: String): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.recordClientUuid == recordClientUuid }.sortedBy { it.id }

    override suspend fun listAllIncludingDeleted(): List<FulfillmentCandidateEntity> = rows.value

    override suspend fun listPendingSync(): List<FulfillmentCandidateEntity> =
        rows.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun listConflictNotAdoptedRecordUuids(): List<String> =
        rows.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .map { it.recordClientUuid }

    override suspend fun listConflictNotAdopted(): List<FulfillmentCandidateEntity> =
        rows.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .sortedWith(compareBy({ it.confirmedAt }, { it.clientUuid }))

    override suspend fun listConflictNotAdoptedForCarePlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidateEntity> =
        listConflictNotAdopted().filter { it.carePlanClientUuid == carePlanClientUuid }

    override fun observeConflictNotAdoptedRecordUuids(): Flow<List<String>> =
        rows.map { list ->
            list.filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }.map { it.recordClientUuid }
        }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.value.size
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(candidate: FulfillmentCandidateEntity): Long = seed(candidate)

    override suspend fun update(candidate: FulfillmentCandidateEntity) {
        rows.value = rows.value.map { if (it.id == candidate.id) candidate else it }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryCarePlanDao : CarePlanDao {
    private val rows = MutableStateFlow<List<CarePlanEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: CarePlanEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id || it.clientUuid == entity.clientUuid } +
            entity.copy(id = id)
        return id
    }

    override fun observeDayPending(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override fun observeTodayPending(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                (
                    it.scheduledAt < nowMillis ||
                        (it.scheduledAt >= dayStart && it.scheduledAt < dayEnd)
                    )
        }.sortedBy { it.scheduledAt }
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = rows.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override suspend fun listOpenFuture(babyId: Long, nowMillis: Long): List<CarePlanEntity> =
        rows.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun listAllOpenFuture(nowMillis: Long): List<CarePlanEntity> =
        rows.value.filter {
            it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun get(id: Long): CarePlanEntity? =
        rows.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? =
        rows.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listAllIncludingDeleted(): List<CarePlanEntity> = rows.value

    override suspend fun listPendingSync(): List<CarePlanEntity> =
        rows.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.value.size
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(plan: CarePlanEntity): Long = seed(plan)

    override suspend fun update(plan: CarePlanEntity) {
        rows.value = rows.value.map { if (it.id == plan.id) plan else it }
    }

    override suspend fun updateSystemCalendarProjection(
        clientUuid: String,
        eventId: String?,
        reminderReady: Boolean,
        pending: Boolean,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarEventId = eventId,
                    systemCalendarReminderReady = reminderReady,
                    systemCalendarProjectionPending = pending,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateSystemCalendarProjectionEnabled(
        clientUuid: String,
        enabled: Boolean,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarProjectionEnabled = enabled,
                )
            } else {
                it
            }
        }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryCustomItemDao : CustomItemDao {
    private val rows = mutableListOf<CustomItemEntity>()
    private val ids = AtomicLong(1)

    fun seed(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id || it.clientUuid == item.clientUuid }
        rows += item.copy(id = id)
        return id
    }

    fun get(clientUuid: String): CustomItemEntity? =
        rows.firstOrNull { it.clientUuid == clientUuid }

    override fun observeAll() = MutableStateFlow(rows.filter { it.deletedAt == null })

    override suspend fun listAll(): List<CustomItemEntity> =
        rows.filter { it.deletedAt == null }

    override suspend fun listAllIncludingDeleted(): List<CustomItemEntity> = rows.toList()

    override suspend fun getById(id: Long): CustomItemEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CustomItemEntity? =
        rows.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listPendingSync(): List<CustomItemEntity> =
        rows.filter { it.syncDirty }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = rows.size
        rows.removeAll {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.size
    }

    override suspend fun markAllPendingSync() {
        rows.replaceAll { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id || it.clientUuid == item.clientUuid }
        rows += item.copy(id = id)
        return id
    }

    override suspend fun update(item: CustomItemEntity) {
        rows.replaceAll { if (it.id == item.id) item else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.replaceAll {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class RecordingTransactionRunner : DatabaseTransactionRunner {
    var runCount = 0
    var depth = 0
    var maxDepth = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        depth += 1
        maxDepth = maxOf(maxDepth, depth)
        return try {
            block()
        } finally {
            depth -= 1
        }
    }
}

internal fun joinedSession(familyId: String) = SyncSession(
    familyId = familyId,
    accessToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    pullGeneration = "current-generation",
    membershipId = "membership-a",
    serverHost = "192.168.1.20",
    serverPort = 8787,
    familyName = "乐乐一家",
)

internal fun sampleAppUpdateMetadata(
    versionCode: Int,
    versionName: String,
    releaseNotes: String? = null,
    sha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    minSupportedVersionCode: Int = 1,
) = AppUpdateMetadata(
    packageName = "com.lezi.babylog",
    versionCode = versionCode,
    versionName = versionName,
    minSupportedVersionCode = minSupportedVersionCode,
    sha256 = sha256,
    releaseNotes = releaseNotes,
)

internal fun SyncSession.expectedMediaReceipt(clientUuid: String): String {
    val namespace = UUID.nameUUIDFromBytes(
        "${baseUrl.trimEnd('/')}\n$familyId".toByteArray(Charsets.UTF_8),
    )
    return "lezi-sync:$namespace:$clientUuid"
}

internal fun testMediaUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

internal fun pendingReplicaCleanup(
    familyId: String = "family-a",
    mediaClientUuids: Set<String> = emptySet(),
    localMediaPaths: Set<String> = emptySet(),
) = com.lezi.babylog.core.database.PendingReplicaCleanup(
    scope = LocalDataClearScope.RecordsOnly,
    familyId = familyId,
    pullGeneration = "known-generation",
    mediaClientUuids = mediaClientUuids,
    localMediaPaths = localMediaPaths,
)

internal class MemoryBabyDao : BabyDao {
    private val rows = MutableStateFlow<List<BabyEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: BabyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<BabyEntity>> =
        rows.map { values -> values.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<BabyEntity> = rows.value.filter { it.deletedAt == null }
    override suspend fun listFamilyAuthority(): List<BabyEntity> =
        rows.value.filter { it.deletedAt == null && it.familyAuthority }
    override suspend fun get(id: Long): BabyEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): BabyEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): BabyEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<BabyEntity> = rows.value

    override suspend fun listPendingSync(): List<BabyEntity> =
        rows.value.filter(BabyEntity::syncDirty).sortedBy(BabyEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.value.size
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun clearFamilyAuthority() {
        rows.value = rows.value.map { it.copy(familyAuthority = false) }
    }

    override suspend fun countByNickname(nickname: String, excludeId: Long): Int =
        rows.value.count {
            it.deletedAt == null &&
                it.nickname.trim() == nickname.trim() &&
                (excludeId < 0 || it.id != excludeId)
        }

    override suspend fun countActive(): Int = rows.value.count { it.deletedAt == null }

    override suspend fun upsert(baby: BabyEntity): Long = seed(baby)

    override suspend fun update(baby: BabyEntity) {
        rows.value = rows.value.map { if (it.id == baby.id) baby else it }
    }

    override suspend fun updateLocalTheme(id: Long, themeColorArgb: Int) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(themeColorArgb = themeColorArgb) else it
        }
    }

    override suspend fun updateLocalSortOrder(id: Long, sortOrder: Int) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(sortOrder = sortOrder) else it
        }
    }

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    avatarMediaUuid = avatarMediaUuid,
                    avatarPath = avatarPath,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (
                it.id == id &&
                it.updatedAt == expectedUpdatedAt &&
                it.avatarPath == expectedAvatarPath
            ) {
                changed = 1
                it.copy(avatarMediaUuid = avatarMediaUuid, syncDirty = true)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (it.id == id && it.avatarMediaUuid == expectedAvatarMediaUuid) {
                changed = 1
                it.copy(avatarPath = avatarPath)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryRecordDao : RecordDao {
    private val rows = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: RecordEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = rows.map {
        it.filter { record ->
            record.babyId == babyId &&
                record.deletedAt == null &&
                record.timestamp in startInclusive until endExclusive
        }.sortedByDescending(RecordEntity::timestamp)
    }

    override fun observeDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = observeRange(babyId, startInclusive, endExclusive)

    override suspend fun listDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun get(id: Long): RecordEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): RecordEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<RecordEntity> = rows.value

    override suspend fun listPendingSync(): List<RecordEntity> =
        rows.value.filter(RecordEntity::syncDirty).sortedBy(RecordEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.value.size
    }

    override suspend fun mergeCanonicalAuthor(
        clientUuid: String,
        expectedUpdatedAt: Long,
        membershipId: String,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (
                it.clientUuid == clientUuid &&
                it.updatedAt == expectedUpdatedAt &&
                membershipId.isNotBlank()
            ) {
                changed = 1
                it.copy(createdByMembershipId = membershipId)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    /** Optional wake table for open-sleep filtering (ticket 06). */
    var wakeObservationRows:
        (() -> List<com.lezi.babylog.core.database.causal.WakeObservationEntity>)? = null

    private fun RecordEntity.isTrulyOpenSleep(): Boolean {
        if (type != "sleep" || deletedAt != null || endTimestamp != null) return false
        if (effectiveWakeObservationClientUuid != null) return false
        val wakes = wakeObservationRows?.invoke().orEmpty()
        return wakes.none { wake ->
            wake.sleepRecordClientUuid == clientUuid &&
                wake.deletedAt == null &&
                !wake.withdrawn &&
                wake.wakeTimestamp >= timestamp
        }
    }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        listOpenSleeps(babyId).firstOrNull()

    override suspend fun listOpenSleeps(babyId: Long): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId && it.isTrulyOpenSleep()
        }.sortedWith(
            compareByDescending<RecordEntity> { it.timestamp }
                .thenByDescending { it.clientUuid },
        )

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        rows.map {
            it.filter { record ->
                record.babyId == babyId && record.isTrulyOpenSleep()
            }.maxWithOrNull(
                compareBy<RecordEntity> { it.timestamp }.thenBy { it.clientUuid },
            )
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        rows.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedByDescending(RecordEntity::timestamp)

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            (
                it.note.orEmpty().contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.payloadJson.contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.type in matchingTypeKeys
                )
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedBy(RecordEntity::timestamp)

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.type == type
        }.sortedBy(RecordEntity::timestamp)

    override suspend fun upsert(record: RecordEntity): Long = seed(record)

    override suspend fun update(record: RecordEntity) {
        rows.value = rows.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(updatedAt = deletedAt, deletedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

internal class MemoryMediaDao : MediaAssetDao {
    private val rows = mutableListOf<MediaAssetEntity>()
    private val ids = AtomicLong(1)
    var failNextTombstoneDelete: Boolean = false

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long = seed(asset)

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy(MediaAssetEntity::id)

    override suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        rows.filter { it.carePlanId == carePlanId }

    override suspend fun listActiveForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        rows.filter { it.carePlanId == carePlanId && it.deletedAt == null }.sortedBy(MediaAssetEntity::id)

    override suspend fun listActiveForWakeObservation(
        wakeObservationId: Long,
    ): List<MediaAssetEntity> =
        rows.filter {
            it.wakeObservationId == wakeObservationId && it.deletedAt == null
        }.sortedBy(MediaAssetEntity::id)

    override suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity? =
        rows.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .maxWithOrNull(compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id })

    override suspend fun listActiveAvatarsForBaby(babyId: Long): List<MediaAssetEntity> =
        rows.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .sortedBy(MediaAssetEntity::id)

    override suspend fun listAllIncludingDeleted(): List<MediaAssetEntity> =
        rows.sortedBy(MediaAssetEntity::id)

    override suspend fun listPendingSync(): List<MediaAssetEntity> =
        rows.filter(MediaAssetEntity::syncDirty).sortedBy(MediaAssetEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun deleteTombstoneRevision(clientUuid: String, updatedAt: Long): Int {
        if (failNextTombstoneDelete) {
            failNextTombstoneDelete = false
            return 0
        }
        val before = rows.size
        rows.removeAll {
            it.clientUuid == clientUuid && it.updatedAt == updatedAt && it.deletedAt != null
        }
        return before - rows.size
    }

    override suspend fun deleteExactRevision(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
    ): Int {
        val before = rows.size
        rows.removeAll {
            it.matchesPublishedRevision(
                expectedClientUuid = clientUuid,
                expectedUpdatedAt = expectedUpdatedAt,
                expectedLocalUri = expectedLocalUri,
                expectedDeletedAt = expectedDeletedAt,
            )
        }
        return before - rows.size
    }

    override suspend fun listMissingLocalBytes(): List<MediaAssetEntity> =
        rows.filter {
            it.deletedAt == null && it.remoteUri != null && it.localUri.isEmpty()
        }.sortedBy(MediaAssetEntity::id)

    override suspend fun getByClientUuid(uuid: String): MediaAssetEntity? =
        rows.find { it.clientUuid == uuid }

    override suspend fun countActiveReferences(localUri: String): Int =
        rows.count { it.localUri == localUri && it.deletedAt == null }

    override suspend fun listPendingFileCleanupClientUuids(): List<String> =
        rows.filter { it.deletedAt != null && it.localUri.isNotBlank() }
            .sortedBy(MediaAssetEntity::id)
            .map(MediaAssetEntity::clientUuid)

    override suspend fun update(asset: MediaAssetEntity) {
        rows.replaceAll { if (it.id == asset.id) asset else it }
    }

    override suspend fun mergePreparedMetadata(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        byteSize: Long,
    ): Int {
        var changed = 0
        rows.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(mime = mime, width = width, height = height, byteSize = byteSize)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun writeCommitReceipt(
        clientUuid: String,
        expectedUpdatedAt: Long,
        expectedLocalUri: String,
        expectedDeletedAt: Long?,
        remoteUri: String,
    ): Int {
        var changed = 0
        rows.replaceAll {
            if (
                it.matchesPublishedRevision(
                    expectedClientUuid = clientUuid,
                    expectedUpdatedAt = expectedUpdatedAt,
                    expectedLocalUri = expectedLocalUri,
                    expectedDeletedAt = expectedDeletedAt,
                )
            ) {
                changed = 1
                it.copy(remoteUri = remoteUri)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun clearRemoteUris() {
        rows.replaceAll { it.copy(remoteUri = null, syncDirty = true) }
    }

    override suspend fun deleteLogMedia() {
        rows.removeAll { it.kind == "log" }
    }

    override suspend fun deleteByClientUuids(clientUuids: List<String>) {
        rows.removeAll { it.clientUuid in clientUuids }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        rows.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class MemoryMediaReferenceDao : com.lezi.babylog.core.database.causal.MediaReferenceDao {
    private val items =
        mutableListOf<com.lezi.babylog.core.database.causal.MediaReferenceEntity>()

    override suspend fun upsert(ref: com.lezi.babylog.core.database.causal.MediaReferenceEntity) {
        items.removeAll {
            it.mediaUuid == ref.mediaUuid &&
                it.holderKind == ref.holderKind &&
                it.holderId == ref.holderId
        }
        items += ref
    }

    override suspend fun remove(mediaUuid: String, holderKind: String, holderId: String) {
        items.removeAll {
            it.mediaUuid == mediaUuid && it.holderKind == holderKind && it.holderId == holderId
        }
    }

    override suspend fun listForMedia(
        mediaUuid: String,
    ): List<com.lezi.babylog.core.database.causal.MediaReferenceEntity> =
        items.filter { it.mediaUuid == mediaUuid }

    override suspend fun countHoldersForLocalUri(localUri: String): Int =
        items.count { it.localUri == localUri }

    override suspend fun countHoldersForMedia(mediaUuid: String): Int =
        items.count { it.mediaUuid == mediaUuid }

    override suspend fun deleteForMedia(mediaUuid: String) {
        items.removeAll { it.mediaUuid == mediaUuid }
    }

    override suspend fun deleteForLogAndWakeMedia() {
        items.clear()
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class MemoryFamilyDao : FamilyDao {
    private val rows = mutableListOf<FamilyEntity>()
    private val ids = AtomicLong(1)

    fun seed(entity: FamilyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun get(id: Long): FamilyEntity? = rows.find { it.id == id }
    override suspend fun listAll(): List<FamilyEntity> = rows.toList()
    override suspend fun insert(family: FamilyEntity): Long = seed(family)
    override suspend fun deleteAll() {
        rows.clear()
    }
}

internal class MemoryWakeObservationDao :
    com.lezi.babylog.core.database.causal.WakeObservationDao {
    private val items =
        mutableListOf<com.lezi.babylog.core.database.causal.WakeObservationEntity>()
    private val ids = AtomicLong(1)

    fun seed(
        entity: com.lezi.babylog.core.database.causal.WakeObservationEntity,
    ): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        items.removeAll { it.clientUuid == entity.clientUuid }
        items += entity.copy(id = id)
        return id
    }

    override suspend fun getByClientUuid(
        uuid: String,
    ): com.lezi.babylog.core.database.causal.WakeObservationEntity? =
        items.find { it.clientUuid == uuid }

    override suspend fun listForSleep(
        sleepRecordClientUuid: String,
    ): List<com.lezi.babylog.core.database.causal.WakeObservationEntity> =
        items.filter { it.sleepRecordClientUuid == sleepRecordClientUuid }

    override suspend fun listActiveForSleep(
        sleepRecordClientUuid: String,
    ): List<com.lezi.babylog.core.database.causal.WakeObservationEntity> =
        items.filter {
            it.sleepRecordClientUuid == sleepRecordClientUuid &&
                it.deletedAt == null &&
                !it.withdrawn
        }

    override suspend fun listPendingSync():
        List<com.lezi.babylog.core.database.causal.WakeObservationEntity> =
        items.filter { it.syncDirty }

    override suspend fun listOpenConflicts():
        List<com.lezi.babylog.core.database.causal.WakeObservationEntity> =
        items.filter { it.openConflictId != null }

    override suspend fun upsert(
        entity: com.lezi.babylog.core.database.causal.WakeObservationEntity,
    ): Long = seed(entity)

    override suspend fun update(
        entity: com.lezi.babylog.core.database.causal.WakeObservationEntity,
    ) {
        items.replaceAll { if (it.clientUuid == entity.clientUuid) entity else it }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class MemoryConflictSummaryDao :
    com.lezi.babylog.core.database.causal.ConflictSummaryDao {
    private val items =
        mutableListOf<com.lezi.babylog.core.database.causal.ConflictSummaryEntity>()

    override fun observeInboxProjection():
        kotlinx.coroutines.flow.Flow<
            List<com.lezi.babylog.core.database.causal.ConflictInboxProjectionRow>,
            > = kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun get(
        conflictId: String,
    ): com.lezi.babylog.core.database.causal.ConflictSummaryEntity? =
        items.find { it.conflictId == conflictId }

    override suspend fun listForRoot(
        entityType: String,
        clientUuid: String,
    ): List<com.lezi.babylog.core.database.causal.ConflictSummaryEntity> =
        items.filter { it.entityType == entityType && it.clientUuid == clientUuid }

    override suspend fun upsert(
        entity: com.lezi.babylog.core.database.causal.ConflictSummaryEntity,
    ) {
        items.removeAll { it.conflictId == entity.conflictId }
        items += entity
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class MemoryConflictSnapshotCacheDao :
    com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao {
    private val items =
        mutableListOf<com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity>()

    override suspend fun get(
        conflictId: String,
    ): com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity? =
        items.find { it.conflictId == conflictId }

    override suspend fun upsert(
        entity: com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity,
    ) {
        items.removeAll { it.conflictId == entity.conflictId }
        items += entity
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class MemorySourceRelationDao : SourceRelationDao() {
    private val relations = linkedMapOf<String, SourceRelationEntity>()
    private val members = linkedMapOf<Pair<String, String>, SourceRelationMemberEntity>()
    private val declarations = linkedMapOf<String, SourceRelationDeclarationEntity>()
    private val memberFlow = MutableStateFlow<List<SourceRelationMemberEntity>>(emptyList())

    private fun publishMembers() {
        memberFlow.value = members.values.sortedWith(
            compareBy<SourceRelationMemberEntity> {
                it.relationId
            }.thenBy { it.recordClientUuid },
        )
    }

    suspend fun seedRaw(
        relation: SourceRelationEntity,
        seededMembers: List<SourceRelationMemberEntity>,
    ) {
        upsertRelationRow(relation)
        seededMembers.forEach { upsertMemberRow(it) }
    }

    override suspend fun get(relationId: String) = relations[relationId]

    override suspend fun listAll() = relations.values.toList()

    override suspend fun upsertRelationRow(
        entity: SourceRelationEntity,
    ) {
        relations[entity.relationId] = entity
    }

    override suspend fun upsertMemberRow(
        member: SourceRelationMemberEntity,
    ) {
        members[member.relationId to member.recordClientUuid] = member
        publishMembers()
    }

    override suspend fun listMembers(relationId: String) =
        members.values.filter { it.relationId == relationId }

    override suspend fun listAllMembers() = members.values.toList()

    override fun observeAllMembers(): Flow<List<SourceRelationMemberEntity>> =
        memberFlow

    override suspend fun listMembersForRecord(recordClientUuid: String) =
        members.values.filter { it.recordClientUuid == recordClientUuid }

    override suspend fun deleteOtherMemberships(
        relationId: String,
        recordClientUuids: List<String>,
    ) {
        members.entries.removeAll { (_, member) ->
            member.recordClientUuid in recordClientUuids && member.relationId != relationId
        }
        publishMembers()
    }

    override suspend fun deleteMembersOutsideCanonicalSet(
        relationId: String,
        recordClientUuids: List<String>,
    ) {
        members.entries.removeAll { (_, member) ->
            member.relationId == relationId && member.recordClientUuid !in recordClientUuids
        }
        publishMembers()
    }

    override suspend fun upsertDeclaration(
        declaration: SourceRelationDeclarationEntity,
    ) {
        declarations[declaration.mutationId] = declaration
    }

    override suspend fun getDeclaration(mutationId: String) = declarations[mutationId]

    override suspend fun listPendingDeclarations() =
        declarations.values.filter { it.status == "pending" }

    override suspend fun deleteAllMembers() {
        members.clear()
        publishMembers()
    }

    override suspend fun deleteAllDeclarations() {
        declarations.clear()
    }

    override suspend fun deleteAll() {
        relations.clear()
    }
}
