package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SyncEntity(
    val type: String,
    val clientUuid: String,
    val payloadJson: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val rev: Long = 0,
)

data class PullResult(
    val entities: List<SyncEntity>,
    val cursor: Long,
    val generation: String = "",
)
data class JoinResult(
    val familyId: String,
    val token: String,
    val role: FamilyRole,
    val entities: List<SyncEntity> = emptyList(),
    val cursor: Long = 0,
    val generation: String = "",
)

interface SyncBackend {
    suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
    ): JoinResult
    suspend fun push(session: SyncSession, entities: List<SyncEntity>): Int
    suspend fun pull(session: SyncSession): PullResult
    suspend fun invite(session: SyncSession): Invite
    suspend fun join(baseUrl: String, code: String, deviceId: String): JoinResult
    suspend fun leave(session: SyncSession)
    suspend fun deleteFamily(session: SyncSession)
    suspend fun putMedia(session: SyncSession, clientUuid: String, bytes: ByteArray, mime: String?)
    suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray
}

/**
 * Deterministic in-memory backend for coordinator and dual-client tests.
 *
 * Mirrors core lezi-sync push rules used by client tests: LWW with existing
 * win on equal `updatedAt`, member avatar ACL, immutable media association,
 * and basic baby/record/media reference checks.
 */
class FakeSyncBackend : SyncBackend {
    private data class Row(val entity: SyncEntity, val rev: Long)
    private val rows = mutableMapOf<String, MutableMap<String, Row>>()
    private val mediaBytes = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val invites = mutableMapOf<String, Pair<String, Long>>()
    private var revision = 0L

    suspend fun push(familyId: String, deviceId: String, entities: List<SyncEntity>): Result<Int> =
        runCatching { pushRows(familyId, entities, FamilyRole.Owner) }

    suspend fun pull(familyId: String, cursor: Long): Result<PullResult> =
        runCatching { pullRows(familyId, cursor) }

    suspend fun invite(familyId: String): Result<Invite> = runCatching {
        val invite = Invite("TEST${invites.size + 1}", System.currentTimeMillis() + 86_400_000)
        invites[invite.code] = familyId to invite.expiresAt
        invite
    }

