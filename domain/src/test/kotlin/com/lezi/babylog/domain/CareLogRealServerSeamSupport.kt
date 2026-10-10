package com.lezi.babylog.domain

import kotlinx.coroutines.flow.map
import com.lezi.babylog.sync.session.toPresentation
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.core.database.MediaLocalPathGate
import com.lezi.babylog.core.database.PendingPublishDao
import com.lezi.babylog.core.database.PendingReplicaCleanup
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.ClientAppVersion
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.NoOpLocalClearRecoveryGate
import com.lezi.babylog.sync.NoOpRemovedDeviceLocalClearGate
import com.lezi.babylog.sync.RealSyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.StagedApkIdentity
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.retry.withForegroundRetryPolicy
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.engine.NoOpCarePlanFamilyAppliedListener
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.ImmutableMediaSpoolRecovery
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.DisasterRestoreRequestIds
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.MemberReconnectOwner
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.TerminalRemovalKind
import com.lezi.babylog.sync.session.hasSameCredentialGeneration
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.PendingMemberLogin
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Independent CareLog + RealSyncPort clients joined to one isolated real lezi-sync.
 *
 * Public seam only: CareLog → SyncPort/RealSyncPort → ReplicaSyncEngine →
 * RetryingSyncBackend → HttpSyncBackend → real server → peer Room/domain.
 *
 * H31 seeds owner+member. H32 may attach extra Owner devices on the same family for
 * concurrent multi-writer branches without inventing a parallel fixture.
 */
