package com.lezi.babylog.sync

import com.lezi.babylog.core.common.validation.StartupBoundaryObservation

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.longOrNull

import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.LocalPersistException
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.PendingPublishDao
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.decodeTerminalReceipt
import com.lezi.babylog.core.database.causal.decodePullDiagnosticReceipt
import com.lezi.babylog.sync.session.UnacceptedFactPresentation
import com.lezi.babylog.sync.session.SkippedPullItem
import com.lezi.babylog.sync.session.toSkippedPullItem
import com.lezi.babylog.sync.session.isAggregateCensusDiagnostic
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SyncStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
import com.lezi.babylog.sync.availability.causeChainContains
import com.lezi.babylog.sync.availability.isAvailabilityTransportFailure
import com.lezi.babylog.sync.availability.toAvailabilityUnavailableReason
import com.lezi.babylog.sync.appupdate.APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.NoOpAppUpdateInstaller
import com.lezi.babylog.sync.appupdate.UnreadableAppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.cleanupAppUpdateStagingFiles
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
import com.lezi.babylog.sync.backend.SyncHeartbeat
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.clientUpdateRequiredOrNull
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.backend.deadline.LOCAL_WRITE_MAX_ELAPSED_MILLIS
import com.lezi.babylog.sync.backend.deadline.withElapsedBudget
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SystemSyncRetryClock
import com.lezi.babylog.sync.backend.syncHttpCodeOrNull
import com.lezi.babylog.sync.clear.LocalReplicaClearCoordinator
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.ConflictSnapshotLoadLocks
import com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshotBuilder
import com.lezi.babylog.sync.engine.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.FamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.engine.ForegroundSyncBlockedException
import com.lezi.babylog.sync.engine.ForegroundSyncDecision
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.engine.NoOpCarePlanFamilyAppliedListener
import com.lezi.babylog.sync.engine.NoOpFamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.engine.ReplicaSyncEngine
import com.lezi.babylog.sync.engine.ReplicaSyncOutcome
import com.lezi.babylog.sync.heartbeat.ForegroundFuseIdentity
import com.lezi.babylog.sync.heartbeat.ForegroundRoundFuse
import com.lezi.babylog.sync.heartbeat.HeartbeatGate
import com.lezi.babylog.sync.heartbeat.HeartbeatJitterSource
import com.lezi.babylog.sync.heartbeat.HeartbeatSessionSnapshot
import com.lezi.babylog.sync.heartbeat.HeartbeatVerdict
import com.lezi.babylog.sync.heartbeat.RandomHeartbeatJitterSource
import com.lezi.babylog.sync.heartbeat.SyncHeartbeatBeat
import com.lezi.babylog.sync.heartbeat.SyncHeartbeatEngine
import com.lezi.babylog.sync.heartbeat.SyncHeartbeatPolicy
import com.lezi.babylog.sync.heartbeat.foregroundFuseIdentity
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.qr.MemberLoginQrCode
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.CAPABILITY_ATOMIC_BUNDLE
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_SYNC_V2
import com.lezi.babylog.sync.session.CAPABILITY_NURSING_PLAN_INTENT_V1
import com.lezi.babylog.sync.session.CAPABILITY_DISASTER_RESTORE
import com.lezi.babylog.sync.session.CAPABILITY_RECORD_MEMBERSHIP_AUTHOR
import com.lezi.babylog.sync.session.CAPABILITY_SYNC_HEARTBEAT_V1
import com.lezi.babylog.sync.session.CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilySessionCommand
import com.lezi.babylog.sync.session.FamilySessionCoordinator
import com.lezi.babylog.sync.session.FamilySessionOutcome
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_MEDIA_IDENTITY_V1
import com.lezi.babylog.sync.session.ServerUpdateRequiredException
import com.lezi.babylog.sync.session.MediaIdentityProtocolException
import com.lezi.babylog.sync.engine.MissingTrustedMediaIdentityException
import com.lezi.babylog.sync.session.TerminalRemovalKind
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.ForegroundSessionEnded
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.SyncSessionPresentation
import com.lezi.babylog.sync.session.toPresentation
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.matchesOrigin
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.session.familyFailureKind
import com.lezi.babylog.sync.session.isUnrecoverableForegroundStop
import com.lezi.babylog.sync.session.shouldContinueIncompleteForegroundCycle

private data class ConflictSnapshotSessionIdentity(
    val familyId: String,
    val deviceId: String,
    val baseUrl: String,
    val membershipId: String,
    val pullGeneration: String,
    val joined: Boolean,
)

/**
 * 0.5 W3 piggyback throttle: non-user rounds (heartbeat kick / foreground
 * continuation) repeat the app-update discovery at most once per hour. User
 * real triggers (foreground return / pull-to-refresh) always check. No new
 * wake source: discovery only rides rounds that already happened.
 */
internal const val APP_UPDATE_PIGGYBACK_MIN_INTERVAL_MILLIS = 60L * 60 * 1000