    suspend fun join(code: String, deviceId: String): Result<JoinResult> = runCatching {
        val invite = invites[code.uppercase()] ?: error("invalid code")
        require(invite.second >= System.currentTimeMillis()) { "expired" }
        val pull = pullRows(invite.first, 0)
        JoinResult(invite.first, "fake-token", FamilyRole.Member, pull.entities, pull.cursor)
    }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
    ) =
        JoinResult("family-${rows.size + 1}", "owner-token", FamilyRole.Owner)

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>) =
        pushRows(session.familyId, entities, session.role)

    override suspend fun pull(session: SyncSession) = pullRows(session.familyId, session.pullCursor)
    override suspend fun invite(session: SyncSession) = invite(session.familyId).getOrThrow()
    override suspend fun join(baseUrl: String, code: String, deviceId: String) =
        join(code, deviceId).getOrThrow()
    override suspend fun leave(session: SyncSession) = Unit
    override suspend fun deleteFamily(session: SyncSession) {
        rows.remove(session.familyId)
        mediaBytes.remove(session.familyId)
    }

    override suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) {
        val existing = rows[session.familyId]?.get("media:$clientUuid")?.entity
        val kind = existing?.let(::mediaKind)
        if (session.role == FamilyRole.Member && kind == "avatar") {
            throw SyncHttpException(403, "Only owner may change avatar")
        }
        mediaBytes.getOrPut(session.familyId) { mutableMapOf() }[clientUuid] = bytes
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        mediaBytes[session.familyId]?.get(clientUuid) ?: byteArrayOf()

    private fun pushRows(familyId: String, entities: List<SyncEntity>, role: FamilyRole): Int {
        val family = rows.getOrPut(familyId) { mutableMapOf() }
        // Apply LWW first so validation sees the same effective set as the server.
        val winners = LinkedHashMap<String, SyncEntity>()
        entities.forEach { entity ->
            val key = "${entity.type}:${entity.clientUuid}"
            val existing = family[key]
            if (existing != null && existing.entity.updatedAt >= entity.updatedAt) return@forEach
            val previous = winners[key]
            if (previous == null || entity.updatedAt > previous.updatedAt) {
                winners[key] = entity
            }
        }
        validatePush(role, family, winners.values.toList())
        var applied = 0
        winners.values.forEach { entity ->
            val key = "${entity.type}:${entity.clientUuid}"
            revision++
            family[key] = Row(entity, revision)
            applied++
        }
        return applied
    }

    private fun validatePush(
        role: FamilyRole,
        family: Map<String, Row>,
        winners: List<SyncEntity>,
    ) {
        val babyIds = family.keys
            .filter { it.startsWith("baby:") }
            .map { it.removePrefix("baby:") }
            .toMutableSet()
        babyIds += winners.filter { it.type == "baby" }.map { it.clientUuid }

        val recordIds = family.keys
            .filter { it.startsWith("record:") }
            .map { it.removePrefix("record:") }
            .toMutableSet()
        recordIds += winners.filter { it.type == "record" }.map { it.clientUuid }

        winners.forEach { entity ->
            when (entity.type) {
                "record" -> {
                    val babyUuid = payloadString(entity, "baby_client_uuid")
                        ?: throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    if (babyUuid !in babyIds) {
                        throw SyncHttpException(409, "record baby_client_uuid does not exist")
                    }
                }
                "media" -> {
                    val kind = mediaKind(entity)
                    val existing = family["media:${entity.clientUuid}"]?.entity
                    val existingKind = existing?.let(::mediaKind)
                    if (role == FamilyRole.Member && (kind == "avatar" || existingKind == "avatar")) {
                        throw SyncHttpException(403, "Only owner may change avatar")
                    }
                    if (existing != null && mediaAssociation(existing) != mediaAssociation(entity)) {
                        throw SyncHttpException(409, "Media kind and association are immutable")
                    }
                    when (kind) {
                        "avatar" -> {
                            val babyUuid = payloadString(entity, "baby_client_uuid")
                            if (babyUuid == null || babyUuid !in babyIds) {
                                throw SyncHttpException(
                                    409,
                                    "avatar baby_client_uuid does not exist",
                                )
                            }
                        }
                        "log" -> {
                            val recordUuid = payloadString(entity, "record_client_uuid")
                            if (recordUuid == null || recordUuid !in recordIds) {
                                throw SyncHttpException(
                                    409,
                                    "log record_client_uuid does not exist",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mediaKind(entity: SyncEntity): String? = payloadString(entity, "kind")

    private fun mediaAssociation(entity: SyncEntity): Triple<String?, String?, String?> =
        Triple(
            mediaKind(entity),
            payloadString(entity, "record_client_uuid"),
            payloadString(entity, "baby_client_uuid"),
        )

    private fun payloadString(entity: SyncEntity, key: String): String? =
        runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject[key]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

    private fun pullRows(familyId: String, cursor: Long): PullResult {
        val changed = rows[familyId].orEmpty().values.filter { it.rev > cursor }.sortedBy { it.rev }
        return PullResult(
            changed.map { it.entity.copy(rev = it.rev) },
            changed.lastOrNull()?.rev ?: cursor,
        )
    }
}

class HttpSyncBackend @Inject constructor() : SyncBackend {
    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
    ) =
        post(baseUrl, "/v1/family/create", null, buildJsonObject {
            put("device_id", deviceId)
            put("create_request_id", createRequestId)
            displayName?.let { put("display_name", it) }
        }).toJoinResult()

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>): Int =
        post(session.baseUrl, "/v1/push", session.familyToken, buildJsonObject {
            put("device_id", session.deviceId)
            session.pullGeneration.takeIf(String::isNotBlank)?.let {
                put("generation", it)
            }
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
        })["applied"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0

    override suspend fun pull(session: SyncSession): PullResult {
        val generation = session.pullGeneration
            .takeIf(String::isNotBlank)
            ?.let { "&generation=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }
            .orEmpty()
        val json = get(
            session.baseUrl,
            "/v1/pull?cursor=${session.pullCursor}$generation",
            session.familyToken,
        )
        return PullResult(
            entities = json.entities(),
            cursor = json["cursor"]?.jsonPrimitive?.longOrNull ?: session.pullCursor,
            generation = json["generation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    override suspend fun invite(session: SyncSession): Invite {
        val json = post(session.baseUrl, "/v1/invite", session.familyToken, buildJsonObject {})
        return inviteFromWire(json)
    }

    override suspend fun join(baseUrl: String, code: String, deviceId: String): JoinResult =
        post(baseUrl, "/v1/join", null, buildJsonObject {
            put("code", code)
            put("device_id", deviceId)
        }).toJoinResult()

    override suspend fun leave(session: SyncSession) {
        post(session.baseUrl, "/v1/leave", session.familyToken, buildJsonObject {})
    }

    override suspend fun deleteFamily(session: SyncSession) {
        post(session.baseUrl, "/v1/family/delete", session.familyToken, buildJsonObject {})
    }

    override suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) = requestBytes(session.baseUrl, "/v1/media/$clientUuid", "PUT", session.familyToken, bytes, mime).let { Unit }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        requestBytes(session.baseUrl, "/v1/media/$clientUuid", "GET", session.familyToken)

    private suspend fun post(base: String, path: String, token: String?, body: JsonObject): JsonObject =
        requestJson(base, path, "POST", token, body)

    private suspend fun get(base: String, path: String, token: String?): JsonObject =
        requestJson(base, path, "GET", token, null)

    private suspend fun requestJson(
        base: String,
        path: String,
        method: String,
        token: String?,
        body: JsonObject?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val connection = open(base, path, method, token)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        }
        readResponse(connection).let { Json.parseToJsonElement(it).jsonObject }
    }

    private suspend fun requestBytes(
        base: String,
        path: String,
        method: String,
        token: String,
        body: ByteArray? = null,
        mime: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(base, path, method, token)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", mime ?: "application/octet-stream")
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
        connection.disconnect()
        if (code !in 200..299) {
            throw SyncHttpException(code, bytes.toString(Charsets.UTF_8))
        }
        bytes
    }

    private fun open(base: String, path: String, method: String, token: String?): HttpURLConnection =
        (URL("${base.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText) }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw SyncHttpException(code, text)
        return text.ifBlank { "{}" }
    }
}

internal fun inviteFromWire(json: JsonObject): Invite {
    val expiresAtSeconds = requireNotNull(
        json["expires_at"]?.jsonPrimitive?.longOrNull,
    ) { "邀请响应缺少 expires_at" }
    require(expiresAtSeconds in 0..Long.MAX_VALUE / MILLIS_PER_SECOND) {
        "邀请失效时间无效"
    }
    return Invite(
        code = requireNotNull(json["code"]?.jsonPrimitive?.contentOrNull) {
            "邀请响应缺少 code"
        },
        expiresAt = expiresAtSeconds * MILLIS_PER_SECOND,
    )
}

@Singleton
class RealSyncPort @Inject constructor(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val policy: HomeNetworkPolicy,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val familyDao: FamilyDao,
    private val clock: PolicyClock,
    private val foregroundState: ForegroundState,
    private val mediaFiles: SyncMediaFileStore,
    private val transactionRunner: DatabaseTransactionRunner,
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
            val normalized = baseUrl.trim().trimEnd('/')
            require(!previous.isJoined || previous.baseUrl == normalized) {
                "请先退出当前家庭，再修改服务器地址"
            }
            if (previous.baseUrl != normalized) {
                resetLocalSyncReceipts(previous)
            }
            preferences.saveServer(baseUrl)
            cachedSession = preferences.session.first()
            currentStatus.value = if (cachedSession.isJoined) SyncStatus.Idle else SyncStatus.Disabled
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun createFamily(displayName: String?) = gatedWithoutSession { baseUrl ->
        val deviceId = preferences.ensureDeviceId()
        val createRequestId = preferences.ensureCreateRequestId()
        backend.create(baseUrl, deviceId, displayName, createRequestId).let { joined ->
            persistJoin(baseUrl, deviceId, joined).also {
                preferences.clearCreateRequestId()
                requestSync(SyncTrigger.LocalWrite)
            }
        }
    }

    override suspend fun joinWithPayload(payload: String) = runCatching {
        syncMutex.withLock {
            require(!preferences.session.first().isJoined) {
                "请先退出当前家庭，再加入新的家庭"
            }
            val decoded = InvitePayloadCodec.decode(payload)
            val baseUrl = decoded.baseUrl.ifBlank { preferences.session.first().baseUrl }
            if (baseUrl.isBlank()) requireAllowed(HomeNetworkDecision.MissingServer)
            val decision = policy.evaluate(baseUrl, foregroundState.isForeground())
            requireAllowed(decision)
            val deviceId = preferences.ensureDeviceId()
            val joined = backend.join(baseUrl, decoded.code, deviceId)
            persistJoin(baseUrl, deviceId, joined).also {
                requestSync(SyncTrigger.PullToRefresh)
            }
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun joinWithCode(code: String): Result<SyncSession> =
        joinWithPayload(code)

    override suspend fun createInvite(familyId: String): Result<Invite> = withAllowedSession {
        require(it.role == FamilyRole.Owner) { "仅家庭管理员可生成邀请" }
        backend.invite(it)
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
            val decision = policy.evaluate(session.baseUrl, foregroundState.isForeground())
            requireAllowed(decision)
            currentStatus.value = SyncStatus.Syncing
            val plan = SyncPlan.forTrigger(trigger)
            var current = session
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
                requireAllowed(
                    policy.evaluate(current.baseUrl, foregroundState.isForeground()),
                )
                val pulled = try {
                    backend.pull(current)
                } catch (error: SyncHttpException) {
                    error.fullResyncCursorOrNull() ?: throw error
                    recoverFullResync(current, mediaEditGuard)
                    null
                }
                if (pulled != null) {
                    applyRemote(current, pulled.entities, mediaEditGuard = mediaEditGuard)
                    downloadMissingMedia(current, mediaEditGuard)
                    preferences.updateCursor(pulled.cursor, pulled.generation)
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
        preferences.clearFamilySession()
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
        preferences.clearFamilySession()
        cachedSession = preferences.session.first()
        currentStatus.value = SyncStatus.Disabled
    }

    override suspend fun clearLocalRecords(clearLocal: suspend () -> Unit): Result<Unit> = runCatching {
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
            clearLocal()
            // Keep the last server incarnation as a push precondition. If the
            // server was restored while records were being cleared, the next
            // dirty Baby must be rejected into full recovery before mutation.
            preferences.updateCursor(0, generation = session.pullGeneration)
            if (session.familyId.isNotBlank()) {
                outboxDao.deleteType(session.familyId, "record")
                val mediaUuids = logMedia.map(MediaAssetEntity::clientUuid)
                mediaUuids.chunked(OUTBOX_DELETE_CHUNK_SIZE).forEach { chunk ->
                    outboxDao.deleteEntities(session.familyId, "media", chunk)
                }
            }
            mediaDao.deleteLogMedia()
            localMediaPaths.forEach { localUri ->
                runCatching { mediaFiles.delete(localUri) }
            }
            cachedSession = preferences.session.first()
        }
    }.onFailure(::updateFailureStatus)

    override suspend fun clearAllLocalData(clearLocal: suspend () -> Unit): Result<Unit> = runCatching {
        syncMutex.withLock {
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
            clearLocal()
            // Full wipe leaves no local replica. Drop generation so the next
            // join/create starts from a clean push precondition.
            preferences.updateCursor(0, generation = "")
            outboxDao.deleteAll()
            mediaDao.deleteAll()
            localMediaPaths.forEach { localUri ->
                runCatching { mediaFiles.delete(localUri) }
            }
            cachedSession = preferences.session.first()
        }
    }.onFailure(::updateFailureStatus)

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
        val unauthorizedAvatarRows = if (session.role == FamilyRole.Member) {
            queued.filter { row ->
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
        val pending = queued - unauthorizedAvatarRows.toSet()
        if (pending.isEmpty()) return true
        val uploads = mutableListOf<MediaAssetEntity>()
        val entities = pending
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
        requireAllowed(policy.evaluate(session.baseUrl, foregroundState.isForeground()))
        backend.push(session, entities)
        uploads.forEach { media ->
            requireAllowed(policy.evaluate(session.baseUrl, foregroundState.isForeground()))
            // Metadata needs the compressed byte size, so preparation happens
            // once before the metadata push and again here. Re-preparing one
            // file at a time bounds resident JPEG bytes to a single upload.
            val prepared = mediaFiles.prepareUpload(media.localUri)
            backend.putMedia(session, media.clientUuid, prepared.bytes, prepared.mime)
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        pending.forEach { row ->
            when (row.entityType) {
                "baby" -> babyDao.markSynced(row.clientUuid, row.updatedAt)
                "record" -> recordDao.markSynced(row.clientUuid, row.updatedAt)
                "media" -> mediaDao.markSynced(row.clientUuid, row.updatedAt)
            }
        }
        outboxDao.deleteIds(pending.map { it.id })
        return true
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
                "record" -> listOfNotNull(
                    payload.string("baby_client_uuid")?.let { "baby" to it },
                )
                "media" -> listOfNotNull(
                    payload.string("record_client_uuid")?.let { "record" to it },
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
        reconcileMemberAvatars: Boolean = false,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val deletedLocalUris = mutableListOf<String>()
        transactionRunner.run {
            val unresolved = mutableListOf<SyncEntity>()
            for (entity in entities.filter { it.type == "baby" }) {
                if (!applyBaby(session, entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "record" }) {
                if (!applyRecord(entity)) unresolved += entity
            }
            for (entity in entities.filter { it.type == "media" }) {
                if (!applyMedia(session, entity, deletedLocalUris)) unresolved += entity
            }
            require(unresolved.isEmpty()) {
                "同步数据引用尚未就绪，保留 cursor 以便重试"
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
            if (reconcileMemberAvatars) {
                reconcileMemberAvatarAuthority(entities, mediaEditGuard)
            }
        }
        deletedLocalUris.forEach { mediaFiles.delete(it) }
        // Keep the parameter explicit: media bytes are authorized by this same
        // session during the immediately following reconciliation.
        check(session.isJoined)
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
                nickname = payload.string("nickname") ?: existing?.nickname ?: "宝宝",
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
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
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

    private suspend fun applyMedia(
        session: SyncSession,
        entity: SyncEntity,
        deletedLocalUris: MutableList<String>,
    ): Boolean {
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val existing = mediaDao.getByClientUuid(entity.clientUuid)
        // Match server LWW: existing wins on equal updatedAt (>= skip).
        if (existing != null && existing.updatedAt >= entity.updatedAt) return true
        val kind = payload.string("kind") ?: existing?.kind ?: return false
        val recordId = payload.string("record_client_uuid")
            ?.let { recordDao.getByClientUuid(it)?.id }
            ?: existing?.recordId
        val referencedBabyId = payload.string("baby_client_uuid")
            ?.let { babyDao.getByClientUuid(it)?.id }
            ?: existing?.babyId
        val babyId = if (kind == "log") null else referencedBabyId
        if (kind == "log" && recordId == null) return false
        if (kind == "avatar" && babyId == null) return false
        mediaDao.upsert(
            MediaAssetEntity(
                id = existing?.id ?: 0,
                recordId = recordId,
                clientUuid = entity.clientUuid,
                kind = kind,
                babyId = babyId,
                localUri = existing?.localUri.orEmpty(),
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

    private suspend fun persistJoin(baseUrl: String, deviceId: String, joined: JoinResult): SyncSession {
        val session = SyncSession(
            baseUrl = baseUrl,
            familyId = joined.familyId,
            familyToken = joined.token,
            deviceId = deviceId,
            role = joined.role,
            pullCursor = joined.cursor,
            pullGeneration = joined.generation,
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

    private suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean = false,
    ) {
        babyDao.markAllPendingSync()
        recordDao.markAllPendingSync()
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
        requireAllowed(policy.evaluate(current.baseUrl, foregroundState.isForeground()))
        val authoritative = backend.pull(current)
        applyRemote(
            current,
            authoritative.entities,
            reconcileMemberAvatars = current.role == FamilyRole.Member,
            mediaEditGuard = mediaEditGuard,
        )
        downloadMissingMedia(current, mediaEditGuard)
        preferences.updateCursor(
            authoritative.cursor,
            authoritative.generation,
        )
        current = preferences.session.first()
        captureLocalChanges(current)
        pushPending(current)
        current = preferences.session.first()
        requireAllowed(policy.evaluate(current.baseUrl, foregroundState.isForeground()))
        val finalPull = backend.pull(current)
        applyRemote(current, finalPull.entities, mediaEditGuard = mediaEditGuard)
        downloadMissingMedia(current, mediaEditGuard)
        preferences.updateCursor(finalPull.cursor, finalPull.generation)
        return preferences.session.first()
    }

    private suspend fun reconcileMemberAvatarAuthority(
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard?,
    ) {
        val serverPointers = entities
            .filter { it.type == "baby" && it.deletedAt == null }
            .associate { entity ->
                val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                entity.clientUuid to payload.string("avatar_media_uuid")
            }
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
            val baseUrl = current.baseUrl
            if (baseUrl.isBlank()) requireAllowed(HomeNetworkDecision.MissingServer)
            val decision = policy.evaluate(baseUrl, foregroundState.isForeground())
            requireAllowed(decision)
            block(baseUrl)
        }
    }.onFailure(::updateFailureStatus)

    private suspend fun <T> withAllowedSession(block: suspend (SyncSession) -> T): Result<T> = runCatching {
        syncMutex.withLock {
            val session = preferences.session.first()
            cachedSession = session
            if (!session.isJoined) throw SyncNotEnabledException()
            val decision = policy.evaluate(session.baseUrl, foregroundState.isForeground())
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
        records.forEach { record ->
            val babyUuid = babyDao.getIncludingDeleted(record.babyId)?.clientUuid
                ?: return@forEach
            enqueue(
                session,
                SyncWireMapper.record(
                    record,
                    babyUuid,
                    record.createdByDeviceId ?: session.deviceId,
                ),
            )
        }
        media.forEach { asset ->
            if (session.role == FamilyRole.Member && asset.kind == "avatar") {
                return@forEach
            }
            val recordUuid = asset.recordId
                ?.let { recordDao.getIncludingDeleted(it)?.clientUuid }
            val babyUuid = asset.babyId
                ?.let { babyDao.getIncludingDeleted(it)?.clientUuid }
            if (
                (asset.kind == "log" && recordUuid == null) ||
                (asset.kind == "avatar" && babyUuid == null)
            ) {
                return@forEach
            }
            enqueue(session, SyncWireMapper.media(asset, recordUuid, babyUuid))
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
                requireAllowed(policy.evaluate(session.baseUrl, foregroundState.isForeground()))
                // Soft-fail missing remote bytes (half-upload / 404) and other
                // media GET failures so the pull cursor can still advance.
                // Empty localUri keeps the asset queued for a later retry.
                val bytes = try {
                    backend.getMedia(session, media.clientUuid)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@forEach
                }
                val localUri = mediaFiles.saveDownloaded(
                    media.clientUuid,
                    media.kind,
                    bytes,
                    media.mime,
                )
                transactionRunner.run {
                    val current = mediaDao.getByClientUuid(media.clientUuid) ?: return@run
                    mediaDao.update(current.copy(localUri = localUri))
                    current.recordId?.let { recordId ->
                        refreshRecordPhotoPaths(recordId, mediaEditGuard)
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
}

private fun SyncEntity.toJson() = buildJsonObject {
    put("type", type)
    put("client_uuid", clientUuid)
    put("payload", Json.parseToJsonElement(payloadJson))
    put("updated_at", updatedAt)
    deletedAt?.let { put("deleted_at", it) }
}

private fun JsonObject.entities(): List<SyncEntity> =
    (get("entities") as? JsonArray).orEmpty().map { element ->
        val value = element.jsonObject
        SyncEntity(
            type = value["type"]!!.jsonPrimitive.content,
            clientUuid = value["client_uuid"]!!.jsonPrimitive.content,
            payloadJson = value["payload"]?.toString() ?: "{}",
            updatedAt = value["updated_at"]!!.jsonPrimitive.longOrNull ?: 0,
            deletedAt = value["deleted_at"]?.jsonPrimitive?.longOrNull,
            rev = value["rev"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

private fun JsonObject.toJoinResult(): JoinResult = JoinResult(
    familyId = get("family_id")!!.jsonPrimitive.content,
    token = get("token")!!.jsonPrimitive.content,
    role = if (get("role")?.jsonPrimitive?.content == "owner") FamilyRole.Owner else FamilyRole.Member,
    entities = entities(),
    cursor = get("cursor")?.jsonPrimitive?.longOrNull ?: 0,
    generation = get("generation")?.jsonPrimitive?.contentOrNull.orEmpty(),
)

private fun HomeNetworkDecision.userMessage(): String = when (this) {
    HomeNetworkDecision.MissingServer -> "请先填写家庭服务器地址"
    HomeNetworkDecision.NotOnWifi, HomeNetworkDecision.ServerUnavailable,
    HomeNetworkDecision.BackingOff -> "无法连接家庭服务器，请确认在家中 Wi‑Fi"
    HomeNetworkDecision.Background -> "家庭同步仅在前台运行"
    HomeNetworkDecision.Allowed -> ""
}

private fun HomeNetworkDecision.toSyncStatus(): SyncStatus = when (this) {
    HomeNetworkDecision.MissingServer -> SyncStatus.Disabled
    HomeNetworkDecision.NotOnWifi,
    HomeNetworkDecision.ServerUnavailable,
    HomeNetworkDecision.BackingOff,
    HomeNetworkDecision.Background,
    -> SyncStatus.BlockedOfflineHome
    HomeNetworkDecision.Allowed -> SyncStatus.Idle
}

private class HomeNetworkBlockedException(message: String) : IllegalStateException(message)

internal class SyncHttpException(
    val statusCode: Int,
    val responseBody: String = "",
) : IllegalStateException("家庭服务器请求失败（HTTP $statusCode）")

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
    private val babyAvatarPaths: MutableMap<String, String?>,
) {
    fun canRefresh(record: RecordEntity): Boolean =
        record.clientUuid !in recordPhotos ||
            recordPhotos[record.clientUuid] == localPhotoPaths(record.payloadJson)

    fun canRefresh(baby: BabyEntity): Boolean =
        baby.clientUuid !in babyAvatarPaths ||
            babyAvatarPaths[baby.clientUuid] == baby.avatarPath

    fun recordRefreshed(clientUuid: String, photos: Set<String>) {
        recordPhotos[clientUuid] = photos
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

private val ENTITY_ORDER = listOf("baby", "record", "media")
private const val PUSH_ROOT_BATCH_SIZE = 200
private const val MAX_PUSH_BATCH_SIZE = 1_000
private const val OUTBOX_DELETE_CHUNK_SIZE = 400
private const val MILLIS_PER_SECOND = 1_000L
private const val RECEIPT_PREFIX = "lezi-sync:"
