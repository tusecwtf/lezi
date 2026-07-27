package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.PendingReplicaCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.SyncStatus
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val policy: HomeNetworkPolicy,
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
    private val transactionRunner: DatabaseTransactionRunner,
    private val pendingReplicaCleanupStore: PendingReplicaCleanupStore,
    private val localClearRecoveryGate: LocalClearRecoveryGate =
        NoOpLocalClearRecoveryGate(),
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener =
        NoOpCarePlanFamilyAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
) : SyncPort {
    private val currentStatus = MutableStateFlow(SyncStatus.Disabled)
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
        transactionRunner = transactionRunner,
        carePlanAppliedListener = carePlanAppliedListener,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        requireRemoteAllowed = { session ->
            val decision = policy.evaluate(
                session.homeLanConfig,
                foregroundState.isForeground(),
            )
            requireAllowed(decision)
            currentStatus.value = SyncStatus.Syncing
        },
        remoteCapabilities = {
            policy.lastHealthStatus.capabilities.toSet()
        },
    )
    private val localReplicaClearCoordinator = LocalReplicaClearCoordinator(
        barrier = syncMutex,
        preferences = preferences,
        outboxDao = outboxDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
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
            val decision = policy.evaluate(config, foregroundState.isForeground())
            requireAllowed(decision)
        },
        onSessionChanged = ::publishSession,
        onSessionObserved = { session -> cachedSession = session },
        requestSync = ::requestSync,
        beforeOperation = ::recoverPendingLocalClearLocked,
    )
    private val syncSignal = Channel<Unit>(Channel.CONFLATED)
    private val pullRequested = AtomicBoolean(false)
    @Volatile private var cachedSession = SyncSession()

    init {
        processScope.launch {
            preferences.migrateSecretsIfNeeded()
        }
        processScope.launch {
            preferences.session.collect { session ->
                cachedSession = session
                if (!session.isJoined) {
                    currentStatus.value = SyncStatus.Disabled
                } else if (currentStatus.value == SyncStatus.Disabled) {
                    currentStatus.value = SyncStatus.Idle
                }
            }
        }
        processScope.launch {
            for (ignored in syncSignal) {
                val trigger = if (pullRequested.getAndSet(false)) {
                    SyncTrigger.Foreground
                } else {
                    SyncTrigger.LocalWrite
                }
                sync(trigger)
            }
        }
    }

    override fun status(): Flow<SyncStatus> = currentStatus
    override fun session(): Flow<SyncSession> = preferences.session
    override fun isEnabled(): Boolean = cachedSession.isJoined
    override fun requestSync(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.LocalWrite) {
            pullRequested.set(true)
        }
        syncSignal.trySend(Unit)
    }

    override suspend fun saveServer(baseUrl: String): Result<Unit> =
        executeFamily(FamilySessionCommand.SaveServer(baseUrl)).map { Unit }

    override suspend fun saveHomeLanConfig(
        config: HomeLanServerConfig,
    ): Result<Unit> =
        executeFamily(FamilySessionCommand.SaveHomeLanConfig(config)).map { Unit }

    override suspend fun createFamily(
        displayName: String?,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<SyncSession> =
        executeFamily(
            FamilySessionCommand.CreateFamily(
                displayName = displayName,
                bootstrapSecret = bootstrapSecret,
                familyName = familyName,
            ),
        ).map { (it as FamilySessionOutcome.Joined).session }

    override suspend fun renameFamily(familyName: String?): Result<Unit> =
        executeFamily(FamilySessionCommand.RenameFamily(familyName)).map { Unit }

    override suspend fun joinFamily(
        command: JoinFamilyCommand,
    ): Result<SyncSession> =
        executeFamily(FamilySessionCommand.JoinFamily(command))
            .map { (it as FamilySessionOutcome.Joined).session }

    override suspend fun createInvite(familyId: String): Result<Invite> =
        executeFamily(FamilySessionCommand.CreateInvite)
            .map { (it as FamilySessionOutcome.InviteCreated).invite }

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> =
        executeFamily(FamilySessionCommand.ListMembers)
            .map { (it as FamilySessionOutcome.MembersListed).members }

    override suspend fun updateMyDisplayName(displayName: String): Result<Unit> =
        executeFamily(FamilySessionCommand.UpdateMyDisplayName(displayName)).map { Unit }

    override suspend fun sync(trigger: SyncTrigger): Result<Unit> = runCatching {
        syncMutex.withLock {
            recoverPendingLocalClearLocked()
            val session = preferences.session.first()
            cachedSession = session
            if (!session.isJoined) {
                currentStatus.value = SyncStatus.Disabled
                return@withLock
            }
            val outcome = replicaSyncEngine.synchronize(session, trigger)
            preferences.markSuccess(clock.nowMillis())
            cachedSession = preferences.session.first()
            currentStatus.value = when (outcome) {
                ReplicaSyncOutcome.Synchronized -> SyncStatus.Idle
            }
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun push(familyId: String) = sync(SyncTrigger.LocalWrite)
    override suspend fun pull(familyId: String) = sync(SyncTrigger.PullToRefresh)

    override suspend fun leave(familyId: String): Result<Unit> =
        executeFamily(FamilySessionCommand.Leave).map { Unit }

    override suspend fun deleteFamily(): Result<Unit> =
        executeFamily(FamilySessionCommand.DeleteFamily).map { Unit }

    override suspend fun clearLocalRecords(
        workflow: LocalClearWorkflow,
    ): Result<Unit> = localReplicaClearCoordinator
        .clear(
            scope = LocalReplicaClearScope.RecordsOnly,
            workflow = workflow,
            recoverDomain = localClearRecoveryGate::recoverPendingLocalClear,
        )
        .onFailure(::updateFailureStatus)

    override suspend fun clearAllLocalData(
        workflow: LocalClearWorkflow,
    ): Result<Unit> = localReplicaClearCoordinator
        .clear(
            scope = LocalReplicaClearScope.AllLocal,
            workflow = workflow,
            recoverDomain = localClearRecoveryGate::recoverPendingLocalClear,
        )
        .onFailure(::updateFailureStatus)

    /** Caller owns [syncMutex]; lock order is sync mutex then domain mutation guard. */
    private suspend fun recoverPendingLocalClearLocked(): LocalClearRecoveryScope? {
        val resumedDomain = localClearRecoveryGate.recoverPendingLocalClear()
        val resumedReplica = localReplicaClearCoordinator.recoverPendingLocked()
        return when {
            resumedDomain == LocalClearRecoveryScope.AllLocal ||
                resumedReplica == LocalClearRecoveryScope.AllLocal ->
                LocalClearRecoveryScope.AllLocal
            resumedDomain != null || resumedReplica != null -> LocalClearRecoveryScope.RecordsOnly
            else -> null
        }
    }

    private suspend fun executeFamily(
        command: FamilySessionCommand,
    ): Result<FamilySessionOutcome> =
        familySessionCoordinator.execute(command).onFailure(::updateFailureStatus)

    private fun publishSession(session: SyncSession) {
        cachedSession = session
        currentStatus.value = if (session.isJoined) SyncStatus.Idle else SyncStatus.Disabled
    }

    private fun requireAllowed(decision: HomeNetworkDecision) {
        if (decision == HomeNetworkDecision.Allowed) return
        currentStatus.value = decision.toSyncStatus()
        throw HomeNetworkBlockedException(decision.userMessage())
    }

    private fun updateFailureStatus(error: Throwable) {
        currentStatus.value = when (error) {
            is HomeNetworkBlockedException -> currentStatus.value
            is SyncNotEnabledException -> SyncStatus.Disabled
            else -> SyncStatus.Error
        }
    }
}

private fun HomeNetworkDecision.userMessage(): String = when (this) {
    HomeNetworkDecision.MissingServer -> "请先填写家庭服务器地址"
    HomeNetworkDecision.MissingSsidAllowlist ->
        "请先填写并点「保存家庭网络与服务器」绑定 Wi‑Fi 名称（最多 2 个，如 2.4G/5G）"
    HomeNetworkDecision.SsidUnavailable -> "无法读取 Wi‑Fi 名称，请开启定位权限后重试"
    HomeNetworkDecision.SsidNotMatched -> "当前 Wi‑Fi 未绑定，请在账户中添加此网络名称"
    HomeNetworkDecision.NotOnWifi, HomeNetworkDecision.ServerUnavailable,
    HomeNetworkDecision.BackingOff -> "无法连接家庭服务器，请确认在家中 Wi‑Fi"
    HomeNetworkDecision.Background -> "家庭同步仅在前台运行"
    HomeNetworkDecision.Allowed -> ""
}

private fun HomeNetworkDecision.toSyncStatus(): SyncStatus = when (this) {
    HomeNetworkDecision.MissingServer,
    HomeNetworkDecision.MissingSsidAllowlist,
    -> SyncStatus.Disabled
    HomeNetworkDecision.NotOnWifi,
    HomeNetworkDecision.SsidUnavailable,
    HomeNetworkDecision.SsidNotMatched,
    HomeNetworkDecision.ServerUnavailable,
    HomeNetworkDecision.BackingOff,
    HomeNetworkDecision.Background,
    -> SyncStatus.BlockedOfflineHome
    HomeNetworkDecision.Allowed -> SyncStatus.Idle
}

private class HomeNetworkBlockedException(message: String) : IllegalStateException(message)

/**
 * Creator-local publish chrome for a care record that is still waiting on an
 * atomic package commit (or failed to publish). Never shown for remote-applied
 * (syncDirty=false) rows.
 *
 * [hasPriorFamilyRevision] distinguishes first publish (create) from a mutation
 * re-publish: receivers keep the prior complete version until the new package
 * commits, so mutation copy must not say "暂不可见" as if the row never existed.
 */
fun localRecordPublishLabel(
    syncDirty: Boolean,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
    hasPriorFamilyRevision: Boolean = false,
): String? {
    if (!familyJoined || !syncDirty) return null
    return when {
        lastSyncFailed && hasPriorFamilyRevision -> "仅本机 · 更新同步失败"
        lastSyncFailed -> "仅本机 · 同步失败"
        hasPriorFamilyRevision -> "仅本机 · 等待更新同步"
        else -> "仅本机 · 等待照片同步"
    }
}

/** Detail copy when the user taps the local-only chrome. */
fun localRecordPublishDetail(
    lastSyncFailed: Boolean,
    hasPriorFamilyRevision: Boolean,
): String = when {
    hasPriorFamilyRevision && lastSyncFailed ->
        "其他成员仍看到上一完整版本；可手动重试，或下次前台同步时自动重试。"
    hasPriorFamilyRevision ->
        "其他成员仍看到上一完整版本，更新发布成功后才会替换。"
    lastSyncFailed ->
        "其他成员暂不可见；可手动重试，或下次前台同步时自动重试。"
    else ->
        "其他成员暂不可见，照片与记录发布成功后才会出现。"
}

/**
 * Creator-local publish chrome for a care plan still waiting on an atomic
 * package commit. Copy mentions that other members neither see nor remind.
 */
fun localCarePlanPublishLabel(
    syncDirty: Boolean,
    familyJoined: Boolean,
    lastSyncFailed: Boolean,
    hasPriorFamilyRevision: Boolean = false,
): String? {
    if (!familyJoined || !syncDirty) return null
    return when {
        lastSyncFailed && hasPriorFamilyRevision -> "仅本机 · 更新同步失败"
        lastSyncFailed -> "仅本机 · 同步失败"
        hasPriorFamilyRevision -> "仅本机 · 等待更新同步"
        else -> "仅本机 · 等待照片同步"
    }
}

/** Detail copy for plan local-only chrome (visibility + family reminders). */
fun localCarePlanPublishDetail(
    lastSyncFailed: Boolean,
    hasPriorFamilyRevision: Boolean,
): String = when {
    hasPriorFamilyRevision && lastSyncFailed ->
        "其他成员仍看到上一完整版本且不会收到新提醒；可手动重试，或下次前台同步时自动重试。"
    hasPriorFamilyRevision ->
        "其他成员仍看到上一完整版本，更新发布成功后才会替换并安排提醒。"
    lastSyncFailed ->
        "其他成员暂不可见、不会提醒；可手动重试，或下次前台同步时自动重试。"
    else ->
        "其他成员暂不可见、不会提醒，计划与照片发布成功后才会出现。"
}
