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
    @Named("appUpdateCacheDir") private val appUpdateCacheDir: File =
        File(System.getProperty("java.io.tmpdir"), "lezi-app-update-test"),
) : SyncPort {
    private val currentStatus = MutableStateFlow(SyncStatus.Disabled)
    private val memberLoginCheckEvents = MutableSharedFlow<MemberLoginCheckResult>(
        extraBufferCapacity = 1,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
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
    override suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult =
        setupProbe.probe(endpointDraft, preferences.verifiedEndpoint.first())

    override suspend fun verifyEndpoint(endpoint: TrustedEndpointProfile): SetupProbeResult =
        setupProbe.probe(endpoint.origin, endpoint)

    override suspend fun trustCertificate(
        candidate: CertificateTrustCandidate,
    ): SetupProbeResult {
        val endpoint = candidate.trustedEndpoint()
        try {
            withContext(NonCancellable) {
                preferences.rememberEndpoint(endpoint)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return SetupProbeResult.Failed.Unreachable
        }
        return setupProbe.probe(endpoint.origin, endpoint)
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

    override fun isEnabled(): Boolean = cachedSession.isJoined
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

    override suspend fun saveServer(baseUrl: String): Result<Unit> =
        executeFamily(FamilySessionCommand.SaveServer(baseUrl)).map { Unit }

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
    ): Result<SyncSession> {
        val result = executeFamily(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, deviceName),
        )
        return result.fold(
            onSuccess = { Result.success((it as FamilySessionOutcome.Joined).session) },
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
        return when (failure) {
            is RemoteDeviceRemovedException -> handleRemoteDeviceRemoved(failure)
            is RemoteMembershipDeletedException -> handleRemoteMembershipDeleted(failure)
            is RemoteFamilyDeletedException -> handleRemoteFamilyDeleted(failure)
            else -> result.onFailure(::updateFailureStatus)
        }
    }

    override suspend fun push(familyId: String) = sync(SyncTrigger.LocalWrite)
    override suspend fun pull(familyId: String) = sync(SyncTrigger.PullToRefresh)

    override suspend fun leave(familyId: String): Result<Unit> {
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
        // Opportunistic staging cleanup whenever the user opens the check path.
        cleanupAppUpdateStaging()
        val session = preferences.session.first()
        if (!session.isJoined) {
            return Result.success(AppUpdateCheckResult.NotJoined)
        }
        return runCatching {
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
            val metadata = backend.getAppUpdateMetadata(session)
            if (clientAppVersion.versionCode >= metadata.versionCode) {
                AppUpdateCheckResult.UpToDate
            } else {
                AppUpdateCheckResult.OptionalUpdate(metadata)
            }
        }.onFailure(::updateFailureStatus)
    }

    override suspend fun installAvailableAppUpdate(
        metadata: AppUpdateMetadata,
    ): Result<AppUpdateInstallResult> {
        val session = preferences.session.first()
        if (!session.isJoined) {
            return Result.failure(IllegalStateException("请先连接家庭服务器后再更新"))
        }
        if (metadata.packageName != "com.lezi.babylog") {
            return Result.failure(IllegalStateException("更新包与本应用不匹配"))
        }
        if (clientAppVersion.versionCode >= metadata.versionCode) {
            return Result.failure(IllegalStateException("当前已是最新版本"))
        }
        return runCatching {
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
            if (!appUpdateInstaller.canRequestPackageInstalls()) {
                return@runCatching AppUpdateInstallResult.RequiresInstallPermission
            }
            cleanupAppUpdateStagingFiles(appUpdateCacheDir)
            val stagingDir = appUpdateStagingDir(appUpdateCacheDir)
            require(stagingDir.mkdirs() || stagingDir.isDirectory) { "无法创建更新暂存目录" }
            val stagingFile = appUpdateStagingApk(appUpdateCacheDir)
            try {
                val bytes = backend.downloadAppUpdateApk(session)
                require(bytes.isNotEmpty()) { "更新包下载为空" }
                val digest = sha256Hex(bytes)
                if (digest != metadata.sha256) {
                    throw IllegalStateException("更新包校验失败，请重试")
                }
                withContext(Dispatchers.IO) {
                    stagingFile.outputStream().use { it.write(bytes) }
                }
                appUpdateInstaller.installFromFile(stagingFile, metadata.packageName)
                AppUpdateInstallResult.SessionStarted
            } finally {
                // Always remove private staging after the attempt so no shareable APK remains.
                cleanupAppUpdateStagingFiles(appUpdateCacheDir)
            }
        }.onFailure(::updateFailureStatus)
    }

    override suspend fun cleanupAppUpdateStaging(): Result<Unit> = runCatching {
        cleanupAppUpdateStagingFiles(appUpdateCacheDir)
    }

    /** Caller owns [syncMutex]; lock order is sync mutex then domain mutation guard. */
    private suspend fun recoverPendingLocalClearLocked(): LocalDataClearScope? {
        preferences.recoverPendingCredentialClear()
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
        removedDeviceLocalClearGate.clearAllLocalFamilyData()
        syncMutex.withLock {
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