internal class CareLogRealServerSeamFixture private constructor(
    val server: IsolatedLeziSyncServer,
    val owner: SeamClient,
    val member: SeamClient,
    private val extraOwners: MutableList<SeamClient> = mutableListOf(),
    private val extraMembers: MutableList<SeamClient> = mutableListOf(),
) : AutoCloseable {
    /** Primary owner followed by any extra Owner devices joined later. */
    val ownerClients: List<SeamClient>
        get() = listOf(owner) + extraOwners

    /** Primary member followed by any extra ordinary members joined later. */
    val memberClients: List<SeamClient>
        get() = listOf(member) + extraMembers

    val allClients: List<SeamClient>
        get() = ownerClients + memberClients

    override fun close() {
        extraMembers.asReversed().forEach { it.close() }
        extraMembers.clear()
        extraOwners.asReversed().forEach { it.close() }
        extraOwners.clear()
        owner.close()
        member.close()
        server.close()
    }

    /**
     * Join another Owner device using the fixture bootstrap secret as root password.
     * Distinct device identities enable concurrent same-base branches under one ACL.
     */
    suspend fun joinExtraOwner(
        label: String,
        deviceId: String = "h32-$label-device",
        deviceName: String = "H32 $label Phone",
    ): SeamClient {
        val endpoint = TrustedEndpointProfile.tofuSpki(
            server.origin,
            server.spkiSha256Base64,
        )
        val client = SeamClient.create(
            label = label,
            endpoint = endpoint,
            deviceId = deviceId,
        )
        try {
            client.port.rememberEndpoint(endpoint).getOrThrow()
            client.port.saveEndpointConfig(
                FamilyEndpointConfig(
                    host = "127.0.0.1",
                    port = server.publicPort,
                    scheme = "https",
                ),
            ).getOrThrow()
            client.port.ownerLogin(
                deviceName = deviceName,
                rootPassword = server.bootstrapSecret,
                takeover = false,
            ).getOrThrow()
            client.awaitIdle()
            client.seedLocalFamilyAnchor()
            client.port.sync(SyncTrigger.Foreground).getOrThrow()
            client.awaitIdle()
            extraOwners += client
            return client
        } catch (error: Throwable) {
            client.close()
            throw IllegalStateException(
                "joinExtraOwner($label) failed; origin=${server.origin} err=$error",
                error,
            )
        }
    }

    /**
     * Join another ordinary Member device via request/approve.
     * Used for author/other ACL cases that must not share Owner privilege.
     */
    suspend fun joinExtraMember(
        label: String,
        displayName: String = "成员$label",
        deviceId: String = "h33-$label-device",
        deviceName: String = "H33 $label Phone",
    ): SeamClient {
        val endpoint = TrustedEndpointProfile.tofuSpki(
            server.origin,
            server.spkiSha256Base64,
        )
        val client = SeamClient.create(
            label = label,
            endpoint = endpoint,
            deviceId = deviceId,
        )
        try {
            client.port.rememberEndpoint(endpoint).getOrThrow()
            client.port.saveEndpointConfig(
                FamilyEndpointConfig(
                    host = "127.0.0.1",
                    port = server.publicPort,
                    scheme = "https",
                ),
            ).getOrThrow()
            client.port.requestMemberLogin(
                displayName = displayName,
                deviceName = deviceName,
            ).getOrThrow()

            val pending = withTimeout(10_000) {
                while (true) {
                    val listed = owner.port.listPendingMemberLogins().getOrThrow()
                    val hit = listed.firstOrNull {
                        it.displayName == displayName || it.deviceName == deviceName
                    }
                    if (hit != null) return@withTimeout hit
                    delay(50)
                }
                error("unreachable")
            }
            owner.port.approveNewMemberLogin(pending.requestId).getOrThrow()

            val joined = withTimeout(15_000) {
                while (true) {
                    when (val check = client.port.checkMemberLogin().getOrThrow()) {
                        is MemberLoginCheckResult.Joined -> return@withTimeout check
                        is MemberLoginCheckResult.Waiting -> delay(50)
                        is MemberLoginCheckResult.Terminal ->
                            error("member login terminal: ${check.status}")
                    }
                }
                error("unreachable")
            }
            check(joined.session.isJoined)
            client.awaitIdle()
            client.seedLocalFamilyAnchor()
            client.port.sync(SyncTrigger.Foreground).getOrThrow()
            client.awaitIdle()
            extraMembers += client
            return client
        } catch (error: Throwable) {
            client.close()
            throw IllegalStateException(
                "joinExtraMember($label) failed; origin=${server.origin} err=$error",
                error,
            )
        }
    }

    suspend fun pullAll(clients: List<SeamClient> = allClients) {
        clients.forEach { it.pullForeground() }
    }


    companion object {
        suspend fun open(setupProbe: SetupProbe? = null): CareLogRealServerSeamFixture {
            assumeToolsPresent()
            val server = IsolatedLeziSyncServer.start()
            return try {
                val endpoint = TrustedEndpointProfile.tofuSpki(
                    server.origin,
                    server.spkiSha256Base64,
                )
                val owner = SeamClient.create(
                    label = "owner",
                    endpoint = endpoint,
                    deviceId = "h31-owner-device",
                    setupProbeOverride = setupProbe,
                )
                val member = SeamClient.create(
                    label = "member",
                    endpoint = endpoint,
                    deviceId = "h31-member-device",
                    setupProbeOverride = setupProbe,
                )
                joinTwoClients(
                    server = server,
                    owner = owner,
                    member = member,
                )
                CareLogRealServerSeamFixture(server, owner, member)
            } catch (error: Throwable) {
                server.close()
                throw error
            }
        }

        private fun assumeToolsPresent() {
            for (tool in listOf("openssl", "curl", "sqlite3")) {
                check(ProcessBuilder(tool, if (tool == "openssl") "version" else "--version").start().waitFor() == 0) {
                    "$tool required for isolated integration proof"
                }
            }
        }

        private suspend fun joinTwoClients(
            server: IsolatedLeziSyncServer,
            owner: SeamClient,
            member: SeamClient,
        ) {
            try {
                owner.port.rememberEndpoint(
                    TrustedEndpointProfile.tofuSpki(server.origin, server.spkiSha256Base64),
                ).getOrThrow()
                member.port.rememberEndpoint(
                    TrustedEndpointProfile.tofuSpki(server.origin, server.spkiSha256Base64),
                ).getOrThrow()

                owner.port.saveEndpointConfig(
                    FamilyEndpointConfig(
                        host = "127.0.0.1",
                        port = server.publicPort,
                        scheme = "https",
                    ),
                ).getOrThrow()
                member.port.saveEndpointConfig(
                    FamilyEndpointConfig(
                        host = "127.0.0.1",
                        port = server.publicPort,
                        scheme = "https",
                    ),
                ).getOrThrow()

                check(owner.currentSession().baseUrl == server.origin) {
                    "owner baseUrl=${owner.currentSession().baseUrl} origin=${server.origin}"
                }

                owner.port.createFamily(
                    displayName = "妈妈",
                    deviceName = "H31 Owner Phone",
                    bootstrapSecret = server.bootstrapSecret,
                    familyName = "H31验收家庭",
                ).getOrThrow()
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "join failed before member flow; ownerBase=${owner.currentSession().baseUrl} " +
                        "origin=${server.origin} endpoint=${owner.preferences.currentEndpoint()} " +
                        "err=$error",
                    error,
                )
            }
            owner.awaitIdle()

            try {
                member.port.requestMemberLogin(
                    displayName = "爸爸",
                    deviceName = "H31 Member Phone",
                ).getOrThrow()

                val pending = withTimeout(10_000) {
                    while (true) {
                        val listed = owner.port.listPendingMemberLogins().getOrThrow()
                        val hit = listed.firstOrNull {
                            it.displayName == "爸爸" || it.deviceName == "H31 Member Phone"
                        }
                        if (hit != null) return@withTimeout hit
                        delay(50)
                    }
                    error("unreachable")
                }
                owner.port.approveNewMemberLogin(pending.requestId).getOrThrow()

                val joined = withTimeout(15_000) {
                    while (true) {
                        when (val check = member.port.checkMemberLogin().getOrThrow()) {
                            is MemberLoginCheckResult.Joined -> return@withTimeout check
                            is MemberLoginCheckResult.Waiting -> delay(50)
                            is MemberLoginCheckResult.Terminal ->
                                error("member login terminal: ${check.status}")
                        }
                    }
                    error("unreachable")
                }
                check(joined.session.isJoined)
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "join failed during member flow; ownerJoined=${owner.currentSession().isJoined} " +
                        "memberBase=${member.currentSession().baseUrl} err=$error",
                    error,
                )
            }
            member.awaitIdle()

            owner.seedLocalFamilyAnchor()
            member.seedLocalFamilyAnchor()

            // Drain any automatic post-join foreground sync to a stable idle cursor.
            owner.port.sync(SyncTrigger.Foreground).getOrThrow()
            member.port.sync(SyncTrigger.Foreground).getOrThrow()
            owner.awaitIdle()
            member.awaitIdle()
        }
    }
}

