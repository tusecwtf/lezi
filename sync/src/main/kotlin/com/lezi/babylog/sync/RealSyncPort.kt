package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.OutboxDao
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
        babyDao = babyDao,
        mediaDao = mediaDao,
        mediaFiles = mediaFiles,
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
                if (preferences.session.first().isJoined) {
                    sync(trigger)
                }
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

    override suspend fun saveServer(baseUrl: String) = runCatching {
        syncMutex.withLock {
            val previous = preferences.session.first()
            val parsed = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
            require(parsed.isServerConfigured) { "请先填写家庭服务器地址" }
            val merged = parsed.copy(allowedSsids = previous.allowedSsids)
            // Changing hosts invalidates upload receipts from the previous server.
            if (previous.baseUrl.isNotBlank() && previous.baseUrl != merged.baseUrl) {
                replicaSyncEngine.resetLocalSyncReceipts(previous)
            }
            preferences.saveHomeLanConfig(merged, clearSessionIfServerChanged = !previous.isJoined)
            cachedSession = preferences.session.first()
            currentStatus.value = if (cachedSession.isJoined) SyncStatus.Idle else SyncStatus.Disabled
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun saveHomeLanConfig(config: HomeLanServerConfig) = runCatching {
        syncMutex.withLock {
            val previous = preferences.session.first()
            val merged = config.withNormalized().let { c ->
                // An empty SSID list must not erase an existing allowlist on a host-only update.
                if (c.allowedSsids.isEmpty() && previous.allowedSsids.isNotEmpty() && c.host == previous.serverHost) {
                    c.copy(allowedSsids = previous.allowedSsids)
                } else {
                    c
                }
            }
            require(merged.isServerConfigured) { "请先填写家庭服务器地址" }
            if (previous.baseUrl.isNotBlank() && previous.baseUrl != merged.baseUrl) {
                replicaSyncEngine.resetLocalSyncReceipts(previous)
            }
            preferences.saveHomeLanConfig(merged, clearSessionIfServerChanged = !previous.isJoined)
            cachedSession = preferences.session.first()
            currentStatus.value = if (cachedSession.isJoined) SyncStatus.Idle else SyncStatus.Disabled
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun createFamily(
        displayName: String?,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<SyncSession> {
        if (bootstrapSecret.isBlank()) {
            return Result.failure<SyncSession>(
                IllegalArgumentException("请填写服务器初始化口令"),
            ).onFailure(::updateFailureStatus)
        }
        return gatedWithoutSession { baseUrl ->
            val deviceId = preferences.ensureDeviceId()
            val createRequestId = preferences.ensureCreateRequestId()
            val joined = try {
                backend.create(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    displayName = memberDisplayNameForWire(displayName),
                    createRequestId = createRequestId,
                    bootstrapSecret = bootstrapSecret,
                    familyName = normalizeFamilyNameForWire(familyName),
                )
            } catch (error: SyncHttpException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    throw BootstrapSecretRejectedException()
                }
                throw error
            }
            persistJoin(baseUrl, deviceId, joined).also {
                preferences.clearCreateRequestId()
                requestSync(SyncTrigger.LocalWrite)
            }
        }
    }

    override suspend fun renameFamily(familyName: String?): Result<Unit> =
        withAllowedSession { session ->
            require(session.role == FamilyRole.Owner) { "仅家庭管理员可修改家庭名" }
            val normalized = normalizeFamilyNameForWire(familyName)
            backend.renameFamily(session, normalized)
            preferences.saveSession(session.copy(familyName = normalized))
            cachedSession = preferences.session.first()
        }

    override suspend fun joinFamily(
        command: JoinFamilyCommand,
    ) = runCatching {
        syncMutex.withLock {
            require(!preferences.session.first().isJoined) {
                "请先退出当前家庭，再加入新的家庭"
            }
            val decoded = InvitePayloadCodec.decode(command.invitation.trim())
            val config = command.homeLanConfig.withNormalized()
            require(config.isServerConfigured) { "请先填写家庭服务器地址" }
            require(config.allowedSsids.isNotEmpty()) { "请至少填写一个家庭 Wi‑Fi 名称" }
            val decision = policy.evaluate(config, foregroundState.isForeground())
            requireAllowed(decision)
            val baseUrl = config.baseUrl
            val deviceId = preferences.ensureDeviceId()
            val joined = backend.join(
                baseUrl,
                decoded.code,
                deviceId,
                memberDisplayNameForWire(command.displayName),
            )
            persistJoin(baseUrl, deviceId, joined, config)
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun createInvite(familyId: String): Result<Invite> = withAllowedSession {
        require(it.role == FamilyRole.Owner) { "仅家庭管理员可生成邀请" }
        backend.invite(it)
    }

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> = withAllowedSession {
        val members = backend.members(it)
        replicaSyncEngine.persistAuthenticatedSelfMembershipIfMissing(it, members)
        cachedSession = preferences.session.first()
        members
    }

    override suspend fun updateMyDisplayName(displayName: String): Result<Unit> =
        withAllowedSession { session ->
            backend.updateMyDisplayName(session, memberDisplayNameForWire(displayName))
        }

    override suspend fun sync(trigger: SyncTrigger): Result<Unit> = runCatching {
        syncMutex.withLock {
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

    override suspend fun leave(familyId: String) = withAllowedSession {
        require(it.role == FamilyRole.Member) {
            "家庭管理员请使用“删除家庭数据”完成退出"
        }
        try {
            backend.leave(it)
        } catch (error: SyncHttpException) {
            if (!error.meansSessionIsGone()) throw error
        }
        outboxDao.deleteFamily(it.familyId)
        replicaSyncEngine.resetLocalSyncReceipts(it)
        preferences.clearAllLocalSyncConfig()
        cachedSession = preferences.session.first()
        currentStatus.value = SyncStatus.Disabled
    }

    override suspend fun deleteFamily() = withAllowedSession {
        require(it.role == FamilyRole.Owner) { "仅家庭管理员可删除家庭" }
        try {
            backend.deleteFamily(it)
        } catch (error: SyncHttpException) {
            if (!error.meansSessionIsGone()) throw error
        }
        outboxDao.deleteFamily(it.familyId)
        replicaSyncEngine.resetLocalSyncReceipts(it)
        preferences.clearAllLocalSyncConfig()
        cachedSession = preferences.session.first()
        currentStatus.value = SyncStatus.Disabled
    }

    override suspend fun clearLocalRecords(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = localReplicaClearCoordinator
        .clear(LocalReplicaClearScope.RecordsOnly, clearLocal)
        .onFailure(::updateFailureStatus)

    override suspend fun clearAllLocalData(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = localReplicaClearCoordinator
        .clear(LocalReplicaClearScope.AllLocal, clearLocal)
        .onFailure(::updateFailureStatus)

    private suspend fun persistJoin(
        baseUrl: String,
        deviceId: String,
        joined: JoinResult,
        joinedConfig: HomeLanServerConfig? = null,
    ): SyncSession {
        val previous = preferences.session.first()
        val parsed = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
        val config = joinedConfig?.withNormalized()
            ?: parsed.copy(allowedSsids = previous.allowedSsids)
        val session = SyncSession(
            familyId = joined.familyId,
            familyToken = joined.token,
            deviceId = deviceId,
            role = joined.role,
            pullCursor = joined.cursor,
            pullGeneration = joined.generation,
            serverHost = config.host.ifBlank { previous.serverHost },
            serverPort = if (config.host.isNotBlank()) config.port else previous.serverPort,
            allowedSsids = config.allowedSsids,
            serverScheme = if (config.host.isNotBlank()) config.scheme else previous.serverScheme,
            // Cached from create/join/rename; cold start has no GET family-name path.
            familyName = joined.familyName?.trim()?.takeIf { it.isNotEmpty() },
            // Server-minted identity; empty when legacy NAS omitted membership_id.
            membershipId = joined.membershipId?.trim().orEmpty(),
        )
        // Upload receipts only prove that bytes exist in the previous
        // server/family namespace. A new family must reconcile them again.
        replicaSyncEngine.resetLocalSyncReceipts(preferences.session.first())
        if (joined.entities.isNotEmpty()) {
            replicaSyncEngine.applyInitialEntities(session, joined.entities)
        }
        preferences.saveSession(session)
        cachedSession = session
        currentStatus.value = SyncStatus.Idle
        return session
    }

    private suspend fun <T> gatedWithoutSession(block: suspend (String) -> T): Result<T> = runCatching {
        syncMutex.withLock {
            val current = preferences.session.first()
            require(!current.isJoined) {
                "请先退出当前家庭，再创建新的家庭"
            }
            val decision = policy.evaluate(current.homeLanConfig, foregroundState.isForeground())
            requireAllowed(decision)
            block(current.homeLanConfig.baseUrl)
        }
    }.onFailure(::updateFailureStatus)

    private suspend fun <T> withAllowedSession(block: suspend (SyncSession) -> T): Result<T> = runCatching {
        syncMutex.withLock {
            val session = preferences.session.first()
            cachedSession = session
            if (!session.isJoined) throw SyncNotEnabledException()
            val decision = policy.evaluate(session.homeLanConfig, foregroundState.isForeground())
            requireAllowed(decision)
            block(session)
        }
    }.onFailure(::updateFailureStatus)

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

private fun SyncHttpException.meansSessionIsGone(): Boolean =
    statusCode == 401


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
