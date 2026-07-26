package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.model.limitBabyNicknameInput
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object AtomicBundleId {
    private const val NAMESPACE = "lezi.atomic-bundle.v1"

    fun forRecord(recordClientUuid: String, updatedAt: Long): String =
        fromRoot("record", recordClientUuid, updatedAt)

    fun forCarePlan(planClientUuid: String, updatedAt: Long): String =
        fromRoot("care_plan", planClientUuid, updatedAt)

    private fun fromRoot(rootType: String, clientUuid: String, updatedAt: Long): String =
        UUID.nameUUIDFromBytes(
            "$NAMESPACE:$rootType:$clientUuid:$updatedAt".toByteArray(Charsets.UTF_8),
        ).toString()
}

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val policy: HomeNetworkPolicy,
    private val networkState: NetworkState,
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
                resetLocalSyncReceipts(previous)
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
                resetLocalSyncReceipts(previous)
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
            persistJoin(baseUrl, deviceId, joined, config).also {
                requestSync(SyncTrigger.PullToRefresh)
            }
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun createInvite(familyId: String): Result<Invite> = withAllowedSession {
        require(it.role == FamilyRole.Owner) { "仅家庭管理员可生成邀请" }
        backend.invite(it)
    }

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> = withAllowedSession {
        val members = backend.members(it)
        persistAuthenticatedSelfMembershipIfMissing(it, members)
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
            // Baseline device-local media fields before snapshotting or any
            // policy/backend I/O. Later sync-owned projections advance this
            // guard; user edits made anywhere in the cycle do not.
            val mediaEditGuard = captureLocalMediaEditGuard()
            // Snapshot first: a non-Wi-Fi write still leaves a durable outbox.
            captureLocalChanges(session)
            val decision = policy.evaluate(session.homeLanConfig, foregroundState.isForeground())
            requireAllowed(decision)
            currentStatus.value = SyncStatus.Syncing
            val plan = SyncPlan.forTrigger(trigger)
            var current = session
            if (
                current.membershipId.isBlank() &&
                policy.supportsRecordMembershipAuthor
            ) {
                current = persistAuthenticatedSelfMembershipIfMissing(
                    current,
                    backend.members(current),
                )
            }
            var recovered = false
            if (current.pullCursor > 0 && current.pullGeneration.isBlank()) {
                current = recoverFullResync(current, mediaEditGuard)
                recovered = true
            }
            if (plan.push && !recovered) {
                try {
                    pushPending(current)
                } catch (error: SyncHttpException) {
                    error.fullResyncCursorOrNull() ?: throw error
                    current = recoverFullResync(current, mediaEditGuard)
                    recovered = true
                }
            }
            if (plan.pull && !recovered) {
                try {
                    current = pullAllPages(
                        initial = current,
                        mediaEditGuard = mediaEditGuard,
                    )
                } catch (error: SyncHttpException) {
                    error.fullResyncCursorOrNull() ?: throw error
                    current = recoverFullResync(current, mediaEditGuard)
                }
            }
            preferences.markSuccess(clock.nowMillis())
            cachedSession = preferences.session.first()
            currentStatus.value = SyncStatus.Idle
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
        resetLocalSyncReceipts(it)
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
        resetLocalSyncReceipts(it)
        preferences.clearAllLocalSyncConfig()
        cachedSession = preferences.session.first()
        currentStatus.value = SyncStatus.Disabled
    }

    override suspend fun clearLocalRecords(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = runCatching {
        syncMutex.withLock {
            val session = preferences.session.first()
            val logMedia = mediaDao.listAllIncludingDeleted().filter { it.kind == "log" }
            val localMediaPaths = buildList {
                addAll(logMedia.map(MediaAssetEntity::localUri))
                recordDao.listAllIncludingDeleted().forEach { record ->
                    addAll(localPhotoPaths(record.payloadJson))
                }
            }.filter(String::isNotBlank).distinct()
            // The authoritative delete must share the same barrier as pull/apply.
            // Otherwise a pull can reinsert records between the domain delete
            // and replica cleanup while the UI still reports success.
            var domainCommitted = false
            try {
                clearLocal { domainCommitted = true }
                check(domainCommitted) { "本机记录清除未确认领域事务已提交" }
                finishLocalRecordsClear(session, logMedia, localMediaPaths)
            } catch (error: Throwable) {
                if (domainCommitted) {
                    runCatching {
                        finishLocalRecordsClear(session, logMedia, localMediaPaths)
                    }.onFailure(error::addSuppressed)
                    throw LocalClearCommittedException(
                        familyServerRetained = session.familyId.isNotBlank(),
                        cause = error,
                    )
                }
                throw error
            }
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun clearAllLocalData(
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = runCatching {
        syncMutex.withLock {
            val familyServerRetained = preferences.session.first().familyId.isNotBlank()
            val allMedia = mediaDao.listAllIncludingDeleted()
            val localMediaPaths = buildList {
                addAll(allMedia.map(MediaAssetEntity::localUri))
                recordDao.listAllIncludingDeleted().forEach { record ->
                    addAll(localPhotoPaths(record.payloadJson))
                }
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }.filter(String::isNotBlank).distinct()
            // Same barrier as clearLocalRecords: domain wipe + outbox/media/files
            // must not interleave with pull/apply.
            var domainCommitted = false
            try {
                clearLocal { domainCommitted = true }
                check(domainCommitted) { "本机数据清除未确认领域事务已提交" }
                finishAllLocalDataClear(localMediaPaths)
            } catch (error: Throwable) {
                if (domainCommitted) {
                    runCatching {
                        finishAllLocalDataClear(localMediaPaths)
                    }.onFailure(error::addSuppressed)
                    throw LocalClearCommittedException(
                        familyServerRetained = familyServerRetained,
                        cause = error,
                    )
                }
                throw error
            }
        }
    }.onFailure(::updateFailureStatus)

    private suspend fun finishLocalRecordsClear(
        session: SyncSession,
        logMedia: List<MediaAssetEntity>,
        localMediaPaths: List<String>,
    ) {
        if (session.familyId.isNotBlank()) {
            outboxDao.deleteType(session.familyId, "record")
            // History clear also wipes care plans + candidates locally.
            outboxDao.deleteType(session.familyId, "care_plan")
            outboxDao.deleteType(session.familyId, "fulfillment_candidate")
            logMedia.map(MediaAssetEntity::clientUuid)
                .chunked(OUTBOX_DELETE_CHUNK_SIZE)
                .forEach { chunk ->
                    outboxDao.deleteEntities(session.familyId, "media", chunk)
                }
        }
        mediaDao.deleteLogMedia()
        localMediaPaths.forEach { localUri ->
            runCatching { mediaFiles.delete(localUri) }
        }
        // Keep the last server incarnation as a push precondition. If the
        // server was restored while records were being cleared, the next dirty
        // Baby must enter full recovery before mutation.
        preferences.updateCursor(0, generation = session.pullGeneration)
        cachedSession = preferences.session.first()
    }

    private suspend fun finishAllLocalDataClear(localMediaPaths: List<String>) {
        outboxDao.deleteAll()
        mediaDao.deleteAll()
        localMediaPaths.forEach { localUri ->
            runCatching { mediaFiles.delete(localUri) }
        }
        // Full wipe leaves no local replica. Drop generation so the next
        // join/create starts from a clean push precondition.
        preferences.updateCursor(0, generation = "")
        cachedSession = preferences.session.first()
    }

    private suspend fun pushPending(session: SyncSession) {
        while (pushPendingBatch(session)) {
            // Each acknowledged batch is deleted before the next peek, so rows
            // beyond the bounded request size cannot be starved by re-snapshotting.
        }
    }

    private suspend fun pushPendingBatch(session: SyncSession): Boolean {
        val roots = outboxDao.peek(session.familyId, PUSH_ROOT_BATCH_SIZE)
        if (roots.isEmpty()) return false
        val queued = expandBatchWithDependencies(session, roots)
        val staleAfterHardDelete = mutableListOf<OutboxEntity>()
        queued.forEach { row ->
            if (isStaleAfterHardDelete(row)) staleAfterHardDelete += row
        }
        if (staleAfterHardDelete.isNotEmpty()) {
            // A committed local clear can fail before its outbox cleanup. Never
            // resurrect a hard-deleted entity on a later sync; deleting these
            // rows is safe because ordinary soft deletes retain their DB row.
            outboxDao.deleteIds(staleAfterHardDelete.map(OutboxEntity::id))
        }
        val unauthorizedAvatarRows = if (session.role == FamilyRole.Member) {
            (queued - staleAfterHardDelete.toSet()).filter { row ->
                row.entityType == "media" &&
                    runCatching {
                        Json.parseToJsonElement(row.payloadJson)
                            .jsonObject
                            .string("kind") == "avatar"
                    }.getOrDefault(false)
            }
        } else {
            emptyList()
        }
        if (unauthorizedAvatarRows.isNotEmpty()) {
            outboxDao.deleteIds(unauthorizedAvatarRows.map { it.id })
        }
        val pending = queued - staleAfterHardDelete.toSet() - unauthorizedAvatarRows.toSet()
        if (pending.isEmpty()) return true

        // Record and care_plan packages always use atomic bundles (0–3 photos).
        // No metadata-first fallback when the NAS lacks atomic_bundle.
        val recordRows = pending.filter { it.entityType == "record" }
        val carePlanRows = pending.filter { it.entityType == "care_plan" }
        val recordClientUuids = recordRows.map { it.clientUuid }.toSet()
        val carePlanClientUuids = carePlanRows.map { it.clientUuid }.toSet()
        val logMediaForRecords = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            val recordId = media.recordId ?: return@filter false
            media.kind == "log" &&
                recordDao.getIncludingDeleted(recordId)?.clientUuid in recordClientUuids
        }
        val logMediaForPlans = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            val planId = media.carePlanId ?: return@filter false
            media.kind == "log" &&
                carePlanDao.get(planId)?.clientUuid in carePlanClientUuids
        }
        val atomicPackageRows =
            (recordRows + carePlanRows + logMediaForRecords + logMediaForPlans).toSet()
        val residual = pending - atomicPackageRows

        if (recordRows.isNotEmpty() || carePlanRows.isNotEmpty()) {
            requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
            if (!policy.supportsAtomicBundle) {
                throw AtomicBundleUnsupportedException()
            }
            // Prefer fulfill Record before completed care_plan so pull pages that
            // end mid-set still apply the fact first. Receivers apply records then
            // co-gate completed plans until the linked record is present (same-txn).
            for (recordRow in recordRows) {
                pushRecordAtomicBundle(session, recordRow, pending)
            }
            for (planRow in carePlanRows) {
                pushCarePlanAtomicBundle(session, planRow, pending)
            }
        }

        if (residual.isEmpty()) return true
        val uploads = mutableListOf<MediaAssetEntity>()
        val entities = residual
            .map { row ->
                var payload = normalizeLegacyOutboxPayload(row.entityType, row.payloadJson)
                if (row.entityType == "media" && row.deletedAt == null) {
                    val media = mediaDao.getByClientUuid(row.clientUuid)
                        ?: error("本地媒体元数据不存在")
                    if (!media.hasReceiptFor(session)) {
                        val prepared = mediaFiles.prepareUpload(media.localUri)
                        val updated = media.copy(
                            mime = prepared.mime,
                            width = prepared.width ?: media.width,
                            height = prepared.height ?: media.height,
                            byteSize = prepared.bytes.size.toLong(),
                        )
                        mediaDao.update(updated)
                        uploads += updated
                        val rawObject = Json.parseToJsonElement(payload).jsonObject
                        payload = JsonObject(
                            rawObject +
                                ("mime" to JsonPrimitive(updated.mime)) +
                                ("byte_size" to JsonPrimitive(updated.byteSize)) +
                                listOfNotNull(
                                    updated.width?.let { "width" to JsonPrimitive(it) },
                                    updated.height?.let { "height" to JsonPrimitive(it) },
                                ).toMap(),
                        ).toString()
                    }
                }
                SyncEntity(
                    row.entityType,
                    row.clientUuid,
                    payload,
                    row.updatedAt,
                    row.deletedAt,
                )
            }
            .sortedBy { ENTITY_ORDER.indexOf(it.type).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
        val pushResult = backend.push(session, entities)
        mergeCanonicalRecordAuthors(
            authors = pushResult.recordAuthors,
            expectedUpdatedAt = entities
                .filter { it.type == "record" }
                .associate { it.clientUuid to it.updatedAt },
        )
        uploads.forEach { media ->
            requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
            // Metadata needs the compressed byte size, so preparation happens
            // once before the metadata push and again here. Re-preparing one
            // file at a time bounds resident JPEG bytes to a single upload.
            val prepared = mediaFiles.prepareUpload(media.localUri)
            backend.putMedia(session, media.clientUuid, prepared.bytes, prepared.mime)
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        residual.forEach { row ->
            when (row.entityType) {
                "baby" -> babyDao.markSynced(row.clientUuid, row.updatedAt)
                "record" -> recordDao.markSynced(row.clientUuid, row.updatedAt)
                "care_plan" -> carePlanDao.markSynced(row.clientUuid, row.updatedAt)
                "media" -> mediaDao.markSynced(row.clientUuid, row.updatedAt)
                "custom_item" -> customItemDao.markSynced(row.clientUuid, row.updatedAt)
                "fulfillment_candidate" ->
                    fulfillmentCandidateDao.markSynced(row.clientUuid, row.updatedAt)
            }
        }
        outboxDao.deleteIds(residual.map { it.id })
        return true
    }

    /**
     * Push one record + its 0–3 log photos as an atomic NAS package.
     * [bundleId] is a deterministic UUID derived from root type + record UUID + updatedAt.
     */
    private suspend fun pushRecordAtomicBundle(
        session: SyncSession,
        recordRow: OutboxEntity,
        pending: List<OutboxEntity>,
    ) {
        val record = recordDao.getByClientUuid(recordRow.clientUuid)
            ?: error("本地记录不存在")
        val baby = babyDao.get(record.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.recordId == record.id
        }
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaBytes = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = normalizeLegacyOutboxPayload(row.entityType, row.payloadJson)
            val media = mediaDao.getByClientUuid(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.bytes.size.toLong(),
                )
                mediaDao.update(updated)
                mediaBytes += updated to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(updated.mime)) +
                        ("byte_size" to JsonPrimitive(updated.byteSize)) +
                        listOfNotNull(
                            updated.width?.let { "width" to JsonPrimitive(it) },
                            updated.height?.let { "height" to JsonPrimitive(it) },
                        ).toMap(),
                ).toString()
            }
            mediaEntities += SyncEntity(
                type = "media",
                clientUuid = row.clientUuid,
                payloadJson = payload,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
        requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
        val preStageCapabilities = policy.lastHealthStatus.capabilities.toSet()
        if (CAPABILITY_ATOMIC_BUNDLE !in preStageCapabilities) {
            throw AtomicBundleUnsupportedException()
        }
        val includeMembershipAuthor =
            CAPABILITY_RECORD_MEMBERSHIP_AUTHOR in preStageCapabilities
        val mapped = SyncWireMapper.record(
            entity = record,
            babyClientUuid = baby.clientUuid,
            createdByDeviceId = record.createdByDeviceId ?: session.deviceId,
            includeMembershipAuthor = includeMembershipAuthor,
        )
        // Prefer durable outbox wire payload (includes baby_client_uuid) when valid.
        val outboxPayload = normalizeLegacyOutboxPayload("record", recordRow.payloadJson)
        val durablePayload = runCatching {
            val obj = Json.parseToJsonElement(outboxPayload).jsonObject
            if ("baby_client_uuid" in obj) outboxPayload else mapped.payloadJson
        }.getOrDefault(mapped.payloadJson)
        val rootPayload = recordPayloadForServerCapability(
            durablePayload = durablePayload,
            mappedPayload = mapped.payloadJson,
            includeMembershipAuthor = includeMembershipAuthor,
        )
        val root = mapped.copy(
            payloadJson = rootPayload,
            updatedAt = recordRow.updatedAt,
            deletedAt = recordRow.deletedAt,
        )
        val bundleId = AtomicBundleId.forRecord(record.clientUuid, recordRow.updatedAt)
        backend.stageBundle(
            session,
            AtomicBundleDraft(
                bundleId = bundleId,
                root = root,
                media = mediaEntities,
            ),
        )
        for ((media, prepared) in mediaBytes) {
            requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
            backend.putBundleMedia(
                session,
                bundleId,
                media.clientUuid,
                prepared.bytes,
                prepared.mime,
            )
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
        val commit = backend.commitBundle(session, bundleId)
        mergeCanonicalRecordAuthors(
            authors = commit.recordAuthors,
            expectedUpdatedAt = mapOf(record.clientUuid to recordRow.updatedAt),
        )
        recordDao.markSynced(record.clientUuid, recordRow.updatedAt)
        mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        outboxDao.deleteIds((listOf(recordRow) + mediaRows).map { it.id })
    }

    /**
     * Push one care plan + its 0–3 plan photos as an atomic NAS package.
     * [bundleId] is a deterministic UUID derived from root type + plan UUID + updatedAt.
     */
    private suspend fun pushCarePlanAtomicBundle(
        session: SyncSession,
        planRow: OutboxEntity,
        pending: List<OutboxEntity>,
    ) {
        val plan = carePlanDao.getByClientUuid(planRow.clientUuid)
            ?: error("本地护理计划不存在")
        val baby = babyDao.get(plan.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.carePlanId == plan.id
        }
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaBytes = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = normalizeLegacyOutboxPayload(row.entityType, row.payloadJson)
            val media = mediaDao.getByClientUuid(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.bytes.size.toLong(),
                )
                mediaDao.update(updated)
                mediaBytes += updated to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(updated.mime)) +
                        ("byte_size" to JsonPrimitive(updated.byteSize)) +
                        listOfNotNull(
                            updated.width?.let { "width" to JsonPrimitive(it) },
                            updated.height?.let { "height" to JsonPrimitive(it) },
                        ).toMap(),
                ).toString()
            }
            mediaEntities += SyncEntity(
                type = "media",
                clientUuid = row.clientUuid,
                payloadJson = payload,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
        val customItemUuid = plan.customItemId
            ?.let { customItemDao.getById(it)?.clientUuid }
        val mapped = SyncWireMapper.carePlan(
            entity = plan,
            babyClientUuid = baby.clientUuid,
            customItemClientUuid = customItemUuid,
        )
        val outboxPayload = normalizeLegacyOutboxPayload("care_plan", planRow.payloadJson)
        val rootPayload = runCatching {
            val obj = Json.parseToJsonElement(outboxPayload).jsonObject
            if ("baby_client_uuid" in obj) outboxPayload else mapped.payloadJson
        }.getOrDefault(mapped.payloadJson)
        val root = mapped.copy(
            payloadJson = rootPayload,
            updatedAt = planRow.updatedAt,
            deletedAt = planRow.deletedAt,
        )
        val bundleId = AtomicBundleId.forCarePlan(plan.clientUuid, planRow.updatedAt)
        requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
        backend.stageBundle(
            session,
            AtomicBundleDraft(
                bundleId = bundleId,
                root = root,
                media = mediaEntities,
            ),
        )
        for ((media, prepared) in mediaBytes) {
            requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
            backend.putBundleMedia(
                session,
                bundleId,
                media.clientUuid,
                prepared.bytes,
                prepared.mime,
            )
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
        backend.commitBundle(session, bundleId)
        carePlanDao.markSynced(plan.clientUuid, planRow.updatedAt)
        mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        outboxDao.deleteIds((listOf(planRow) + mediaRows).map { it.id })
    }

    private suspend fun isStaleAfterHardDelete(row: OutboxEntity): Boolean = when (row.entityType) {
        "baby" -> babyDao.getByClientUuid(row.clientUuid) == null
        "record" -> recordDao.getByClientUuid(row.clientUuid) == null
        "care_plan" -> carePlanDao.getByClientUuid(row.clientUuid) == null
        "custom_item" -> customItemDao.getByClientUuid(row.clientUuid) == null
        "fulfillment_candidate" ->
            fulfillmentCandidateDao.getByClientUuid(row.clientUuid) == null
        "media" -> {
            val media = mediaDao.getByClientUuid(row.clientUuid)
            val recordId = media?.recordId
            val carePlanId = media?.carePlanId
            media == null || (
                media.kind == "log" && (
                    (recordId != null && recordDao.getIncludingDeleted(recordId) == null) ||
                        (carePlanId != null && carePlanDao.get(carePlanId) == null) ||
                        (recordId == null && carePlanId == null)
                    )
                )
        }
        else -> false
    }

    private suspend fun expandBatchWithDependencies(
        session: SyncSession,
        roots: List<OutboxEntity>,
    ): List<OutboxEntity> {
        val selected = linkedMapOf<Pair<String, String>, OutboxEntity>()
        roots.forEach { row -> selected[row.entityType to row.clientUuid] = row }
        var index = 0
        while (index < selected.size) {
            val row = selected.values.elementAt(index++)
            val payload = runCatching {
                Json.parseToJsonElement(
                    normalizeLegacyOutboxPayload(row.entityType, row.payloadJson),
                ).jsonObject
            }.getOrNull() ?: continue
            val dependencies = when (row.entityType) {
                "baby" -> listOfNotNull(
                    payload.string("avatar_media_uuid")?.let { "media" to it },
                )
                "record" -> {
                    // Baby for FK + every pending log media row for this record so
                    // atomic packages stage a complete 0–3 photo manifest.
                    val babyDep = payload.string("baby_client_uuid")?.let { "baby" to it }
                    val mediaDeps = recordDao.getByClientUuid(row.clientUuid)?.id?.let { recordId ->
                        mediaDao.listForRecord(recordId)
                            .filter { it.kind == "log" }
                            .map { "media" to it.clientUuid }
                    }.orEmpty()
                    listOfNotNull(babyDep) + mediaDeps
                }
                "care_plan" -> {
                    val babyDep = payload.string("baby_client_uuid")?.let { "baby" to it }
                    val customDep = payload.string("custom_item_client_uuid")
                        ?.let { "custom_item" to it }
                    val fulfilledDep = payload.string("fulfilled_record_client_uuid")
                        ?.let { "record" to it }
                    val mediaDeps = carePlanDao.getByClientUuid(row.clientUuid)?.id?.let { planId ->
                        mediaDao.listForCarePlan(planId)
                            .filter { it.kind == "log" }
                            .map { "media" to it.clientUuid }
                    }.orEmpty()
                    listOfNotNull(babyDep, customDep, fulfilledDep) + mediaDeps
                }
                "fulfillment_candidate" -> listOfNotNull(
                    payload.string("care_plan_client_uuid")?.let { "care_plan" to it },
                    payload.string("record_client_uuid")?.let { "record" to it },
                )
                "media" -> listOfNotNull(
                    payload.string("record_client_uuid")?.let { "record" to it },
                    payload.string("care_plan_client_uuid")?.let { "care_plan" to it },
                    payload.string("baby_client_uuid")?.let { "baby" to it },
                )
                else -> emptyList()
            }
            dependencies.forEach { reference ->
                if (reference !in selected) {
                    outboxDao.find(
                        session.familyId,
                        reference.first,
                        reference.second,
                    )?.let { selected[reference] = it }
                }
            }
        }
        require(selected.size <= MAX_PUSH_BATCH_SIZE) {
            "同步依赖批次过大，请稍后重试"
        }
        return selected.values.toList()
    }

    private suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val deletedLocalUris = mutableListOf<String>()
        // Atomic receive: download all log media bytes for new/updated packages into
        // a staging map BEFORE any Room apply, so partial failure never exposes a
        // record/plan with placeholder media or advances past an incomplete package.
        val stagedLogMediaBytes = stageLogMediaDownloads(session, entities)
        val appliedCarePlanUuids = mutableListOf<String>()
        transactionRunner.run {
            val unresolved = mutableListOf<SyncEntity>()
            for (entity in entities.filter { it.type == "baby" }) {
                if (!applyBaby(session, entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "custom_item" }) {
                if (!applyCustomItem(entity)) unresolved += entity
            }
            // Fulfillment full-set: record(+photos) before completed care_plan before
            // fulfillment_candidate. Incomplete sets leave cursor unmoved (unresolved).
            for (entity in entities.filter { it.type == "record" }) {
                if (!applyRecord(entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "care_plan" }) {
                val applied = applyCarePlan(entity)
                if (!applied) {
                    unresolved += entity
                } else {
                    appliedCarePlanUuids += entity.clientUuid
                }
            }
            for (entity in entities.filter { it.type == "media" }) {
                if (!applyMedia(session, entity, deletedLocalUris, stagedLogMediaBytes)) {
                    unresolved += entity
                }
            }
            for (entity in entities.filter { it.type == "fulfillment_candidate" }) {
                if (!applyFulfillmentCandidate(entity)) unresolved += entity
            }
            require(unresolved.isEmpty()) {
                "同步数据引用尚未就绪，保留 cursor 以便重试"
            }
            // Full page applied: re-link each affected plan to the deterministic
            // authority (independent of care_plan LWW / push arrival order).
            val planUuidsForResolve = buildSet {
                entities.filter { it.type == "fulfillment_candidate" }.forEach { entity ->
                    runCatching {
                        Json.parseToJsonElement(entity.payloadJson).jsonObject
                            .string("care_plan_client_uuid")
                    }.getOrNull()?.let { add(it) }
                }
                entities.filter { it.type == "care_plan" }.forEach { add(it.clientUuid) }
            }
            for (planUuid in planUuidsForResolve) {
                resolveFulfillmentAuthority(planUuid)
            }
            entities.filter { it.type == "record" }
                .mapNotNull { entity -> recordDao.getByClientUuid(entity.clientUuid)?.babyId }
                .distinct()
                .forEach { healDuplicateOpenSleeps(it) }
            val affectedRecords = (
                entities.filter { it.type == "record" }
                    .mapNotNull { entity -> recordDao.getByClientUuid(entity.clientUuid)?.id } +
                    entities.filter { it.type == "media" }
                        .mapNotNull { entity ->
                            mediaDao.getByClientUuid(entity.clientUuid)?.recordId
                        }
                )
                .distinct()
            affectedRecords.forEach {
                refreshRecordPhotoPaths(it, mediaEditGuard)
            }
            val affectedPlans = (
                entities.filter { it.type == "care_plan" }
                    .mapNotNull { entity -> carePlanDao.getByClientUuid(entity.clientUuid)?.id } +
                    entities.filter { it.type == "media" }
                        .mapNotNull { entity ->
                            mediaDao.getByClientUuid(entity.clientUuid)?.carePlanId
                        }
                )
                .distinct()
            affectedPlans.forEach {
                refreshCarePlanPhotoPaths(it, mediaEditGuard)
            }
            (
                entities.filter { it.type == "baby" }
                    .mapNotNull { entity -> babyDao.getByClientUuid(entity.clientUuid)?.id } +
                    entities.filter { it.type == "media" }
                        .mapNotNull { entity ->
                            mediaDao.getByClientUuid(entity.clientUuid)?.babyId
                        }
                )
                .distinct()
                .forEach {
                    refreshBabyAvatar(it, mediaEditGuard)
                }
        }
        deletedLocalUris.forEach { mediaFiles.delete(it) }
        // Side effects only after full package apply — never during partial download.
        if (appliedCarePlanUuids.isNotEmpty()) {
            carePlanAppliedListener.onFamilyCarePlansApplied(appliedCarePlanUuids.distinct())
        }
        check(session.isJoined)
    }

    /**
     * Apply a remote care plan. Custom-item plans wait until the definition is
     * local (return false → page retries, plan stays invisible). Concurrent local
     * dirty revisions are not clobbered.
     *
     * Completed plans that link a fulfilled record require that record to already
     * be local (same-page records are applied first) so receivers never see
     * completed-without-Record partial state.
     */
    private suspend fun applyCarePlan(entity: SyncEntity): Boolean {
        val existing = carePlanDao.getByClientUuid(entity.clientUuid)
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
        // Keep in-flight local create/edit until push commits.
        if (existing != null && existing.syncDirty) return true
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val babyUuid = payload.string("baby_client_uuid") ?: return false
        val baby = babyDao.getByClientUuid(babyUuid) ?: return false
        val customItemUuid = payload.string("custom_item_client_uuid")
        val customItemId = if (customItemUuid != null) {
            val def = customItemDao.getByClientUuid(customItemUuid) ?: return false
            def.id
        } else {
            null
        }
        val status = payload.string("status") ?: existing?.status ?: "pending"
        val fulfilledRecordUuid = if ("fulfilled_record_client_uuid" in payload) {
            payload.string("fulfilled_record_client_uuid")
        } else {
            existing?.fulfilledRecordClientUuid
        }
        // Full-set co-gate: completed + linked record must not appear without the fact.
        if (
            entity.deletedAt == null &&
            status == "completed" &&
            !fulfilledRecordUuid.isNullOrBlank()
        ) {
            if (recordDao.getByClientUuid(fulfilledRecordUuid) == null) return false
        }
        carePlanDao.upsert(
            CarePlanEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = payload.string("type") ?: existing?.type ?: "other",
                customItemId = customItemId ?: existing?.customItemId,
                scheduledAt = payload.long("scheduled_at") ?: existing?.scheduledAt
                    ?: entity.updatedAt,
                scheduledZoneId = payload.string("scheduled_zone_id")
                    ?: existing?.scheduledZoneId
                    ?: "UTC",
                note = if ("note" in payload) payload.string("note") else existing?.note,
                payloadJson = preserveDeviceLocalPhotos(
                    remotePayloadJson = SyncWireMapper.carePlanPayloadJson(payload),
                    existingPayloadJson = existing?.payloadJson,
                ),
                schemaVersion = SyncWireMapper.carePlanSchemaVersion(payload),
                status = status,
                createdByMembershipId = payload.string("created_by_membership_id")
                    ?: existing?.createdByMembershipId
                    ?: "",
                fulfilledRecordClientUuid = fulfilledRecordUuid,
                fulfilledAt = if ("fulfilled_at" in payload) {
                    payload.long("fulfilled_at")
                } else {
                    existing?.fulfilledAt
                },
                sourceRecordClientUuid = existing?.sourceRecordClientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        return true
    }

    /**
     * Apply a remote fulfillment candidate. Requires plan + record to already be
     * local so the candidate is never the sole visible half of a fulfill result.
     * Winner selection runs after the full page apply via [resolveFulfillmentAuthority].
     */
    private suspend fun applyFulfillmentCandidate(entity: SyncEntity): Boolean {
        val existing = fulfillmentCandidateDao.getByClientUuid(entity.clientUuid)
        // Keep in-flight local dirty until push commits (then markSynced clears dirty).
        if (existing != null && existing.syncDirty) return true
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val planUuid = payload.string("care_plan_client_uuid") ?: return false
        val recordUuid = payload.string("record_client_uuid") ?: return false
        // Full-set: plan and record must already be applied (or present).
        if (carePlanDao.getByClientUuid(planUuid) == null) return false
        if (recordDao.getByClientUuid(recordUuid) == null) return false
        val remoteMembership = payload.string("submitter_membership_id").orEmpty()
        val remoteRole = payload.string("submitter_role").orEmpty()
        val remoteConfirmed = payload.long("confirmed_at")
        // Ticket 26 multi-device convergence: originators keep equal/higher updatedAt
        // after markSynced, but must still adopt server-frozen stamps (role/membership/
        // confirmed_at) so every device adjudicates with the same evidence.
        if (existing != null && existing.updatedAt >= entity.updatedAt) {
            val needsStampMerge =
                (existing.submitterRole.isBlank() && remoteRole.isNotBlank()) ||
                    (existing.submitterMembershipId.isBlank() && remoteMembership.isNotBlank()) ||
                    (
                        remoteConfirmed != null &&
                            remoteConfirmed != existing.confirmedAt &&
                            remoteRole.isNotBlank()
                        )
            if (!needsStampMerge) return true
            fulfillmentCandidateDao.update(
                existing.copy(
                    confirmedAt = remoteConfirmed ?: existing.confirmedAt,
                    submitterMembershipId = remoteMembership.ifBlank {
                        existing.submitterMembershipId
                    },
                    submitterRole = remoteRole.ifBlank { existing.submitterRole },
                    updatedAt = maxOf(existing.updatedAt, entity.updatedAt),
                    deletedAt = entity.deletedAt ?: existing.deletedAt,
                    syncDirty = false,
                ),
            )
            return true
        }
        fulfillmentCandidateDao.upsert(
            FulfillmentCandidateEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = if ("actual_timestamp" in payload) {
                    payload.long("actual_timestamp")
                } else {
                    existing?.actualTimestamp
                },
                confirmedAt = remoteConfirmed
                    ?: existing?.confirmedAt
                    ?: entity.updatedAt,
                submitterMembershipId = remoteMembership.ifBlank {
                    existing?.submitterMembershipId.orEmpty()
                },
                submitterRole = remoteRole.ifBlank {
                    existing?.submitterRole.orEmpty()
                },
                // Preserve local adoption until resolve re-marks the full set.
                adoptionStatus = existing?.adoptionStatus.orEmpty(),
                // Device-local convert pointer (ticket 27); never overwrite from wire.
                convertedRecordClientUuid = existing?.convertedRecordClientUuid.orEmpty(),
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        return true
    }

    /**
     * Re-link [CarePlanEntity.fulfilledRecordClientUuid] to the deterministic winner
     * among local candidates and mark losers conflict-not-adopted. Does not delete
     * records or photos. Local-only (no syncDirty) so plan LWW push order cannot
     * permanently pin a non-winner on any device.
     */
    private suspend fun resolveFulfillmentAuthority(carePlanClientUuid: String) {
        val live = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
            .filter { it.deletedAt == null }
        if (live.isEmpty()) return
        val resolution = FulfillmentAuthority.resolve(
            live.map {
                FulfillmentCandidateEvidence(
                    clientUuid = it.clientUuid,
                    recordClientUuid = it.recordClientUuid,
                    confirmedAt = it.confirmedAt,
                    submitterRole = it.submitterRole,
                )
            },
        ) ?: return
        // Same pure patches as CareLog.resolveFulfillmentAuthorityForPlan.
        val patches = FulfillmentAuthority.adoptionStatusPatches(
            liveClientUuidToStatus = live.associate { it.clientUuid to it.adoptionStatus },
            resolution = resolution,
        )
        if (patches.isNotEmpty()) {
            val byUuid = live.associateBy { it.clientUuid }
            for ((clientUuid, status) in patches) {
                val candidate = byUuid[clientUuid] ?: continue
                fulfillmentCandidateDao.update(candidate.copy(adoptionStatus = status))
            }
        }
        val plan = carePlanDao.getByClientUuid(carePlanClientUuid) ?: return
        if (plan.deletedAt != null) return
        if (
            !FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = plan.status,
                currentFulfilledRecordClientUuid = plan.fulfilledRecordClientUuid,
                currentFulfilledAt = plan.fulfilledAt,
                resolution = resolution,
            )
        ) {
            return
        }
        carePlanDao.update(
            plan.copy(
                status = CarePlanStatus.COMPLETED.storageKey,
                fulfilledRecordClientUuid = resolution.winnerRecordClientUuid,
                fulfilledAt = resolution.winnerConfirmedAt,
                // Keep updatedAt/syncDirty — resolution is device-local convergence.
                updatedAt = plan.updatedAt,
                syncDirty = plan.syncDirty,
            ),
        )
    }

    /**
     * Apply a remote custom item definition with pure updated_at LWW.
     * Preserves local sortOrder (layout). Does not resurrect local layout prefs.
     */
    private suspend fun applyCustomItem(entity: SyncEntity): Boolean {
        val existing = customItemDao.getByClientUuid(entity.clientUuid)
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
        // Keep in-flight local rename/delete until push commits.
        if (existing != null && existing.syncDirty) return true
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val name = payload.string("name")?.trim().orEmpty()
        if (name.isEmpty() && entity.deletedAt == null) return false
        val iconSlot = payload["icon_slot"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: existing?.iconSlot
            ?: 0
        val creator = payload.string("created_by_membership_id")
            ?: existing?.createdByMembershipId
            ?: ""
        val familyId = existing?.familyId
            ?: familyDao.listAll().firstOrNull()?.id
            ?: return false
        customItemDao.upsert(
            CustomItemEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                familyId = familyId,
                name = name.ifBlank { existing?.name.orEmpty() },
                iconSlot = iconSlot.coerceIn(0, 7),
                // Device layout: keep local order; new remote items append.
                sortOrder = existing?.sortOrder
                    ?: customItemDao.listAllIncludingDeleted().size,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                createdByMembershipId = creator,
                syncDirty = false,
            ),
        )
        return true
    }

    private suspend fun applyBaby(session: SyncSession, entity: SyncEntity): Boolean {
        val existing = babyDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) {
            if (session.role == FamilyRole.Member && "avatar_media_uuid" in payload) {
                val remoteAvatar = payload.string("avatar_media_uuid")
                if (existing.avatarMediaUuid != remoteAvatar) {
                    babyDao.update(
                        existing.copy(
                            avatarMediaUuid = remoteAvatar,
                            avatarPath = existing.avatarPath,
                        ),
                    )
                }
            }
            return true
        }
        val familyId = existing?.familyId ?: familyDao.listAll().firstOrNull()?.id ?: return false
        babyDao.upsert(
            BabyEntity(
                id = existing?.id ?: 0,
                familyId = familyId,
                nickname = payload.string("nickname")
                    ?.let(::limitBabyNicknameInput)
                    ?.trim()
                    ?.ifBlank { null }
                    ?: existing?.nickname
                    ?: "宝宝",
                sex = if ("sex" in payload) payload.string("sex") else existing?.sex,
                birthdayEpochDay = SyncWireMapper.birthdayEpochDay(payload)
                    ?: existing?.birthdayEpochDay
                    ?: return false,
                birthWeightGrams = if ("birth_weight_grams" in payload) {
                    payload.long("birth_weight_grams")?.toInt()
                } else {
                    existing?.birthWeightGrams
                },
                dueDateEpochDay = if (
                    "due_date" in payload || "due_date_epoch_day" in payload
                ) {
                    SyncWireMapper.dueDateEpochDay(payload)
                } else {
                    existing?.dueDateEpochDay
                },
                themeColorArgb = existing?.themeColorArgb ?: 0xFFE6A67A.toInt(),
                sortOrder = existing?.sortOrder ?: 0,
                clientUuid = entity.clientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                avatarMediaUuid = if ("avatar_media_uuid" in payload) {
                    payload.string("avatar_media_uuid")
                } else {
                    existing?.avatarMediaUuid
                },
                avatarPath = existing?.avatarPath,
            ),
        )
        return true
    }

    private suspend fun applyRecord(entity: SyncEntity): Boolean {
        val existing = recordDao.getByClientUuid(entity.clientUuid)
        // Match server LWW for business fields. Equal revisions may still carry
        // a server-owned author metadata acknowledgement from an upgraded NAS.
        if (existing != null && existing.updatedAt > entity.updatedAt) return true
        if (existing != null && existing.updatedAt == entity.updatedAt) {
            runCatching { Json.parseToJsonElement(entity.payloadJson).jsonObject }
                .getOrNull()
                ?.string("created_by_membership_id")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { membershipId ->
                    recordDao.mergeCanonicalAuthor(
                        clientUuid = entity.clientUuid,
                        expectedUpdatedAt = entity.updatedAt,
                        membershipId = membershipId,
                    )
                }
            return true
        }
        // Concurrent local dirty mutation: never clobber the in-flight package or
        // clear syncDirty mid-edit. Creator keeps the local complete revision until
        // push commits; receivers still see the prior published package.
        if (existing != null && existing.syncDirty) return true
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val babyUuid = payload.string("baby_client_uuid") ?: return false
        val baby = babyDao.getByClientUuid(babyUuid) ?: return false
        recordDao.upsert(
            RecordEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = payload.string("type") ?: existing?.type ?: "other",
                timestamp = payload.long("timestamp") ?: entity.updatedAt,
                endTimestamp = payload.long("end_timestamp"),
                note = payload.string("note"),
                createdByUserId = existing?.createdByUserId ?: 1,
                createdByMembershipId = payload.string("created_by_membership_id")
                    ?: existing?.createdByMembershipId
                    ?: "",
                createdByDeviceId = payload.string("created_by_device_id")
                    ?: existing?.createdByDeviceId,
                payloadJson = preserveDeviceLocalPhotos(
                    remotePayloadJson = SyncWireMapper.recordPayloadJson(payload),
                    existingPayloadJson = existing?.payloadJson,
                ),
                schemaVersion = SyncWireMapper.recordSchemaVersion(payload),
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        return true
    }

    private suspend fun mergeCanonicalRecordAuthors(
        authors: List<CanonicalRecordAuthor>,
        expectedUpdatedAt: Map<String, Long>,
    ) {
        authors.distinctBy(CanonicalRecordAuthor::clientUuid).forEach { author ->
            val updatedAt = expectedUpdatedAt[author.clientUuid] ?: return@forEach
            val membershipId = author.createdByMembershipId.trim()
            if (membershipId.isNotEmpty()) {
                recordDao.mergeCanonicalAuthor(author.clientUuid, updatedAt, membershipId)
            }
        }
    }

    /**
     * Keep at most one open sleep per baby after sync apply. Older open
     * intervals are closed at the next open's start and flagged as anomaly so
     * sleep aggregates cannot double-count forever.
     */
    private suspend fun healDuplicateOpenSleeps(babyId: Long) {
        val opens = recordDao.listOpenSleeps(babyId)
        if (opens.size <= 1) return
        // listOpenSleeps is newest-first; keep the latest open, close the rest.
        val keep = opens.first()
        val stale = opens.drop(1).sortedWith(
            compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
        )
        val chain = stale + keep
        val now = clock.nowMillis()
        for (index in 0 until chain.lastIndex) {
            val current = chain[index]
            val nextStart = chain[index + 1].timestamp
            val end = if (nextStart > current.timestamp) {
                nextStart
            } else {
                current.timestamp + 60_000L
            }
            val flagged = withSleepAnomaly(current.payloadJson, current.schemaVersion)
            val updatedAt = if (current.updatedAt == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                maxOf(now, current.updatedAt + 1)
            }
            recordDao.update(
                current.copy(
                    endTimestamp = end,
                    payloadJson = flagged.first,
                    schemaVersion = flagged.second,
                    updatedAt = updatedAt,
                    syncDirty = true,
                ),
            )
        }
    }

    private fun withSleepAnomaly(
        payloadJson: String,
        schemaVersion: Int,
    ): Pair<String, Int> {
        val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
        val sleep = document.payload as? SleepPayload
            ?: return payloadJson to schemaVersion
        val normalized = document.copy(
            payload = sleep.copy(anomaly = true),
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
    }

    /**
     * Download every non-deleted log media blob referenced in this pull page into
     * durable local files first. Any failure aborts the page so cursor stays put
     * and no placeholder media rows are written.
     */
    private suspend fun stageLogMediaDownloads(
        session: SyncSession,
        entities: List<SyncEntity>,
    ): Map<String, String> {
        val staged = linkedMapOf<String, String>()
        val logMedia = entities.filter { entity ->
            if (entity.type != "media" || entity.deletedAt != null) return@filter false
            val kind = runCatching {
                Json.parseToJsonElement(entity.payloadJson).jsonObject.string("kind")
            }.getOrNull()
            kind == "log"
        }
        for (entity in logMedia) {
            val existing = mediaDao.getByClientUuid(entity.clientUuid)
            if (existing != null && existing.updatedAt >= entity.updatedAt &&
                existing.localUri.isNotBlank()
            ) {
                staged[entity.clientUuid] = existing.localUri
                continue
            }
            requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
            val bytes = backend.getMedia(session, entity.clientUuid)
            val localUri = mediaFiles.saveDownloaded(
                entity.clientUuid,
                "log",
                bytes,
                payload.string("mime") ?: existing?.mime,
            )
            staged[entity.clientUuid] = localUri
        }
        return staged
    }

    private suspend fun applyMedia(
        session: SyncSession,
        entity: SyncEntity,
        deletedLocalUris: MutableList<String>,
        stagedLogMediaBytes: Map<String, String> = emptyMap(),
    ): Boolean {
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val existing = mediaDao.getByClientUuid(entity.clientUuid)
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
        val kind = payload.string("kind") ?: existing?.kind ?: return false
        val recordClientUuid = payload.string("record_client_uuid")
        val carePlanClientUuid = payload.string("care_plan_client_uuid")
        // Wire XOR: log media must not claim both owners.
        if (kind == "log" && recordClientUuid != null && carePlanClientUuid != null) {
            return false
        }
        val recordId = recordClientUuid
            ?.let { recordDao.getByClientUuid(it)?.id }
            ?: existing?.recordId?.takeIf { carePlanClientUuid == null }
        val carePlanId = carePlanClientUuid
            ?.let { carePlanDao.getByClientUuid(it)?.id }
            ?: existing?.carePlanId?.takeIf { recordClientUuid == null }
        val referencedBabyId = payload.string("baby_client_uuid")
            ?.let { babyDao.getByClientUuid(it)?.id }
            ?: existing?.babyId
        val babyId = if (kind == "log") null else referencedBabyId
        if (kind == "log" && recordId == null && carePlanId == null) return false
        if (kind == "avatar" && babyId == null) return false
        val stagedLocal = stagedLogMediaBytes[entity.clientUuid]
        // Log media without staged local bytes would be a placeholder — refuse.
        if (kind == "log" && entity.deletedAt == null && stagedLocal.isNullOrBlank() &&
            existing?.localUri.isNullOrBlank()
        ) {
            return false
        }
        mediaDao.upsert(
            MediaAssetEntity(
                id = existing?.id ?: 0,
                recordId = recordId,
                carePlanId = carePlanId,
                clientUuid = entity.clientUuid,
                kind = kind,
                babyId = babyId,
                localUri = stagedLocal ?: existing?.localUri.orEmpty(),
                remoteUri = session.receiptFor(entity.clientUuid),
                mime = payload.string("mime"),
                width = payload.long("width")?.toInt(),
                height = payload.long("height")?.toInt(),
                byteSize = payload.long("byte_size") ?: 0,
                createdAt = existing?.createdAt ?: entity.updatedAt,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        if (entity.deletedAt != null && !existing?.localUri.isNullOrBlank()) {
            deletedLocalUris += existing!!.localUri
            mediaDao.update(
                requireNotNull(mediaDao.getByClientUuid(entity.clientUuid)).copy(localUri = ""),
            )
        }
        return true
    }

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
        resetLocalSyncReceipts(preferences.session.first())
        if (joined.entities.isNotEmpty()) {
            applyRemote(session, joined.entities)
        }
        preferences.saveSession(session)
        cachedSession = session
        currentStatus.value = SyncStatus.Idle
        return session
    }

    private suspend fun persistAuthenticatedSelfMembershipIfMissing(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession {
        if (session.membershipId.isNotBlank()) return session
        val membershipId = members
            .singleOrNull { it.isSelf }
            ?.membershipId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return session
        val updated = session.copy(membershipId = membershipId)
        preferences.saveSession(updated)
        cachedSession = updated
        return updated
    }

    private suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean = false,
    ) {
        babyDao.markAllPendingSync()
        recordDao.markAllPendingSync()
        carePlanDao.markAllPendingSync()
        mediaDao.listAllIncludingDeleted().forEach { media ->
            val hasCurrentReceipt = previous.isJoined && media.hasReceiptFor(previous)
            val preserveCurrentReceipt = !invalidateCurrentReceipts ||
                (previous.role == FamilyRole.Member && media.kind == "avatar")
            val nextReceipt = when {
                media.remoteUri.isNullOrBlank() -> null
                media.remoteUri!!.startsWith(RECEIPT_PREFIX) -> {
                    if (hasCurrentReceipt && !preserveCurrentReceipt) null else media.remoteUri
                }
                !previous.isJoined -> null
                !preserveCurrentReceipt -> null
                else -> previous.receiptFor(media.clientUuid)
            }
            mediaDao.update(
                media.copy(
                    remoteUri = nextReceipt,
                    syncDirty = true,
                ),
            )
        }
    }

    private suspend fun recoverFullResync(
        previous: SyncSession,
        mediaEditGuard: LocalMediaEditGuard,
    ): SyncSession {
        resetLocalSyncReceipts(
            previous = previous,
            invalidateCurrentReceipts = true,
        )
        preferences.updateCursor(0, generation = "")
        var current = preferences.session.first()
        current = pullAllPages(
            initial = current,
            reconcileMemberAvatars = current.role == FamilyRole.Member,
            deferCursorUntilComplete = true,
            mediaEditGuard = mediaEditGuard,
        )
        captureLocalChanges(current)
        pushPending(current)
        current = preferences.session.first()
        return pullAllPages(
            initial = current,
            mediaEditGuard = mediaEditGuard,
        )
    }

    /**
     * Applies one bounded server page at a time. Normal incremental pulls
     * durably advance only after that page (including media materialization)
     * succeeds; the authoritative pre-push phase of full resync publishes its
     * cursor only after every page succeeds. `hasMore` is an additive wire
     * field, so an older unpaged server remains compatible only when its one
     * page is below the current server page-capacity signal.
     */
    private suspend fun pullAllPages(
        initial: SyncSession,
        reconcileMemberAvatars: Boolean = false,
        deferCursorUntilComplete: Boolean = false,
        mediaEditGuard: LocalMediaEditGuard,
    ): SyncSession {
        var current = initial
        val authoritativeMemberAvatarPointers = if (reconcileMemberAvatars) {
            linkedMapOf<String, String?>()
        } else {
            null
        }
        var pageCount = 0
        do {
            require(pageCount < MAX_PULL_PAGE_COUNT) {
                "家庭服务器同步超过 $MAX_PULL_PAGE_COUNT 页上限，请稍后重试"
            }
            pageCount++
            requireAllowed(policy.evaluate(current.homeLanConfig, foregroundState.isForeground()))
            val pulled = backend.pull(current)
            require(pulled.cursor >= current.pullCursor) {
                "家庭服务器返回了倒退的同步 cursor"
            }
            require(
                pulled.hasMore != null || pulled.entities.size < SYNC_PULL_PAGE_ENTITY_LIMIT,
            ) {
                "家庭服务器返回了满页数据但缺少 has_more，无法确认同步已完成"
            }
            if (pulled.hasMore == true) {
                require(pulled.cursor > current.pullCursor) {
                    "家庭服务器分页 cursor 未推进"
                }
            }
            if (current.pullGeneration.isNotBlank() && pulled.generation.isNotBlank()) {
                require(pulled.generation == current.pullGeneration) {
                    "家庭服务器在分页期间切换了同步代际"
                }
            }
            authoritativeMemberAvatarPointers?.let { pointers ->
                pulled.entities
                    .filter { it.type == "baby" }
                    .forEach { entity ->
                        if (entity.deletedAt == null) {
                            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                            pointers[entity.clientUuid] = payload.string("avatar_media_uuid")
                        } else {
                            pointers.remove(entity.clientUuid)
                        }
                    }
            }
            applyRemote(
                current,
                pulled.entities,
                mediaEditGuard = mediaEditGuard,
            )
            downloadMissingMedia(current, mediaEditGuard)
            val nextGeneration = pulled.generation.ifBlank { current.pullGeneration }
            if (!deferCursorUntilComplete) {
                preferences.updateCursor(pulled.cursor, nextGeneration)
                current = preferences.session.first()
            } else {
                // The authoritative pre-push phase of full resync must not
                // publish a partial cursor. Otherwise a restart can push the
                // re-queued local replica before the remaining server pages
                // have been applied.
                current = current.copy(
                    pullCursor = pulled.cursor,
                    pullGeneration = nextGeneration,
                )
            }
        } while (pulled.hasMore == true)
        authoritativeMemberAvatarPointers?.let { pointers ->
            transactionRunner.run {
                reconcileMemberAvatarAuthority(pointers, mediaEditGuard)
            }
        }
        if (deferCursorUntilComplete) {
            preferences.updateCursor(current.pullCursor, current.pullGeneration)
            current = preferences.session.first()
        }
        return current
    }

    private suspend fun reconcileMemberAvatarAuthority(
        serverPointers: Map<String, String?>,
        mediaEditGuard: LocalMediaEditGuard?,
    ) {
        babyDao.listAllIncludingDeleted().forEach { baby ->
            val authoritativePointer = serverPointers[baby.clientUuid]
            if (baby.avatarMediaUuid != authoritativePointer) {
                babyDao.updateAvatarReplica(
                    clientUuid = baby.clientUuid,
                    avatarMediaUuid = authoritativePointer,
                    avatarPath = null,
                )
                mediaEditGuard?.babyRefreshed(baby.clientUuid, null)
            }
        }
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

    private suspend fun captureLocalChanges(session: SyncSession) {
        val babies = babyDao.listPendingSync()
        val records = recordDao.listPendingSync()
        val carePlans = carePlanDao.listPendingSync()
        val customItems = customItemDao.listPendingSync()
        val fulfillmentCandidates = fulfillmentCandidateDao.listPendingSync()
        materializeLocalMedia(
            includeAvatars = session.role != FamilyRole.Member,
            babies = babies,
            records = records,
        )
        val directlyChangedMedia = mediaDao.listPendingSync()
        val referencedMedia = buildList {
            babies.forEach { baby ->
                mediaDao.activeAvatarForBaby(baby.id)?.let(::add)
            }
            records.forEach { record ->
                addAll(mediaDao.listForRecord(record.id))
            }
            carePlans.forEach { plan ->
                addAll(mediaDao.listForCarePlan(plan.id))
            }
        }
        val media = ensurePortableMediaUuids(
            (directlyChangedMedia + referencedMedia).distinctBy(MediaAssetEntity::id),
        )
        babies.forEach { baby ->
            val eligibleAvatars = media.filter {
                it.kind == "avatar" &&
                    it.babyId == baby.id &&
                    it.deletedAt == null &&
                    (session.role != FamilyRole.Member || it.hasReceiptFor(session))
            }
            val avatarMediaUuid = baby.avatarMediaUuid
                ?.let { pointer ->
                    eligibleAvatars.firstOrNull { it.clientUuid == pointer }?.clientUuid
                }
                ?: if (session.role == FamilyRole.Member) {
                    null
                } else {
                    eligibleAvatars
                        .maxWithOrNull(
                            compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id },
                        )
                        ?.clientUuid
                }
            enqueue(
                session,
                SyncWireMapper.baby(
                    baby,
                    avatarMediaUuid,
                ),
            )
        }
        customItems.forEach { item ->
            enqueue(session, SyncWireMapper.customItem(item))
        }
        // Outbox materialization only; atomic commit order is record packages →
        // care_plan packages → fulfillment_candidate residual (see pushOutboxBatch).
        records.forEach { record ->
            val babyUuid = babyDao.getIncludingDeleted(record.babyId)?.clientUuid
                ?: return@forEach
            enqueue(
                session,
                SyncWireMapper.record(
                    record,
                    babyUuid,
                    record.createdByDeviceId ?: session.deviceId,
                    includeMembershipAuthor = policy.supportsRecordMembershipAuthor,
                ),
            )
        }
        carePlans.forEach { plan ->
            val babyUuid = babyDao.getIncludingDeleted(plan.babyId)?.clientUuid
                ?: return@forEach
            val customItemUuid = plan.customItemId
                ?.let { customItemDao.getById(it)?.clientUuid }
            // Custom-item plans need a definition on the wire path; if the def is
            // still local-only, capture it via customItems dirty (dependency expand).
            enqueue(
                session,
                SyncWireMapper.carePlan(
                    plan,
                    babyUuid,
                    customItemUuid,
                ),
            )
        }
        fulfillmentCandidates.forEach { candidate ->
            enqueue(session, SyncWireMapper.fulfillmentCandidate(candidate))
        }
        media.forEach { asset ->
            if (session.role == FamilyRole.Member && asset.kind == "avatar") {
                return@forEach
            }
            val recordUuid = asset.recordId
                ?.let { recordDao.getIncludingDeleted(it)?.clientUuid }
            val carePlanUuid = asset.carePlanId
                ?.let { carePlanDao.get(it)?.clientUuid }
            val babyUuid = asset.babyId
                ?.let { babyDao.getIncludingDeleted(it)?.clientUuid }
            if (asset.kind == "log") {
                // XOR ownership: record OR care_plan, never both, never neither.
                if (recordUuid == null && carePlanUuid == null) return@forEach
                if (recordUuid != null && carePlanUuid != null) return@forEach
            }
            if (asset.kind == "avatar" && babyUuid == null) {
                return@forEach
            }
            enqueue(
                session,
                SyncWireMapper.media(
                    asset,
                    recordClientUuid = recordUuid,
                    babyClientUuid = babyUuid,
                    carePlanClientUuid = carePlanUuid,
                ),
            )
        }
    }

    private suspend fun enqueue(session: SyncSession, entity: SyncEntity) {
        outboxDao.enqueue(
            com.lezi.babylog.core.database.OutboxEntity(
                familyId = session.familyId,
                entityType = entity.type,
                clientUuid = entity.clientUuid,
                payloadJson = entity.payloadJson,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
            ),
        )
    }

    private suspend fun materializeLocalMedia(
        includeAvatars: Boolean,
        babies: List<BabyEntity>,
        records: List<RecordEntity>,
    ) {
        if (includeAvatars) {
            babies.forEach { snapshot ->
                val snapshotPath = snapshot.avatarPath?.takeIf { it.isNotBlank() }
                val inspected = snapshotPath?.let { mediaFiles.inspect(it) }
                transactionRunner.run {
                    val baby = babyDao.getIncludingDeleted(snapshot.id) ?: return@run
                    if (
                        baby.updatedAt != snapshot.updatedAt ||
                        baby.avatarPath != snapshot.avatarPath
                    ) {
                        return@run
                    }
                    val existing = mediaDao.activeAvatarForBaby(baby.id)
                    val path = baby.avatarPath?.takeIf { it.isNotBlank() }
                    if (path == null) {
                        if (existing != null) {
                            mediaDao.update(
                                existing.copy(
                                    updatedAt = baby.updatedAt,
                                    deletedAt = baby.updatedAt,
                                    syncDirty = true,
                                ),
                            )
                        }
                        if (baby.avatarMediaUuid != null) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = null,
                            )
                        }
                        return@run
                    }
                    if (existing?.localUri == path) {
                        if (baby.avatarMediaUuid != existing.clientUuid) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = existing.clientUuid,
                            )
                        }
                        return@run
                    }
                    val info = inspected ?: return@run
                    if (existing != null) {
                        mediaDao.update(
                            existing.copy(
                                updatedAt = baby.updatedAt,
                                deletedAt = baby.updatedAt,
                                syncDirty = true,
                            ),
                        )
                    }
                    val avatarMediaUuid = UUID.randomUUID().toString()
                    mediaDao.upsert(
                        MediaAssetEntity(
                            clientUuid = avatarMediaUuid,
                            kind = "avatar",
                            babyId = baby.id,
                            localUri = path,
                            mime = info.mime,
                            width = info.width,
                            height = info.height,
                            byteSize = info.byteSize,
                            createdAt = baby.updatedAt,
                            updatedAt = baby.updatedAt,
                        ),
                    )
                    check(
                        babyDao.updateAvatarMediaForLocalSnapshot(
                            id = baby.id,
                            expectedUpdatedAt = baby.updatedAt,
                            expectedAvatarPath = baby.avatarPath,
                            avatarMediaUuid = avatarMediaUuid,
                        ) == 1,
                    ) {
                        "宝宝头像在媒体快照期间发生变化"
                    }
                }
            }
        }
        records.forEach { snapshot ->
            val snapshotPaths = if (snapshot.deletedAt == null) {
                localPhotoPaths(snapshot.payloadJson)
            } else {
                emptySet()
            }
            val inspected = snapshotPaths.associateWith { path ->
                mediaFiles.inspect(path)
            }
            transactionRunner.run {
                val record = recordDao.getIncludingDeleted(snapshot.id) ?: return@run
                if (
                    record.updatedAt != snapshot.updatedAt ||
                    record.deletedAt != snapshot.deletedAt ||
                    record.payloadJson != snapshot.payloadJson
                ) {
                    return@run
                }
                val paths = if (record.deletedAt == null) {
                    localPhotoPaths(record.payloadJson)
                } else {
                    emptySet()
                }
                val existing = mediaDao.listForRecord(record.id)
                    .map { asset ->
                        if (asset.kind == "log" && asset.babyId != null) {
                            asset.copy(babyId = null).also { mediaDao.update(it) }
                        } else {
                            asset
                        }
                    }
                paths.forEach { path ->
                    if (existing.any { it.localUri == path && it.deletedAt == null }) {
                        return@forEach
                    }
                    val info = inspected[path] ?: return@forEach
                    mediaDao.upsert(
                        MediaAssetEntity(
                            recordId = record.id,
                            clientUuid = UUID.randomUUID().toString(),
                            kind = "log",
                            babyId = null,
                            localUri = path,
                            mime = info.mime,
                            width = info.width,
                            height = info.height,
                            byteSize = info.byteSize,
                            createdAt = record.updatedAt,
                            updatedAt = record.updatedAt,
                        ),
                    )
                }
                existing.filter {
                    it.deletedAt == null && it.localUri !in paths
                }.forEach {
                    mediaDao.update(
                        it.copy(
                            updatedAt = record.updatedAt,
                            deletedAt = record.updatedAt,
                            syncDirty = true,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun ensurePortableMediaUuids(
        candidates: List<MediaAssetEntity>,
    ): List<MediaAssetEntity> {
        val portable = mutableListOf<MediaAssetEntity>()
        candidates.forEach { media ->
            if (runCatching { UUID.fromString(media.clientUuid) }.isSuccess) {
                portable += media
            } else {
                val updated = media.copy(clientUuid = UUID.randomUUID().toString())
                mediaDao.update(updated)
                portable += updated
            }
        }
        return portable
    }

    private suspend fun downloadMissingMedia(
        session: SyncSession,
        mediaEditGuard: LocalMediaEditGuard?,
    ) {
        mediaDao.listMissingLocalBytes()
            .filter { it.hasReceiptFor(session) }
            .forEach { media ->
                requireAllowed(policy.evaluate(session.homeLanConfig, foregroundState.isForeground()))
                // A 404 is an isolated half-upload and stays queued for retry.
                // Auth, server, and transport failures fail the whole cycle so
                // they cannot be reported as a successful sync.
                val bytes = try {
                    backend.getMedia(session, media.clientUuid)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SyncHttpException) {
                    if (error.statusCode == 404) return@forEach
                    throw error
                }
                val localUri = try {
                    mediaFiles.saveDownloaded(
                        media.clientUuid,
                        media.kind,
                        bytes,
                        media.mime,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@forEach
                }
                transactionRunner.run {
                    val current = mediaDao.getByClientUuid(media.clientUuid) ?: return@run
                    mediaDao.update(current.copy(localUri = localUri))
                    current.recordId?.let { recordId ->
                        refreshRecordPhotoPaths(recordId, mediaEditGuard)
                    }
                    current.carePlanId?.let { planId ->
                        refreshCarePlanPhotoPaths(planId, mediaEditGuard)
                    }
                    current.babyId?.let { babyId ->
                        refreshBabyAvatar(babyId, mediaEditGuard)
                    }
                }
            }
    }

    private suspend fun refreshRecordPhotoPaths(
        recordId: Long,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val record = recordDao.getIncludingDeleted(recordId) ?: return
        if (mediaEditGuard?.canRefresh(record) == false) return
        val photos = mediaDao.listActiveForRecord(recordId)
            .map(MediaAssetEntity::localUri)
            .filter(String::isNotBlank)
        val payload = runCatching { Json.parseToJsonElement(record.payloadJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val next = JsonObject(
            if (photos.isEmpty()) {
                payload - "photos"
            } else {
                payload + (
                    "photos" to JsonArray(photos.map(::JsonPrimitive))
                )
            },
        ).toString()
        if (next != record.payloadJson) {
            recordDao.updatePayloadReplica(
                id = record.id,
                expectedPayloadJson = record.payloadJson,
                payloadJson = next,
            )
        }
        mediaEditGuard?.recordRefreshed(record.clientUuid, photos.toSet())
    }

    private suspend fun refreshCarePlanPhotoPaths(
        carePlanId: Long,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val plan = carePlanDao.get(carePlanId) ?: return
        if (mediaEditGuard?.canRefreshCarePlan(plan) == false) return
        val photos = mediaDao.listActiveForCarePlan(carePlanId)
            .map(MediaAssetEntity::localUri)
            .filter(String::isNotBlank)
        val payload = runCatching { Json.parseToJsonElement(plan.payloadJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val next = JsonObject(
            if (photos.isEmpty()) {
                payload - "photos"
            } else {
                payload + (
                    "photos" to JsonArray(photos.map(::JsonPrimitive))
                )
            },
        ).toString()
        if (next != plan.payloadJson) {
            carePlanDao.updatePayloadReplica(
                id = plan.id,
                expectedPayloadJson = plan.payloadJson,
                payloadJson = next,
            )
        }
        mediaEditGuard?.carePlanRefreshed(plan.clientUuid, photos.toSet())
    }

    private suspend fun refreshBabyAvatar(
        babyId: Long,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val baby = babyDao.getIncludingDeleted(babyId) ?: return
        if (mediaEditGuard?.canRefresh(baby) == false) return
        val avatarPath = baby.avatarMediaUuid
            ?.let { mediaDao.getByClientUuid(it) }
            ?.takeIf {
                it.kind == "avatar" &&
                    it.babyId == babyId &&
                    it.deletedAt == null
            }
            ?.localUri
            ?.takeIf(String::isNotBlank)
        if (baby.avatarPath != avatarPath) {
            babyDao.updateAvatarPathForReplica(
                id = baby.id,
                expectedAvatarMediaUuid = baby.avatarMediaUuid,
                avatarPath = avatarPath,
            )
        }
        mediaEditGuard?.babyRefreshed(baby.clientUuid, avatarPath)
    }

    private suspend fun captureLocalMediaEditGuard(): LocalMediaEditGuard =
        LocalMediaEditGuard(
            recordPhotos = recordDao.listAllIncludingDeleted()
                .associate { it.clientUuid to localPhotoPaths(it.payloadJson) }
                .toMutableMap(),
            carePlanPhotos = carePlanDao.listAllIncludingDeleted()
                .associate { it.clientUuid to localPhotoPaths(it.payloadJson) }
                .toMutableMap(),
            babyAvatarPaths = babyDao.listAllIncludingDeleted()
                .associate { it.clientUuid to it.avatarPath }
                .toMutableMap(),
        )

    private suspend fun normalizeLegacyOutboxPayload(type: String, raw: String): String {
        if (type != "record") return raw
        var payload = Json.parseToJsonElement(raw).jsonObject
        if (payload["baby_client_uuid"] == null) {
            val babyId = payload["baby_id"]?.jsonPrimitive?.longOrNull
            val babyUuid = babyId?.let { babyDao.getIncludingDeleted(it)?.clientUuid }
            require(!babyUuid.isNullOrBlank()) { "记录缺少宝宝同步标识" }
            payload = JsonObject(payload - "baby_id" + ("baby_client_uuid" to JsonPrimitive(babyUuid)))
        }
        val inner = payload["payload_json"]
        if (inner is JsonPrimitive && inner.isString) {
            val parsed = runCatching { Json.parseToJsonElement(inner.content).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            payload = JsonObject(payload + ("payload_json" to JsonObject(parsed - "photos")))
        }
        return payload.toString()
    }

    private fun recordPayloadForServerCapability(
        durablePayload: String,
        mappedPayload: String,
        includeMembershipAuthor: Boolean,
    ): String {
        val durable = Json.parseToJsonElement(durablePayload).jsonObject
        val mapped = Json.parseToJsonElement(mappedPayload).jsonObject
        val author = mapped["created_by_membership_id"]
        return JsonObject(
            if (includeMembershipAuthor && author != null) {
                durable + ("created_by_membership_id" to author)
            } else {
                durable - "created_by_membership_id"
            },
        ).toString()
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

private fun SyncHttpException.fullResyncCursorOrNull(): Long? {
    if (statusCode != 409) return null
    val detail = runCatching {
        Json.parseToJsonElement(responseBody).jsonObject["detail"]?.jsonObject
    }.getOrNull() ?: return null
    if (detail.string("code") !in setOf("cursor_ahead", "generation_changed")) return null
    if (detail.string("action") != "full_resync") return null
    return detail.long("reset_cursor")?.takeIf { it == 0L }
}

private fun SyncSession.receiptFor(clientUuid: String): String {
    val namespace = UUID.nameUUIDFromBytes(
        "${baseUrl.trimEnd('/')}\n$familyId".toByteArray(Charsets.UTF_8),
    )
    return "$RECEIPT_PREFIX$namespace:$clientUuid"
}

private fun MediaAssetEntity.hasReceiptFor(session: SyncSession): Boolean =
    remoteUri == clientUuid || remoteUri == session.receiptFor(clientUuid)

private class LocalMediaEditGuard(
    private val recordPhotos: MutableMap<String, Set<String>>,
    private val carePlanPhotos: MutableMap<String, Set<String>> = mutableMapOf(),
    private val babyAvatarPaths: MutableMap<String, String?>,
) {
    fun canRefresh(record: RecordEntity): Boolean =
        record.clientUuid !in recordPhotos ||
            recordPhotos[record.clientUuid] == localPhotoPaths(record.payloadJson)

    fun canRefreshCarePlan(plan: CarePlanEntity): Boolean =
        plan.clientUuid !in carePlanPhotos ||
            carePlanPhotos[plan.clientUuid] == localPhotoPaths(plan.payloadJson)

    fun canRefresh(baby: BabyEntity): Boolean =
        baby.clientUuid !in babyAvatarPaths ||
            babyAvatarPaths[baby.clientUuid] == baby.avatarPath

    fun recordRefreshed(clientUuid: String, photos: Set<String>) {
        recordPhotos[clientUuid] = photos
    }

    fun carePlanRefreshed(clientUuid: String, photos: Set<String>) {
        carePlanPhotos[clientUuid] = photos
    }

    fun babyRefreshed(clientUuid: String, avatarPath: String?) {
        babyAvatarPaths[clientUuid] = avatarPath
    }
}

private fun preserveDeviceLocalPhotos(
    remotePayloadJson: String,
    existingPayloadJson: String?,
): String {
    val photos = existingPayloadJson
        ?.let { raw ->
            runCatching {
                Json.parseToJsonElement(raw).jsonObject["photos"] as? JsonArray
            }.getOrNull()
        }
        ?: return remotePayloadJson
    val remote = runCatching {
        Json.parseToJsonElement(remotePayloadJson).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))
    return JsonObject(remote + ("photos" to photos)).toString()
}

private fun localPhotoPaths(raw: String): Set<String> =
    runCatching {
        val photos = Json.parseToJsonElement(raw).jsonObject["photos"] as? JsonArray
        photos.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter(String::isNotBlank)
            .toSet()
    }.getOrDefault(emptySet())

private val ENTITY_ORDER = listOf(
    "baby",
    "custom_item",
    "record",
    "care_plan",
    "media",
    "fulfillment_candidate",
)
private const val PUSH_ROOT_BATCH_SIZE = 200
private const val MAX_PUSH_BATCH_SIZE = 1_000
/** Normal home libraries are far smaller; reaching this many pages is anomalous. */
private const val MAX_PULL_PAGE_COUNT = 500
private const val SYNC_PULL_PAGE_ENTITY_LIMIT = 200
private const val OUTBOX_DELETE_CHUNK_SIZE = 400
private const val RECEIPT_PREFIX = "lezi-sync:"

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