internal class SeamClient private constructor(
    val label: String,
    val preferences: InMemorySyncPreferences,
    val fakes: Fakes,
    val port: RealSyncPort,
    val careLog: CareLog,
    val foreground: MutableForegroundState,
    val clock: MutablePolicyClock,
    private val appUpdateCacheDir: File,
) {
    fun currentSession(): SyncSession = preferences.current()

    suspend fun awaitIdle(timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) {
            var idleStreak = 0
            while (true) {
                val status = port.status().first()
                when (status) {
                    SyncStatus.Idle, SyncStatus.Disabled -> {
                        // Require a short quiet window so the process actor cannot leave
                        // a just-finished LocalWrite and immediately start another pass
                        // before the fixture asserts Idle.
                        idleStreak += 1
                        if (idleStreak >= 3 &&
                            fakes.babies.listPendingSync().isEmpty() &&
                            fakes.records.listPendingSync().isEmpty() &&
                            fakes.carePlans.listPendingSync().isEmpty()
                        ) {
                            return@withTimeout
                        }
                    }
                    SyncStatus.Error -> {
                        idleStreak = 0
                        val drained = port.sync(SyncTrigger.Foreground)
                        if (drained.isFailure) {
                            error("$label sync error: ${drained.exceptionOrNull()}")
                        }
                    }
                    else -> idleStreak = 0
                }
                delay(25)
            }
        }
    }

    suspend fun settleLocalWrite() {
        // notifyLocalChanges is async via the process actor; drive the same LocalWrite plan
        // explicitly so the acceptance fixture is deterministic under JVM unit tests.
        val result = port.sync(SyncTrigger.LocalWrite)
        check(result.isSuccess) { "$label LocalWrite failed: ${result.exceptionOrNull()}" }
        awaitIdle()
    }

    suspend fun pullForeground() {
        val result = port.sync(SyncTrigger.Foreground)
        check(result.isSuccess) { "$label Foreground pull failed: ${result.exceptionOrNull()}" }
        awaitIdle()
    }

    suspend fun seedLocalFamilyAnchor() {
        // ReplicaSyncEngine.applyBaby needs a local family row for familyId assignment.
        // Owner CareLog.createBaby also creates one; members only receive babies via pull.
        val now = clock.nowMillis()
        val userId = fakes.users.get()?.id ?: fakes.users.upsert(
            com.lezi.babylog.core.database.LocalUserEntity(
                displayName = label,
                deviceId = preferences.current().deviceId.ifBlank { label },
                createdAt = now,
            ),
        )
        if (fakes.families.listAll().isEmpty()) {
            fakes.families.insert(
                com.lezi.babylog.core.database.FamilyEntity(
                    ownerUserId = userId,
                    createdAt = now,
                ),
            )
        }
    }

    fun close() {
        appUpdateCacheDir.deleteRecursively()
    }

    companion object {
        fun create(
            label: String,
            endpoint: TrustedEndpointProfile,
            deviceId: String,
            setupProbeOverride: SetupProbe? = null,
        ): SeamClient {
            val preferences = InMemorySyncPreferences(
                initial = SyncSession(deviceId = deviceId),
            )
            // Trust is established out-of-band for the fixture (openssl-minted SPKI pin).
            // SetupProbe is not exercised for TOFU minting here; rememberEndpoint pins the profile.
            runBlockingRemember(preferences, endpoint)

            val clientVersion = ClientAppVersion(
                versionCode = 35,
                versionName = "0.5.5",
                packageName = "com.lezi.babylog",
                localDataContractVersion = 7,
            )
            // Production parity: RealSyncPort always sits behind RetryingSyncBackend
            // (see SyncModule). H34 acceptance requires the same request/retry owner.
            val backend = HttpSyncBackend(
                preferences = preferences,
                clientAppVersion = clientVersion,
            ).withForegroundRetryPolicy()
            val setupProbe = setupProbeOverride ?: SetupProbe { draft, trusted ->
                val resolved = trusted
                    ?: runCatching { TrustedEndpointProfile.systemPki(draft) }.getOrNull()
                    ?: return@SetupProbe SetupProbeResult.Failed.InvalidAddress
                if (resolved.origin == endpoint.origin) {
                    SetupProbeResult.Ready(
                        endpoint = endpoint,
                        familyState = if (preferences.current().familyId.isBlank()) {
                            SetupFamilyState.Empty
                        } else {
                            SetupFamilyState.Configured
                        },
                    )
                } else {
                    SetupProbeResult.Failed.Unreachable
                }
            }

            val foreground = MutableForegroundState(foreground = true)
            val clock = MutablePolicyClock(now = System.currentTimeMillis())
            val mediaFiles = EmptySyncMediaFileStore()
            val mediaSpool = EmptyImmutableMediaSpool()
            val pathGate = MediaLocalPathGate()
            val pendingPublish = object : PendingPublishDao {
                override fun observeCount(): Flow<Int> = MutableStateFlow(0)
            }
            val pendingReplicaCleanup = object : PendingReplicaCleanupStore {
                // This seam uses in-memory DAOs, but must honor the durable store's
                // stage/read/delete contract across the production clear workflow.
                private val staged = java.util.concurrent.atomic.AtomicReference<PendingReplicaCleanup?>(null)
                override suspend fun load(): PendingReplicaCleanup? = staged.get()
                override suspend fun stage(pending: PendingReplicaCleanup) {
                    val captured = pending.copy(mediaClientUuids = pending.mediaClientUuids.toSet(),
                        localMediaPaths = pending.localMediaPaths.toSet())
                    check(staged.compareAndSet(null, captured)) { "pending replica cleanup already staged" }
                }
                override suspend fun delete() { staged.set(null) }
            }
            val appUpdateCacheDir = FilesTempDir("lezi-h31-app-update-$label")

            // Placeholder port field replaced after RealSyncPort construction via CareLog wiring.
            lateinit var port: RealSyncPort
            val liveFakes = Fakes(
                syncPort = object : com.lezi.babylog.sync.SyncPort by com.lezi.babylog.sync.NoOpSyncPort() {
                    override fun sessionPresentation() = session().map { it.toPresentation() }
                    override fun session() = preferences.session
                    override fun status() = port.status()
                    override fun notifyLocalChanges() = port.notifyLocalChanges()
                    override fun requestSync(trigger: SyncTrigger) = port.requestSync(trigger)
                    override suspend fun sync(trigger: SyncTrigger) = port.sync(trigger)
                    override suspend fun clearLocalData(
                        scope: LocalDataClearScope,
                        workflow: com.lezi.babylog.sync.LocalClearWorkflow,
                    ) = port.clearLocalData(scope, workflow)
                },
            )
            val liveMediaCleanup = ReferenceAwareMediaFileCleanup(
                mediaDao = liveFakes.media,
                mediaReferenceDao = liveFakes.mediaReferences,
                mediaFiles = mediaFiles,
                transactionRunner = liveFakes.transactions,
                pathGate = pathGate,
            )
            val liveFulfillment = FulfillmentAuthoritySettlement(
                carePlanDao = liveFakes.carePlans,
                fulfillmentCandidateDao = liveFakes.fulfillmentCandidates,
                transactionRunner = liveFakes.transactions,
            )
            port = RealSyncPort(
                backend = backend,
                preferences = preferences,
                setupProbe = setupProbe,
                foregroundSyncGate = ForegroundSyncGate(),
                pendingPublishDao = pendingPublish,
                recordDao = liveFakes.records,
                carePlanDao = liveFakes.carePlans,
                babyDao = liveFakes.babies,
                mediaDao = liveFakes.media,
                customItemDao = liveFakes.customItems,
                familyDao = liveFakes.families,
                clock = clock,
                foregroundState = foreground,
                mediaFiles = mediaFiles,
                immutableMediaSpool = mediaSpool,
                mediaFileCleanup = liveMediaCleanup,
                transactionRunner = liveFakes.transactions,
                pendingReplicaCleanupStore = pendingReplicaCleanup,
                localClearRecoveryGate = CarePlanFamilyProjectionModule.localClearRecoveryGate(
                    dagger.Lazy { liveFakes.localDataClearCoordinator() },
                ),
                removedDeviceLocalClearGate = CarePlanFamilyProjectionModule.removedDeviceLocalClearGate(
                    dagger.Lazy { liveFakes.localDataClearCoordinator() },
                ),
                carePlanAppliedListener = NoOpCarePlanFamilyAppliedListener(),
                fulfillmentCandidateDao = liveFakes.fulfillmentCandidates,
                fulfillmentAuthoritySettlement = liveFulfillment,
                wakeObservationDao = liveFakes.wakeObservations,
                conflictSummaryDao = liveFakes.conflictSummaries,
                conflictSnapshotCacheDao = liveFakes.conflictSnapshotCache,
                sourceRelationDao = liveFakes.sourceRelations,
                clientAppVersion = clientVersion,
                appUpdateInstaller = NoOpSeamAppUpdateInstaller,
                apkIdentityReader = NoOpSeamApkIdentityReader,
                appUpdateCacheDir = appUpdateCacheDir,
            )

            val careLog = CareLog(
                babyDao = liveFakes.babies,
                recordDao = liveFakes.records,
                carePlanDao = liveFakes.carePlans,
                customItemDao = liveFakes.customItems,
                localUserDao = liveFakes.users,
                familyDao = liveFakes.families,
                membershipDao = liveFakes.memberships,
                mediaAssetDao = liveFakes.media,
                settings = liveFakes.settings,
                syncPort = port,
                reminderCleanup = liveFakes.reminders,
                transactionRunner = liveFakes.transactions,
                systemCalendar = liveFakes.systemCalendar,
                fulfillmentCandidateDao = liveFakes.fulfillmentCandidates,
                fulfillmentAuthoritySettlement = liveFulfillment,
                calendarReminderMutationGuard = liveFakes.calendarReminderMutationGuard,
                clock = clock,
                mediaPathGate = pathGate,
                localDataMutationEpoch = liveFakes.localDataMutationEpoch,
                wakeObservationDao = liveFakes.wakeObservations,
                conflictSummaryDao = liveFakes.conflictSummaries,
                conflictSnapshotCacheDao = liveFakes.conflictSnapshotCache,
                sourceRelationDao = liveFakes.sourceRelations,
                recordWakeProjectionDao = liveFakes.timelineWindow,
            )

            return SeamClient(
                label = label,
                preferences = preferences,
                fakes = liveFakes,
                port = port,
                careLog = careLog,
                foreground = foreground,
                clock = clock,
                appUpdateCacheDir = appUpdateCacheDir,
            )
        }

        private fun runBlockingRemember(
            preferences: InMemorySyncPreferences,
            endpoint: TrustedEndpointProfile,
        ) {
            // rememberEndpoint is suspend; call via runBlocking-less spin using
            // the already-synchronous MutableStateFlow implementation.
            preferences.rememberEndpointBlocking(endpoint)
        }
    }
}

