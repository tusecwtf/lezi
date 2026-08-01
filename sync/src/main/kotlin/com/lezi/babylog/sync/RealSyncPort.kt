package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SyncStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val setupProbe: SetupProbe,
    private val foregroundSyncGate: ForegroundSyncGate,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val familyDao: FamilyDao,
    private val clock: PolicyClock,
    private val foregroundState: ForegroundState,
    private val mediaFiles: SyncMediaFileStore,
    private val mediaFileCleanup: ReferenceAwareMediaFileCleanup,
    private val transactionRunner: DatabaseTransactionRunner,
    private val pendingReplicaCleanupStore: PendingReplicaCleanupStore,
    private val localClearRecoveryGate: LocalClearRecoveryGate =
        NoOpLocalClearRecoveryGate(),
    private val removedDeviceLocalClearGate: RemovedDeviceLocalClearGate =
        NoOpRemovedDeviceLocalClearGate(),
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener =
        NoOpCarePlanFamilyAppliedListener(),
    private val familyBabyAppliedListener: FamilyBabyAuthorityAppliedListener =
        NoOpFamilyBabyAuthorityAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val clientAppVersion: ClientAppVersion = ClientAppVersion.FALLBACK,
    private val appUpdateInstaller: AppUpdateInstaller = NoOpAppUpdateInstaller,
    private val apkIdentityReader: AppUpdateApkIdentityReader =
        UnreadableAppUpdateApkIdentityReader,
    @Named("appUpdateCacheDir") private val appUpdateCacheDir: File =
        File(System.getProperty("java.io.tmpdir"), "lezi-app-update-test"),
) : SyncPort {
    private val currentStatus = MutableStateFlow(SyncStatus.Disabled)
    private val optionalAppUpdateState =
        MutableStateFlow<AppUpdateMetadata?>(null)
    private val forcedAppUpdateState =
        MutableStateFlow<ForcedAppUpdateState?>(null)
    /** Process-session "稍后" suppressions keyed by server package versionCode. */
    private val dismissedOptionalUpdateVersionCodes =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val memberLoginCheckEvents = MutableSharedFlow<MemberLoginCheckResult>(
        extraBufferCapacity = 1,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    /**
     * Serializes app-update install + staging cleanup so about-check, banner install,
     * and force overlay cannot race the same private staging path.
     */
    private val appUpdateInstallMutex = Mutex()
    private val replicaSyncEngine = ReplicaSyncEngine(
        backend = backend,
        preferences = preferences,
        outboxDao = outboxDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaDao = mediaDao,
        customItemDao = customItemDao,
        familyDao = familyDao,
        clock = clock,
        mediaFiles = mediaFiles,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactionRunner,
        carePlanAppliedListener = carePlanAppliedListener,
        familyBabyAppliedListener = familyBabyAppliedListener,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        requireRemoteAllowed = { session ->
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
            currentStatus.value = SyncStatus.Syncing
        },
    )
    private val localReplicaClearCoordinator = LocalReplicaClearCoordinator(
        barrier = syncMutex,
        preferences = preferences,
        outboxDao = outboxDao,
        babyDao = babyDao,
        mediaDao = mediaDao,
        mediaFiles = mediaFiles,
        transactionRunner = transactionRunner,
        pendingStore = pendingReplicaCleanupStore,
    )
    private val familySessionCoordinator = FamilySessionCoordinator(
        backend = backend,
        preferences = preferences,
        outboxDao = outboxDao,
        replica = replicaSyncEngine,
        barrier = syncMutex,
        requireRemoteAllowed = { config ->
            val decision = foregroundSyncGate.evaluate(
                config,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
        },
        onSessionChanged = ::publishSession,
        onSessionObserved = { session -> cachedSession = session },
        requestSync = ::requestSync,
        recoverReclaimedSession = ::recoverReclaimedSessionLocked,
        beforeOperation = ::recoverPendingLocalClearLocked,
    )
    private val syncSignal = Channel<Unit>(Channel.CONFLATED)
    private val pullRequested = AtomicBoolean(false)
    @Volatile private var cachedSession = SyncSession()

    init {
        processScope.launch {
            runProcessStartupRecovery(::updateFailureStatus) {
                val resumeTerminalRemoval = syncMutex.withLock {
                    recoverPendingLocalClearLocked()
                    preferences.hasPendingDeviceRemovalClear() ||
                        preferences.hasPendingMembershipDeletionClear() ||
                        preferences.hasPendingFamilyDeletionClear()
                }
                if (resumeTerminalRemoval) finishPendingTerminalIdentityClear()
            }
        }
        processScope.launch {
            preferences.session.collect { session ->
                cachedSession = session
                if (session.reauthRequired) {
                    currentStatus.value = SyncStatus.ReauthRequired
                } else if (!session.isJoined) {
                    currentStatus.value = SyncStatus.Disabled
                    // Unjoined devices never show optional/forced update surfaces.
                    optionalAppUpdateState.value = null
                    forcedAppUpdateState.value = null
                } else if (currentStatus.value == SyncStatus.Disabled) {
                    currentStatus.value = SyncStatus.Idle
                }
            }
        }
        processScope.launch {
            var consecutiveRetryableFailures = 0
            var scheduledRetry: Job? = null
            var scheduledRetryNeedsPull = false
            for (ignored in syncSignal) {
                if (scheduledRetryNeedsPull) {
                    pullRequested.set(true)
                }
                scheduledRetryNeedsPull = false
                scheduledRetry?.cancel()
                scheduledRetry = null
                val trigger = if (pullRequested.getAndSet(false)) {
                    SyncTrigger.Foreground
                } else {
                    SyncTrigger.LocalWrite
                }
                val result = sync(trigger)
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    consecutiveRetryableFailures = 0
                    continue
                }
                val retryDelay = ForegroundSyncRetryPolicy.delayMillis(
                    failure = failure,
                    consecutiveFailures = consecutiveRetryableFailures,
                )
                if (retryDelay == null || !foregroundState.isForeground()) {
                    consecutiveRetryableFailures = 0
                    continue
                }
                consecutiveRetryableFailures += 1
                val retryNeedsPull = trigger != SyncTrigger.LocalWrite
                scheduledRetryNeedsPull = retryNeedsPull
                scheduledRetry = processScope.launch {
                    delay(retryDelay)
                    if (foregroundState.isForeground()) {
                        if (retryNeedsPull) pullRequested.set(true)
                        syncSignal.trySend(Unit)
                    }
                }
            }
        }
    }

    override fun status(): Flow<SyncStatus> = currentStatus
    override fun session(): Flow<SyncSession> = preferences.session
    override fun verifiedEndpoint(): Flow<TrustedEndpointProfile?> = preferences.verifiedEndpoint
    override fun pendingMemberLogin(): Flow<PendingMemberLogin?> = preferences.pendingMemberLogin
    override fun memberLoginChecks(): Flow<MemberLoginCheckResult> = memberLoginCheckEvents
    override fun availableOptionalAppUpdate(): Flow<AppUpdateMetadata?> =
        optionalAppUpdateState

    override fun availableForcedAppUpdate(): Flow<ForcedAppUpdateState?> =
        forcedAppUpdateState

    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        setupProbe.probe(endpointDraft, preferences.verifiedEndpoint.first())

    override suspend fun verifyEndpoint(endpoint: TrustedEndpointProfile): SetupProbeResult =
        setupProbe.probe(endpoint.origin, endpoint)

    override suspend fun trustCertificate(
        candidate: CertificateTrustCandidate,
    ): SetupProbeResult {
        val endpoint = candidate.trustedEndpoint()
        val result = setupProbe.probe(endpoint.origin, endpoint)
        if (result !is SetupProbeResult.Ready) {
            val staleEndpoint = preferences.verifiedEndpoint.first()
            if (staleEndpoint?.origin == endpoint.origin) {
                try {
                    withContext(NonCancellable) {
                        preferences.forgetEndpoint()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return SetupProbeResult.Failed.Unreachable
                }
            }
            return result
        }
        try {
            withContext(NonCancellable) {
                preferences.rememberEndpoint(result.endpoint)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return SetupProbeResult.Failed.Unreachable
        }
        return result
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        try {
            preferences.rememberEndpoint(endpoint)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    override suspend fun forgetEndpoint(): Result<Unit> =
        try {
            preferences.forgetEndpoint()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    override fun requestSync(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.LocalWrite) {
            pullRequested.set(true)
        }
        syncSignal.trySend(Unit)
    }

    override suspend fun cleanupTombstonedMedia(clientUuids: Set<String>): Result<Unit> =
        runCatching {
            syncMutex.withLock {
                mediaFileCleanup.cleanupTombstones(clientUuids)
            }
        }

    override suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
    ): Result<Unit> =
        executeFamily(FamilySessionCommand.SaveEndpointConfig(config)).map { Unit }

    override suspend fun createFamily(
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> =
        executeFamily(
            FamilySessionCommand.CreateFamily(
                displayName = displayName,
                deviceName = deviceName,
                bootstrapSecret = bootstrapSecret,
                familyName = familyName,
            ),
        ).map {
            val joined = it as FamilySessionOutcome.Joined
            CreateFamilyResult(
                session = joined.session,
                reclaimed = joined.reclaimed,
                dataRecovery = joined.dataRecovery,
            )
        }

    override suspend fun ownerLogin(
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
    ): Result<OwnerLoginResult> =
        executeFamily(
            FamilySessionCommand.OwnerLogin(
                deviceName = deviceName,
                rootPassword = rootPassword,
                takeover = takeover,
            ),
        ).map {
            val joined = it as FamilySessionOutcome.Joined
            OwnerLoginResult(
                session = joined.session,
                dataRecovery = joined.dataRecovery,
            )
        }

    override suspend fun requestMemberLogin(
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = executeFamily(
        FamilySessionCommand.RequestMemberLogin(displayName, deviceName),
    ).map { (it as FamilySessionOutcome.MemberLoginRequested).request }

    override suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> {
        val result = executeFamily(FamilySessionCommand.CheckMemberLogin)
            .map { (it as FamilySessionOutcome.MemberLoginChecked).result }
        result.getOrNull()?.let { memberLoginCheckEvents.emit(it) }
        return result
    }

    override suspend fun cancelMemberLogin(): Result<Unit> =
        executeFamily(FamilySessionCommand.CancelMemberLogin).map { Unit }

    override suspend fun listPendingMemberLogins(): Result<List<PendingMemberLoginRequest>> =
        executeFamily(FamilySessionCommand.ListPendingMemberLogins)
            .map { (it as FamilySessionOutcome.PendingMemberLoginsListed).requests }

    override suspend fun approveNewMemberLogin(requestId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.ApproveNewMemberLogin(requestId)).map { Unit }

    override suspend fun bindExistingMemberLogin(
        requestId: String,
        membershipId: String,
    ): Result<Unit> = executeFamily(
        FamilySessionCommand.BindExistingMemberLogin(requestId, membershipId),
    ).map { Unit }

    override suspend fun rejectMemberLogin(requestId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.RejectMemberLogin(requestId)).map { Unit }

    override suspend fun createMemberLoginQrPayload(
        membershipId: String,
    ): Result<MemberLoginQrPayload> = executeFamily(
        FamilySessionCommand.CreateMemberLoginGrant(membershipId),
    ).mapCatching { outcome ->
        val grant = (outcome as FamilySessionOutcome.MemberLoginGrantCreated).grant
        val session = preferences.session.first()
        val endpoint = requireNotNull(preferences.verifiedEndpoint.first()) {
            "当前家庭服务器尚未建立可信 HTTPS 配置"
        }
        require(endpoint.matchesOrigin(session.baseUrl)) {
            "当前家庭会话与可信服务器地址不一致"
        }
        MemberLoginQrPayload(
            endpoint = endpoint,
            grant = grant.grant,
            familyName = grant.familyName,
            memberDisplayName = grant.memberDisplayName,
            expiresAtEpochSeconds = grant.expiresAtEpochSeconds,
        )
    }

    override suspend fun claimMemberLoginQr(
        payload: MemberLoginQrPayload,
        deviceName: String,
    ): Result<MemberLoginQrResult> {
        val result = executeFamily(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, deviceName),
        )
        return result.fold(
            onSuccess = {
                val joined = it as FamilySessionOutcome.Joined
                Result.success(
                    MemberLoginQrResult(
                        session = joined.session,
                        dataRecovery = joined.dataRecovery,
                    ),
                )
            },
            onFailure = { error ->
                Result.failure(
                    when {
                        error is SyncHttpException && error.statusCode in setOf(404, 409, 410) ->
                            MemberLoginQrUnavailableException()
                        error.causeChainContains<SpkiPinMismatchException>() ->
                            MemberLoginQrTrustChangedException()
                        else -> error
                    },
                )
            },
        )
    }

    override suspend fun renameFamily(familyName: String?): Result<Unit> =
        executeFamily(FamilySessionCommand.RenameFamily(familyName)).map { Unit }

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> =
        executeFamily(FamilySessionCommand.ListMembers)
            .map { (it as FamilySessionOutcome.MembersListed).members }

    override suspend fun updateMyDisplayName(
        displayName: String,
    ): Result<DisplayNameUpdateResult> =
        executeFamily(FamilySessionCommand.UpdateMyDisplayName(displayName)).map {
            (it as FamilySessionOutcome.DisplayNameUpdateCompleted).result
        }

    override suspend fun listPendingMemberRenameRequests(): Result<List<PendingMemberRenameRequest>> =
        executeFamily(FamilySessionCommand.ListPendingMemberRenames).map {
            (it as FamilySessionOutcome.PendingMemberRenamesListed).requests
        }

    override suspend fun approveMemberRename(requestId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.ApproveMemberRename(requestId)).map { Unit }

    override suspend fun rejectMemberRename(requestId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.RejectMemberRename(requestId)).map { Unit }

    override suspend fun cancelMyMemberRename(): Result<Unit> =
        executeFamily(FamilySessionCommand.CancelMyMemberRename).map { Unit }

    override suspend fun addFamilyMember(displayName: String): Result<FamilyMember> =
        executeFamily(FamilySessionCommand.AddFamilyMember(displayName)).map {
            (it as FamilySessionOutcome.FamilyMemberAdded).member
        }

    override suspend fun renameFamilyMember(
        membershipId: String,
        displayName: String,
    ): Result<Unit> = executeFamily(
        FamilySessionCommand.RenameFamilyMember(membershipId, displayName),
    ).map { Unit }

    override suspend fun renameFamilyDevice(
        deviceId: String,
        deviceName: String,
    ): Result<Unit> = executeFamily(
        FamilySessionCommand.RenameFamilyDevice(deviceId, deviceName),
    ).map { Unit }

    override suspend fun sync(trigger: SyncTrigger): Result<Unit> {
        val result = runCatching {
            if (
                trigger == SyncTrigger.Foreground &&
                !preferences.session.first().isJoined &&
                preferences.pendingMemberLogin.first() != null
            ) {
                checkMemberLogin().getOrThrow()
                return@runCatching
            }
            syncMutex.withLock {
                recoverPendingLocalClearLocked()
                val session = preferences.session.first()
                cachedSession = session
                if (!session.isJoined) {
                    currentStatus.value = SyncStatus.Disabled
                    return@withLock
                }
                synchronizeJoinedSessionLocked(session, trigger)
            }
        }
        val failure = result.exceptionOrNull()
        var handledClientUpdateRequired = false
        val mapped = when (failure) {
            is RemoteDeviceRemovedException -> handleRemoteDeviceRemoved(failure)
            is RemoteMembershipDeletedException -> handleRemoteMembershipDeleted(failure)
            is RemoteFamilyDeletedException -> handleRemoteFamilyDeleted(failure)
            is ClientUpdateRequiredException -> {
                handledClientUpdateRequired = true
                handleClientUpdateRequired(failure)
            }
            else -> {
                // Wire code may still arrive as SyncHttpException if a backend skips mapping.
                if (failure is SyncHttpException &&
                    syncHttpCodeOrNull(failure.responseBody) == "client_update_required"
                ) {
                    handledClientUpdateRequired = true
                    handleClientUpdateRequired(ClientUpdateRequiredException())
                } else {
                    result.onFailure(::updateFailureStatus)
                }
            }
        }
        // Piggyback update discovery on user-facing sync/handshake only
        // (not LocalWrite spam). Failures never change SyncStatus.
        // Skip after client_update_required: handle already classified metadata (or shell).
        // Only discover after a *successful* sync: a failed PullToRefresh (network/5xx)
        // must not run non-preserving classify and tear an existing force shell via
        // temporary non-Forced metadata (AUDIT-20260801-P1-01 假正常 demotion).
        if (
            trigger != SyncTrigger.LocalWrite &&
            !handledClientUpdateRequired &&
            mapped.isSuccess
        ) {
            discoverAppUpdateBestEffort(publishWhenDismissed = false)
        }
        return mapped
    }

    override suspend fun leave(): Result<Unit> {
        val remote = executeFamily(FamilySessionCommand.Leave)
        val failure = remote.exceptionOrNull()
        if (failure != null && failure !is RemoteMembershipDeletedException) {
            return remote.map { Unit }
        }
        return completeConfirmedMembershipDeletion()
    }

    override suspend fun logoutCurrentDevice(): Result<Unit> {
        val remote = executeFamily(FamilySessionCommand.LogoutCurrentDevice)
        if (remote.isFailure) return remote.map { Unit }
        return completeConfirmedDeviceRemoval()
    }

    override suspend fun revokeFamilyDevice(deviceId: String): Result<Unit> {
        val currentDeviceId = preferences.session.first().deviceId
        val remote = executeFamily(FamilySessionCommand.RevokeFamilyDevice(deviceId))
        if (remote.isFailure) return remote.map { Unit }
        return if (deviceId.trim() == currentDeviceId) {
            completeConfirmedDeviceRemoval()
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun removeMember(membershipId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.RemoveMember(membershipId)).map { Unit }

    override suspend fun deleteFamily(familyName: String, rootPassword: String): Result<Unit> {
        val remote = executeFamily(FamilySessionCommand.DeleteFamily(familyName, rootPassword))
        val failure = remote.exceptionOrNull()
        if (failure != null && failure !is RemoteFamilyDeletedException) {
            return remote.map { Unit }
        }
        return completeConfirmedFamilyDeletion()
    }

    override suspend fun clearLocalData(
        scope: LocalDataClearScope,
        workflow: LocalClearWorkflow,
    ): Result<Unit> = localReplicaClearCoordinator
        .clear(
            scope = scope,
            workflow = workflow,
            recoverDomain = localClearRecoveryGate::recoverPendingLocalClear,
        )
        .onFailure(::updateFailureStatus)

    override suspend fun checkAppUpdate(): Result<AppUpdateCheckResult> {
        // Opportunistic staging cleanup on the check path — never while an install
        // pipeline holds [appUpdateInstallMutex] (would wipe a partial staging APK).
        withAppUpdateInstallLockOrElse(
            onBusy = { },
            block = { cleanupAppUpdateStagingFiles(appUpdateCacheDir) },
        )
        val session = preferences.session.first()
        if (!session.isJoined) {
            optionalAppUpdateState.value = null
            forcedAppUpdateState.value = null
            return Result.success(AppUpdateCheckResult.NotJoined)
        }
        // Manual check must never mutate SyncStatus — surface failures only to the UI.
        return runCatching {
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            if (decision != ForegroundSyncDecision.Allowed) {
                throw ForegroundSyncBlockedException(decision)
            }
            val metadata = backend.getAppUpdateMetadata(session)
            // When a force shell is already up (CUR / PackageUnknown / WithPackage),
            // only Forced metadata may advance it — bare UpToDate/Optional must not
            // demote to 假正常 (AUDIT-20260801-P1-01).
            classifyAndPublishAppUpdate(
                metadata = metadata,
                respectOptionalDismissal = true,
                preserveExistingForceShell = forcedAppUpdateState.value != null,
            )
        }.recoverCatching { error ->
            // Map wire gate failures out of generic check-update "network" copy.
            val required = when (error) {
                is ClientUpdateRequiredException -> error
                is SyncHttpException -> error.clientUpdateRequiredOrNull()
                else -> null
            }
            if (required != null) {
                // Same fail-closed policy as sync CUR handling (single helper).
                resolveForceShellAfterClientUpdateRequired()
                    ?: throw required
            } else {
                throw error
            }
        }
    }

    override fun dismissOptionalAppUpdate(versionCode: Int) {
        if (versionCode > 0) {
            dismissedOptionalUpdateVersionCodes.add(versionCode)
        }
        val current = optionalAppUpdateState.value
        if (current != null && current.versionCode == versionCode) {
            optionalAppUpdateState.value = null
        }
    }

    override suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> {
        val session = preferences.session.first()
        if (!session.isJoined) {
            return Result.failure(IllegalStateException("请先连接家庭服务器后再更新"))
        }
        metadataPackageMismatchOrNull(metadata)?.let { return Result.failure(it) }
        if (clientAppVersion.versionCode >= metadata.versionCode) {
            return Result.failure(IllegalStateException("当前已是最新版本"))
        }
        // About + banner + force overlay share one pipeline; second call fails closed while busy.
        // Banner dismiss and other install-started mutations only run after the lock is held.
        return withAppUpdateInstallLockOrElse(
            onBusy = { Result.failure(AppUpdateInstallInProgressException()) },
            block = {
                dismissOptionalAppUpdate(metadata.versionCode)
                // Download / sha256 / archive identity / PackageInstaller stay off the main thread.
                // Failures surface only via Result — never mutate SyncStatus
                // (mirror checkAppUpdate; do not call requireAllowed / updateFailureStatus).
                runCatching {
                    withContext(Dispatchers.IO) {
                        val decision = foregroundSyncGate.evaluate(
                            session.endpointConfig,
                            preferences.verifiedEndpoint.first(),
                            foregroundState.isForeground(),
                        )
                        if (decision != ForegroundSyncDecision.Allowed) {
                            throw ForegroundSyncBlockedException(decision)
                        }
                        if (!appUpdateInstaller.canRequestPackageInstalls()) {
                            return@withContext AppUpdateInstallResult.RequiresInstallPermission
                        }
                        cleanupAppUpdateStagingFiles(appUpdateCacheDir)
                        val stagingDir = appUpdateStagingDir(appUpdateCacheDir)
                        require(stagingDir.mkdirs() || stagingDir.isDirectory) {
                            "无法创建更新暂存目录"
                        }
                        val stagingFile = appUpdateStagingApk(appUpdateCacheDir)
                        try {
                            val bytes = backend.downloadAppUpdateApk(session)
                            require(bytes.isNotEmpty()) { "更新包下载为空" }
                            val digest = sha256Hex(bytes)
                            if (digest != metadata.sha256) {
                                throw IllegalStateException("更新包校验失败，请重试")
                            }
                            stagingFile.outputStream().use { it.write(bytes) }
                            // PackageInstaller commit only after archive package/version/signer match.
                            val identityError = verifyStagedApkIdentity(
                                archive = apkIdentityReader.readArchive(stagingFile),
                                installedCerts = apkIdentityReader.installedSigningCertSha256(),
                                local = clientAppVersion,
                                metadata = metadata,
                            )
                            if (identityError != null) {
                                throw IllegalStateException(identityError)
                            }
                            appUpdateInstaller.installFromFile(
                                stagingFile,
                                clientAppVersion.packageName,
                            )
                            AppUpdateInstallResult.SessionStarted
                        } finally {
                            // Always remove private staging after the attempt so no shareable APK remains.
                            cleanupAppUpdateStagingFiles(appUpdateCacheDir)
                        }
                    }
                }
            },
        )
    }

    override suspend fun cleanupAppUpdateStaging(): Result<Unit> = runCatching {
        withAppUpdateInstallLockOrElse(
            onBusy = { },
            block = { cleanupAppUpdateStagingFiles(appUpdateCacheDir) },
        )
    }

    /**
     * Single owner of [appUpdateInstallMutex] try/finally so check cleanup, install,
     * and explicit cleanup share one skip-vs-busy policy.
     */
    private suspend inline fun <T> withAppUpdateInstallLockOrElse(
        onBusy: () -> T,
        block: suspend () -> T,
    ): T {
        if (!appUpdateInstallMutex.tryLock()) {
            return onBusy()
        }
        try {
            return block()
        } finally {
            appUpdateInstallMutex.unlock()
        }
    }

    /** Reject metadata whose packageName is not this process applicationId. */
    private fun metadataPackageMismatchOrNull(
        metadata: AppUpdateMetadata,
    ): IllegalStateException? {
        if (metadata.packageName != clientAppVersion.packageName) {
            return IllegalStateException(APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE)
        }
        return null
    }

    /**
     * Best-effort authenticated metadata check for optional/forced updates.
     * Never throws to callers and never mutates [currentStatus].
     */
    private suspend fun discoverAppUpdateBestEffort(
        publishWhenDismissed: Boolean,
    ) {
        try {
            val session = preferences.session.first()
            if (!session.isJoined) {
                optionalAppUpdateState.value = null
                forcedAppUpdateState.value = null
                return
            }
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            if (decision != ForegroundSyncDecision.Allowed) return
            val metadata = backend.getAppUpdateMetadata(session)
            classifyAndPublishAppUpdate(
                metadata = metadata,
                respectOptionalDismissal = !publishWhenDismissed,
            )
        } catch (_: Throwable) {
            // Swallow: handshake piggyback must not fail sync or mark SyncStatus.Error.
        }
    }

    /**
     * Classifies server metadata against the local versionCode and publishes the
     * matching optional/forced flow. Forced always wins over optional.
     * Rejects metadata whose packageName is not this process applicationId so UI
     * never offers install of a different app.
     *
     * When [preserveExistingForceShell] is true and classification is not Forced,
     * an existing force surface is left intact, optional is not published, and the
     * **returned** [AppUpdateCheckResult] stays force-honest ([ForcedUpdate] for a
     * retained package, [ForcedPackageUnknown] otherwise) — never bare UpToDate/
     * Optional, so Settings/Family/retry UI cannot claim 假正常 while the shell blocks.
     * Handshake piggyback after a successful sync keeps the default (false) so a
     * genuine non-gated client can clear a stale shell.
     */
    private fun classifyAndPublishAppUpdate(
        metadata: AppUpdateMetadata,
        respectOptionalDismissal: Boolean,
        preserveExistingForceShell: Boolean = false,
    ): AppUpdateCheckResult {
        metadataPackageMismatchOrNull(metadata)?.let { mismatch ->
            optionalAppUpdateState.value = null
            throw mismatch
        }
        val local = clientAppVersion.versionCode
        if (local < metadata.minSupportedVersionCode) {
            optionalAppUpdateState.value = null
            forcedAppUpdateState.value = ForcedAppUpdateState.WithPackage(metadata)
            return AppUpdateCheckResult.ForcedUpdate(metadata)
        }
        if (preserveExistingForceShell) {
            when (val existing = forcedAppUpdateState.value) {
                is ForcedAppUpdateState.WithPackage -> {
                    // Fail closed: keep last installable package; Result matches shell.
                    optionalAppUpdateState.value = null
                    return AppUpdateCheckResult.ForcedUpdate(existing.metadata)
                }
                ForcedAppUpdateState.PackageUnknown -> {
                    optionalAppUpdateState.value = null
                    return AppUpdateCheckResult.ForcedPackageUnknown
                }
                null -> {
                    // No shell to preserve — fall through to normal dual-tier.
                }
            }
        }
        forcedAppUpdateState.value = null
        if (local >= metadata.versionCode) {
            optionalAppUpdateState.value = null
            return AppUpdateCheckResult.UpToDate
        }
        publishOptionalAppUpdateIfEligible(
            metadata = metadata,
            respectDismissal = respectOptionalDismissal,
        )
        return AppUpdateCheckResult.OptionalUpdate(metadata)
    }

    private fun publishOptionalAppUpdateIfEligible(
        metadata: AppUpdateMetadata,
        respectDismissal: Boolean,
    ) {
        if (respectDismissal &&
            metadata.versionCode in dismissedOptionalUpdateVersionCodes
        ) {
            val current = optionalAppUpdateState.value
            if (current?.versionCode == metadata.versionCode) {
                optionalAppUpdateState.value = null
            }
            return
        }
        optionalAppUpdateState.value = metadata
    }

    /**
     * Fail-closed force surface after server `client_update_required`.
     * When [acceptedForcedPackage] is true, [classifyAndPublishAppUpdate] already set
     * [ForcedAppUpdateState.WithPackage]. Otherwise preserve a prior installable package
     * or publish [ForcedAppUpdateState.PackageUnknown] — never leave silent Idle.
     */
    private fun publishForceShellPreservingPackage(
        previousForced: ForcedAppUpdateState?,
        acceptedForcedPackage: Boolean,
    ) {
        if (acceptedForcedPackage) return
        optionalAppUpdateState.value = null
        forcedAppUpdateState.value = when (previousForced) {
            is ForcedAppUpdateState.WithPackage -> previousForced
            ForcedAppUpdateState.PackageUnknown -> previousForced
            null -> ForcedAppUpdateState.PackageUnknown
        }
    }

    /**
     * Single owner of CUR → metadata → force-shell policy for both sync handling and
     * [checkAppUpdate] recover. Best-effort load so force UI can install; only accept
     * [AppUpdateCheckResult.ForcedUpdate]. Optional/up-to-date after CUR would clear
     * force and reintroduce silent Idle — fail closed with a force shell instead.
     *
     * @return installable ForcedUpdate when classification succeeded; null when a
     * force shell was published without accepting ForcedUpdate (caller rethrows CUR).
     */
    private suspend fun resolveForceShellAfterClientUpdateRequired():
        AppUpdateCheckResult.ForcedUpdate? {
        val previousForced = forcedAppUpdateState.value
        var accepted: AppUpdateCheckResult.ForcedUpdate? = null
        try {
            val session = preferences.session.first()
            if (session.isJoined) {
                val decision = foregroundSyncGate.evaluate(
                    session.endpointConfig,
                    preferences.verifiedEndpoint.first(),
                    foregroundState.isForeground(),
                )
                if (decision == ForegroundSyncDecision.Allowed) {
                    val metadata = backend.getAppUpdateMetadata(session)
                    // Never tear shell on non-Forced classification while resolving CUR —
                    // same policy as checkAppUpdate retry under an active force surface.
                    val classified = classifyAndPublishAppUpdate(
                        metadata = metadata,
                        respectOptionalDismissal = false,
                        preserveExistingForceShell = true,
                    )
                    if (classified is AppUpdateCheckResult.ForcedUpdate) {
                        accepted = classified
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall through to force shell when metadata/gate path fails.
        }
        publishForceShellPreservingPackage(
            previousForced = previousForced,
            acceptedForcedPackage = accepted != null,
        )
        return accepted
    }

    /**
     * After the server rejects sync with client_update_required, resolve force shell
     * (shared with [checkAppUpdate] recover) and keep SyncStatus non-vague.
     */
    private suspend fun handleClientUpdateRequired(
        error: ClientUpdateRequiredException,
    ): Result<Unit> {
        resolveForceShellAfterClientUpdateRequired()
        // Do not leave a vague SyncStatus.Error — force UI is the primary recovery path.
        // Keep Idle when joined so the status line is not "sync failed / NAS down".
        if (cachedSession.isJoined && !cachedSession.reauthRequired) {
            currentStatus.value = SyncStatus.Idle
        } else {
            updateFailureStatus(error)
        }
        return Result.failure(error)
    }

    /** Caller owns [syncMutex]; lock order is sync mutex then domain mutation guard. */
    private suspend fun recoverPendingLocalClearLocked(): LocalDataClearScope? {
        preferences.recoverPendingCredentialClear()
        familySessionCoordinator.recoverPendingReplicaReset()
        val resumedDomain = localClearRecoveryGate.recoverPendingLocalClear()
        val resumedReplica = localReplicaClearCoordinator.recoverPendingLocked()
        return LocalDataClearScope.widest(resumedDomain, resumedReplica)
    }

    private suspend fun completeConfirmedDeviceRemoval(): Result<Unit> = try {
        preferences.markPendingDeviceRemovalClear()
        finishPendingTerminalIdentityClear()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        updateFailureStatus(failure)
        Result.failure(failure)
    }

    private suspend fun handleRemoteDeviceRemoved(
        removal: RemoteDeviceRemovedException,
    ): Result<Unit> = try {
        preferences.markPendingDeviceRemovalClear()
        finishPendingTerminalIdentityClear()
        updateFailureStatus(removal)
        Result.failure(removal)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cleanupFailure: Throwable) {
        cleanupFailure.addSuppressed(removal)
        updateFailureStatus(cleanupFailure)
        Result.failure(cleanupFailure)
    }

    private suspend fun completeConfirmedMembershipDeletion(): Result<Unit> = try {
        preferences.markPendingMembershipDeletionClear()
        finishPendingTerminalIdentityClear()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        updateFailureStatus(failure)
        Result.failure(failure)
    }

    private suspend fun handleRemoteMembershipDeleted(
        deletion: RemoteMembershipDeletedException,
    ): Result<Unit> = try {
        preferences.markPendingMembershipDeletionClear()
        finishPendingTerminalIdentityClear()
        updateFailureStatus(deletion)
        Result.failure(deletion)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cleanupFailure: Throwable) {
        cleanupFailure.addSuppressed(deletion)
        updateFailureStatus(cleanupFailure)
        Result.failure(cleanupFailure)
    }

    private suspend fun completeConfirmedFamilyDeletion(): Result<Unit> = try {
        preferences.markPendingFamilyDeletionClear()
        finishPendingTerminalIdentityClear()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        updateFailureStatus(failure)
        Result.failure(failure)
    }

    private suspend fun handleRemoteFamilyDeleted(
        deletion: RemoteFamilyDeletedException,
    ): Result<Unit> = try {
        preferences.markPendingFamilyDeletionClear()
        finishPendingTerminalIdentityClear()
        updateFailureStatus(deletion)
        Result.failure(deletion)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cleanupFailure: Throwable) {
        cleanupFailure.addSuppressed(deletion)
        updateFailureStatus(cleanupFailure)
        Result.failure(cleanupFailure)
    }

    private suspend fun finishPendingTerminalIdentityClear() {
        syncMutex.withLock {
            // Terminal identity and every local family projection converge under
            // the same barrier used by foreground sync. Credentials are already
            // made unusable by the durable terminal marker before this method.
            removedDeviceLocalClearGate.clearAllLocalFamilyData()
            preferences.clearAllLocalSyncConfig()
            preferences.clearPendingDeviceRemovalClear()
            preferences.clearPendingMembershipDeletionClear()
            preferences.clearPendingFamilyDeletionClear()
            publishSession(preferences.session.first())
        }
    }

    /** Caller owns [syncMutex]; identity remains committed when recovery is retryable. */
    private suspend fun recoverReclaimedSessionLocked(
        session: SyncSession,
    ): InitialFamilyDataRecovery = try {
        currentStatus.value = SyncStatus.Syncing
        synchronizeJoinedSessionLocked(session, SyncTrigger.PullToRefresh)
        InitialFamilyDataRecovery.Complete
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        updateFailureStatus(error)
        InitialFamilyDataRecovery.RetryRequired
    }

    private suspend fun synchronizeJoinedSessionLocked(
        session: SyncSession,
        trigger: SyncTrigger,
    ) {
        val outcome = replicaSyncEngine.synchronize(session, trigger)
        preferences.markSuccess(clock.nowMillis())
        cachedSession = preferences.session.first()
        currentStatus.value = when (outcome) {
            ReplicaSyncOutcome.Synchronized -> SyncStatus.Idle
        }
    }

    private suspend fun executeFamily(
        command: FamilySessionCommand,
    ): Result<FamilySessionOutcome> =
        familySessionCoordinator.execute(command).onFailure(::updateFailureStatus)

    private fun publishSession(session: SyncSession) {
        cachedSession = session
        currentStatus.value = when {
            session.reauthRequired -> SyncStatus.ReauthRequired
            session.isJoined -> SyncStatus.Idle
            else -> SyncStatus.Disabled
        }
    }

    private fun requireAllowed(decision: ForegroundSyncDecision) {
        if (decision == ForegroundSyncDecision.Allowed) return
        currentStatus.value = when (decision) {
            ForegroundSyncDecision.MissingEndpoint,
            ForegroundSyncDecision.UntrustedEndpoint,
            -> SyncStatus.Disabled
            ForegroundSyncDecision.Background ->
                if (cachedSession.isJoined) SyncStatus.Idle else SyncStatus.Disabled
            ForegroundSyncDecision.Allowed -> error("allowed decision handled above")
        }
        throw ForegroundSyncBlockedException(decision)
    }

    private fun updateFailureStatus(error: Throwable) {
        currentStatus.value = when (error) {
            is ForegroundSyncBlockedException -> currentStatus.value
            is SyncNotEnabledException -> SyncStatus.Disabled
            is ReauthRequiredException -> SyncStatus.ReauthRequired
            is RemoteDeviceRemovedException -> SyncStatus.Disabled
            is RemoteMembershipDeletedException -> SyncStatus.Disabled
            is RemoteFamilyDeletedException -> SyncStatus.Disabled
            else -> SyncStatus.Error
        }
    }
}

/** Test / default installer so JVM unit tests need no PackageInstaller. */
internal object NoOpAppUpdateInstaller : AppUpdateInstaller {
    override fun canRequestPackageInstalls(): Boolean = true
    override fun installFromFile(apkFile: File, expectedPackageName: String) = Unit
    override fun createManageUnknownSourcesIntent(): android.content.Intent =
        android.content.Intent()
}

private inline fun <reified T : Throwable> Throwable.causeChainContains(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

/** Prevents recoverable startup I/O failures from reaching the process uncaught handler. */
internal suspend fun runProcessStartupRecovery(
    reportFailure: (Throwable) -> Unit,
    recover: suspend () -> Unit,
) {
    try {
        recover()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure.startupCancellationCauseOrNull()?.let { throw it }
        reportFailure(failure)
    }
}

private fun Throwable.startupCancellationCauseOrNull(): CancellationException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) return current
        current = current.cause
    }
    return null
}

private fun ForegroundSyncDecision.userMessage(): String = when (this) {
    ForegroundSyncDecision.MissingEndpoint -> "请先连接可信家庭服务器"
    ForegroundSyncDecision.UntrustedEndpoint -> "服务器地址已变化，请重新确认并登录"
    ForegroundSyncDecision.Background -> "家庭同步仅在前台运行"
    ForegroundSyncDecision.Allowed -> ""
}

internal class ForegroundSyncBlockedException(
    val decision: ForegroundSyncDecision,
) : IllegalStateException(decision.userMessage())

/**
 * Creator-local publish chrome for a care record that is still waiting on an
 * atomic package commit (or failed to publish). Never shown for remote-applied
 * (syncDirty=false) rows.
 *
 * [publicationState] distinguishes first publish (create) from a mutation
 * re-publish: receivers keep the prior complete version until the new package
 * commits, so mutation copy must not say "暂不可见" as if the row never existed.
 */
fun localRecordPublishLabel(
    syncDirty: Boolean,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
    publicationState: RootPublicationState,
): String? {
    if (
        !familyJoined ||
        !syncDirty ||
        publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED
    ) return null
    val hasPriorFamilyRevision =
        publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED
    return when {
        lastSyncFailed && hasPriorFamilyRevision -> "仅本机 · 更新同步失败"
        lastSyncFailed -> "仅本机 · 同步失败"
        hasPriorFamilyRevision -> "仅本机 · 等待更新同步"
        else -> "仅本机 · 等待家庭同步"
    }
}

/** Detail copy when the user taps the local-only chrome. */
fun localRecordPublishDetail(
    lastSyncFailed: Boolean,
    publicationState: RootPublicationState,
): String = when {
    publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED && lastSyncFailed ->
        "其他成员仍看到上一完整版本；可手动重试，或下次前台同步时自动重试。"
    publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED ->
        "其他成员仍看到上一完整版本，更新发布成功后才会替换。"
    publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED ->
        "当前版本已完成家庭同步。"
    lastSyncFailed ->
        "其他成员暂不可见；可手动重试，或下次前台同步时自动重试。"
    else ->
        "其他成员暂不可见，记录发布成功后才会出现。"
}

/**
 * Creator-local publish chrome for a care plan still waiting on an atomic
 * package commit. Copy mentions that other members neither see nor remind.
 */
fun localCarePlanPublishLabel(
    syncDirty: Boolean,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
    publicationState: RootPublicationState,
): String? {
    if (
        !familyJoined ||
        !syncDirty ||
        publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED
    ) return null
    val hasPriorFamilyRevision =
        publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED
    return when {
        lastSyncFailed && hasPriorFamilyRevision -> "仅本机 · 更新同步失败"
        lastSyncFailed -> "仅本机 · 同步失败"
        hasPriorFamilyRevision -> "仅本机 · 等待更新同步"
        else -> "仅本机 · 等待家庭同步"
    }
}

/** Detail copy for plan local-only chrome (visibility + family reminders). */
fun localCarePlanPublishDetail(
    lastSyncFailed: Boolean,
    publicationState: RootPublicationState,
): String = when {
    publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED && lastSyncFailed ->
        "其他成员仍看到上一完整版本且不会收到新提醒；可手动重试，或下次前台同步时自动重试。"
    publicationState == RootPublicationState.PREVIOUS_VERSION_PUBLISHED ->
        "其他成员仍看到上一完整版本，更新发布成功后才会替换并安排提醒。"
    publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED ->
        "当前版本已完成家庭同步。"
    lastSyncFailed ->
        "其他成员暂不可见、不会提醒；可手动重试，或下次前台同步时自动重试。"
    else ->
        "其他成员暂不可见、不会提醒，护理计划发布成功后才会出现。"
}