private fun SyncSession.conflictSnapshotIdentity() = ConflictSnapshotSessionIdentity(
    familyId = familyId,
    deviceId = deviceId,
    baseUrl = baseUrl,
    membershipId = membershipId,
    pullGeneration = pullGeneration,
    joined = isJoined,
)

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
    private val immutableMediaSpool: ImmutableMediaSpool,
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
    private val fulfillmentAuthoritySettlement: FulfillmentAuthoritySettlement,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao,
    private val sourceRelationDao: SourceRelationDao? = null,
    private val clientAppVersion: ClientAppVersion = ClientAppVersion.FALLBACK,
    private val appUpdateInstaller: AppUpdateInstaller = NoOpAppUpdateInstaller,
    private val apkIdentityReader: AppUpdateApkIdentityReader =
        UnreadableAppUpdateApkIdentityReader,
    @Named("appUpdateCacheDir") private val appUpdateCacheDir: File =
        File(System.getProperty("java.io.tmpdir"), "lezi-app-update-test"),
    @Named("restoreSnapshotsDir") private val restoreSnapshotsDir: File =
        File(appUpdateCacheDir, "restore-snapshots"),
    private val mediaReferenceDao: com.lezi.babylog.core.database.causal.MediaReferenceDao? = null,
) : SyncPort {
    private val currentStatus = MutableStateFlow(SyncStatus.Disabled)
    private val currentFailureKind = MutableStateFlow<FailureKind?>(null)
    private val currentAvailability =
        MutableStateFlow<FamilyServerAvailability>(FamilyServerAvailability.Disabled)

    // --- 0.4.8 foreground heartbeat (wire §1.5, ticket 04) ---------------------
    /**
     * Unified probe engine (ticket 03). One authenticated beat per deadline
     * feeds both the change verdict for the existing conflated foreground loop
     * and [FamilyServerAvailability]. Arming accepts either compat signal
     * (research §6): the setup-status advertisement below, or the engine's
     * own one-shot discovery beat — so a joined foreground client that never
     * opens the network settings is covered too. An old server costs exactly
     * one 404 per process and then stays byte-identical-0.4.7 silent forever.
     */
    private val heartbeatEngine = SyncHeartbeatEngine(
        backend = backend,
        clock = clock,
        jitter = HeartbeatJitterSource { scheduledIntervalMillis ->
            heartbeatJitter.jitterMillis(scheduledIntervalMillis)
        },
        availabilitySnapshot = { currentAvailability.value },
    )

    /** Test-only deterministic jitter; production jitters randomly within ±20%. */
    @Volatile
    internal var heartbeatJitterOverride: HeartbeatJitterSource? = null
    private val heartbeatJitter: HeartbeatJitterSource
        get() = heartbeatJitterOverride ?: RandomHeartbeatJitterSource

    /** Test-only loop scope; production runs the loop on [processScope]. */
    @Volatile
    internal var heartbeatLoopScopeOverride: CoroutineScope? = null

    private val heartbeatLoopJob = AtomicReference<Job?>(null)

    /** Conflated residency re-evaluation kicks (foreground return / capability). */
    private val heartbeatEvaluation = Channel<Unit>(Channel.CONFLATED)

    /** Test-only observation of the engine deadline (absolute epoch millis or null). */
    internal val heartbeatNextBeatAtMillis: StateFlow<Long?>
        get() = heartbeatEngine.nextBeatAtMillis

    /** Test-only observation of loop liveness (residency teardown contract). */
    internal val heartbeatLoopActive: Boolean
        get() = heartbeatLoopJob.get()?.isActive == true

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

    private val memberLoginCheckEvents = MutableSharedFlow<MemberLoginCheckResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Test-only: await piggyback metadata so existing handshake assertions stay
     * deterministic. Production always launches after [sync] returns.
     */
    internal var awaitPiggybackAppUpdateDiscovery: Boolean = false
    /**
     * Test-only elapsed clock for the foreground cycle / disaster-restore cap.
     * Production uses realtime.
     */
    internal var familyElapsedClock: SyncRetryClock = SystemSyncRetryClock
    private val syncMutex = Mutex()
    /** Invalidates detail intents captured before a committed local/identity clear. */
    private val conflictSnapshotClearEpoch = AtomicLong()
    private val conflictSnapshotLoadLocks = ConflictSnapshotLoadLocks()
    private val conflictSnapshotProjection = ConflictSnapshotProjection(
        summaries = conflictSummaryDao,
        snapshots = conflictSnapshotCacheDao,
        transactions = transactionRunner,
    )
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
        immutableMediaSpool = immutableMediaSpool,
        mediaFileCleanup = mediaFileCleanup,
        transactionRunner = transactionRunner,
        carePlanAppliedListener = carePlanAppliedListener,
        familyBabyAppliedListener = familyBabyAppliedListener,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        fulfillmentAuthoritySettlement = fulfillmentAuthoritySettlement,
        requireRemoteAllowed = { session ->
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
            currentStatus.value = SyncStatus.Syncing
        },
        wakeObservationDao = wakeObservationDao,
        conflictSummaryDao = conflictSummaryDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
        sourceRelationDao = sourceRelationDao,
    )
    private val restoreFileSnapshots by lazy {
        com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore(restoreSnapshotsDir,
            incompleteSourceGuard = { paths, action -> mediaFileCleanup.withOwnedPaths(paths, action) })
    }
    private val restoreSnapshotJournal by lazy {
        com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal(
            requireNotNull(conflictSnapshotCacheDao), immutableMediaSpool, transactionRunner, restoreFileSnapshots,
        )
    }
    private val restoreFileLifecycle by lazy {
        com.lezi.babylog.sync.disasterrecovery.RestoreFileLifecycleOwner(
            conflictSnapshotCacheDao, restoreFileSnapshots, transactionRunner, mediaDao, mediaFiles, mediaFileCleanup, babyDao,
            currentFamilyId = { preferences.session.first().familyId },
            verifiedBytes = { restoreFileSnapshots.metrics.verificationBytes += it })
    }
    private suspend fun captureRestoreRows() = com.lezi.babylog.sync.disasterrecovery.CapturedRestoreRows(
        babyDao.listAllIncludingDeleted(), recordDao.listAllIncludingDeleted(),
        carePlanDao.listAllIncludingDeleted(), customItemDao.listAllIncludingDeleted(),
        fulfillmentCandidateDao.listAllIncludingDeleted(), wakeObservationDao.listAllIncludingDeleted(),
        mediaDao.listAllIncludingDeleted(),
    )
    private val terminalSpoolRetirement by lazy {
        mediaReferenceDao?.let { references ->
            com.lezi.babylog.sync.disasterrecovery.RestoreTerminalSpoolRetirementOwner(
                conflictSnapshotCacheDao, immutableMediaSpool, mediaFiles, mediaDao, babyDao,
                references, transactionRunner, mediaFileCleanup, ::captureRestoreRows,
                currentSession = { preferences.session.first() }, pendingClear = pendingReplicaCleanupStore,
                commandFence = syncMutex,
            )
        }
    }
    private val sourceLogoutAdmission by lazy {
        com.lezi.babylog.sync.sourcerelation.SourceCommandLogoutAdmission(
            conflictSnapshotCacheDao, requireNotNull(sourceRelationDao), transactionRunner,
            currentSession = { preferences.session.first() }, trustedEndpoint = { preferences.verifiedEndpoint.first() },
            hasVerifiedTerminalRemoval = {
                preferences.hasPendingDeviceRemovalClear() || preferences.hasPendingMembershipDeletionClear() ||
                    preferences.hasPendingFamilyDeletionClear()
            })
    }
    private val sourceRelationCommandOwner by lazy {
        com.lezi.babylog.sync.sourcerelation.SourceRelationCommandOwner(
            requireNotNull(sourceRelationDao), conflictSnapshotCacheDao, transactionRunner, backend,
            currentSession = { preferences.session.first() },
            currentEndpoint = { preferences.verifiedEndpoint.first() }, nowMillis = clock::nowMillis)
    }
    private val disasterRecoverySnapshotBuilder = DisasterRecoverySnapshotBuilder(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        mediaDao = mediaDao,
        wakeObservationDao = wakeObservationDao,
        mediaFiles = mediaFiles,
        transactions = transactionRunner,
        sourceRelationDao = sourceRelationDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
    )
    private val localReplicaClearCoordinator = LocalReplicaClearCoordinator(
        barrier = syncMutex,
        preferences = preferences,
        babyDao = babyDao,
        mediaDao = mediaDao,
        mediaFiles = mediaFiles,
        mediaSpoolClear = com.lezi.babylog.sync.media.ScopedMediaSpoolClear(
            babyDao = babyDao,
            cache = conflictSnapshotCacheDao,
            summaries = conflictSummaryDao,
            spool = immutableMediaSpool,
        ),
        transactionRunner = transactionRunner,
        pendingStore = pendingReplicaCleanupStore,
        terminalSpoolOwner = { terminalSpoolRetirement },
        restoreOwnedPaths = { restoreFileLifecycle.ownedArtifactPaths() },
        reclaimRestoreFiles = {
            restoreFileLifecycle.reclaimRetired(force = true)
            sourceLogoutAdmission.restoreNotice()
        },
        clearSourceEvidence = { scope, session, clear -> sourceLogoutAdmission.aroundClear(scope, session, clear) },
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
        recoverRestoreAuthority = ::recoverRestoreAuthoritySwitch,
        beforeDeviceLogout = { sourceLogoutAdmission.admit(it) },
        afterDeviceLogoutConfirmed = { sourceLogoutAdmission.logoutConfirmed() },
    )
    private val syncSignal = Channel<Unit>(Channel.CONFLATED)
    private val pullRequested = AtomicBoolean(false)
    /**
     * Set only by the real-trigger seam ([requestSync] with a non-LocalWrite
     * trigger); consumed by the next Foreground round's piggyback gate so
     * heartbeat kicks and continuations — which share the conflated channel and
     * the same Foreground trigger value — stay throttled (0.5 W3).
     */
    private val realUserSyncTriggerRequested = AtomicBoolean(false)
    private val lastAcceptedNetworkRecoveredAtMillis = AtomicReference<Long?>(null)
    /**
     * Single owner of the intra-cluster zero-progress budget AND the
     * cross-beat fuse (review 2026-09-05 P2-6): after a foreground cluster
     * chasing one observed remote signal exhausts its zero-progress budget,
     * identical later beats keep probing and feeding availability but never
     * start another data round. See [ForegroundRoundFuse].
     */
    private val heartbeatRoundFuse = ForegroundRoundFuse()
    @Volatile private var serverUpgradeBlockedIdentity: ForegroundFuseIdentity? = null

    /**
     * Set when the NEXT Foreground-triggered round must run in full: a
     * heartbeat NeedsSync kick (0.5 ticket 04 — a kicked round is the only
     * in-band proof that the server changed after the last quiet beat, so it
     * must never be tip-skipped) and a zero-progress continuation (its
     * predecessor round failed; the retry must never be swallowed). Consumed
     * by the conflated sync-signal consumer alongside [pullRequested].
     */
    private val foregroundRoundSkipVeto = AtomicBoolean(false)

    /**
     * 0.5 ticket 04 (review C9): the freshest heartbeat no-change proof —
     * three keys proving "server head/generation/directory unchanged as of
     * the beat itself". Written ONLY by heartbeat beats (a no-change
     * answer creates/refreshes it, every other outcome destroys it), so a
     * server without heartbeat capability can never produce a proof and the
     * tip-skip never fires against a 0.4.7 origin. Volatile: written by the
     * heartbeat loop / manual refresh without the sync mutex, read at the
     * sync entry — an in-flight beat simply makes the decision conservatively
     * stale.
     */
    @Volatile
    private var quietHeartbeatProof: QuietProof? = null

    /**
     * 0.5 ticket 04 freshness-window anchor: the last successful full cycle
     * (handshake + pull, any trigger) completion time. C9's
     * `fullCycleCompletedAtMillis` lives here rather than inside
     * [QuietProof] on purpose: a full cycle is a capability observation, not
     * a "nothing changed" proof, so it must never mint a proof by itself.
     */
    @Volatile
    private var quietFreshnessAnchorMillis: Long? = null

    /** Last session identity seen by the tip-skip invalidation above. */
    @Volatile
    private var quietProofIdentity: ForegroundFuseIdentity? = null

    @Volatile
    private var cachedSession = SyncSession()

    init {
        StartupBoundaryObservation.record("sync:activate")
        processScope.launch {
            StartupBoundaryObservation.record("sync:recovery-start")
            runProcessStartupRecovery(::updateFailureStatus) {
                val resumeTerminalRemoval = syncMutex.withLock {
                    recoverPendingLocalClearLocked()
                    if (sourceRelationDao != null) sourceLogoutAdmission.restoreNotice()
                    sourceRelationDao?.repairLegacyAutoAlignedSummaries()
                    preferences.hasPendingDeviceRemovalClear() ||
                        preferences.hasPendingMembershipDeletionClear() ||
                        preferences.hasPendingFamilyDeletionClear()
                }
                if (resumeTerminalRemoval) finishPendingTerminalIdentityClear()
            }
        }
        processScope.launch {
            StartupBoundaryObservation.record("sync:session-collect")
            preferences.session.collect { session ->
                // Identity switches (join/rejoin, unjoin, endpoint change,
                // terminal clear) retire any cross-beat fuse keyed to the
                // previous session/endpoint — suppression never crosses.
                heartbeatRoundFuse.onSessionObserved(session.foregroundFuseIdentity())
                // A tip-skip quiet proof never crosses an identity switch —
                // but ordinary session mutations (markSuccess timestamps)
                // must not wipe it, so only a real identity change clears.
                val identity = session.foregroundFuseIdentity()
                if (identity != quietProofIdentity) {
                    quietHeartbeatProof = null
                    quietFreshnessAnchorMillis = null
                    quietProofIdentity = identity
                }
                cachedSession = session
                if (session.reauthRequired) {
                    currentStatus.value = SyncStatus.ReauthRequired
                    currentFailureKind.value = FailureKind.SessionExpired
                } else if (!session.isJoined) {
                    currentStatus.value = SyncStatus.Disabled
                    currentFailureKind.value = null
                    // Unjoined devices never show optional/forced update surfaces.
                    optionalAppUpdateState.value = null
                    clearForcedAppUpdateState()
                } else if (currentStatus.value == SyncStatus.Disabled) {
                    currentStatus.value = SyncStatus.Idle
                }
                // Session residency drives the heartbeat loop: unjoined or
                // reauthRequired cancels it immediately (never probe a dead
                // endpoint); re-joining makes it eligible again while foreground.
                refreshHeartbeatLoop()
            }
        }
        processScope.launch {
            for (ignored in heartbeatEvaluation) {
                refreshHeartbeatLoop()
            }
        }
        processScope.launch {
            for (ignored in syncSignal) {
                if (!foregroundState.isForeground()) {
                    // Drop leftover Syncing only after taking the barrier.
                    // tryLock fails if sync()/syncWhenAvailable already holds
                    // it — leave their Syncing alone. isLocked is not atomic
                    // with the write.
                    if (syncMutex.tryLock()) {
                        try {
                            if (currentStatus.value == SyncStatus.Syncing) {
                                currentStatus.value = SyncStatus.Idle
                            }
                        } finally {
                            syncMutex.unlock()
                        }
                    }
                    continue
                }
                val trigger = if (pullRequested.getAndSet(false)) {
                    SyncTrigger.Foreground
                } else {
                    SyncTrigger.LocalWrite
                }
                // The kick/continuation veto is consumed with the same signal
                // so it can never outlive the round it belongs to.
                val skipVetoed = foregroundRoundSkipVeto.getAndSet(false)
                // Typed failures (including residual timeout) must not kill this
                // consumer; later foreground / local-write / heartbeat kicks
                // still need a live loop. True cancellation still ends it.
                runCatching {
                    syncInternal(trigger, allowQuietSkip = !skipVetoed)
                }.onFailure { failure ->
                    if (failure is CancellationException &&
                        failure !is ForegroundSessionEnded &&
                        failure !is TimeoutCancellationException
                    ) {
                        throw failure
                    }
                }
            }
        }
    }

    override fun status(): Flow<SyncStatus> = currentStatus
    override fun lastFailureKind(): Flow<FailureKind?> = currentFailureKind
    override fun availability(): Flow<FamilyServerAvailability> = currentAvailability
    override fun lastServerHealthyAt(): Flow<Long?> = preferences.lastServerHealthyAt
    override fun sessionPresentation(): Flow<SyncSessionPresentation> =
        preferences.session.map(SyncSession::toPresentation).distinctUntilChanged()
    @Deprecated("Use sessionPresentation() outside sync internals")
    override fun session(): Flow<SyncSession> = preferences.session
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun pendingPublishCount(): Flow<Int> = preferences.session.flatMapLatest { session ->
        if (!session.isJoined) {
            flowOf(0)
        } else {
            pendingPublishDao.observeCount()
        }
    }
    override fun deviceRemovedReceipt(): Flow<DeviceRemovedCleanupReceipt?> =
        preferences.deviceRemovedReceipt
    override suspend fun consumeDeviceRemovedReceipt() {
        preferences.clearDeviceRemovedReceipt()
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun unacceptedFact(): Flow<UnacceptedFactPresentation?> = preferences.session.flatMapLatest { session ->
        if (!session.isJoined) {
            flowOf(null)
        } else {
            conflictSnapshotCacheDao.observeTerminalReceiptJournals().map { rows ->
                terminalReceiptFacts(rows).minByOrNull { it.recordedAt }
            }
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun unacceptedFacts(): Flow<List<UnacceptedFactPresentation>> =
        preferences.session.flatMapLatest { session ->
            if (!session.isJoined) {
                flowOf(emptyList())
            } else {
                conflictSnapshotCacheDao.observeTerminalReceiptJournals().map { rows ->
                    terminalReceiptFacts(rows).sortedBy { it.recordedAt }
                }
            }
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun skippedPullItems(): Flow<List<SkippedPullItem>> =
        preferences.session.flatMapLatest { session ->
            if (!session.isJoined) {
                flowOf(emptyList())
            } else {
                conflictSnapshotCacheDao.observePullDiagnosticJournals().map { rows ->
                    rows.mapNotNull { row ->
                        runCatching {
                            decodePullDiagnosticReceipt(row.payloadJson)
                        }.getOrNull()
                    }.filterNot { isAggregateCensusDiagnostic(it.entityType, it.clientUuid) }
                        .map { it.toSkippedPullItem() }
                        .sortedBy { it.recordedAt }
                }
            }
        }.distinctUntilChanged()
    override fun pendingGenerationResync(): Flow<Boolean> = preferences.pendingGenerationResync
    override fun familyReadSnapshot(): Flow<com.lezi.babylog.sync.session.FamilyReadSnapshot> =
        preferences.familyReadSnapshot

    override fun familyMemberDirectory(): Flow<List<FamilyMember>> =
        preferences.familyMemberDirectory

    override fun refreshFamilyMemberDirectory() {
        processScope.launch { listFamilyMembers() }
    }

    /**
     * Two-formed availability probe (0.4.8 探针形态统一, spec Implementation
     * Decisions + research §4.6; ticket 05). Routing happens INSIDE the port so
     * the network-settings host stays copy/control-identical:
     *
     *  - **Joined + pinned endpoint + a beat-capable gate** (Active, or
     *    NotAdvertised/DiscoveryPending so a discovery beat can arm): exactly
     *    ONE authenticated engine beat executes immediately — the loop's
     *    cadence and +8s debounce are deliberately bypassed because a manual
     *    refresh is user-initiated — and its outcome publishes through the
     *    SAME StateFlow lines the loop uses. Zero anonymous calls in the
     *    steady state. Because the beat is an ordinary engine beat, its
     *    bookkeeping reschedules the loop deadline from `now`: a NeedsSync
     *    verdict (or a rehabilitation out of Unavailable) resets the cadence
     *    to the 60s baseline, a quiet beat continues the no-change backoff —
     *    a manual refresh is modeled as "the loop fired one beat now".
     *    Mutex serialization against an in-flight loop beat is the engine's;
     *    this caller simply waits. The beat's verdict NEVER kicks a sync
     *    round from this path — a settings-page availability refresh must not
     *    launch data rounds (the anonymous path never did either); the
     *    resident loop re-derives the same verdict at its next deadline
     *    (≤30s after a NeedsSync reset), so no change signal is lost.
     *    NotAnswered beats leave availability untouched (returned as-is), and
     *    thrown terminal session signals route through
     *    [routeHeartbeatTerminalFailure] exactly like a loop beat — never
     *    swallowed, never an availability failure.
     *  - **Unjoined / no session / no pinned endpoint / gate already
     *    [HeartbeatGate.EndpointMissing]** (old server): the anonymous
     *    `/health` + `/ready` + setup-status triple, byte-for-byte the
     *    historical behavior including TrustChanged/TOFU surfacing and the
     *    setup-status capability observation.
     *
     * One anonymous tail is kept on the heartbeat form: when the beat ANSWERS
     * but the engine cannot construct an availability update (no remembered
     * metadata for this origin — the closed three-key wire carries no server
     * version / family state), this refresh completes first-establishment
     * through the anonymous triple, which stays the authority for
     * first-establishment per the engine contract. Same for a discovery beat
     * answered 404 (engine permanently disabled → old-server behavior). In
     * both tails the triple runs inside this same user-initiated refresh.
     */
    override suspend fun probeServerAvailability(
        reason: AvailabilityProbeReason,
    ): Result<FamilyServerAvailability> {
        if (!foregroundState.isForeground()) {
            return Result.failure(ForegroundSyncBlockedException(ForegroundSyncDecision.Background))
        }
        return foregroundState.whileForeground { probeAvailabilityWithinForeground(reason) }
    }

    private suspend fun probeAvailabilityWithinForeground(
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
            val session = preferences.session.first()
            val heartbeatForm = session.isJoined &&
                heartbeatEngine.gate.value != HeartbeatGate.EndpointMissing
            val direct = if (heartbeatForm) {
                probeAvailabilityByHeartbeat(session, previous)
            } else {
                null
            }
            direct ?: probeAvailabilityAnonymously(endpoint, previous, lastHealthyAt)
        }
    }

    /**
     * The heartbeat form of [probeServerAvailability]: one immediate engine
     * beat whose outcome publishes through the loop's own StateFlow lines.
     * Returns null when this refresh must fall through to the anonymous
     * triple: a 2xx answer without remembered availability metadata
     * (first-establishment tail) or the discovery beat's 404 (engine
     * permanently disabled → old-server behavior). Never publishes the
     * transient `Checking` state — that remains the anonymous triple's
     * long-call signal; the beat is a single quick Probe-budget request, so
     * the page keeps showing the previous truth until the beat answers.
     */
    private suspend fun probeAvailabilityByHeartbeat(
        session: SyncSession,
        previous: FamilyServerAvailability,
    ): Result<FamilyServerAvailability>? {
        if (heartbeatEngine.gate.value == HeartbeatGate.NotAdvertised) {
            // Consume the one-shot discovery permit first so beat() is legal;
            // the +8s permit debounce is irrelevant here — the beat below runs
            // immediately and its own bookkeeping overwrites the deadline.
            heartbeatEngine.allowDiscoveryBeat()
        }
        val snapshot = HeartbeatSessionSnapshot(
            pullCursor = session.pullCursor,
            pullGeneration = session.pullGeneration,
            cachedDirectoryGeneration =
                preferences.familyMemberDirectoryGeneration.first(),
        )
        val beat = try {
            heartbeatEngine.beat(session, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (terminal: Throwable) {
            // Same routing as the loop: terminal session signals complete
            // even if residency refresh cancels this probe mid-clear — never
            // swallowed, never demoted to an availability failure.
            withContext(NonCancellable) {
                routeHeartbeatTerminalFailure(terminal)
            }
            return Result.success(currentAvailability.value)
        }
        val published = publishHeartbeatAvailability(beat)
        noteQuietHeartbeatBeatOutcome(beat)
        return when (beat) {
            is SyncHeartbeatBeat.Answered -> when {
                published == null -> {
                    // First-establishment tail: the wire's three keys cannot
                    // build an Available; the anonymous triple stays the
                    // authority for first-establishment (engine contract).
                    null
                }
                else -> {
                    // The manual refresh additionally persists the healthy
                    // timestamp exactly like the historical anonymous probe.
                    preferences.saveLastServerHealthyAt(
                        (published as? FamilyServerAvailability.Available)
                            ?.lastHealthyAtMillis
                            ?: clock.nowMillis(),
                    )
                    Result.success(published)
                }
            }
            is SyncHeartbeatBeat.Degraded -> Result.success(beat.unavailable)
            is SyncHeartbeatBeat.NotAnswered -> Result.success(previous)
            SyncHeartbeatBeat.EndpointMissing -> {
                // Old server discovered by THIS refresh: let the residency
                // evaluator retire the parked loop (gate outranks everything).
                heartbeatEvaluation.trySend(Unit)
                null
            }
        }
    }

    /**
     * Availability publication shared by the loop's beat handling and the
     * manual refresh path: Answered/Degraded publish their update through
     * the same StateFlow, NotAnswered/EndpointMissing publish nothing.
     * Returns the published value, or null when the beat published nothing.
     */
    private fun publishHeartbeatAvailability(beat: SyncHeartbeatBeat): FamilyServerAvailability? =
        when (beat) {
            is SyncHeartbeatBeat.Answered ->
                beat.availabilityUpdate?.also { currentAvailability.value = it }
            is SyncHeartbeatBeat.Degraded ->
                beat.unavailable.also { currentAvailability.value = it }
            is SyncHeartbeatBeat.NotAnswered -> null
            SyncHeartbeatBeat.EndpointMissing -> null
        }

    /**
     * The anonymous form of [probeServerAvailability]: the historical
     * `/health` + `/ready` + setup-status triple, byte-for-byte unchanged —
     * still the authority for unjoined/no-token scenarios, old servers, and
     * heartbeat first-establishment.
     */
    private suspend fun probeAvailabilityAnonymously(
        endpoint: TrustedEndpointProfile,
        previous: FamilyServerAvailability,
        lastHealthyAt: Long?,
    ): Result<FamilyServerAvailability> {
        currentAvailability.value = FamilyServerAvailability.Checking(lastHealthyAt)
        return try {
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
            if (CAPABILITY_SYNC_HEARTBEAT_V1 in setupReady.capabilities) {
                // Capability accelerator (ticket 04 + discovery fix): the
                // availability probe is the only joined-state observer of
                // /v1/setup-status (settings refresh and the probe
                // reasons). It always probes the current verified
                // endpoint, so reconnect-candidate probes cannot arm the
                // engine against a different server. This is no longer
                // the only arming route — the loop's one-shot discovery
                // beat arms without any advertisement — but it stays the
                // fast path, and a 404-disabled engine never re-arms
                // here (the 404 gate outranks a late advertisement).
                heartbeatEngine.onServerCapabilityAdvertised()
                heartbeatEvaluation.trySend(Unit)
            }
            Result.success(available)
        } catch (timeout: TimeoutCancellationException) {
            publishAvailabilityFailure(
                previous = previous,
                lastHealthyAt = lastHealthyAt,
                reason = FamilyServerUnavailableReason.ResponseTimedOut,
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
    override fun verifiedEndpoint(): Flow<TrustedEndpointProfile?> = preferences.verifiedEndpoint
    override fun pendingMemberLogin(): Flow<PendingMemberLogin?> = preferences.pendingMemberLogin
    override fun memberLoginChecks(): Flow<MemberLoginCheckResult> = memberLoginCheckEvents

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
                        preferences.forgetEndpoint(retainMemberAttempts = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    throw persistFailure(error)
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
        } catch (error: Throwable) {
            throw persistFailure(error)
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
        SetupProbeResult.Failed.ResponseTimedOut
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
            FamilyServerUnavailableReason.ResponseTimedOut ->
                SetupProbeResult.Failed.ResponseTimedOut
        }
    } catch (failure: Throwable) {
        // Only transport leftovers keep the network cause; internal bugs must not
        // surface as "连不上家里的服务器" copy.
        if (failure is IOException) {
            SetupProbeResult.Failed.Unreachable
        } else {
            SetupProbeResult.Failed.Unexpected
        }
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit> =
        try {
            preferences.rememberEndpoint(endpoint)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(LocalPersistException(error))
        }

    override suspend fun forgetEndpoint(): Result<Unit> =
        try {
            pendingReconnectMember.set(null)
            preferences.forgetEndpoint()
            pendingReconnectMember.set(null)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    override fun requestAuthoritativeForegroundSync() {
        quietHeartbeatProof = null
        foregroundRoundSkipVeto.set(true)
        requestSync(SyncTrigger.Foreground)
    }

    override fun requestSync(trigger: SyncTrigger) {
        // requestSync is the REAL-trigger seam (foreground return, local
        // write, pull-to-refresh): it authorizes a retry, so any cross-beat
        // zero-progress fuse releases and the budget resets. The heartbeat
        // kick and the internal continuation deliberately do NOT pass through
        // here — they use [kickHeartbeatRound] / [retriggerForegroundCycle]
        // so a synthesized Foreground can never masquerade as a real
        // foreground return and clear the fuse.
        heartbeatRoundFuse.onRealUserTrigger()
        if (trigger != SyncTrigger.LocalWrite) {
            serverUpgradeBlockedIdentity = null
            pullRequested.set(true)
            realUserSyncTriggerRequested.set(true)
        } else {
            // Local write completed: reset the healthy heartbeat cadence to the
            // 60s baseline (wire §1.5; no-op while the engine gate is dormant).
            heartbeatEngine.onLocalWriteCompleted()
        }
        if (trigger == SyncTrigger.Foreground) {
            // Foreground return: re-evaluate heartbeat residency. A relaunch
            // debounces its first beat via the engine's +8s onForegroundReturned.
            heartbeatEvaluation.trySend(Unit)
        }
        syncSignal.trySend(Unit)
    }

    /**
     * Launch or tear down the heartbeat loop so it matches the residency
     * contract exactly: foreground + joined session + trusted (TOFU-pinned)
     * endpoint + a gate that still allows beats. The gate allows beats while
     * Active, while a discovery beat is pending, and from the default
     * NotAdvertised state — the loop then opens with the engine's one-shot
     * discovery beat (compat matrix research §6, either signal decides), so
     * residency alone arms the engine without any settings visit. Only the
     * permanent 404 gate ([HeartbeatGate.EndpointMissing]) suppresses the
     * loop. Unjoined / reauthRequired / lost endpoint trust cancels the loop
     * immediately — never probe a dead endpoint. A background transition is
     * enforced by the foreground residency owner immediately; a subsequent
     * foreground-return kick starts a fresh loop and debounce deadline.
     */
    private suspend fun refreshHeartbeatLoop() {
        val shouldRun = foregroundState.isForeground() &&
            cachedSession.isJoined &&
            heartbeatEngine.gate.value != HeartbeatGate.EndpointMissing &&
            preferences.verifiedEndpoint.first() != null
        val job = heartbeatLoopJob.get()
        if (!shouldRun) {
            job?.cancel()
            return
        }
        if (job?.isActive == true) return
        while (true) {
            val current = heartbeatLoopJob.get()
            if (current?.isActive == true) return
            val started = (heartbeatLoopScopeOverride ?: processScope).launch {
                foregroundState.whileForeground { runHeartbeatLoop() }
            }
            if (heartbeatLoopJob.compareAndSet(current, started)) {
                started.invokeOnCompletion { heartbeatLoopJob.compareAndSet(started, null) }
                return
            }
            // Lost a race against a concurrent launch or completion handler;
            // re-evaluate and retry so a kick never drops on the floor.
            started.cancel()
        }
    }

    /**
     * The cancel-style foreground heartbeat loop (spec 客户端循环). Every wake
     * re-checks residency and tears the coroutine down when it is lost —
     * unlike the syncSignal consumer above, it never skips-and-idles. The
     * first beat after a (re)launch is debounced by the engine's
     * +8s [SyncHeartbeatEngine.onForegroundReturned] (or, on the discovery
     * path, by the permit itself). While the gate is still NotAdvertised each
     * iteration asks the engine for its one-shot discovery beat
     * ([SyncHeartbeatEngine.allowDiscoveryBeat], idempotent): the un-advertised
     * beat's own outcome then arms, permanently disables (old server: exactly
     * one request per process), or re-opens discovery on the failed ladder.
     * A due beat landing while `SyncStatus.Syncing` is skipped entirely (no
     * probe queue, no accumulation) and the single absolute deadline in
     * [SyncHeartbeatEngine.nextBeatAtMillis] is re-derived afterwards. A
     * NeedsSync verdict kicks the EXISTING conflated foreground loop — never a
     * second signal channel — through [kickHeartbeatRound]: once the cross-beat
     * zero-progress fuse holds an observed signal, identical beats stay silent
     * (probes and availability continue) until the signal itself changes or a
     * real trigger releases it; conflation, the zero-progress budget and the
     * 120s round cap all apply unchanged.
     */
    private suspend fun runHeartbeatLoop() {
        heartbeatEngine.onForegroundReturned()
        while (true) {
            if (!foregroundState.isForeground()) return
            val session = preferences.session.first()
            if (!session.isJoined) return
            val decision = foregroundSyncGate.evaluate(
                session.endpointConfig,
                preferences.verifiedEndpoint.first(),
                foregroundState.isForeground(),
            )
            if (decision != ForegroundSyncDecision.Allowed) return
            if (heartbeatEngine.gate.value == HeartbeatGate.NotAdvertised) {
                // Discovery: exactly one un-advertised beat decides (research
                // §6). Idempotent — after the beat armed or 404-disabled the
                // engine this never runs again.
                heartbeatEngine.allowDiscoveryBeat()
            }
            val deadline = heartbeatEngine.nextBeatAtMillis.filterNotNull().first()
            val waitMillis = deadline - clock.nowMillis()
            if (waitMillis > 0) {
                // A local write or lifecycle hook can move or clear this deadline.
                // Suspend on the deadline itself, so the old timer never hides a reset.
                kotlinx.coroutines.withTimeoutOrNull(waitMillis) {
                    heartbeatEngine.nextBeatAtMillis.first { it != deadline }
                }
                continue
            }
            if (currentStatus.value == SyncStatus.Syncing) {
                // Skip the due beat; wait for the round to leave Syncing, then
                // re-derive (hooks may have rescheduled the deadline meanwhile).
                // Cap the wait so a leaked Syncing cannot park this loop forever.
                try {
                    withTimeout(
                        ForegroundSyncCycle.MAX_ELAPSED_MILLIS +
                            LOCAL_WRITE_MAX_ELAPSED_MILLIS,
                    ) {
                        currentStatus.first { it != SyncStatus.Syncing }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Re-derive from current state. Still Syncing → wait again.
                }
                continue
            }
            val snapshot = HeartbeatSessionSnapshot(
                pullCursor = session.pullCursor,
                pullGeneration = session.pullGeneration,
                cachedDirectoryGeneration =
                    preferences.familyMemberDirectoryGeneration.first(),
            )
            val beat = try {
                heartbeatEngine.beat(session, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (terminal: Throwable) {
                val current = preferences.session.first()
                if (current.deviceId != session.deviceId || current.familyId != session.familyId) {
                    // The beat was issued by a session that has already been replaced.
                    continue
                }
                // Terminal session signals route through the SAME handling as
                // sync() and must complete even if residency refresh cancels
                // this loop mid-clear — never swallowed, never partial.
                withContext(NonCancellable) {
                    routeHeartbeatTerminalFailure(terminal)
                }
                return
            }
            // Publication goes through the same shared helper as the manual
            // refresh path; only this loop owns the sync kick and teardown.
            publishHeartbeatAvailability(beat)
            noteQuietHeartbeatBeatOutcome(beat)
            when (beat) {
                is SyncHeartbeatBeat.Answered -> {
                    if (beat.verdict == HeartbeatVerdict.NeedsSync) {
                        kickHeartbeatRound(beat.signal, session)
                    }
                }
                SyncHeartbeatBeat.EndpointMissing -> return
                else -> Unit
            }
        }
    }

    /**
     * The heartbeat's only action (wire §1.5): after the cross-beat fuse
     * clears the observed signal, kick the EXISTING conflated foreground
     * loop. A fused (session/endpoint × identical observed signal) beat
     * stops here — its probe and availability publication already happened —
     * and only a different observed signal (any of the three keys, watermark
     * regression included), a real user trigger, or durable progress releases
     * it. Deliberately not [requestSync]: the kick must not reset the fuse
     * the way a real foreground return does, and this loop already
     * re-evaluated residency itself this iteration.
     */
    private fun kickHeartbeatRound(signal: SyncHeartbeat, session: SyncSession) {
        if (serverUpgradeBlockedIdentity == session.foregroundFuseIdentity()) return
        if (!heartbeatRoundFuse.shouldKickRound(signal, session.foregroundFuseIdentity())) return
        pullRequested.set(true)
        // 0.5 ticket 04: a kicked round is the only in-band proof that the
        // server changed after the last quiet beat — it must run in full,
        // never tip-skipped.
        foregroundRoundSkipVeto.set(true)
        syncSignal.trySend(Unit)
    }

    /**
     * Silent intra-cluster continuation (zero-progress budget): re-enter the
     * same conflated channel WITHOUT [requestSync]'s real-trigger side
     * effects — an internal continuation is neither a user foreground return
     * nor a local write, so it releases neither the cross-beat fuse nor (no
     * residency change being involved) the heartbeat loop.
     */
    private fun retriggerForegroundCycle() {
        pullRequested.set(true)
        // 0.5 ticket 04: a continuation retries a round that just failed —
        // the retry must never be swallowed by a still-fresh quiet proof.
        foregroundRoundSkipVeto.set(true)
        syncSignal.trySend(Unit)
    }

    /**
     * Terminal mapping for a beat's thrown session signals — deliberately
     * NARROWER than [sync]'s failure route. Only the four typed terminal
     * exceptions (device removal, membership/family deletion,
     * client-update-required) route to the same handlers as [sync] and are
     * never swallowed; everything else is a plain failure-status update. The
     * heartbeat path intentionally does NOT:
     *  - unwrap `SyncHttpException.clientUpdateRequiredOrNull()` from a raw
     *     [SyncHttpException] the way [sync]'s else branch does — a wire-level
     *     4xx on the heartbeat must never drag in the app-update piggyback
     *     flow (research §6: no app-update piggybacking on the old-server
     *     path); and
     *  - call demoteAvailabilityAfterTransportFailure — the engine already
     *     publishes availability itself through its [SyncHeartbeatBeat.Degraded]
     *     results, so a sync()-style demotion here would double-count the
     *     failure hysteresis.
     */
    private suspend fun routeHeartbeatTerminalFailure(failure: Throwable) {
        when (failure) {
            is RemoteDeviceRemovedException -> handleRemoteDeviceRemoved(failure)
            is RemoteMembershipDeletedException -> handleRemoteMembershipDeleted(failure)
            is RemoteFamilyDeletedException -> handleRemoteFamilyDeleted(failure)
            is ClientUpdateRequiredException -> handleClientUpdateRequired(failure)
            else -> updateFailureStatus(failure)
        }
    }

    override fun notifyLocalChanges() {
        requestSync(SyncTrigger.LocalWrite)
    }

    override fun notifyNetworkRecovered() {
        if (serverUpgradeBlockedIdentity == cachedSession.foregroundFuseIdentity()) return
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
        // Network recovery is a REAL trigger: retries stay allowed and the
        // cross-beat fuse releases with the existing 30s dedupe unchanged.
        heartbeatRoundFuse.onRealUserTrigger()
        pullRequested.set(true)
        syncSignal.trySend(Unit)
    }

    override suspend fun cleanupTombstonedMedia(clientUuids: Set<String>): Result<Unit> =
        try {
            if (syncMutex.tryLock()) {
                try {
                    mediaFileCleanup.cleanupTombstones(clientUuids)
                } finally {
                    syncMutex.unlock()
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    override suspend fun fetchConflictSnapshot(
        conflictId: String,
    ): com.lezi.babylog.sync.conflict.ConflictSnapshot {
        val requestedClearEpoch = conflictSnapshotClearEpoch.get()
        return try {
            conflictSnapshotLoadLocks.withLock(conflictId) {
                check(conflictSnapshotClearEpoch.get() == requestedClearEpoch) {
                    "冲突详情请求已跨越 session/local clear 边界"
                }
                val session = preferences.session.first()
                check(session.isJoined) { "未加入家庭，无法加载冲突详情" }
                val sessionIdentity = session.conflictSnapshotIdentity()
                val isLoadCurrent: suspend () -> Boolean = {
                    conflictSnapshotClearEpoch.get() == requestedClearEpoch &&
                        preferences.session.first().conflictSnapshotIdentity() == sessionIdentity
                }
                suspend fun load() = conflictSnapshotProjection.loadComplete(
                    conflictId = conflictId,
                    persistenceBarrier = syncMutex,
                    isLoadCurrent = isLoadCurrent,
                ) { request -> backend.fetchConflictSnapshotPage(session, conflictId, request) }
                try {
                    load()
                } catch (failure: SyncHttpException) {
                    val code = syncHttpCodeOrNull(failure.responseBody)
                    val receiptCanRestart = code in setOf(
                        "invalid_snapshot_token",
                        "snapshot_expired",
                        "snapshot_stale",
                    )
                    if (!receiptCanRestart ||
                        !conflictSnapshotProjection.discardStaging(conflictId)
                    ) {
                        throw failure
                    }
                    load()
                }
            }
        } finally {
            backend.releaseForegroundKeepAlive()
        }
    }

    override suspend fun resolveConflict(
        conflictId: String,
        request: com.lezi.babylog.sync.backend.ConflictResolveRequest,
    ): com.lezi.babylog.sync.backend.ConflictResolveResult {
        try {
            val session = preferences.session.first()
            check(session.isJoined) { "未加入家庭，无法解决冲突" }
            return backend.resolveConflict(session, conflictId, request)
        } finally {
            backend.releaseForegroundKeepAlive()
        }
    }

    override suspend fun withdrawConflictBranches(
        conflictId: String,
        request: com.lezi.babylog.sync.backend.ConflictWithdrawRequest,
    ): com.lezi.babylog.sync.backend.ConflictWithdrawResult {
        try {
            val session = preferences.session.first()
            check(session.isJoined) { "未加入家庭，无法撤回冲突分支" }
            return backend.withdrawConflictBranches(session, conflictId, request)
        } finally {
            backend.releaseForegroundKeepAlive()
        }
    }

    override suspend fun declareSourceRelation(
        request: com.lezi.babylog.sync.backend.SourceRelationDeclareRequest,
    ): com.lezi.babylog.sync.backend.SourceRelationResult = syncMutex.withLock {
        require(preferences.disasterRestoreCheckpoint.first() == null) {
            "家庭恢复正在进行，来源关系选择已暂停；请先完成或明确取消原恢复批次"
        }
        try { sourceRelationCommandOwner.declare(request) }
        finally { backend.releaseForegroundKeepAlive() }
    }

    override suspend fun resolveSourceRelationGroup(
        request: com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest,
    ): com.lezi.babylog.sync.backend.SourceRelationResult = syncMutex.withLock {
        require(preferences.disasterRestoreCheckpoint.first() == null) {
            "家庭恢复正在进行，来源关系选择已暂停；请先完成或明确取消原恢复批次"
        }
        try { sourceRelationCommandOwner.resolveGroup(request) }
        finally { backend.releaseForegroundKeepAlive() }
    }

    override suspend fun saveEndpointConfig(
        config: FamilyEndpointConfig,
    ): Result<Unit> {
        finishTerminalClearBeforeNewSession()?.let { return Result.failure(it) }
        return executeFamily(FamilySessionCommand.SaveEndpointConfig(config)).map { Unit }
    }

    override suspend fun createFamily(
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        finishTerminalClearBeforeNewSession()?.let { return Result.failure(it) }
        return executeFamily(
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
    }

    override suspend fun ownerLogin(
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
        candidateBaseUrl: String?,
    ): Result<OwnerLoginResult> {
        finishTerminalClearBeforeNewSession()?.let { return Result.failure(it) }
        return executeFamily(
            FamilySessionCommand.OwnerLogin(
                deviceName = deviceName,
                rootPassword = rootPassword,
                takeover = takeover,
                candidateBaseUrl = candidateBaseUrl,
            ),
        ).map {
            val joined = it as FamilySessionOutcome.Joined
            OwnerLoginResult(
                session = joined.session,
                dataRecovery = joined.dataRecovery,
            )
        }
    }

    override suspend fun reconnectOwner(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        rootPassword: String,
    ): Result<OwnerLoginResult> = runCatching {
        require(rootPassword.isNotBlank()) { "请填写管理员根密码" }
        finishTerminalClearBeforeNewSession()?.let { throw it }
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
            val device = com.lezi.babylog.sync.session.requireDeviceName(deviceName)
            val probed = probeOwnerFamilyWithoutTakeover(endpoint, device, rootPassword)
            require(probed.role == FamilyRole.Owner) { "管理员登录响应角色无效" }
            if (probed.familyId != previous.familyId) {
                revokeForeignProbeDevice(probed, endpoint)
                throw DifferentFamilyServerException()
            }
            // The non-destructive probe already authenticated this family and minted
            // exactly one device. Adopt it as the reconnect result; a second takeover
            // request would silently revoke unrelated administrator devices.
            val joined = probed
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
            } catch (error: Throwable) {
                InitialFamilyDataRecovery.RetryRequired(
                    causeKind = familyFailureKind(error),
                )
            }
            OwnerLoginResult(session, dataRecovery)
        }
    }

    override suspend fun prepareDisasterRecovery(): Result<DisasterRecoverySummary> = runCatching {
        syncMutex.withLock {
            requireRetainedOwnerSession()
            preferences.pendingDisasterRestoreRequestIds()?.let { ids ->
                if (restoreSnapshotJournal.exists(ids.start)) return@withLock restoreSnapshotJournal.load(ids.start).use { it.summary }
                restoreFileSnapshots.completed(ids.start)?.let { return@withLock restoreSnapshotJournal.completedSummary(it) }
                error("原本机恢复准备尚未完成，请继续或取消；不能用新摘要替换原请求")
            }
            disasterRecoverySnapshotBuilder.summary()
        }
    }

    override suspend fun startDisasterRecovery(
        endpoint: TrustedEndpointProfile,
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ): Result<DisasterRecoveryProgress> = startDisasterRecoveryRequest(endpoint, ownerDisplayName, deviceName, rootPassword)

    private suspend fun startDisasterRecoveryRequest(
        endpoint: TrustedEndpointProfile, ownerDisplayName: String, deviceName: String, rootPassword: String,
        expectedRequestId: String? = null, retainedFamilyName: String? = null,
    ): Result<DisasterRecoveryProgress> = mapDisasterRecoveryClientUpdateRequired(
        inviteHostHint = hostForLanInvite(endpoint),
        result = runCatching {
            require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
            syncMutex.withLock {
                if (expectedRequestId != null) require(preferences.pendingDisasterRestoreRequestIds()?.start == expectedRequestId) {
                    "恢复请求已变化，请重新查询原批次"
                }
                preferences.disasterRestoreCheckpoint.first()?.let {
                    return@withLock resumeDisasterRecoveryLocked(it)
                }
                val previous = requireRetainedOwnerSession()
                val probe = probeReconnectCandidate(endpoint.origin, endpoint)
                if (probe == SetupProbeResult.Failed.Incompatible)
                    throw com.lezi.babylog.sync.disasterrecovery.RestoreAuthorityUnsupportedException()
                require(
                    probe is SetupProbeResult.Ready &&
                        probe.familyState == com.lezi.babylog.sync.session.SetupFamilyState.Empty,
                ) { "家庭灾难恢复只适用于已校验的空服务器" }
                if ("restore_authority_v1" !in (probe as SetupProbeResult.Ready).capabilities)
                    throw com.lezi.babylog.sync.disasterrecovery.RestoreAuthorityUnsupportedException()
                val familyName = requireNotNull(
                    retainedFamilyName ?: previous.familyName?.trim()?.takeIf(String::isNotEmpty),
                ) {
                    "本机缺少旧家庭名称，无法安全恢复"
                }
                val requestIds = preferences.ensureDisasterRestoreRequestIds()
                val ownerName = requireMemberDisplayName(ownerDisplayName)
                val restoredDeviceName = requireDeviceName(deviceName)
                val startIntent = buildJsonObject {
                    put("origin", endpoint.origin); put("trust", endpoint.trustMode.name)
                    put("spki", endpoint.spkiSha256?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("family", previous.familyId); put("family_name", familyName)
                    put("owner_name", ownerName); put("device_name", restoredDeviceName)
                }
                if (!restoreSnapshotJournal.exists(requestIds.start)) {
                    val pointer = disasterRecoverySnapshotBuilder.capture(requestIds.start, previous.familyId,
                        restoreFileSnapshots)
                    // Pointer and exact nonsecret target/arguments commit together before dispatch.
                    restoreSnapshotJournal.bind(pointer, previous.familyId, startIntent)
                }
                restoreSnapshotJournal.requireStartIntent(requestIds.start, startIntent)
                val prepared = restoreSnapshotJournal.load(requestIds.start)
                prepared.use { snapshot ->
                    transactionRunner.run { requireUnchangedRestoreRelations(snapshot) }
                    val batch = backend.startDisasterRestore(
                        endpoint = endpoint,
                        requestId = requestIds.start,
                        familyId = previous.familyId,
                        familyName = familyName,
                        ownerDisplayName = ownerName,
                        deviceName = restoredDeviceName,
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
                        // New snapshots own their complete equality evidence in the immutable file.
                        entityVersions = if (snapshot.fileSnapshot == null) snapshot.retirementVersions else emptyList(),
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

    override suspend fun retryDisasterRecoveryStart(rootPassword: String): Result<DisasterRecoveryProgress> = runCatching {
        val retained = syncMutex.withLock {
            val ids = preferences.pendingDisasterRestoreRequestIds() ?: throw NoPendingDisasterRecoveryException()
            val intent = restoreSnapshotJournal.read(ids.start)["start_intent"]?.jsonObject
                ?: error("旧开始请求缺少固定目标和参数，原快照已保留，需要受控修复")
            require(intent.keys == setOf("origin", "trust", "spki", "family", "family_name", "owner_name", "device_name"))
            val origin = intent.getValue("origin").jsonPrimitive.content
            val endpoint = when (intent.getValue("trust").jsonPrimitive.content) {
                "SystemPki" -> { require(intent["spki"] == kotlinx.serialization.json.JsonNull); TrustedEndpointProfile.systemPki(origin) }
                "TofuSpki" -> TrustedEndpointProfile.tofuSpki(origin, intent.getValue("spki").jsonPrimitive.content)
                else -> error("恢复目标信任信息无效，原快照已保留")
            }
            Triple(ids.start, endpoint, intent)
        }
        startDisasterRecoveryRequest(retained.second,
            retained.third.getValue("owner_name").jsonPrimitive.content,
            retained.third.getValue("device_name").jsonPrimitive.content,
            rootPassword, expectedRequestId = retained.first,
            retainedFamilyName = retained.third.getValue("family_name").jsonPrimitive.content).getOrThrow()
    }

    override suspend fun resumeDisasterRecovery(): Result<DisasterRecoveryProgress> {
        val checkpoint = preferences.disasterRestoreCheckpoint.first()
        return mapDisasterRecoveryClientUpdateRequired(
            inviteHostHint = checkpoint?.let { hostForLanInvite(it.endpoint) },
            result = runCatching {
                syncMutex.withLock {
                    val current = preferences.disasterRestoreCheckpoint.first()
                    if (current == null) {
                        val request = preferences.pendingDisasterRestoreRequestIds() ?: throw NoPendingDisasterRecoveryException()
                        // Room binding precedes dispatch. A missing preference receipt after
                        // binding is an unknown remote start, never local-only cancellation.
                        val bound = restoreSnapshotJournal.exists(request.start)
                        val intent = if (bound) restoreSnapshotJournal.read(request.start)["start_intent"]?.jsonObject else null
                        require(!bound || intent != null) { "旧开始请求缺少固定目标和参数，原快照已保留，需要受控修复" }
                        return@withLock DisasterRecoveryProgress(null,
                            if (bound) "start_unknown" else "local_capture_pending", 0,
                            retainedStart = intent?.let { DisasterRecoveryStartPreview(
                                it.getValue("origin").jsonPrimitive.content, it.getValue("owner_name").jsonPrimitive.content,
                                it.getValue("device_name").jsonPrimitive.content) })
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
                syncMutex.withLock {
                    val current = requireNotNull(preferences.disasterRestoreCheckpoint.first()) {
                        "没有等待提交的家庭恢复批次"
                    }
                    if (restoreFileLifecycle.recoverPreparedRetirement(current.startRequestId)) {
                        recoverPendingLocalClearLocked()
                        error("家庭恢复批次已在本机退休，请重新开始")
                    }
                    val knownPhase = if (restoreSnapshotJournal.exists(current.startRequestId))
                        restoreSnapshotJournal.read(current.startRequestId)["phase"]?.jsonPrimitive?.content else null
                    if (knownPhase == "switched" || (knownPhase == "committed" &&
                            preferences.pendingReplicaResetPrevious() != null &&
                            preferences.pendingReplicaResetCredentialsReady())) {
                        check(recoverRestoreAuthoritySwitch())
                        requestSync(SyncTrigger.Foreground)
                        return@withLock OwnerLoginResult(preferences.session.first(), InitialFamilyDataRecovery.Complete)
                    }
                    require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
                    val token = preferences.disasterRestoreToken().also {
                        require(it.isNotBlank()) { "家庭恢复凭据已丢失，请取消后重新开始" }
                    }
                    val previous = requireRetainedOwnerSession()
                    require(previous.familyId == current.familyId) {
                        "本机家庭身份已变化，恢复已停止"
                    }
                    requireRestoreAuthorityEndpoint(current.endpoint)
                    restoreSnapshotJournal.load(current.startRequestId).use { snapshot ->
                        requireCapturedRestoreRelations(snapshot)
                        if (current.status != "commit_uncertain" && current.status != "committed") {
                            transactionRunner.run { requireUnchangedRestoreRelations(snapshot) }
                        }
                    }
                    preferences.saveDisasterRestoreCheckpoint(current.copy(status = "commit_uncertain"), token)
                    if (!restoreSnapshotJournal.isSwitched(current.startRequestId)) {
                        restoreSnapshotJournal.phase(current.startRequestId, "commit_uncertain")
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
                    restoreSnapshotJournal.committed(current.startRequestId, session)
                    preferences.saveDisasterRestoreCheckpoint(current.copy(status = "committed"), token)
                    preferences.saveSessionPendingReplicaReset(session, previous)
                    check(recoverRestoreAuthoritySwitch())
                    currentAvailability.value = FamilyServerAvailability.Disabled
                    requestSync(SyncTrigger.Foreground)
                    OwnerLoginResult(session, InitialFamilyDataRecovery.Complete)
                }
            },
        )
    }

    override suspend fun cancelDisasterRecovery(): Result<Unit> = runCatching {
        syncMutex.withLock {
            val checkpoint = preferences.disasterRestoreCheckpoint.first()
            if (checkpoint == null) {
                val request = preferences.pendingDisasterRestoreRequestIds() ?: return@withLock
                restoreFileLifecycle.retireUndispatched(request.start)
                preferences.clearDisasterRestoreCheckpoint()
                restoreFileLifecycle.reclaimRetired(force = true)
                return@withLock
            }
            restoreFileLifecycle.recoverPreparedRetirement(checkpoint.startRequestId)
            if (restoreSnapshotJournal.isRetiring(checkpoint.startRequestId)) {
                recoverPendingLocalClearLocked()
                return@withLock
            }
            val token = preferences.disasterRestoreToken()
            require(token.isNotBlank()) { "家庭恢复凭据已丢失，请清除本机恢复状态" }
            if (restoreSnapshotJournal.read(checkpoint.startRequestId)["format"]?.jsonPrimitive?.content == "2") {
                restoreFileLifecycle.retireAfterConfirmedCancellation(
                    checkpoint, currentCheckpoint = { preferences.disasterRestoreCheckpoint.first() },
                ) { backend.cancelDisasterRestore(checkpoint.endpoint, checkpoint.batchId, token) }
            } else {
                val cancelled = backend.cancelDisasterRestore(checkpoint.endpoint, checkpoint.batchId, token)
                require(cancelled.batchId == checkpoint.batchId && cancelled.status == "cancelled") {
                    "服务器尚未确认取消原恢复批次，请保留恢复信息"
                }
                restoreSnapshotJournal.cancel(checkpoint.startRequestId)
            }
            preferences.clearDisasterRestoreCheckpoint()
            restoreFileLifecycle.reclaimRetired(force = true)
            conflictSnapshotCacheDao?.let { cache ->
                com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement(
                    cache, mediaDao, mediaFiles, immutableMediaSpool, transactionRunner,
                ).reclaim()
            }
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
            preferences.memberLoginAttempt(reconnect = true)?.let { retained ->
                require(retained.endpointOrigin == endpoint.origin) {
                    "原候选服务器仍有结果待确认的申请，请先在这台设备放弃等待"
                }
                val live = pendingReconnectMember.get()
                if (live != null && preferences.isReconnectMemberAttemptCurrent(live.request.operationId, live.owner)) {
                    return@withLock live.request
                }
                pendingReconnectMember.set(null)
                return@withLock retained
            }
            require(pendingReconnectMember.get() == null) {
                "已有一条候选服务器加入申请"
            }
            val owner = preferences.memberReconnectOwner()
            val probe = probeReconnectCandidate(endpoint.origin, endpoint)
            require(
                probe is SetupProbeResult.Ready &&
                    probe.familyState == com.lezi.babylog.sync.session.SetupFamilyState.Configured,
            ) { "候选家庭服务器尚未完成配置或连接校验" }
            val normalizedDisplayName = requireMemberDisplayName(displayName)
            val normalizedDeviceName = requireDeviceName(deviceName)
            val uncertain = PendingMemberLogin(
                requestId = "", displayName = normalizedDisplayName, deviceName = normalizedDeviceName,
                expiresAtEpochSeconds = 0L, operationId = java.util.UUID.randomUUID().toString(),
                endpointOrigin = endpoint.origin, remoteOutcomeUnknown = true,
            )
            check(preferences.beginReconnectMemberAttempt(uncertain, owner)) {
                "当前家庭或服务器信任已变化，原候选申请未发送"
            }
            val receipt = try {
                backend.requestMemberLogin(endpoint, normalizedDisplayName, normalizedDeviceName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is com.lezi.babylog.sync.backend.MemberLoginRequestNotSentException ||
                    (failure is SyncHttpException && failure.statusCode in setOf(400, 401, 403, 404, 409, 422, 429))
                ) {
                    preferences.clearMemberLoginAttempt(reconnect = true, expectedOperationId = uncertain.operationId)
                    throw failure
                }
                check(preferences.isReconnectMemberAttemptCurrent(uncertain.operationId, owner)) {
                    "原候选申请已放弃或身份已变化"
                }
                return@withLock uncertain
            }
            check(preferences.isReconnectMemberAttemptCurrent(uncertain.operationId, owner)) {
                "原候选申请已放弃或身份已变化，迟到回执未恢复连接"
            }
            val public = PendingMemberLogin(
                requestId = receipt.requestId,
                displayName = normalizedDisplayName,
                deviceName = normalizedDeviceName,
                expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
                operationId = uncertain.operationId,
                endpointOrigin = endpoint.origin,
            )
            pendingReconnectMember.set(
                CandidateMemberReconnectAttempt(
                    endpoint = endpoint,
                    owner = owner,
                    pendingSecret = receipt.pendingSecret,
                    request = public,
                ),
            )
            if (!preferences.isReconnectMemberAttemptCurrent(uncertain.operationId, owner)) {
                pendingReconnectMember.set(null)
                error("原候选申请已放弃或身份已变化")
            }
            public
        }
    }.onFailure { it.cancellationCauseOrNull()?.let { cancellation -> throw cancellation } }

    override suspend fun recoverPendingReconnectMember(): Result<PendingMemberLogin?> = runCatching {
        reconnectMutex.withLock {
            val retained = preferences.memberLoginAttempt(reconnect = true)
            val live = pendingReconnectMember.get()
            if (live != null && preferences.isReconnectMemberAttemptCurrent(live.request.operationId, live.owner)) {
                live.request
            } else {
                pendingReconnectMember.set(null)
                retained
            }
        }
    }.onFailure { it.cancellationCauseOrNull()?.let { cancellation -> throw cancellation } }

    override suspend fun checkReconnectMember(): Result<MemberLoginCheckResult> = runCatching {
        reconnectMutex.withLock {
            if (pendingReconnectMember.get() == null) {
                preferences.memberLoginAttempt(reconnect = true)?.let {
                    throw MemberLoginOutcomeUnknownException(it)
                }
            }
            val attempt = requireNotNull(pendingReconnectMember.get()) {
                "没有等待管理员确认的候选服务器申请"
            }
            suspend fun requireCurrentAttempt() {
                if (!preferences.isReconnectMemberAttemptCurrent(attempt.request.operationId, attempt.owner)) {
                    pendingReconnectMember.compareAndSet(attempt, null)
                    error("原候选申请已放弃或当前家庭身份已变化")
                }
            }
            requireCurrentAttempt()
            val status = backend.memberLoginStatus(attempt.endpoint, attempt.pendingSecret)
            requireCurrentAttempt()
            when (status) {
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
                            preferences.clearMemberLoginAttempt(reconnect = true, expectedOperationId = attempt.request.operationId)
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
                        check(preferences.activateReconnectMemberIfCurrent(
                            attempt.request.operationId, attempt.owner, session, attempt.endpoint,
                        )) { "原候选申请已放弃或当前家庭身份已变化，未恢复旧连接" }
                        publishSession(session)
                        currentAvailability.value = FamilyServerAvailability.Disabled
                    }
                    pendingReconnectMember.set(null)
                    preferences.clearMemberLoginAttempt(reconnect = true, expectedOperationId = attempt.request.operationId)
                    val dataRecovery = try {
                        requestSync(SyncTrigger.Foreground)
                        InitialFamilyDataRecovery.NotRequired
                    } catch (error: Throwable) {
                        InitialFamilyDataRecovery.RetryRequired(
                            causeKind = familyFailureKind(error),
                        )
                    }
                    MemberLoginCheckResult.Joined(
                        session,
                        dataRecovery,
                    )
                }
                else -> {
                    pendingReconnectMember.set(null)
                    preferences.clearMemberLoginAttempt(reconnect = true, expectedOperationId = attempt.request.operationId)
                    MemberLoginCheckResult.Terminal(status)
                }
            }
        }
    }.onFailure { it.cancellationCauseOrNull()?.let { cancellation -> throw cancellation } }

    override suspend fun cancelReconnectMember(): Result<Unit> = runCatching {
        val attempt = reconnectMutex.withLock {
            preferences.clearMemberLoginAttempt(reconnect = true)
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
        val ownerEpoch = preferences.familyReadSnapshot.first().identityEpoch
        val remote = executeFamily(FamilySessionCommand.ListMembers)
        val directory = remote.getOrElse { return Result.failure(it) }
            .let { it as FamilySessionOutcome.MembersListed }
        return try {
            // A→B→A is a different local epoch even when the wire identity is identical.
            // Legacy adapters retain their old read API; their combined view stays roster-free.
            if (ownerEpoch != null) {
                check(preferences.saveFamilyMemberDirectoryIfCurrent(
                    expectedIdentityEpoch = ownerEpoch,
                    generation = directory.generation,
                    members = directory.members,
                )) { "家庭身份已变化，请重新刷新成员" }
            } else {
                preferences.saveFamilyMemberDirectorySnapshot(directory.generation, directory.members)
            }
            Result.success(directory.members)
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

    override suspend fun sync(trigger: SyncTrigger): Result<Unit> =
        // Public seam (syncWhenAvailable / direct PullToRefresh): never
        // tip-skip-eligible — only the conflated consumer's Foreground rounds
        // behind a fresh quiet proof may skip (0.5 ticket 04).
        syncInternal(trigger, allowQuietSkip = false)

    private suspend fun syncInternal(
        trigger: SyncTrigger,
        allowQuietSkip: Boolean,
    ): Result<Unit> {
        if (!foregroundState.isForeground()) {
            return Result.failure(ForegroundSyncBlockedException(ForegroundSyncDecision.Background))
        }
        return foregroundState.whileForeground {
            syncWithinForeground(trigger, allowQuietSkip)
        }
    }

    private suspend fun syncWithinForeground(
        trigger: SyncTrigger,
        allowQuietSkip: Boolean,
    ): Result<Unit> {
        if (trigger == SyncTrigger.PullToRefresh) {
            serverUpgradeBlockedIdentity = null
            // The direct suspend seam of the user pull-to-refresh (timeline /
            // members / summary refresh): a REAL retry authorization, same
            // release semantics as requestSync(PullToRefresh). The conflated
            // consumer only ever passes Foreground/LocalWrite here, so an
            // internal round can never take this branch.
            heartbeatRoundFuse.onRealUserTrigger()
        }
        val progressBefore = if (trigger != SyncTrigger.LocalWrite) {
            snapshotReplicaProgress()
        } else {
            null
        }
        var actedSession: SyncSession? = null
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
                actedSession = session
                if (!session.isJoined) {
                    currentStatus.value = SyncStatus.Disabled
                    return@withLock
                }
                synchronizeJoinedSessionLocked(
                    session,
                    trigger,
                    allowQuietSkip = allowQuietSkip && trigger == SyncTrigger.Foreground,
                )
            }
        }.recoverCatching { failure ->
            if (failure !is MissingTrustedMediaIdentityException) throw failure
            val acted = actedSession ?: throw failure
            val session = preferences.session.first()
            if (session.foregroundFuseIdentity() != acted.foregroundFuseIdentity()) throw failure
            val endpoint = preferences.verifiedEndpoint.first()
            val setup = withTimeout(10_000) { setupProbe.probe(session.baseUrl, endpoint) }
            if (preferences.session.first().foregroundFuseIdentity() != acted.foregroundFuseIdentity()) {
                throw failure
            }
            if (setup is SetupProbeResult.Ready) {
                if (CAPABILITY_CAUSAL_MEDIA_IDENTITY_V1 !in setup.capabilities) {
                    serverUpgradeBlockedIdentity = session.foregroundFuseIdentity()
                    throw ServerUpdateRequiredException(failure)
                }
                throw MediaIdentityProtocolException(failure)
            }
            throw failure
        }
        val failure = result.exceptionOrNull()
        if (failure is CancellationException && failure !is TimeoutCancellationException) {
            // Cancellation is control flow, not a sync failure. Restore the durable session's
            // steady projection before propagating it so no collector is orphaned in Syncing.
            // Residual TimeoutCancellationException is a typed response timeout.
            publishSession(cachedSession)
            throw failure
        }
        if (
            failure != null &&
            trigger != SyncTrigger.LocalWrite &&
            progressBefore != null &&
            shouldAutoContinueForeground(failure, progressBefore)
        ) {
            currentFailureKind.value = null
            if (foregroundState.isForeground()) {
                retriggerForegroundCycle()
            } else if (syncMutex.tryLock()) {
                try {
                    if (currentStatus.value == SyncStatus.Syncing) {
                        currentStatus.value = SyncStatus.Idle
                    }
                } finally {
                    syncMutex.unlock()
                }
            }
            return Result.success(Unit)
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
        // 0.5 W3 throttle: user real triggers always check; heartbeat-kick and
        // continuation rounds only when the last *successful* check is stale
        // (>1h). No new wake source — discovery never schedules a round.
        if (
            trigger != SyncTrigger.LocalWrite &&
            !handledClientUpdateRequired &&
            mapped.isSuccess &&
            shouldPiggybackAppUpdateDiscovery(trigger)
        ) {
            if (awaitPiggybackAppUpdateDiscovery) {
                discoverAppUpdateBestEffort(publishWhenDismissed = false)
            } else {
                processScope.launch {
                    discoverAppUpdateBestEffort(publishWhenDismissed = false)
                }
            }
        }
        return mapped
    }

    /**
     * Throttle gate (0.5 W3): a round may ride the app-update discovery when it
     * was caused by a real user trigger (pull-to-refresh directly, or a
     * foreground return through [requestSync]'s authorization flag), or when the
     * durable "last successful check" baseline is older than
     * [APP_UPDATE_PIGGYBACK_MIN_INTERVAL_MILLIS]. The flag is consumed here so a
     * later kick round cannot inherit a stale authorization.
     */
    private suspend fun shouldPiggybackAppUpdateDiscovery(trigger: SyncTrigger): Boolean {
        if (trigger == SyncTrigger.PullToRefresh || realUserSyncTriggerRequested.getAndSet(false)) {
            return true
        }
        val lastCheckedAt = preferences.lastAppUpdateCheckedAt.first() ?: return true
        return clock.nowMillis() - lastCheckedAt >= APP_UPDATE_PIGGYBACK_MIN_INTERVAL_MILLIS
    }

    override suspend fun leave(): Result<Unit> {
        val remote = executeFamily(FamilySessionCommand.Leave)
        val failure = remote.exceptionOrNull()
        if (failure != null && failure !is RemoteMembershipDeletedException) {
            return remote.map { Unit }
        }
        return completeConfirmedMembershipDeletion()
    }

    override suspend fun prepareSourceCommandLogout(): Result<SourceCommandLogoutConsent?> = try {
        Result.success(syncMutex.withLock { sourceLogoutAdmission.prepare() })
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { Result.failure(error) }

    override suspend fun logoutCurrentDevice(consent: SourceCommandLogoutConsent): Result<Unit> {
        val remote = executeFamily(FamilySessionCommand.LogoutCurrentDeviceWithSourceConsent(consent))
        if (remote.isFailure) return remote.map { Unit }
        return completeConfirmedDeviceRemoval()
    }

    override fun sourceCommandClearNotice(): Flow<com.lezi.babylog.sync.sourcerelation.SourceCommandClearNotice?> =
        sourceLogoutAdmission.notice

    override suspend fun acknowledgeSourceCommandClearNotice(notice: com.lezi.babylog.sync.sourcerelation.SourceCommandClearNotice) {
        sourceLogoutAdmission.acknowledge(notice)
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
    ): Result<Unit> {
        conflictSnapshotClearEpoch.incrementAndGet()
        return localReplicaClearCoordinator.clear(
            scope = scope,
            workflow = workflow,
            recoverDomain = localClearRecoveryGate::recoverPendingLocalClear,
        )
        .onFailure(::updateFailureStatus)
    }

    override suspend fun abandonRejectedMutation(
        entityType: String,
        clientUuid: String,
    ): Result<Unit> = runCatching {
        replicaSyncEngine.abandonMutation(entityType, clientUuid)
    }

    override suspend fun abandonPendingLocalMutations(
        entities: List<PendingLocalMutationRef>,
    ): Result<Unit> = runCatching {
        entities.forEach { entity ->
            replicaSyncEngine.abandonMutation(entity.entityType, entity.clientUuid)
        }
    }

    override suspend fun dismissUnresolvedLocally(
        entityType: String,
        clientUuid: String,
        kind: UnresolvedLocalKind,
    ): Result<Unit> = runCatching {
        replicaSyncEngine.dismissUnresolvedLocally(entityType, clientUuid, kind)
    }

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
                            // APK streams chunk-by-chunk into private staging (never a
                            // whole-package ByteArray on low-memory phones); the digest
                            // covers exactly the bytes that reached the file.
                            val receipt = stagingFile.outputStream().use { target ->
                                backend.downloadAppUpdateApk(session, target)
                            }
                            require(receipt.byteCount > 0L) { "更新包下载为空" }
                            if (receipt.sha256 != metadata.sha256) {
                                throw IllegalStateException("更新包校验失败，请重试")
                            }
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
            // Only a fully successful check arms the throttle window; a failed
            // metadata/gate path must let the next round retry promptly.
            preferences.saveLastAppUpdateCheckedAt(clock.nowMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        preferences.disasterRestoreCheckpoint.first()?.let { checkpoint ->
            restoreFileLifecycle.recoverPreparedRetirement(checkpoint.startRequestId)
            if (restoreSnapshotJournal.isRetiring(checkpoint.startRequestId)) {
                preferences.clearDisasterRestoreCheckpoint()
            }
        }
        familySessionCoordinator.recoverPendingReplicaReset()
        if (preferences.disasterRestoreCheckpoint.first() == null) {
            preferences.pendingDisasterRestoreRequestIds()?.let { request ->
                if (restoreFileLifecycle.recoverUndispatchedRetirement(request.start))
                    preferences.clearDisasterRestoreCheckpoint()
            }
            terminalSpoolRetirement?.reclaim()
            restoreFileLifecycle.reclaimRetired()
            conflictSnapshotCacheDao?.let { cache ->
                com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement(
                    cache, mediaDao, mediaFiles, immutableMediaSpool, transactionRunner,
                ).reclaim()
            }
        }
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
        // Loss visibility (0.5.4 S3): the cleanup receipt is durable BEFORE the
        // terminal marker and reads the dirty count BEFORE Room is wiped, so a
        // crash can never resume the clear via the pending marker without a
        // receipt. Clear semantics themselves are unchanged.
        val receipt = DeviceRemovedCleanupReceipt(
            removedAtEpochMillis = clock.nowMillis(),
            clearedPendingCount = pendingPublishDao.observeCount().first().coerceAtLeast(0),
        )
        if (removal.actedFor != null) {
            check(preferences.stageTerminalRemovalIfCurrent(removal.actedFor, TerminalRemovalKind.Device, receipt)) {
                "过期会话的设备撤销已忽略"
            }
        } else {
            preferences.saveDeviceRemovedReceipt(receipt)
            preferences.markPendingDeviceRemovalClear()
        }
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
        if (deletion.actedFor != null) {
            check(preferences.stageTerminalRemovalIfCurrent(deletion.actedFor, TerminalRemovalKind.Membership)) {
                "过期会话的删除响应已忽略"
            }
        } else {
            preferences.markPendingMembershipDeletionClear()
        }
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
        if (deletion.actedFor != null) {
            check(preferences.stageTerminalRemovalIfCurrent(deletion.actedFor, TerminalRemovalKind.Family)) {
                "过期会话的删除响应已忽略"
            }
        } else {
            preferences.markPendingFamilyDeletionClear()
        }
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

    private suspend fun finishTerminalClearBeforeNewSession(): Throwable? {
        val pending = preferences.hasPendingDeviceRemovalClear() ||
            preferences.hasPendingMembershipDeletionClear() ||
            preferences.hasPendingFamilyDeletionClear()
        if (!pending) return null
        return try {
            finishPendingTerminalIdentityClear()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure
        }
    }

    private suspend fun probeOwnerFamilyWithoutTakeover(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        rootPassword: String,
    ): com.lezi.babylog.sync.backend.SessionBootstrapResult {
        val probeRequestId = preferences.ensureOwnerLoginRequestId()
        return try {
            backend.ownerLogin(
                endpoint = endpoint,
                deviceName = deviceName,
                loginRequestId = probeRequestId,
                rootPassword = rootPassword,
                takeover = false,
            )
        } catch (error: SyncHttpException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                preferences.clearOwnerLoginRequestId()
                throw OwnerRootPasswordRejectedException()
            }
            throw error
        }
    }

    private suspend fun revokeForeignProbeDevice(
        probed: com.lezi.babylog.sync.backend.SessionBootstrapResult,
        endpoint: TrustedEndpointProfile,
    ) {
        val config = FamilyEndpointConfig.fromBaseUrl(endpoint.origin).withNormalized()
        val probeSession = SyncSession(
            familyId = probed.familyId,
            accessToken = probed.accessToken,
            refreshToken = probed.refreshToken,
            accessExpiresAtEpochSeconds = probed.accessExpiresAtEpochSeconds,
            deviceId = probed.deviceId,
            role = probed.role,
            serverHost = config.host,
            serverPort = config.port,
            serverScheme = config.scheme,
            membershipId = probed.membershipId.trim(),
        )
        try {
            backend.logoutCurrentDevice(probeSession)
            preferences.clearOwnerLoginRequestId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IllegalStateException(
                "对方服务器可能多了一台管理员设备，请在那台服务器上删除它",
                error,
            )
        }
    }

    private suspend fun finishPendingTerminalIdentityClear() {
        conflictSnapshotClearEpoch.incrementAndGet()
        syncMutex.withLock {
            // Another recovery owner may have completed while this caller waited.
            if (!preferences.hasPendingDeviceRemovalClear() &&
                !preferences.hasPendingMembershipDeletionClear() &&
                !preferences.hasPendingFamilyDeletionClear()
            ) return@withLock

            // Terminal identity and every local family projection converge under
            // the same barrier used by foreground sync. Credentials are already
            // made unusable by the durable terminal marker before this method.
            localReplicaClearCoordinator.clearUnderBarrier(
                scope = LocalDataClearScope.AllLocalData,
                workflow = removedDeviceLocalClearGate.localClearWorkflow(),
                recoverDomain = localClearRecoveryGate::recoverPendingLocalClear,
            )
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
        allowQuietSkip: Boolean = false,
    ) {
        try {
            synchronizeJoinedSessionLockedBody(session, trigger, allowQuietSkip)
        } finally {
            replicaSyncEngine.invalidatePendingPublishRoundCache()
        }
    }

    private suspend fun synchronizeJoinedSessionLockedBody(
        session: SyncSession,
        trigger: SyncTrigger,
        allowQuietSkip: Boolean,
    ) {
        if (allowQuietSkip && shouldSkipQuietForegroundRound(session)) {
            // 0.5 ticket 04 tip-skip: the freshest heartbeat beat answered
            // "nothing changed" within the freshness window and no recovery /
            // cleanup / publish / missing-media seam is pending, so this
            // foreground-return round skips the handshake and the guaranteed-
            // empty pull entirely — zero data requests. Deliberately touches
            // NO observable state: no Syncing flip, no markSuccess, no fuse
            // release, no lastServerHealthyAt refresh, no census comparison.
            //
            // 安全论证 (0.5 design §2-W3 / review C8): skipping the handshake
            // skips the protocol/capability exact match, but the server
            // version floor is still enforced on every heartbeat probe
            // (tools/lezi-sync `require_supported_client` guards the
            // heartbeat route too), so an outdated client cannot sit out a
            // server upgrade — the beat's 4xx surfaces
            // ClientUpdateRequiredException through the existing terminal
            // mapping. The missing capability-exact-match exposure is bounded
            // by the freshness window (the heartbeat policy's no-change cap).
            return
        }
        val outcome = withElapsedBudget(
            kind = FamilyHttpFailureKind.SyncTookTooLong,
            maxElapsedMillis = if (trigger == SyncTrigger.LocalWrite) {
                LOCAL_WRITE_MAX_ELAPSED_MILLIS
            } else {
                ForegroundSyncCycle.MAX_ELAPSED_MILLIS
            },
            clock = familyElapsedClock,
        ) {
            replicaSyncEngine.synchronize(session, trigger)
        }
        preferences.markSuccess(clock.nowMillis())
        cachedSession = preferences.session.first()
        // Success is durable progress: release any armed cross-beat fuse and
        // reset the zero-progress budget for the round's own identity.
        heartbeatRoundFuse.onDurableProgress(cachedSession.foregroundFuseIdentity())
        // 0.5 ticket 04 (review C9): the completed round restarts the tip-skip
        // freshness window at this proven "server head == local cursor" point.
        // It stamps ONLY the anchor — the negative proof itself must come from
        // a heartbeat beat, so a server without heartbeat capability can never
        // make a round skippable.
        if (trigger == SyncTrigger.LocalWrite) {
            // Push-only success moved the server head without moving pullCursor.
            // A still-fresh no-change proof would skip the pull that publishes it.
            quietHeartbeatProof = null
        } else {
            noteQuietFullCycleCompleted()
        }
        currentStatus.value = when (outcome) {
            ReplicaSyncOutcome.Synchronized -> SyncStatus.Idle
        }
        currentFailureKind.value = null
    }

    /**
     * 0.5 ticket 04 tip-skip predicate (one-vote veto, design §2-W3). The
     * round may only skip when ALL of the following hold:
     *  - the foreground gate would allow the round anyway (a blocked round
     *    keeps surfacing its exception exactly as before — skipping must not
     *    mask the gate);
     *  - the freshest heartbeat beat answered no-change and its three keys
     *    match the CURRENT session snapshot (any NeedsSync / degraded /
     *    throttled / endpoint-missing beat has destroyed the proof);
     *  - the last successful full cycle (handshake + pull) is inside the
     *    freshness window — the window constant is the heartbeat policy's
     *    no-change backoff cap (ticket 07 keeps them shared on purpose);
     *  - no pending generation resync (LocalWrite generation fault awaiting
     *    the full round), no pending device/membership/family clear, no
     *    unconsumed replica reset receipt, no pending publish units, and no
     *    missing media beyond confirmed-404 residents (engine seam).
     *
     * 心跳 kick 与零进度续跑的 Foreground 轮在信号消费处已带否决
     * ([foregroundRoundSkipVeto]) — 它们永不进入本判定。
     */
    private suspend fun shouldSkipQuietForegroundRound(session: SyncSession): Boolean {
        val gateDecision = foregroundSyncGate.evaluate(
            session.endpointConfig,
            preferences.verifiedEndpoint.first(),
            foregroundState.isForeground(),
        )
        if (gateDecision != ForegroundSyncDecision.Allowed) return false
        val proof = quietHeartbeatProof ?: return false
        val anchorAt = quietFreshnessAnchorMillis ?: return false
        val windowMillis = SyncHeartbeatPolicy.MAX_NO_CHANGE_INTERVAL_MILLIS
        val anchorAge = clock.nowMillis() - anchorAt
        if (anchorAge < 0 || anchorAge > windowMillis) return false
        if (proof.headRev != session.pullCursor) return false
        if (proof.generation != session.pullGeneration) return false
        val directory = preferences.familyReadSnapshot.first()
        if (directory.identityEpoch != null) {
            if (!directory.hasCurrentDirectory || proof.directoryGeneration != directory.directoryGeneration) return false
        } else if (proof.directoryGeneration != preferences.familyMemberDirectoryGeneration.first()) {
            return false
        }
        if (preferences.hasPendingGenerationResync()) return false
        if (
            preferences.hasPendingDeviceRemovalClear() ||
            preferences.hasPendingMembershipDeletionClear() ||
            preferences.hasPendingFamilyDeletionClear()
        ) {
            return false
        }
        if (replicaSyncEngine.hasQuietForegroundRoundBlockers(session)) return false
        return true
    }

    /** Stamps the freshness-window anchor; never mints a proof by itself. */
    private fun noteQuietFullCycleCompleted() {
        quietFreshnessAnchorMillis = clock.nowMillis()
    }

    /**
     * Tip-skip proof bookkeeping (0.5 ticket 04): only a no-change answer
     * refreshes the quiet proof; every other beat outcome (kick verdict,
     * degradation, throttle, endpoint loss) destroys it — a round may only
     * skip behind the freshest possible "nothing changed" evidence. Both the
     * manual refresh path and the heartbeat loop run through this helper.
     */
    private fun noteQuietHeartbeatBeatOutcome(beat: SyncHeartbeatBeat) {
        if (beat is SyncHeartbeatBeat.Answered && beat.verdict == HeartbeatVerdict.NoAction) {
            noteQuietHeartbeatBeat(beat.signal)
        } else {
            quietHeartbeatProof = null
        }
    }

    private fun noteQuietHeartbeatBeat(signal: SyncHeartbeat) {
        quietHeartbeatProof = QuietProof(
            generation = signal.generation,
            headRev = signal.headRev,
            directoryGeneration = signal.directoryGeneration,
        )
    }

    private suspend fun requireRetainedOwnerSession(): SyncSession =
        preferences.session.first().also { session ->
            require(session.familyId.isNotBlank() && session.role == FamilyRole.Owner) {
                "只有仍保留旧家庭管理员身份的设备可以恢复空服务器"
            }
        }

    private suspend fun requireRestoreAuthorityEndpoint(endpoint: TrustedEndpointProfile) {
        val probe = probeReconnectCandidate(endpoint.origin, endpoint)
        if (probe == SetupProbeResult.Failed.Incompatible)
            throw com.lezi.babylog.sync.disasterrecovery.RestoreAuthorityUnsupportedException()
        check(probe is SetupProbeResult.Ready) { "暂时无法验证恢复服务器，原恢复信息已保留，请稍后重试" }
        if ("restore_authority_v1" !in probe.capabilities)
            throw com.lezi.babylog.sync.disasterrecovery.RestoreAuthorityUnsupportedException()
    }

    private suspend fun resumeDisasterRecoveryLocked(
        checkpoint: DisasterRestoreCheckpoint,
    ): DisasterRecoveryProgress {
        // Released app34 checkpoints have no immutable Room snapshot. Preserve them and
        // the local dataset; even a remote401 must not silently abandon an unproved batch.
        if (restoreFileLifecycle.recoverPreparedRetirement(checkpoint.startRequestId)) {
            recoverPendingLocalClearLocked()
            throw IllegalStateException("家庭恢复批次已在本机退休，请重新开始")
        }
        val localSnapshot = restoreSnapshotJournal.read(checkpoint.startRequestId)
        if (localSnapshot["phase"]?.jsonPrimitive?.content == "retiring") {
            recoverPendingLocalClearLocked()
            throw IllegalStateException("家庭恢复批次已失效，请重新开始")
        }
        if (checkpoint.status == "committed" && localSnapshot["phase"]?.jsonPrimitive?.content == "switched" &&
            restoreSnapshotJournal.matchesTarget(checkpoint.startRequestId, preferences.session.first()) &&
            preferences.pendingReplicaResetCredentialsReady()) {
            return DisasterRecoveryProgress(null, "committed", checkpoint.expiresAtEpochSeconds, localActivationReady = true)
        }
        val localSummary = restoreSnapshotJournal.load(checkpoint.startRequestId).use { snapshot ->
            transactionRunner.run { requireUnchangedRestoreRelations(snapshot) }
            snapshot.summary
        }
        if (checkpoint.status == "committed" &&
            localSnapshot["phase"]?.jsonPrimitive?.content in setOf("committed", "switched") &&
            restoreSnapshotJournal.matchesTarget(checkpoint.startRequestId, preferences.session.first()) &&
            preferences.pendingReplicaResetCredentialsReady()) {
            return DisasterRecoveryProgress(localSummary, "committed", checkpoint.expiresAtEpochSeconds, localActivationReady = true)
        }
        val token = preferences.disasterRestoreToken().also {
            require(it.isNotBlank()) { "家庭恢复凭据已丢失，请取消后重新开始" }
        }
        requireRetainedOwnerSession().also {
            require(it.familyId == checkpoint.familyId) { "本机家庭身份已变化，恢复已停止" }
        }
        requireRestoreAuthorityEndpoint(checkpoint.endpoint)
        val remote = try {
            backend.disasterRestoreStatus(
                checkpoint.endpoint,
                checkpoint.batchId,
                token,
            )
        } catch (error: SyncHttpException) {
            // Absent batches deliberately return 401 just like invalid recovery tokens.
            // This endpoint's credential is restore-scoped, never the active family session.
            if (error.statusCode == 401 || error.statusCode == 404 || error.statusCode == 410) {
                // Neither a missing capability nor expiry proves an uncertain commit failed.
                require(checkpoint.status != "commit_uncertain" && checkpoint.status != "committed") {
                    "家庭恢复提交结果尚未确认，请保留原恢复信息并重试"
                }
                // Retain durable artifact ownership before forgetting the replay identifiers.
                if (localSnapshot["format"]?.jsonPrimitive?.int == 2) {
                    restoreFileLifecycle.retirePrepared(checkpoint.startRequestId,
                        com.lezi.babylog.sync.disasterrecovery.RestoreFileRetirementReason.Unavailable)
                } else restoreSnapshotJournal.retireUnavailable(checkpoint.startRequestId)
                preferences.clearDisasterRestoreCheckpoint()
                restoreFileLifecycle.reclaimRetired(force = true)
                com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement(
                    conflictSnapshotCacheDao, mediaDao, mediaFiles, immutableMediaSpool, transactionRunner,
                ).reclaim()
                throw IllegalStateException("家庭恢复批次已失效，请重新开始", error)
            }
            throw error
        }
        restoreSnapshotJournal.read(checkpoint.startRequestId)
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
        restoreSnapshotJournal.load(checkpoint.startRequestId).use { snapshot ->
            val uploadManifest = remote.status == "started"
            val activeCheckpoint = checkpoint
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
    ): DisasterRestoreStatus = withElapsedBudget(
        kind = FamilyHttpFailureKind.SyncTookTooLong,
        maxElapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS,
        clock = familyElapsedClock,
    ) {
        if (uploadManifest) {
            currentCoroutineContext()[ElapsedBudgetContext]?.requireRemaining()
            backend.putDisasterRestoreManifest(
                endpoint = checkpoint.endpoint,
                batchId = checkpoint.batchId,
                recoveryToken = token,
                requestId = checkpoint.manifestRequestId,
                entities = snapshot.entities,
                media = snapshot.media.map { it.spec },
                sourceRelations = requireNotNull(snapshot.sourceRelations) {
                    "旧恢复快照缺少完整来源关系证据，原数据已保留，请取消后重新开始"
                },
            )
        }
        snapshot.media.forEach { media ->
            currentCoroutineContext()[ElapsedBudgetContext]?.requireRemaining()
            backend.putDisasterRestoreMedia(
                endpoint = checkpoint.endpoint,
                batchId = checkpoint.batchId,
                recoveryToken = token,
                clientUuid = media.clientUuid,
                source = media.source,
            )
        }
        currentCoroutineContext()[ElapsedBudgetContext]?.requireRemaining()
        backend.disasterRestoreStatus(
            checkpoint.endpoint,
            checkpoint.batchId,
            token,
        ).also {
            require(it.readyToCommit || it.committed) {
                "家庭数据或照片尚未完整上传"
            }
        }
    }

    private fun requireCapturedRestoreRelations(snapshot: com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshot) {
        require(snapshot.sourceRelations != null && snapshot.sourceRelationEvidence != null) {
            "旧恢复快照缺少完整来源关系证据；原数据和批次已保留，请明确取消后重新开始"
        }
    }

    /** Caller owns the sync command fence and a Room transaction. */
    private suspend fun requireUnchangedRestoreRelations(snapshot: com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshot) {
        requireCapturedRestoreRelations(snapshot)
        val dao = requireNotNull(sourceRelationDao) { "来源关系存储不可用，恢复已停止" }
        require(conflictSnapshotCacheDao.getTransportJournal("source-relation-command-v1") == null &&
            dao.listUnsettledDeclarations().isEmpty()) { "来源关系操作结果尚未确认；请先在原服务器确认原操作，恢复数据已保留" }
        require(snapshot.sourceRelationEvidence ==
            com.lezi.babylog.sync.disasterrecovery.readRestoreSourceRelationEvidence(dao)) {
            "来源关系在恢复快照之后发生变化；原批次和本机选择已保留，不能覆盖当前选择"
        }
    }

    /** Dispatch before generic replica reset; the Room marker makes a cross-store retry idempotent. */
    private suspend fun recoverRestoreAuthoritySwitch(): Boolean {
        val checkpoint = preferences.disasterRestoreCheckpoint.first() ?: return false
        val previous = preferences.pendingReplicaResetPrevious()
        val restored = preferences.session.first()
        val switched = restoreSnapshotJournal.exists(checkpoint.startRequestId) &&
            restoreSnapshotJournal.isSwitched(checkpoint.startRequestId)
        if (previous == null && !switched) {
            require(checkpoint.status != "commit_uncertain" && checkpoint.status != "committed") {
                "家庭恢复提交结果待确认，请继续原恢复批次"
            }
            return false
        }
        require(restoreSnapshotJournal.matchesTarget(checkpoint.startRequestId, restored)) {
            "恢复会话尚未完整保存，请重放原恢复提交"
        }
        require(preferences.pendingReplicaResetCredentialsReady()) {
            "恢复凭据保存被中断，请重放原恢复提交"
        }
        if (previous != null) {
            require(restored.familyId == checkpoint.familyId && previous.familyId == checkpoint.familyId)
            retireDisasterRestoreReceipts(restored, checkpoint)
        }
        preferences.completePendingRestoreSession(checkpoint.endpoint)
        val active = preferences.session.first()
        require(active.isJoined) { "恢复凭据尚未可用，请重放原恢复提交" }
        if (restoreSnapshotJournal.read(checkpoint.startRequestId)["format"]?.jsonPrimitive?.int == 2) {
            restoreFileLifecycle.retirePublished(checkpoint.startRequestId)
        }
        preferences.clearDisasterRestoreCheckpoint()
        publishSession(preferences.session.first())
        try {
            terminalSpoolRetirement?.reclaim()
            com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement(
                conflictSnapshotCacheDao, mediaDao, mediaFiles, immutableMediaSpool, transactionRunner,
            ).compactSnapshot(com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(checkpoint.startRequestId))
            restoreFileLifecycle.reclaimRetired(force = true)
        } catch (failure: Exception) {
            failure.cancellationCauseOrNull()?.let { throw it }
            // Authority is already published. The durable marker retries cleanup at startup;
            // report the existing sync warning without returning a false restore failure.
            currentFailureKind.value = FailureKind.UnexpectedError
            currentStatus.value = SyncStatus.Error
        }
        return true
    }

    private suspend fun retireDisasterRestoreReceipts(
        restored: SyncSession,
        checkpoint: DisasterRestoreCheckpoint,
    ) {
        if (restoreSnapshotJournal.isSwitched(checkpoint.startRequestId)) return
        val snapshot = restoreSnapshotJournal.load(checkpoint.startRequestId)
        snapshot.use {
            restoreFileLifecycle.withAuthorityTransition(snapshot) { checkedMedia, ownedPaths ->
            transactionRunner.run {
                if (restoreSnapshotJournal.isSwitched(checkpoint.startRequestId)) return@run
                requireUnchangedRestoreRelations(snapshot)
                val captured = com.lezi.babylog.sync.disasterrecovery.CapturedRestoreRows(
                    babyDao.listAllIncludingDeleted(), recordDao.listAllIncludingDeleted(),
                    carePlanDao.listAllIncludingDeleted(), customItemDao.listAllIncludingDeleted(),
                    fulfillmentCandidateDao.listAllIncludingDeleted(), wakeObservationDao.listAllIncludingDeleted(),
                    mediaDao.listAllIncludingDeleted(),
                )
                check(checkedMedia.all { (uuid, row) -> captured.mediaRow(uuid) == row }) {
                    "服务器已提交恢复，本机照片刚刚变化；原批次已保留，请重试本机激活"
                }
                val versions = snapshot.retirementVersions.associateBy { it.type to it.clientUuid }
                val restoredPayloads = snapshot.entities.associate {
                    (it.type to it.clientUuid) to Json.parseToJsonElement(it.payloadJson).jsonObject
                }
                fun unchanged(type: String, uuid: String): Boolean {
                    // Legacy toString hashes can alias null and literal text; never use them to clean current facts.
                    if (snapshot.evidenceVersion != 2) return false
                    val evidence = versions[type to uuid]?.localEvidence ?: return false
                    return evidence == captured.exactEvidence(type, uuid)
                }
                fun base(type: String, uuid: String): String? =
                    versions[type to uuid]?.takeIf { it.restored }?.let {
                        com.lezi.babylog.sync.disasterrecovery.RestoreAuthority.baseline(checkpoint.batchId, type, uuid)
                    }
                fun dirty(type: String, uuid: String) = !unchanged(type, uuid)
                captured.babies.forEach { row ->
                    val pending = dirty("baby", row.clientUuid)
                    val payload = restoredPayloads["baby" to row.clientUuid]?.takeIf { !pending }
                    val avatar = if (payload != null) payload["avatar_media_uuid"]?.jsonPrimitive?.contentOrNull else row.avatarMediaUuid
                    babyDao.update(row.copy(
                        avatarMediaUuid = avatar,
                        avatarPath = avatar?.let { uuid ->
                            ownedPaths[uuid]?.takeIf { unchanged("media", uuid) }
                                ?: if (avatar == row.avatarMediaUuid) row.avatarPath else
                                    captured.mediaRow(avatar)?.localUri
                        },
                        baseVersion = base("baby", row.clientUuid),
                        mutationId = if (pending) UUID.randomUUID().toString() else null,
                        syncDirty = pending, openConflictId = null, localBranchVersionId = null,
                    ))
                }
                captured.records.forEach { row ->
                    val pending = dirty("record", row.clientUuid)
                    recordDao.update(row.copy(
                        baseVersion = base("record", row.clientUuid),
                        mutationId = if (pending) UUID.randomUUID().toString() else null,
                        syncDirty = pending, openConflictId = null, localBranchVersionId = null,
                        createdByMembershipId = restored.membershipId,
                        familyPublishedUpdatedAt = if (!pending && base("record", row.clientUuid) != null) row.updatedAt else null,
                    ))
                }
                captured.plans.forEach { row ->
                    val pending = dirty("care_plan", row.clientUuid)
                    val payload = restoredPayloads["care_plan" to row.clientUuid]?.takeIf { !pending }
                    carePlanDao.update(row.copy(
                        status = payload?.get("status")?.jsonPrimitive?.content ?: row.status,
                        fulfilledRecordClientUuid = if (payload != null)
                            payload["fulfilled_record_client_uuid"]?.jsonPrimitive?.contentOrNull else row.fulfilledRecordClientUuid,
                        fulfilledAt = if (payload != null) payload["fulfilled_at"]?.jsonPrimitive?.longOrNull else row.fulfilledAt,
                        baseVersion = base("care_plan", row.clientUuid),
                        mutationId = if (pending) UUID.randomUUID().toString() else null,
                        syncDirty = pending, openConflictId = null, localBranchVersionId = null,
                        createdByMembershipId = restored.membershipId,
                        familyPublishedUpdatedAt = if (!pending && base("care_plan", row.clientUuid) != null) row.updatedAt else null,
                    ))
                }
                captured.customItems.forEach { row ->
                    val pending = dirty("custom_item", row.clientUuid)
                    customItemDao.update(row.copy(
                        baseVersion = base("custom_item", row.clientUuid),
                        mutationId = if (pending) UUID.randomUUID().toString() else null,
                        syncDirty = pending, openConflictId = null, localBranchVersionId = null,
                        createdByMembershipId = restored.membershipId,
                    ))
                }
                captured.wakes.forEach { row ->
                    val pending = dirty("wake_observation", row.clientUuid)
                    wakeObservationDao.update(row.copy(
                        baseVersion = base("wake_observation", row.clientUuid),
                        mutationId = if (pending) UUID.randomUUID().toString() else null,
                        syncDirty = pending, openConflictId = null, localBranchVersionId = null,
                        observerMembershipId = restored.membershipId,
                        familyPublishedUpdatedAt = if (!pending && base("wake_observation", row.clientUuid) != null) row.updatedAt else null,
                    ))
                }
                captured.candidates.forEach { row ->
                    fulfillmentCandidateDao.update(row.copy(
                        submitterMembershipId = restored.membershipId, submitterRole = "owner",
                        syncDirty = !unchanged("fulfillment_candidate", row.clientUuid),
                    ))
                }
                val uploadedMedia = snapshot.media.associateBy { it.clientUuid }
                captured.media.forEach { row ->
                    val uploaded = uploadedMedia[row.clientUuid]
                    val exact = uploaded != null && unchanged("media", row.clientUuid)
                    val verified = uploaded != null && row.clientUuid in ownedPaths
                    mediaDao.update(row.copy(
                        localUri = ownedPaths[row.clientUuid] ?: row.localUri,
                        remoteUri = if (verified || exact) restored.receiptFor(row.clientUuid) else null,
                        sha256 = if (verified || exact) uploaded?.spec?.sha256 else row.sha256,
                        byteSize = if (verified || exact) requireNotNull(uploaded).spec.byteSize else row.byteSize,
                        syncDirty = !exact && !unchanged("media", row.clientUuid),
                        baseVersion = null, mutationId = null,
                        openConflictId = null, localBranchVersionId = null,
                    ))
                }
                // Preserve durable byte references before retiring old-authority envelopes.
                // Plain manifests carry no replayable operation, receipt or authority binding.
                val cache = requireNotNull(conflictSnapshotCacheDao)
                val originalSpoolRows = cache.listFrozenMediaSpoolManifests()
                val retainedGroups = originalSpoolRows.map {
                    com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest(it.payloadJson)
                }
                val journal = restoreSnapshotJournal.read(checkpoint.startRequestId)
                val previousFileOwners = cache.listRestoreFileOwners()
                val previousTerminalSeals = cache.listRestoreTerminalSpoolSeals()
                cache.deleteAll()
                conflictSummaryDao?.deleteAll()
                cache.deleteAllTransportJournals()
                (previousFileOwners + previousTerminalSeals).forEach { owner ->
                    cache.putTransportJournal(owner.journalKey, owner.payloadJson, owner.contentEpoch)
                }
                retainedGroups.forEach { group ->
                    cache.putFrozenMediaSpoolManifest(group.mutationId,
                        com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup(group), 0)
                }
                captured.media.filter { row ->
                    row.deletedAt == null && row.clientUuid in uploadedMedia &&
                        (row.clientUuid in ownedPaths || unchanged("media", row.clientUuid))
                }.forEach { row ->
                    cache.putTransportJournal("restored-media-bytes-v1:${row.clientUuid}",
                        ownedPaths[row.clientUuid] ?: row.localUri, row.updatedAt)
                }
                cache.putTransportJournal(
                    com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(checkpoint.startRequestId),
                    journal.toString(), 0,
                )
                // Keep declarations (local source intent), remove only old derived authority.
                val relationDao = requireNotNull(sourceRelationDao)
                relationDao.deleteAllMembers()
                relationDao.deleteAll()
                requireNotNull(snapshot.sourceRelations).forEach { relation ->
                    relationDao.applyPullSummary(relation.relationId, relation.displayClientUuid,
                        com.lezi.babylog.core.database.causal.SourceRelationRole.DISPLAY,
                        relation.sourceClientUuids, clock.nowMillis(), relation.autoAligned)
                }
                cache.putTransportJournal(
                    com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.KEY,
                    com.lezi.babylog.sync.disasterrecovery.RestoreArtifactRetirement.encode(
                        com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal.key(checkpoint.startRequestId),
                        retainedGroups.map { it.mutationId },
                    ), 0,
                )
                val terminalSeals = terminalSpoolRetirement?.captureAcceptedSwitch(
                    snapshot, journal, checkpoint, restored, captured, originalSpoolRows, ownedPaths,
                ).orEmpty()
                terminalSpoolRetirement?.persistCaptured(terminalSeals)
                restoreSnapshotJournal.phase(checkpoint.startRequestId, "switched")
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

    private suspend fun snapshotReplicaProgress(): ReplicaProgressSnapshot {
        val session = preferences.session.first()
        return ReplicaProgressSnapshot(
            pullCursor = session.pullCursor,
            pullGeneration = session.pullGeneration,
            pendingPublish = pendingPublishDao.observeCount().first(),
        )
    }

    private suspend fun shouldAutoContinueForeground(
        error: Throwable,
        before: ReplicaProgressSnapshot,
    ): Boolean {
        val identity = cachedSession.foregroundFuseIdentity()
        if (isUnrecoverableForegroundStop(error)) {
            heartbeatRoundFuse.resetRoundCounter(identity)
            return false
        }
        val after = snapshotReplicaProgress()
        val progressMade = after.pullCursor != before.pullCursor ||
            after.pullGeneration != before.pullGeneration ||
            after.pendingPublish < before.pendingPublish
        if (!shouldContinueIncompleteForegroundCycle(error, progressMade)) {
            heartbeatRoundFuse.resetRoundCounter(identity)
            return false
        }
        if (progressMade) {
            heartbeatRoundFuse.onDurableProgress(identity)
            return true
        }
        return heartbeatRoundFuse.onZeroProgressRound(identity)
    }

    private fun updateFailureStatus(error: Throwable) {
        val kind = familyFailureKind(error)
            ?: error.takeIf { it is ReauthRequiredException }?.let { FailureKind.SessionExpired }
        if (kind != FailureKind.HouseholdSyncing) {
            currentFailureKind.value = kind
        }
        currentStatus.value = when (error) {
            is ForegroundSyncBlockedException ->
                if (currentStatus.value == SyncStatus.Syncing) {
                    SyncStatus.Idle
                } else {
                    currentStatus.value
                }
            is SyncNotEnabledException -> SyncStatus.Disabled
            is ReauthRequiredException -> SyncStatus.ReauthRequired
            is RemoteDeviceRemovedException -> SyncStatus.Disabled
            is RemoteMembershipDeletedException -> SyncStatus.Disabled
            is RemoteFamilyDeletedException -> SyncStatus.Disabled
            is FamilyHttpException -> if (
                error.kind == FamilyHttpFailureKind.HouseholdSyncing
            ) {
                currentStatus.value
            } else {
                SyncStatus.Error
            }
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
    val owner: com.lezi.babylog.sync.session.MemberReconnectOwner,
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
    SetupProbeResult.Failed.AddressNotFound,
    SetupProbeResult.Failed.Unreachable,
    -> FamilyServerUnavailableReason.Unreachable
    SetupProbeResult.Failed.ResponseTimedOut ->
        FamilyServerUnavailableReason.ResponseTimedOut
    // Availability banner only says "currently unreachable"; the failure dialog
    // keeps the dedicated UnexpectedError kind via familyFailureKind(probe).
    SetupProbeResult.Failed.Unexpected ->
        FamilyServerUnavailableReason.Unreachable
}

private suspend fun <T> captureAvailabilityProbe(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

private fun persistFailure(error: Throwable): LocalPersistException =
    error as? LocalPersistException ?: LocalPersistException(error)

private fun terminalReceiptFacts(
    rows: List<com.lezi.babylog.core.database.causal.CausalTransportJournalEntity>,
): List<UnacceptedFactPresentation> = rows.mapNotNull { row ->
    runCatching {
        decodeTerminalReceipt(row.payloadJson)
    }.getOrNull()
}.filterNot { it.abandoned || it.entityType == "fulfillment_candidate" }
    .map { receipt ->
        UnacceptedFactPresentation(
            entityType = receipt.entityType,
            clientUuid = receipt.clientUuid,
            recordedAt = receipt.recordedAt,
        )
    }

private const val AVAILABILITY_TIMEOUT_MILLIS = 8_000L

private data class ReplicaProgressSnapshot(
    val pullCursor: Long,
    val pullGeneration: String,
    val pendingPublish: Int,
)

/**
 * 0.5 ticket 04 (review C9): heartbeat no-change proof consumed by the
 * tip-skip decision at the sync entry. The three wire keys are the "server did
 * not move" negative proof from the latest beat; the freshness window itself
 * is anchored separately at the last completed full cycle
 * ([RealSyncPort.quietFreshnessAnchorMillis]).
 */
private data class QuietProof(
    val generation: String,
    val headRev: Long,
    val directoryGeneration: String,
)
private val REQUIRED_HEALTH_CAPABILITIES = setOf(
    CAPABILITY_NURSING_PLAN_INTENT_V1,
    CAPABILITY_ATOMIC_BUNDLE,
    CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
    CAPABILITY_DISASTER_RESTORE,
    CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT,
    // 0.4.0 causal generation (wire §1): one atomic capability or stop care sync.
    CAPABILITY_CAUSAL_SYNC_V2,
)

/** Reauth keeps the family replica/identity; only a true leave/unconfigure retires force UI. */
private fun SyncSession.retainsFamilyIdentityForReauth(): Boolean =
    reauthRequired && familyId.isNotBlank() && membershipId.isNotBlank() && baseUrl.isNotBlank()

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
        failure.cancellationCauseOrNull()?.let { throw it }
        reportFailure(failure)
    }
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
    hasUnacceptedReceipt: Boolean = false,
): String? {
    if (!familyJoined) return null
    if (hasUnacceptedReceipt) {
        return "仅本机 · 家里没收下"
    }
    if (!syncDirty || publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED) return null
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
    hasUnacceptedReceipt: Boolean = false,
): String? {
    if (!familyJoined) return null
    if (hasUnacceptedReceipt) {
        return "仅本机 · 家里没收下"
    }
    if (!syncDirty || publicationState == RootPublicationState.CURRENT_VERSION_PUBLISHED) return null
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