internal fun formulaPayloadJson(
    amountMl: Int,
    preparedMl: Int? = null,
    durationMinutes: Int? = null,
): String =
    RecordPayloadCodec.encode(
        RecordPayloadDocument(
            type = RecordType.FORMULA,
            payload = MilkPayload(
                type = RecordType.FORMULA,
                amountMl = amountMl,
                preparedMl = preparedMl,
                durationMinutes = durationMinutes,
            ),
            schemaVersion = com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        ),
    )


internal class MutableForegroundState(
    foreground: Boolean = true,
) : ForegroundState by (com.lezi.babylog.sync.session.ProcessForegroundState().apply {
    setForeground(foreground)
})

internal class MutablePolicyClock(var now: Long) : PolicyClock {
    override fun nowMillis(): Long = now
}

internal class InMemorySyncPreferences(
    initial: SyncSession,
) : SyncPreferences {
    private val state = MutableStateFlow(initial)
    private val endpointState = MutableStateFlow<TrustedEndpointProfile?>(null)
    private val memberDirectoryState = MutableStateFlow<List<FamilyMember>>(emptyList())
    override val familyMemberDirectoryGeneration = MutableStateFlow("")
    private val lastHealthyState = MutableStateFlow<Long?>(null)
    private val pendingMemberState = MutableStateFlow<PendingMemberLogin?>(null)
    private val disasterRestoreState = MutableStateFlow<DisasterRestoreCheckpoint?>(null)
    private var memberPendingSecret = ""
    private var reconnectMemberAttempt: PendingMemberLogin? = null
    private var activeMemberQrClaim: String? = null
    private var disasterRestoreRecoveryToken = ""
    private var disasterRestoreRequestIds: DisasterRestoreRequestIds? = null
    private var createRequestId: String? = null
    private var ownerLoginRequestId: String? = null
    private var refreshRequestId: String? = null
    private var pendingDeviceRemovalClear = false
    private var pendingMembershipDeletionClear = false
    private var pendingFamilyDeletionClear = false
    override val pendingGenerationResync = MutableStateFlow(false)
    private var pendingReplicaResetPrevious: SyncSession? = null
    private var pendingReplicaResetSession: SyncSession? = null

    override val session: Flow<SyncSession> = state
    override val verifiedEndpoint: Flow<TrustedEndpointProfile?> = endpointState
    override val familyMemberDirectory: Flow<List<FamilyMember>> = memberDirectoryState
    override val lastServerHealthyAt: Flow<Long?> = lastHealthyState
    override val pendingMemberLogin: Flow<PendingMemberLogin?> = pendingMemberState
    override val disasterRestoreCheckpoint: Flow<DisasterRestoreCheckpoint?> = disasterRestoreState

    fun current(): SyncSession = state.value

    fun currentEndpoint(): TrustedEndpointProfile? = endpointState.value

    fun rememberEndpointBlocking(endpoint: TrustedEndpointProfile) {
        endpointState.value = endpoint
    }

    override suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile) {
        endpointState.value = endpoint
    }

    override suspend fun forgetEndpoint(retainMemberAttempts: Boolean) {
        activeMemberQrClaim = null
        if (!retainMemberAttempts) {
            pendingMemberState.value = null
            memberPendingSecret = ""
            reconnectMemberAttempt = null
        }
        val previous = endpointState.value
        endpointState.value = null
        if (previous != null && previous.origin == state.value.baseUrl) {
            clearDeviceCredentialsForReauth()
        }
    }

    override suspend fun saveFamilyMemberDirectory(members: List<FamilyMember>) {
        familyMemberDirectoryGeneration.value = ""
        memberDirectoryState.value = members.map { it.copy(devices = null) }
    }

    override suspend fun saveFamilyMemberDirectorySnapshot(
        generation: String,
        members: List<FamilyMember>,
    ) {
        familyMemberDirectoryGeneration.value = generation
        memberDirectoryState.value = members.map { it.copy(devices = null) }
    }

    override suspend fun clearFamilyMemberDirectory() {
        familyMemberDirectoryGeneration.value = ""
        memberDirectoryState.value = emptyList()
    }

    override suspend fun saveLastServerHealthyAt(atMillis: Long) {
        lastHealthyState.value = atMillis
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
            forgetFamilyScopedPendingGenerationResync()
        }
        state.value = next
    }

    override suspend fun stageTerminalRemovalIfCurrent(
        expected: SyncSession,
        kind: TerminalRemovalKind,
        receipt: com.lezi.babylog.sync.DeviceRemovedCleanupReceipt?,
    ): Boolean {
        val current = state.value
        if (!current.hasSameCredentialGeneration(expected)) return false
        if (!state.compareAndSet(current, current.copy(reauthRequired = true))) return false
        when (kind) {
            TerminalRemovalKind.Device -> markPendingDeviceRemovalClear()
            TerminalRemovalKind.Membership -> markPendingMembershipDeletionClear()
            TerminalRemovalKind.Family -> markPendingFamilyDeletionClear()
        }
        if (receipt != null) saveDeviceRemovedReceipt(receipt)
        return true
    }

    override suspend fun saveRefreshedSessionIfCurrent(expected: SyncSession, refreshed: SyncSession): Boolean {
        val current = state.value
        if (!current.hasSameCredentialGeneration(expected)) return false
        val saved = state.compareAndSet(current, current.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            accessExpiresAtEpochSeconds = refreshed.accessExpiresAtEpochSeconds,
            reauthRequired = false,
        ))
        if (saved) refreshRequestId = null
        return saved
    }

    override suspend fun clearDeviceCredentialsForReauthIfCurrent(expected: SyncSession): Boolean {
        val current = state.value
        if (!current.hasSameCredentialGeneration(expected)) return false
        return state.compareAndSet(current, current.copy(
            accessToken = "", refreshToken = "", accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        ))
    }

    override suspend fun saveSession(session: SyncSession) {
        createRequestId = null
        ownerLoginRequestId = null
        refreshRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        reconnectMemberAttempt = null
        activeMemberQrClaim = null
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        val previous = state.value
        if (previous.familyId != session.familyId) {
            memberDirectoryState.value = emptyList()
            forgetFamilyScopedPendingGenerationResync()
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
        createRequestId = null
        ownerLoginRequestId = null
        refreshRequestId = null
        pendingMemberState.value = null
        memberPendingSecret = ""
        reconnectMemberAttempt = null
        activeMemberQrClaim = null
        pendingReplicaResetPrevious = previous
        pendingReplicaResetSession = session
        state.value = session.copy(
            accessToken = "",
            refreshToken = "",
            accessExpiresAtEpochSeconds = 0,
            reauthRequired = true,
        )
    }

    override suspend fun pendingReplicaResetPrevious(): SyncSession? = pendingReplicaResetPrevious

    override suspend fun completePendingReplicaReset() {
        val session = pendingReplicaResetSession ?: return
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        state.value = session
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        state.value = state.value.copy(pullCursor = cursor, pullGeneration = generation)
    }

    override suspend fun updatePullCheckpoint(
        cursor: Long,
        generation: String,
        familyName: String?,
    ) {
        state.value = state.value.copy(
            pullCursor = cursor,
            pullGeneration = generation,
            familyName = familyName?.trim()?.takeIf { it.isNotEmpty() },
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
            state.value = state.value.copy(deviceId = "h31-device-${UUID.randomUUID()}")
        }
        return state.value.deviceId
    }

    override suspend fun ensureCreateRequestId(): String =
        createRequestId ?: UUID.randomUUID().toString().also { createRequestId = it }

    override suspend fun ensureOwnerLoginRequestId(): String =
        ownerLoginRequestId ?: UUID.randomUUID().toString().also { ownerLoginRequestId = it }

    override suspend fun clearOwnerLoginRequestId() {
        ownerLoginRequestId = null
    }

    override suspend fun ensureRefreshRequestId(): String =
        refreshRequestId ?: UUID.randomUUID().toString().also { refreshRequestId = it }

    override suspend fun beginMemberQrClaim(operationId: String, owner: MemberReconnectOwner): Boolean {
        if (state.value.isJoined || memberReconnectOwner() != owner) return false
        activeMemberQrClaim = operationId
        return true
    }

    override suspend fun endMemberQrClaim(operationId: String) {
        if (activeMemberQrClaim == operationId) activeMemberQrClaim = null
    }

    override suspend fun activateMemberQrClaimIfCurrent(
        operationId: String,
        owner: MemberReconnectOwner,
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        pendingReplicaResetPrevious: SyncSession?,
    ): Boolean {
        if (activeMemberQrClaim != operationId || memberReconnectOwner() != owner) return false
        require(owner.familyId.isBlank() || owner.familyId == session.familyId)
        if (pendingReplicaResetPrevious == null) saveSession(session)
        else saveSessionPendingReplicaReset(session, pendingReplicaResetPrevious)
        endpointState.value = endpoint
        return true
    }

    override suspend fun beginReconnectMemberAttempt(
        attempt: PendingMemberLogin,
        owner: MemberReconnectOwner,
    ): Boolean {
        if (memberReconnectOwner() != owner) return false
        saveMemberLoginAttempt(attempt, reconnect = true)
        return true
    }

    override suspend fun isReconnectMemberAttemptCurrent(
        operationId: String,
        owner: MemberReconnectOwner,
    ): Boolean = memberReconnectOwner() == owner && reconnectMemberAttempt?.operationId == operationId

    override suspend fun activateReconnectMemberIfCurrent(
        operationId: String,
        owner: MemberReconnectOwner,
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
    ): Boolean {
        if (!isReconnectMemberAttemptCurrent(operationId, owner)) return false
        require(session.familyId == owner.familyId)
        saveReconnectedSession(session, endpoint)
        return true
    }

    override suspend fun saveMemberLoginAttempt(attempt: PendingMemberLogin, reconnect: Boolean) {
        require(attempt.remoteOutcomeUnknown && attempt.operationId.isNotBlank() && attempt.requestId.isBlank())
        val previous = memberLoginAttempt(reconnect)
        require(previous == null || previous.operationId == attempt.operationId) {
            "已有结果待确认的加入申请，请先处理原申请"
        }
        if (reconnect) reconnectMemberAttempt = attempt else pendingMemberState.value = attempt
    }

    override suspend fun memberLoginAttempt(reconnect: Boolean): PendingMemberLogin? =
        if (reconnect) reconnectMemberAttempt else pendingMemberState.value?.takeIf { it.remoteOutcomeUnknown }

    override suspend fun clearMemberLoginAttempt(reconnect: Boolean, expectedOperationId: String?) {
        if (expectedOperationId != null && memberLoginAttempt(reconnect)?.operationId != expectedOperationId) return
        if (reconnect) reconnectMemberAttempt = null
        else if (pendingMemberState.value?.remoteOutcomeUnknown == true) pendingMemberState.value = null
    }

    override suspend fun savePendingMemberLogin(
        receipt: MemberLoginReceipt,
        displayName: String,
        deviceName: String,
        expectedOperationId: String?,
    ) {
        require(receipt.requestId.isNotBlank() && receipt.pendingSecret.isNotBlank())
        val originalAttempt = memberLoginAttempt()
        check(expectedOperationId == null || originalAttempt?.operationId == expectedOperationId) {
            "原加入申请已放弃，迟到的回执未写入凭据"
        }
        memberPendingSecret = receipt.pendingSecret
        pendingMemberState.value = PendingMemberLogin(
            requestId = receipt.requestId,
            displayName = displayName,
            deviceName = deviceName,
            expiresAtEpochSeconds = receipt.expiresAtEpochSeconds,
            operationId = originalAttempt?.operationId ?: receipt.requestId,
            endpointOrigin = originalAttempt?.endpointOrigin.orEmpty(),
        )
    }

    override suspend fun isPendingMemberLoginCurrent(
        requestId: String,
        owner: MemberReconnectOwner,
    ): Boolean = requestId.isNotBlank() && pendingMemberState.value?.requestId == requestId &&
        pendingMemberState.value?.remoteOutcomeUnknown == false && memberReconnectOwner() == owner

    override suspend fun pendingMemberSecretIfCurrent(
        requestId: String,
        owner: MemberReconnectOwner,
    ): String? = if (isPendingMemberLoginCurrent(requestId, owner)) memberPendingSecret else null

    override suspend fun activatePendingMemberIfCurrent(
        requestId: String,
        owner: MemberReconnectOwner,
        session: SyncSession,
        pendingReplicaResetPrevious: SyncSession?,
    ): Boolean {
        if (!isPendingMemberLoginCurrent(requestId, owner)) return false
        require(owner.familyId.isBlank() || owner.familyId == session.familyId)
        if (pendingReplicaResetPrevious == null) saveSession(session)
        else saveSessionPendingReplicaReset(session, pendingReplicaResetPrevious)
        return true
    }

    override suspend fun pendingMemberSecret(): String = memberPendingSecret

    override suspend fun clearPendingMemberLogin(expectedOperationId: String?) {
        if (expectedOperationId != null && pendingMemberState.value?.operationId != expectedOperationId) return
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

    override suspend fun pendingDisasterRestoreRequestIds(): DisasterRestoreRequestIds? = disasterRestoreRequestIds

    override suspend fun ensureDisasterRestoreRequestIds(): DisasterRestoreRequestIds =
        disasterRestoreRequestIds ?: DisasterRestoreRequestIds(
            start = UUID.randomUUID().toString(),
            manifest = UUID.randomUUID().toString(),
            commit = UUID.randomUUID().toString(),
        ).also { disasterRestoreRequestIds = it }

    override suspend fun disasterRestoreToken(): String = disasterRestoreRecoveryToken

    override suspend fun clearDisasterRestoreCheckpoint() {
        disasterRestoreState.value = null
        disasterRestoreRecoveryToken = ""
        disasterRestoreRequestIds = null
        refreshRequestId = null
    }

    override suspend fun clearCreateRequestId() {
        createRequestId = null
    }

    override suspend fun clearAllLocalSyncConfig() {
        state.value = SyncSession()
        memberDirectoryState.value = emptyList()
        pendingMemberState.value = null
        memberPendingSecret = ""
        reconnectMemberAttempt = null
        activeMemberQrClaim = null
        disasterRestoreState.value = null
        disasterRestoreRecoveryToken = ""
        disasterRestoreRequestIds = null
        pendingReplicaResetPrevious = null
        pendingReplicaResetSession = null
        forgetFamilyScopedPendingGenerationResync()
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
    }

    override suspend fun markPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = true
    }

    override suspend fun hasPendingFamilyDeletionClear(): Boolean = pendingFamilyDeletionClear

    override suspend fun clearPendingFamilyDeletionClear() {
        pendingFamilyDeletionClear = false
    }

    override suspend fun markPendingGenerationResync() {
        pendingGenerationResync.value = true
    }

    override suspend fun hasPendingGenerationResync(): Boolean = pendingGenerationResync.value

    override suspend fun clearPendingGenerationResync() {
        forgetFamilyScopedPendingGenerationResync()
    }

    private fun forgetFamilyScopedPendingGenerationResync() {
        pendingGenerationResync.value = false
    }
}

