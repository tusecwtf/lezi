package com.lezi.babylog.sync
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.PendingPublishDao
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SyncStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerAvailabilityPolicy
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.appupdate.APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.NoOpAppUpdateInstaller
import com.lezi.babylog.sync.appupdate.UnreadableAppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.cleanupAppUpdateStagingFiles
import com.lezi.babylog.sync.appupdate.sha256Hex
import com.lezi.babylog.sync.appupdate.verifyStagedApkIdentity
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.PendingMemberRenameRequest
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.clientUpdateRequiredOrNull
import com.lezi.babylog.sync.clear.LocalReplicaClearCoordinator
import com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshotBuilder
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.FamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.engine.ForegroundSyncBlockedException
import com.lezi.babylog.sync.engine.ForegroundSyncDecision
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.engine.ForegroundSyncRetryPolicy
import com.lezi.babylog.sync.engine.NoOpCarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.NoOpFamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.engine.ReplicaSyncEngine
import com.lezi.babylog.sync.engine.ReplicaSyncOutcome
import com.lezi.babylog.sync.media.MediaPrepareException
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.CAPABILITY_ATOMIC_BUNDLE
import com.lezi.babylog.sync.session.CAPABILITY_AUTHORITATIVE_RECONCILE
import com.lezi.babylog.sync.session.CAPABILITY_DISASTER_RESTORE
import com.lezi.babylog.sync.session.CAPABILITY_RECORD_MEMBERSHIP_AUTHOR
import com.lezi.babylog.sync.session.CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilySessionCommand
import com.lezi.babylog.sync.session.FamilySessionCoordinator
import com.lezi.babylog.sync.session.FamilySessionOutcome
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.matchesOrigin
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName
import com.lezi.babylog.sync.session.receiptFor

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val setupProbe: SetupProbe,
    private val foregroundSyncGate: ForegroundSyncGate,
    private val pendingPublishDao: PendingPublishDao,
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
    private val currentAvailability =
        MutableStateFlow<FamilyServerAvailability>(FamilyServerAvailability.Disabled)
    private val availabilityProbeMutex = Mutex()
    private val reconnectMutex = Mutex()
    private val pendingReconnectMember = AtomicReference<CandidateMemberReconnectAttempt?>(null)
    private val optionalAppUpdateState =
        MutableStateFlow<AppUpdateMetadata?>(null)
    private val forcedAppUpdateState =
        MutableStateFlow<ForcedAppUpdateState?>(null)
    /**
     * Host override for force-shell 8767 invite guidance after restore-path CUR
     * (candidate / checkpoint origin). Null → UI falls back to session.serverHost.
     */
    private val forcedUpdateLanInviteHostState = MutableStateFlow<String?>(null)
    /** Process-session "稍后" suppressions keyed by server package versionCode. */
    private val dismissedOptionalUpdateVersionCodes =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val neighborAlignmentHintEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
    )
    private val memberLoginCheckEvents = MutableSharedFlow<MemberLoginCheckResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
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
    private val disasterRecoverySnapshotBuilder = DisasterRecoverySnapshotBuilder(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        mediaDao = mediaDao,
        mediaFiles = mediaFiles,
    )
    private val localReplicaClearCoordinator = LocalReplicaClearCoordinator(
        barrier = syncMutex,
        preferences = preferences,
        babyDao = babyDao,
        mediaDao = mediaDao,
        mediaFiles = mediaFiles,
        transactionRunner = transactionRunner,
        pendingStore = pendingReplicaCleanupStore,
    )
    private val familySessionCoordinator = FamilySessionCoordinator(
        backend = backend,
        preferences = preferences,
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
        beforeOperation = ::recoverPendingLocalClearLocked,
        launchBestEffort = { work -> processScope.launch { work() } },
    )
    private val syncSignal = Channel<Unit>(Channel.CONFLATED)
    private val pullRequested = AtomicBoolean(false)
    private val pendingAvailabilityReason =
        AtomicReference(AvailabilityProbeReason.LocalChanges)
    private val lastAcceptedNetworkRecoveredAtMillis = AtomicReference<Long?>(null)
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
                    clearForcedAppUpdateState()
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
                if (!foregroundState.isForeground()) continue
                if (scheduledRetryNeedsPull) {
                    pullRequested.set(true)
                }
                scheduledRetryNeedsPull = false
                val trigger = if (pullRequested.getAndSet(false)) {
                    SyncTrigger.Foreground
                } else {
                    SyncTrigger.LocalWrite
                }
                val probeReason = pendingAvailabilityReason.getAndSet(
                    AvailabilityProbeReason.LocalChanges,
                )
                val availability = probeServerAvailability(probeReason).getOrNull()
                if (availability !is FamilyServerAvailability.Available) {
                    if (trigger != SyncTrigger.LocalWrite) {
                        scheduledRetryNeedsPull = true
                    }
                    val unavailable = availability as? FamilyServerAvailability.Unavailable
                    if (unavailable != null) {
                        val forced = probeReason == AvailabilityProbeReason.Foreground ||
                            probeReason == AvailabilityProbeReason.NetworkRecovered ||
                            probeReason == AvailabilityProbeReason.PullToRefresh
                        if (scheduledRetry?.isActive != true || forced) {
                            scheduledRetry?.cancel()
                            val retryDelay =
                                (unavailable.nextProbeAtMillis - clock.nowMillis()).coerceAtLeast(0)
                            scheduledRetry = processScope.launch {
                                delay(retryDelay)
                                if (foregroundState.isForeground()) {
                                    pendingAvailabilityReason.set(
                                        AvailabilityProbeReason.RetryDeadline,
                                    )
                                    syncSignal.trySend(Unit)
                                }
                            }
                        }
                    }
                    continue
                }
                scheduledRetry?.cancel()
                scheduledRetry = null
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
    override fun availability(): Flow<FamilyServerAvailability> = currentAvailability
    override fun lastServerHealthyAt(): Flow<Long?> = preferences.lastServerHealthyAt
    override fun session(): Flow<SyncSession> = preferences.session
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun pendingPublishCount(): Flow<Int> = preferences.session.flatMapLatest { session ->
        if (!session.isJoined) {
            flowOf(0)
        } else {
            pendingPublishDao.observeCount()
        }
    }
    override fun familyMemberDirectory(): Flow<List<FamilyMember>> =
        preferences.familyMemberDirectory

    override fun refreshFamilyMemberDirectory() {
        processScope.launch { listFamilyMembers() }
    }

    override suspend fun probeServerAvailability(
        reason: AvailabilityProbeReason,
    ): Result<FamilyServerAvailability> {
        val endpoint = preferences.verifiedEndpoint.first()
            ?: return Result.success(FamilyServerAvailability.Disabled).also {
                currentAvailability.value = FamilyServerAvailability.Disabled
            }
        val now = clock.nowMillis()
        if (!FamilyServerAvailabilityPolicy.shouldProbe(currentAvailability.value, reason, now)) {
            return Result.success(currentAvailability.value)
        }
        return availabilityProbeMutex.withLock {
            val lockedNow = clock.nowMillis()
            if (
                !FamilyServerAvailabilityPolicy.shouldProbe(
                    currentAvailability.value,
                    reason,
                    lockedNow,
                )
            ) {
                return@withLock Result.success(currentAvailability.value)
            }
            val previous = currentAvailability.value
            val lastHealthyAt = when (previous) {
                is FamilyServerAvailability.Available -> previous.lastHealthyAtMillis
                is FamilyServerAvailability.Checking -> previous.lastHealthyAtMillis
                is FamilyServerAvailability.Unavailable -> previous.lastHealthyAtMillis
                FamilyServerAvailability.Disabled -> null
            }
            currentAvailability.value = FamilyServerAvailability.Checking(lastHealthyAt)
            try {
                val (healthOutcome, readyOutcome, setupOutcome) =
                    withTimeout(AVAILABILITY_TIMEOUT_MILLIS) {
                        supervisorScope {
                            val health = async {
                                captureAvailabilityProbe {
                                    backend.anonymousHealth(endpoint)
                                }
                            }
                            val ready = async {
                                captureAvailabilityProbe {
                                    backend.anonymousReady(endpoint)
                                }
                            }
                            val setup = async {
                                captureAvailabilityProbe {
                                    setupProbe.probe(endpoint.origin, endpoint)
                                }
                            }
                            Triple(health.await(), ready.await(), setup.await())
                        }
                    }
                val setup = setupOutcome.getOrElse { failure ->
                    throw AvailabilityProbeFailure(failure.toAvailabilityUnavailableReason())
                }
                val setupReady = setup as? SetupProbeResult.Ready
                    ?: throw AvailabilityProbeFailure(setup.toUnavailableReason())
                val health = healthOutcome.getOrElse { failure ->
                    throw AvailabilityProbeFailure(failure.toAvailabilityUnavailableReason())
                }
                val ready = readyOutcome.getOrElse { failure ->
                    throw AvailabilityProbeFailure(failure.toAvailabilityUnavailableReason())
                }
                if (
                    !health.capabilities.containsAll(REQUIRED_HEALTH_CAPABILITIES) ||
                    health.version != ready.version
                ) {
                    throw AvailabilityProbeFailure(FamilyServerUnavailableReason.Incompatible)
                }
                val healthyAt = clock.nowMillis()
                preferences.saveLastServerHealthyAt(healthyAt)
                val available = FamilyServerAvailability.Available(
                    endpointOrigin = endpoint.origin,
                    serverVersion = health.version,
                    lastHealthyAtMillis = healthyAt,
                    leaseUntilMillis = healthyAt + FamilyServerAvailabilityPolicy.HEALTHY_LEASE_MILLIS,
                    familyState = setupReady.familyState,
                )
                currentAvailability.value = available
                Result.success(available)
            } catch (timeout: TimeoutCancellationException) {
                publishAvailabilityFailure(
                    previous = previous,
                    lastHealthyAt = lastHealthyAt,
                    reason = FamilyServerUnavailableReason.Unreachable,
                )
            } catch (cancelled: CancellationException) {
                currentAvailability.value = previous
                throw cancelled
            } catch (failure: AvailabilityProbeFailure) {
                publishAvailabilityFailure(previous, lastHealthyAt, failure.reason)
            } catch (_: Throwable) {
                publishAvailabilityFailure(
                    previous,
                    lastHealthyAt,
                    FamilyServerUnavailableReason.Unreachable,
                )
            }
        }
    }
    override fun verifiedEndpoint(): Flow<TrustedEndpointProfile?> = preferences.verifiedEndpoint
    override fun pendingMemberLogin(): Flow<PendingMemberLogin?> = preferences.pendingMemberLogin
    override fun memberLoginChecks(): Flow<MemberLoginCheckResult> = memberLoginCheckEvents

    override fun neighborAlignmentHints(): Flow<String> = neighborAlignmentHintEvents
    override fun availableOptionalAppUpdate(): Flow<AppUpdateMetadata?> =
        optionalAppUpdateState

    override fun availableForcedAppUpdate(): Flow<ForcedAppUpdateState?> =
        forcedAppUpdateState

    override fun forcedUpdateLanInviteHost(): Flow<String?> =
        forcedUpdateLanInviteHostState

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

    override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult =
        probeReconnectCandidate(endpointDraft, null)

    override suspend fun trustReconnectCertificate(
        candidate: CertificateTrustCandidate,
    ): SetupProbeResult {
        val endpoint = candidate.trustedEndpoint()
        return probeReconnectCandidate(endpoint.origin, endpoint)
    }

    private suspend fun probeReconnectCandidate(
        endpointDraft: String,
        trustedEndpoint: TrustedEndpointProfile?,
    ): SetupProbeResult = try {
        withTimeout(AVAILABILITY_TIMEOUT_MILLIS) {
            val setup = setupProbe.probe(endpointDraft, trustedEndpoint)
            if (setup !is SetupProbeResult.Ready) return@withTimeout setup
            val (healthOutcome, readyOutcome) = supervisorScope {
                val health = async {
                    captureAvailabilityProbe { backend.anonymousHealth(setup.endpoint) }
                }
                val ready = async {
                    captureAvailabilityProbe { backend.anonymousReady(setup.endpoint) }
                }
                health.await() to ready.await()
            }
            val health = healthOutcome.getOrElse { failure ->
                throw AvailabilityProbeFailure(failure.toAvailabilityUnavailableReason())
            }
            val ready = readyOutcome.getOrElse { failure ->
                throw AvailabilityProbeFailure(failure.toAvailabilityUnavailableReason())
            }
            if (
                !health.capabilities.containsAll(REQUIRED_HEALTH_CAPABILITIES) ||
                health.version != ready.version
            ) {
                SetupProbeResult.Failed.Incompatible
            } else {
                setup
            }
        }
    } catch (_: TimeoutCancellationException) {
        SetupProbeResult.Failed.Unreachable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AvailabilityProbeFailure) {
        when (failure.reason) {
            FamilyServerUnavailableReason.Maintenance -> SetupProbeResult.Failed.Maintenance
            FamilyServerUnavailableReason.Incompatible -> SetupProbeResult.Failed.Incompatible
            FamilyServerUnavailableReason.NotLezi -> SetupProbeResult.Failed.NotLezi
            FamilyServerUnavailableReason.TrustChanged ->
                SetupProbeResult.Failed.CertificateChanged
            FamilyServerUnavailableReason.Unreachable -> SetupProbeResult.Failed.Unreachable
        }
    } catch (_: Throwable) {
        SetupProbeResult.Failed.Unreachable
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
        pendingAvailabilityReason.accumulateAndGet(trigger.toAvailabilityProbeReason()) {
                current,
                incoming,
            ->
            mergeAvailabilityProbeReason(current, incoming)
        }
        syncSignal.trySend(Unit)
    }

    override fun notifyLocalChanges() {
        requestSync(SyncTrigger.LocalWrite)
    }

    override fun notifyNetworkRecovered() {
        val now = clock.nowMillis()
        while (true) {
            val lastAccepted = lastAcceptedNetworkRecoveredAtMillis.get()
            if (
                !FamilyServerAvailabilityPolicy.shouldAcceptNetworkRecoveredSignal(
                    lastAcceptedAtMillis = lastAccepted,
                    nowMillis = now,
                )
            ) {
                return
            }
            if (lastAcceptedNetworkRecoveredAtMillis.compareAndSet(lastAccepted, now)) break
        }
        pullRequested.set(true)
        pendingAvailabilityReason.set(AvailabilityProbeReason.NetworkRecovered)
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

    override suspend fun reconnectOwner(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        rootPassword: String,
    ): Result<OwnerLoginResult> = runCatching {
        require(rootPassword.isNotBlank()) { "请填写管理员根密码" }
        val candidate = probeReconnectCandidate(endpoint.origin, endpoint)
        require(candidate is SetupProbeResult.Ready) { "候选家庭服务器尚未通过连接校验" }
        require(candidate.familyState == com.lezi.babylog.sync.session.SetupFamilyState.Configured) {
            "空服务器只能使用家庭灾难恢复"
        }
        syncMutex.withLock {
            require(preferences.disasterRestoreCheckpoint.first() == null) {
                "请先完成或取消当前家庭灾难恢复"
            }
            val previous = preferences.session.first()
            require(previous.familyId.isNotBlank()) { "本机没有可重连的家庭身份" }
            val loginRequestId = preferences.ensureOwnerLoginRequestId()
            val joined = try {
                backend.ownerLogin(
                    endpoint = endpoint,
                    deviceName = com.lezi.babylog.sync.session.requireDeviceName(deviceName),
                    loginRequestId = loginRequestId,
                    rootPassword = rootPassword,
                    // Network-settings reconnect is recovery of the one retained
                    // Owner identity. Revoke older Owner devices by default so a
                    // retry does not silently accumulate ghost administrators.
                    takeover = true,
                )
            } catch (error: SyncHttpException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    throw OwnerRootPasswordRejectedException()
                }
                if (error.statusCode == 409) {
                    preferences.clearOwnerLoginRequestId()
                    throw IllegalStateException("登录方式或设备称呼已变化，请重试", error)
                }
                throw error
            }
            require(joined.role == FamilyRole.Owner) { "管理员登录响应角色无效" }
            if (joined.familyId != previous.familyId) throw DifferentFamilyServerException()
            val config = FamilyEndpointConfig.fromBaseUrl(endpoint.origin).withNormalized()
            val session = SyncSession(
                familyId = joined.familyId,
                accessToken = joined.accessToken,
                refreshToken = joined.refreshToken,
                accessExpiresAtEpochSeconds = joined.accessExpiresAtEpochSeconds,
                deviceId = joined.deviceId,
                role = joined.role,
                pullCursor = 0L,
                pullGeneration = joined.generation,
                serverHost = config.host,
                serverPort = config.port,
                serverScheme = config.scheme,
                familyName = joined.familyName?.trim()?.takeIf(String::isNotEmpty),
                membershipId = joined.membershipId.trim(),
                pendingCreatorAcknowledgements = previous.pendingCreatorAcknowledgements,
            )
            preferences.saveReconnectedSession(session, endpoint)
            publishSession(session)
            currentAvailability.value = FamilyServerAvailability.Disabled
            val dataRecovery = try {
                requestSync(SyncTrigger.Foreground)
                InitialFamilyDataRecovery.NotRequired
            } catch (_: Throwable) {
                InitialFamilyDataRecovery.RetryRequired
            }
            OwnerLoginResult(session, dataRecovery)
        }
    }

    override suspend fun prepareDisasterRecovery(): Result<DisasterRecoverySummary> = runCatching {
        requireRetainedOwnerSession()
        disasterRecoverySnapshotBuilder.build().use { it.summary }
    }

    override suspend fun startDisasterRecovery(
        endpoint: TrustedEndpointProfile,
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ): Result<DisasterRecoveryProgress> = mapDisasterRecoveryClientUpdateRequired(
        inviteHostHint = hostForLanInvite(endpoint),
        result = runCatching {
            require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
            syncMutex.withLock {
                preferences.disasterRestoreCheckpoint.first()?.let {
                    return@withLock resumeDisasterRecoveryLocked(it)
                }
                val previous = requireRetainedOwnerSession()
                val probe = probeReconnectCandidate(endpoint.origin, endpoint)
                require(
                    probe is SetupProbeResult.Ready &&
                        probe.familyState == com.lezi.babylog.sync.session.SetupFamilyState.Empty,
                ) { "家庭灾难恢复只适用于已校验的空服务器" }
                val familyName = requireNotNull(
                    previous.familyName?.trim()?.takeIf(String::isNotEmpty),
                ) {
                    "本机缺少旧家庭名称，无法安全恢复"
                }
                val requestIds = preferences.ensureDisasterRestoreRequestIds()
                disasterRecoverySnapshotBuilder.build().use { snapshot ->
                    val batch = backend.startDisasterRestore(
                        endpoint = endpoint,
                        requestId = requestIds.start,
                        familyId = previous.familyId,
                        familyName = familyName,
                        ownerDisplayName = requireMemberDisplayName(ownerDisplayName),
                        deviceName = requireDeviceName(deviceName),
                        rootPassword = rootPassword,
                    )
                    var checkpoint = DisasterRestoreCheckpoint(
                        batchId = batch.batchId,
                        endpoint = endpoint,
                        familyId = previous.familyId,
                        startRequestId = requestIds.start,
                        manifestRequestId = requestIds.manifest,
                        commitRequestId = requestIds.commit,
                        expiresAtEpochSeconds = batch.expiresAtEpochSeconds,
                        status = batch.status,
                        entityVersions = snapshot.retirementVersions,
                    )
                    preferences.saveDisasterRestoreCheckpoint(checkpoint, batch.recoveryToken)
                    val status = uploadDisasterRecoverySnapshot(
                        checkpoint,
                        batch.recoveryToken,
                        snapshot,
                        uploadManifest = true,
                    )
                    checkpoint = checkpoint.copy(
                        status = status.status,
                        expiresAtEpochSeconds = status.expiresAtEpochSeconds,
                    )
                    preferences.saveDisasterRestoreCheckpoint(checkpoint, batch.recoveryToken)
                    DisasterRecoveryProgress(
                        summary = snapshot.summary,
                        status = status.status,
                        expiresAtEpochSeconds = status.expiresAtEpochSeconds,
                    )
                }
            }
        },
    )

    override suspend fun resumeDisasterRecovery(): Result<DisasterRecoveryProgress> {
        val checkpoint = preferences.disasterRestoreCheckpoint.first()
        return mapDisasterRecoveryClientUpdateRequired(
            inviteHostHint = checkpoint?.let { hostForLanInvite(it.endpoint) },
            result = runCatching {
                syncMutex.withLock {
                    val current = requireNotNull(preferences.disasterRestoreCheckpoint.first()) {
                        "没有可继续的家庭恢复批次"
                    }
                    resumeDisasterRecoveryLocked(current)
                }
            },
        )
    }

    override suspend fun commitDisasterRecovery(
        rootPassword: String,
    ): Result<OwnerLoginResult> {
        val checkpoint = preferences.disasterRestoreCheckpoint.first()
        return mapDisasterRecoveryClientUpdateRequired(
            inviteHostHint = checkpoint?.let { hostForLanInvite(it.endpoint) },
            result = runCatching {
                require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
                syncMutex.withLock {
                    val current = requireNotNull(preferences.disasterRestoreCheckpoint.first()) {
                        "没有等待提交的家庭恢复批次"
                    }
                    val token = preferences.disasterRestoreToken().also {
                        require(it.isNotBlank()) { "家庭恢复凭据已丢失，请取消后重新开始" }
                    }
                    val previous = requireRetainedOwnerSession()
                    require(previous.familyId == current.familyId) {
                        "本机家庭身份已变化，恢复已停止"
                    }
                    val joined = backend.commitDisasterRestore(
                        endpoint = current.endpoint,
                        batchId = current.batchId,
                        recoveryToken = token,
                        requestId = current.commitRequestId,
                        rootPassword = rootPassword,
                    )
                    require(joined.role == FamilyRole.Owner) { "家庭恢复响应角色无效" }
                    if (joined.familyId != previous.familyId) throw DifferentFamilyServerException()
                    val config = FamilyEndpointConfig
                        .fromBaseUrl(current.endpoint.origin)
                        .withNormalized()
                    val session = SyncSession(
                        familyId = joined.familyId,
                        accessToken = joined.accessToken,
                        refreshToken = joined.refreshToken,
                        accessExpiresAtEpochSeconds = joined.accessExpiresAtEpochSeconds,
                        deviceId = joined.deviceId,
                        role = joined.role,
                        pullCursor = 0L,
                        pullGeneration = joined.generation,
                        serverHost = config.host,
                        serverPort = config.port,
                        serverScheme = config.scheme,
                        familyName = joined.familyName?.trim()?.takeIf(String::isNotEmpty),
                        membershipId = joined.membershipId.trim(),
                    )
                    retireDisasterRestoreReceipts(session, current)
                    preferences.saveReconnectedSession(session, current.endpoint)
                    preferences.clearDisasterRestoreCheckpoint()
                    publishSession(session)
                    currentAvailability.value = FamilyServerAvailability.Disabled
                    requestSync(SyncTrigger.Foreground)
                    OwnerLoginResult(session, InitialFamilyDataRecovery.Complete)
                }
            },
        )
    }

    override suspend fun cancelDisasterRecovery(): Result<Unit> = runCatching {
        syncMutex.withLock {
            val checkpoint = preferences.disasterRestoreCheckpoint.first() ?: return@withLock
            val token = preferences.disasterRestoreToken()
            require(token.isNotBlank()) { "家庭恢复凭据已丢失，请清除本机恢复状态" }
            backend.cancelDisasterRestore(
                checkpoint.endpoint,
                checkpoint.batchId,
                token,
            )
            preferences.clearDisasterRestoreCheckpoint()
        }
    }

    override suspend fun requestMemberLogin(
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = executeFamily(
        FamilySessionCommand.RequestMemberLogin(displayName, deviceName),
    ).map { (it as FamilySessionOutcome.MemberLoginRequested).request }

    override suspend fun requestReconnectMember(
        endpoint: TrustedEndpointProfile,
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> = runCatching {
        reconnectMutex.withLock {
            require(pendingReconnectMember.get() == null) {
                "已有一条候选服务器加入申请"
            }
            val probe = probeReconnectCandidate(endpoint.origin, endpoint)
            require(
                probe is SetupProbeResult.Ready &&
                    probe.familyState == com.lezi.babylog.sync.session.SetupFamilyState.Configured,
            ) { "候选家庭服务器尚未完成配置或连接校验" }
            val normalizedDisplayName = requireMemberDisplayName(displayName)
            val normalizedDeviceName = requireDeviceName(deviceName)
            val receipt = backend.requestMemberLogin(
                endpoint,
                normalizedDisplayName,
                normalizedDeviceName,
            )
            val public = PendingMemberLogin(
                requestId = receipt.requestId,
                displayName = normalizedDisplayName,
                deviceName = normalizedDeviceName,
                expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
            )
            pendingReconnectMember.set(
                CandidateMemberReconnectAttempt(
                    endpoint = endpoint,
                    pendingSecret = receipt.pendingSecret,
                    request = public,
                ),
            )
            public
        }
    }

    override suspend fun checkReconnectMember(): Result<MemberLoginCheckResult> = runCatching {
        reconnectMutex.withLock {
            val attempt = requireNotNull(pendingReconnectMember.get()) {
                "没有等待管理员确认的候选服务器申请"
            }
            when (val status = backend.memberLoginStatus(attempt.endpoint, attempt.pendingSecret)) {
                com.lezi.babylog.sync.backend.MemberLoginStatus.Pending ->
                    MemberLoginCheckResult.Waiting(attempt.request)
                com.lezi.babylog.sync.backend.MemberLoginStatus.Approved,
                com.lezi.babylog.sync.backend.MemberLoginStatus.Claimed,
                -> {
                    val joined = try {
                        backend.claimMemberLogin(attempt.endpoint, attempt.pendingSecret)
                    } catch (error: SyncHttpException) {
                        if (
                            status == com.lezi.babylog.sync.backend.MemberLoginStatus.Claimed &&
                            error.statusCode in setOf(404, 409, 410)
                        ) {
                            pendingReconnectMember.set(null)
                            return@withLock MemberLoginCheckResult.Terminal(status)
                        }
                        throw error
                    }
                    val previous = preferences.session.first()
                    if (joined.familyId != previous.familyId) throw DifferentFamilyServerException()
                    require(joined.role == FamilyRole.Member) { "成员登录响应角色无效" }
                    val config = FamilyEndpointConfig.fromBaseUrl(attempt.endpoint.origin)
                        .withNormalized()
                    val session = SyncSession(
                        familyId = joined.familyId,
                        accessToken = joined.accessToken,
                        refreshToken = joined.refreshToken,
                        accessExpiresAtEpochSeconds = joined.accessExpiresAtEpochSeconds,
                        deviceId = joined.deviceId,
                        role = joined.role,
                        pullCursor = 0L,
                        pullGeneration = joined.generation,
                        serverHost = config.host,
                        serverPort = config.port,
                        serverScheme = config.scheme,
                        familyName = joined.familyName?.trim()?.takeIf(String::isNotEmpty),
                        membershipId = joined.membershipId.trim(),
                        pendingCreatorAcknowledgements = previous.pendingCreatorAcknowledgements,
                    )
                    syncMutex.withLock {
                        preferences.saveReconnectedSession(session, attempt.endpoint)
                        publishSession(session)
                        currentAvailability.value = FamilyServerAvailability.Disabled
                    }
                    pendingReconnectMember.set(null)
                    val dataRecovery = try {
                        requestSync(SyncTrigger.Foreground)
                        InitialFamilyDataRecovery.NotRequired
                    } catch (_: Throwable) {
                        InitialFamilyDataRecovery.RetryRequired
                    }
                    MemberLoginCheckResult.Joined(
                        session,
                        dataRecovery,
                    )
                }
                else -> {
                    pendingReconnectMember.set(null)
                    MemberLoginCheckResult.Terminal(status)
                }
            }
        }
    }

    override suspend fun cancelReconnectMember(): Result<Unit> = runCatching {
        val attempt = reconnectMutex.withLock {
            pendingReconnectMember.getAndSet(null)
        }
        if (attempt != null) {
            processScope.launch {
                try {
                    backend.cancelMemberLogin(attempt.endpoint, attempt.pendingSecret)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The candidate attempt is already retired locally; remote cleanup expires.
                }
            }
        }
    }

    override suspend fun checkMemberLogin(): Result<MemberLoginCheckResult> {
        val result = executeFamily(FamilySessionCommand.CheckMemberLogin)
            .map { (it as FamilySessionOutcome.MemberLoginChecked).result }
        result.getOrNull()?.let(memberLoginCheckEvents::tryEmit)
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

    override suspend fun createMemberLoginQrCode(
        membershipId: String,
    ): Result<MemberLoginQrCode> = executeFamily(
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
        MemberLoginQrCode(
            payload = MemberLoginQrPayload(
                endpoint = endpoint,
                grant = grant.grant,
                familyName = grant.familyName,
                memberDisplayName = grant.memberDisplayName,
                expiresAtEpochSeconds = grant.expiresAtEpochSeconds,
            ),
            landingUrl = grant.landingUrl,
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

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> {
        val remote = executeFamily(FamilySessionCommand.ListMembers)
        val members = remote.getOrElse { return Result.failure(it) }
            .let { (it as FamilySessionOutcome.MembersListed).members }
        return try {
            preferences.saveFamilyMemberDirectory(members)
            Result.success(members)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

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

    override suspend fun syncWhenAvailable(trigger: SyncTrigger): Result<Unit> {
        val availability = probeServerAvailability(trigger.toAvailabilityProbeReason())
            .getOrElse { return Result.failure(it) }
        if (availability !is FamilyServerAvailability.Available) {
            return Result.failure(FamilyServerCurrentlyUnavailableException())
        }
        return sync(trigger)
    }

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
        if (failure is CancellationException) {
            // Cancellation is control flow, not a sync failure. Restore the durable session's
            // steady projection before propagating it so no collector is orphaned in Syncing.
            publishSession(cachedSession)
            throw failure
        }
        if (failure != null) demoteAvailabilityAfterTransportFailure(failure)
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
                val clientUpdateRequired =
                    (failure as? SyncHttpException)?.clientUpdateRequiredOrNull()
                if (clientUpdateRequired != null) {
                    handledClientUpdateRequired = true
                    handleClientUpdateRequired(clientUpdateRequired)
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
            val retainedForce = forcedAppUpdateState.value
                .takeIf { session.retainsFamilyIdentityForReauth() }
            if (retainedForce == null) clearForcedAppUpdateState()
            return Result.success(
                when (retainedForce) {
                    is ForcedAppUpdateState.WithPackage ->
                        AppUpdateCheckResult.ForcedUpdate(retainedForce.metadata)
                    ForcedAppUpdateState.PackageUnknown ->
                        AppUpdateCheckResult.ForcedPackageUnknown
                    null -> AppUpdateCheckResult.NotJoined
                },
            )
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
                }.onSuccess { result ->
                    if (result == AppUpdateInstallResult.SessionStarted) {
                        val current = optionalAppUpdateState.value
                        if (current?.versionCode == metadata.versionCode) {
                            optionalAppUpdateState.value = null
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
                if (!session.retainsFamilyIdentityForReauth()) {
                    clearForcedAppUpdateState()
                }
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
     * When [preserveExistingForceShell] is true (active force shell, or CUR resolve):
     * - any metadata with `versionCode > local` publishes installable
     *   [ForcedAppUpdateState.WithPackage] (even if dual-tier would be optional-only);
     * - otherwise an existing force surface is left intact, optional is not published,
     *   and the **returned** [AppUpdateCheckResult] stays force-honest
     *   ([ForcedUpdate] for a retained package, [ForcedPackageUnknown] otherwise) —
     *   never bare UpToDate/Optional, so Settings/Family/retry UI cannot claim 假正常
     *   while the shell blocks.
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
        // Dual-tier forced (local < min) always installs the advertised package.
        if (local < metadata.minSupportedVersionCode) {
            optionalAppUpdateState.value = null
            forcedAppUpdateState.value = ForcedAppUpdateState.WithPackage(metadata)
            return AppUpdateCheckResult.ForcedUpdate(metadata)
        }
        if (preserveExistingForceShell) {
            // CUR / active force shell: versionCode > local is always installable,
            // even when minSupported alone would leave dual-tier optional-only.
            if (local < metadata.versionCode) {
                optionalAppUpdateState.value = null
                forcedAppUpdateState.value = ForcedAppUpdateState.WithPackage(metadata)
                return AppUpdateCheckResult.ForcedUpdate(metadata)
            }
            when (val existing = forcedAppUpdateState.value) {
                is ForcedAppUpdateState.WithPackage -> {
                    // Nothing newer to install — keep last installable package.
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
        clearForcedAppUpdateState()
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
     * [checkAppUpdate] recover. Best-effort load so force UI can install.
     * After CUR, any reachable metadata with `versionCode > local` becomes installable
     * [ForcedUpdate] (even dual-tier optional-only); metadata/channel failure or
     * nothing newer than local still leaves a non-silent force shell
     * ([PackageUnknown] or a prior [WithPackage]).
     *
     * @return installable ForcedUpdate when a package was accepted; null when a
     * force shell was published without an installable package (caller rethrows CUR).
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
                    // preserveExistingForceShell=true: CUR treats versionCode > local as
                    // installable ForcedUpdate (not optional banner / PackageUnknown).
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

    /**
     * Disaster-restore writes are not wrapped by [RefreshingSyncBackend]'s CUR mapping.
     * Map wire `client_update_required` to [ClientUpdateRequiredException], publish the
     * same force shell as sync, and prefer the restore candidate host for 8767 guidance.
     */
    private suspend fun <T> mapDisasterRecoveryClientUpdateRequired(
        inviteHostHint: String?,
        result: Result<T>,
    ): Result<T> {
        val failure = result.exceptionOrNull() ?: return result
        val required = when (failure) {
            is ClientUpdateRequiredException -> failure
            is SyncHttpException -> failure.clientUpdateRequiredOrNull()
            else -> null
        } ?: return result
        val host = inviteHostHint?.trim()?.takeIf { it.isNotEmpty() }
        if (host != null) {
            forcedUpdateLanInviteHostState.value = host
        }
        resolveForceShellAfterClientUpdateRequired()
        return Result.failure(required)
    }

    private fun hostForLanInvite(endpoint: TrustedEndpointProfile): String? =
        runCatching {
            FamilyEndpointConfig.fromBaseUrl(endpoint.origin).withNormalized().host
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Clears force shell and any restore-candidate invite-host override together. */
    private fun clearForcedAppUpdateState() {
        forcedAppUpdateState.value = null
        forcedUpdateLanInviteHostState.value = null
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

    private suspend fun synchronizeJoinedSessionLocked(
        session: SyncSession,
        trigger: SyncTrigger,
    ) {
        val outcome = replicaSyncEngine.synchronize(session, trigger)
        preferences.markSuccess(clock.nowMillis())
        cachedSession = preferences.session.first()
        currentStatus.value = when (outcome) {
            is ReplicaSyncOutcome.Synchronized -> {
                maybeEmitNeighborAlignmentHint(
                    session = session,
                    neighborLoserClientUuids = outcome.neighborLoserClientUuids,
                )
                SyncStatus.Idle
            }
        }
    }

    /**
     * At most one light hint per sync cycle when the server explicitly listed a
     * neighbor-loser uuid authored by the current membership. Ordinary remote
     * deletes without that signal never use this copy.
     */
    private suspend fun maybeEmitNeighborAlignmentHint(
        session: SyncSession,
        neighborLoserClientUuids: Set<String>,
    ) {
        if (neighborLoserClientUuids.isEmpty()) return
        val membershipId = session.membershipId
        if (membershipId.isBlank()) return
        val selfAuthored = neighborLoserClientUuids.any { uuid ->
            recordDao.getByClientUuid(uuid)?.createdByMembershipId == membershipId
        }
        if (!selfAuthored) return
        neighborAlignmentHintEvents.tryEmit("已与家人同一时间的记录对齐")
    }

    private suspend fun requireRetainedOwnerSession(): SyncSession =
        preferences.session.first().also { session ->
            require(session.familyId.isNotBlank() && session.role == FamilyRole.Owner) {
                "只有仍保留旧家庭管理员身份的设备可以恢复空服务器"
            }
        }

    private suspend fun resumeDisasterRecoveryLocked(
        checkpoint: DisasterRestoreCheckpoint,
    ): DisasterRecoveryProgress {
        val token = preferences.disasterRestoreToken().also {
            require(it.isNotBlank()) { "家庭恢复凭据已丢失，请取消后重新开始" }
        }
        requireRetainedOwnerSession().also {
            require(it.familyId == checkpoint.familyId) { "本机家庭身份已变化，恢复已停止" }
        }
        val remote = try {
            backend.disasterRestoreStatus(
                checkpoint.endpoint,
                checkpoint.batchId,
                token,
            )
        } catch (error: SyncHttpException) {
            if (error.statusCode == 404 || error.statusCode == 410) {
                preferences.clearDisasterRestoreCheckpoint()
                throw IllegalStateException("家庭恢复批次已失效，请重新开始", error)
            }
            throw error
        }
        if (remote.readyToCommit || remote.committed) {
            preferences.saveDisasterRestoreCheckpoint(
                checkpoint.copy(
                    status = remote.status,
                    expiresAtEpochSeconds = remote.expiresAtEpochSeconds,
                ),
                token,
            )
            return DisasterRecoveryProgress(null, remote.status, remote.expiresAtEpochSeconds)
        }
        disasterRecoverySnapshotBuilder.build().use { snapshot ->
            val uploadManifest = remote.status == "started"
            val activeCheckpoint = if (uploadManifest) {
                checkpoint.copy(entityVersions = snapshot.retirementVersions)
            } else {
                checkpoint
            }
            val status = uploadDisasterRecoverySnapshot(
                activeCheckpoint,
                token,
                snapshot,
                uploadManifest,
            )
            preferences.saveDisasterRestoreCheckpoint(
                activeCheckpoint.copy(
                    status = status.status,
                    expiresAtEpochSeconds = status.expiresAtEpochSeconds,
                ),
                token,
            )
            return DisasterRecoveryProgress(
                snapshot.summary,
                status.status,
                status.expiresAtEpochSeconds,
            )
        }
    }

    private suspend fun uploadDisasterRecoverySnapshot(
        checkpoint: DisasterRestoreCheckpoint,
        token: String,
        snapshot: com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshot,
        uploadManifest: Boolean,
    ): DisasterRestoreStatus {
        if (uploadManifest) {
            backend.putDisasterRestoreManifest(
                endpoint = checkpoint.endpoint,
                batchId = checkpoint.batchId,
                recoveryToken = token,
                requestId = checkpoint.manifestRequestId,
                entities = snapshot.entities,
                media = snapshot.media.map { it.spec },
            )
        }
        snapshot.media.forEach { media ->
            backend.putDisasterRestoreMedia(
                endpoint = checkpoint.endpoint,
                batchId = checkpoint.batchId,
                recoveryToken = token,
                clientUuid = media.clientUuid,
                source = media.source,
            )
        }
        return backend.disasterRestoreStatus(
            checkpoint.endpoint,
            checkpoint.batchId,
            token,
        ).also {
            require(it.readyToCommit || it.committed) {
                "家庭数据或照片尚未完整上传"
            }
        }
    }

    private suspend fun retireDisasterRestoreReceipts(
        restored: SyncSession,
        checkpoint: DisasterRestoreCheckpoint,
    ) {
        transactionRunner.run {
            // Care remains writable while a restore batch uploads. Re-author every local row to
            // the new Owner, including rows created after the immutable restore manifest, but
            // retire publication state only when the Room version is exactly the version
            // activated by the server. Newer local work stays dirty and publishes after switch.
            recordDao.listAllIncludingDeleted().forEach { record ->
                if (record.createdByMembershipId != restored.membershipId) {
                    recordDao.update(record.copy(createdByMembershipId = restored.membershipId))
                }
            }
            carePlanDao.listAllIncludingDeleted().forEach { plan ->
                if (plan.createdByMembershipId != restored.membershipId) {
                    carePlanDao.update(plan.copy(createdByMembershipId = restored.membershipId))
                }
            }
            customItemDao.listAllIncludingDeleted().forEach { item ->
                if (item.createdByMembershipId != restored.membershipId) {
                    customItemDao.update(item.copy(createdByMembershipId = restored.membershipId))
                }
            }
            fulfillmentCandidateDao.listAllIncludingDeleted().forEach { candidate ->
                if (
                    candidate.submitterMembershipId != restored.membershipId ||
                    candidate.submitterRole != "owner"
                ) {
                    fulfillmentCandidateDao.update(
                        candidate.copy(
                            submitterMembershipId = restored.membershipId,
                            submitterRole = "owner",
                        ),
                    )
                }
            }
            checkpoint.entityVersions.forEach { version ->
                when (version.type) {
                    "baby" -> babyDao.markSynced(version.clientUuid, version.updatedAt)
                    "record" -> recordDao.getByClientUuid(version.clientUuid)
                        ?.takeIf { it.updatedAt == version.updatedAt }
                        ?.let { record ->
                            recordDao.update(
                                record.copy(
                                    createdByMembershipId = if (version.restored) {
                                        restored.membershipId
                                    } else {
                                        record.createdByMembershipId
                                    },
                                    familyPublishedUpdatedAt = if (version.restored) {
                                        record.updatedAt
                                    } else {
                                        record.familyPublishedUpdatedAt
                                    },
                                    syncDirty = false,
                                ),
                            )
                        }
                    "care_plan" -> carePlanDao.getByClientUuid(version.clientUuid)
                        ?.takeIf { it.updatedAt == version.updatedAt }
                        ?.let { plan ->
                            carePlanDao.update(
                                plan.copy(
                                    createdByMembershipId = if (version.restored) {
                                        restored.membershipId
                                    } else {
                                        plan.createdByMembershipId
                                    },
                                    familyPublishedUpdatedAt = if (version.restored) {
                                        plan.updatedAt
                                    } else {
                                        plan.familyPublishedUpdatedAt
                                    },
                                    syncDirty = false,
                                ),
                            )
                        }
                    "custom_item" -> customItemDao.getByClientUuid(version.clientUuid)
                        ?.takeIf { it.updatedAt == version.updatedAt }
                        ?.let { item ->
                            customItemDao.update(
                                item.copy(
                                    createdByMembershipId = if (version.restored) {
                                        restored.membershipId
                                    } else {
                                        item.createdByMembershipId
                                    },
                                    syncDirty = false,
                                ),
                            )
                        }
                    "fulfillment_candidate" ->
                        fulfillmentCandidateDao.getByClientUuid(version.clientUuid)
                            ?.takeIf { it.updatedAt == version.updatedAt }
                            ?.let { candidate ->
                                fulfillmentCandidateDao.update(
                                    candidate.copy(
                                        submitterMembershipId = if (version.restored) {
                                            restored.membershipId
                                        } else {
                                            candidate.submitterMembershipId
                                        },
                                        submitterRole = if (version.restored) {
                                            "owner"
                                        } else {
                                            candidate.submitterRole
                                        },
                                        syncDirty = false,
                                    ),
                                )
                            }
                    "media" -> mediaDao.getByClientUuid(version.clientUuid)
                        ?.takeIf { it.updatedAt == version.updatedAt }
                        ?.let { media ->
                            mediaDao.update(
                                media.copy(
                                    remoteUri = if (version.restored) {
                                        restored.receiptFor(media.clientUuid)
                                    } else {
                                        media.remoteUri
                                    },
                                    syncDirty = false,
                                ),
                            )
                        }
                }
            }
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

    private fun demoteAvailabilityAfterTransportFailure(failure: Throwable) {
        if (!failure.isAvailabilityTransportFailure()) return
        val available = currentAvailability.value as? FamilyServerAvailability.Available ?: return
        publishAvailabilityFailure(
            previous = available,
            lastHealthyAt = available.lastHealthyAtMillis,
            reason = failure.toAvailabilityUnavailableReason(),
        )
    }

    private fun publishAvailabilityFailure(
        previous: FamilyServerAvailability,
        lastHealthyAt: Long?,
        reason: FamilyServerUnavailableReason,
    ): Result<FamilyServerAvailability> {
        val failures = (previous as? FamilyServerAvailability.Unavailable)
            ?.consecutiveFailures
            ?.plus(1)
            ?: 1
        val unavailable = FamilyServerAvailability.Unavailable(
            reason = reason,
            lastHealthyAtMillis = lastHealthyAt,
            nextProbeAtMillis = clock.nowMillis() +
                FamilyServerAvailabilityPolicy.retryDelayMillis(failures),
            consecutiveFailures = failures,
        )
        currentAvailability.value = unavailable
        return Result.success(unavailable)
    }
}

private data class CandidateMemberReconnectAttempt(
    val endpoint: TrustedEndpointProfile,
    val pendingSecret: String,
    val request: PendingMemberLogin,
) {
    override fun toString(): String =
        "CandidateMemberReconnectAttempt(endpoint=$endpoint, requestId=${request.requestId}, " +
            "pendingSecret=<redacted>)"
}

private class AvailabilityProbeFailure(
    val reason: FamilyServerUnavailableReason,
) : Exception()

private fun SetupProbeResult.toUnavailableReason(): FamilyServerUnavailableReason = when (this) {
    is SetupProbeResult.Ready -> error("ready availability must be handled before failure mapping")
    is SetupProbeResult.CertificateApprovalRequired -> FamilyServerUnavailableReason.TrustChanged
    SetupProbeResult.Failed.CertificateChanged -> FamilyServerUnavailableReason.TrustChanged
    SetupProbeResult.Failed.Maintenance -> FamilyServerUnavailableReason.Maintenance
    SetupProbeResult.Failed.Incompatible -> FamilyServerUnavailableReason.Incompatible
    SetupProbeResult.Failed.NotLezi,
    SetupProbeResult.Failed.InvalidAddress,
    -> FamilyServerUnavailableReason.NotLezi
    SetupProbeResult.Failed.Unreachable -> FamilyServerUnavailableReason.Unreachable
}

private suspend fun <T> captureAvailabilityProbe(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

private fun Throwable.toAvailabilityUnavailableReason(): FamilyServerUnavailableReason = when {
    causeChainContains<SpkiPinMismatchException>() -> FamilyServerUnavailableReason.TrustChanged
    this is SyncHttpException && statusCode >= 500 -> FamilyServerUnavailableReason.Maintenance
    this is SyncHttpException && statusCode == 404 -> FamilyServerUnavailableReason.NotLezi
    this is IllegalArgumentException -> FamilyServerUnavailableReason.Incompatible
    else -> FamilyServerUnavailableReason.Unreachable
}

private fun Throwable.isAvailabilityTransportFailure(): Boolean = when {
    causeChainContains<MediaPrepareException>() -> false
    this is SyncHttpException -> statusCode == 408 || statusCode in 500..599
    else -> causeChainContains<IOException>()
}

private const val AVAILABILITY_TIMEOUT_MILLIS = 8_000L
private val REQUIRED_HEALTH_CAPABILITIES = setOf(
    CAPABILITY_ATOMIC_BUNDLE,
    CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
    CAPABILITY_DISASTER_RESTORE,
    CAPABILITY_AUTHORITATIVE_RECONCILE,
    CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT,
)

/** Reauth keeps the family replica/identity; only a true leave/unconfigure retires force UI. */
private fun SyncSession.retainsFamilyIdentityForReauth(): Boolean =
    reauthRequired && familyId.isNotBlank() && membershipId.isNotBlank() && baseUrl.isNotBlank()

private fun SyncTrigger.toAvailabilityProbeReason(): AvailabilityProbeReason = when (this) {
    SyncTrigger.Foreground -> AvailabilityProbeReason.Foreground
    SyncTrigger.PullToRefresh -> AvailabilityProbeReason.PullToRefresh
    SyncTrigger.LocalWrite -> AvailabilityProbeReason.LocalChanges
}

private fun mergeAvailabilityProbeReason(
    current: AvailabilityProbeReason,
    incoming: AvailabilityProbeReason,
): AvailabilityProbeReason = if (incoming.priority >= current.priority) incoming else current

private val AvailabilityProbeReason.priority: Int
    get() = when (this) {
        AvailabilityProbeReason.LocalChanges -> 0
        AvailabilityProbeReason.RetryDeadline -> 1
        AvailabilityProbeReason.Foreground -> 2
        AvailabilityProbeReason.PullToRefresh -> 3
        AvailabilityProbeReason.NetworkRecovered -> 4
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