private object NoOpSeamAppUpdateInstaller : AppUpdateInstaller {
    override fun canRequestPackageInstalls(): Boolean = false
    override fun installFromFile(apkFile: File, expectedPackageName: String) = Unit
    override fun createManageUnknownSourcesIntent() =
        android.content.Intent()
}

private object NoOpSeamApkIdentityReader : AppUpdateApkIdentityReader {
    override fun readArchive(apkFile: File): StagedApkIdentity? = null
    override fun installedSigningCertSha256(): Set<String> = emptySet()
}

private class EmptySyncMediaFileStore : SyncMediaFileStore {
    override suspend fun inspect(localUri: String): LocalMediaInfo? = null
    override suspend fun prepareUpload(localUri: String): PreparedMedia =
        error("H31 seam fixture does not upload media")
    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = "memory://$kind/$clientUuid"
    override suspend fun delete(localUri: String) = Unit
    override suspend fun sweepUnreferenced(
        scope: LocalDataClearScope,
        retainedLocalUris: Set<String>,
    ) = Unit
}

private class EmptyImmutableMediaSpool : ImmutableMediaSpool {
    override suspend fun freezeGroup(
        mutationId: String,
        sources: List<ImmutableMediaSpoolSource>,
    ): ImmutableMediaSpoolGroup {
        require(sources.isEmpty()) { "H31 fixture is no-media only" }
        return ImmutableMediaSpoolGroup(mutationId, emptyList())
    }

    override suspend fun recoverGroup(mutationId: String): ImmutableMediaSpoolRecovery? = null

    override suspend fun open(
        mutationId: String,
        item: ImmutableMediaSpoolItem,
    ): SyncMediaUploadSource = object : SyncMediaUploadSource {
        override val contentLength: Long = 0
        override val mime: String? = null
        override fun openStream(): InputStream = InputStream.nullInputStream()
    }

    override suspend fun discardGroup(mutationId: String) = Unit

    override suspend fun recoverAndSweep(
        retainedMutationIds: Set<String>,
    ): Map<String, ImmutableMediaSpoolRecovery> = emptyMap()
}

private fun FilesTempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }
