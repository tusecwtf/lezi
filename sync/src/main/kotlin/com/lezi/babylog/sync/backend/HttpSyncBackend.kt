package com.lezi.babylog.sync.backend

import com.lezi.babylog.core.common.validation.StartupBoundaryObservation
import com.lezi.babylog.sync.media.parseCanonicalMediaMime
import com.lezi.babylog.sync.media.requireCanonicalMediaMime
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.ConflictSnapshotPaging
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.sync.conflict.toConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import com.lezi.babylog.core.common.deadline.RealtimeDeadline
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import com.lezi.babylog.sync.backend.deadline.cancelActiveIo
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.ClientAppVersion
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpAttemptContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpDeadlinePolicy
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpDisconnectWatchdog
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpNameResolver
import com.lezi.babylog.sync.backend.deadline.FamilyHttpOperation
import com.lezi.babylog.sync.backend.deadline.FamilyHttpWriteStallException
import com.lezi.babylog.sync.backend.deadline.SystemFamilyHttpNameResolver
import com.lezi.babylog.sync.backend.deadline.asFamilyHttpFailure
import com.lezi.babylog.sync.backend.deadline.boundedConnectTimeoutMillis
import com.lezi.babylog.sync.backend.deadline.connectFamilyHttp
import com.lezi.babylog.sync.backend.deadline.boundedWriteStallTimeoutMillis
import com.lezi.babylog.sync.backend.deadline.isFamilyTrustFailure
import com.lezi.babylog.sync.backend.deadline.resolveFamilyHttpHost
import com.lezi.babylog.sync.backend.retry.SyncRetryAttemptContext
import com.lezi.babylog.sync.backend.retry.SyncRetryBudgetExceededException
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryOperation
import com.lezi.babylog.sync.backend.retry.SystemSyncRetryClock
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.matchesOrigin
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.normalizeHttpsOrigin
import com.lezi.babylog.sync.session.pinnedSslSocketFactory
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName

private const val BOOTSTRAP_SECRET_HEADER = "X-Lezi-Bootstrap-Secret"
internal const val REFRESH_REQUEST_ID_HEADER = "X-Lezi-Refresh-Request-Id"
internal const val MEMBER_REQUEST_VIEW_HEADER = "X-Lezi-Member-Request-View"
internal const val OPEN_MEMBER_REQUEST_VIEW = "open-v1"
private val REFRESH_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
internal const val MAX_SYNC_JSON_RESPONSE_BYTES = 16 * 1024 * 1024
internal const val MAX_SYNC_MEDIA_RESPONSE_BYTES = 10 * 1024 * 1024
/** Self-hosted release APK download bound (full package, not media). */
internal const val MAX_SYNC_APP_UPDATE_APK_BYTES = 100 * 1024 * 1024
private const val MAX_SYNC_ERROR_RESPONSE_BYTES = 64 * 1024
private const val DEFAULT_UPLOAD_WRITE_STALL_TIMEOUT_MILLIS = 30_000L
private const val DEFAULT_JSON_WRITE_STALL_TIMEOUT_MILLIS = 5_000L
/**
 * Idle keep-alive TTL (0.5 W3): after a foreground cycle, undetached 2xx handles
 * stay connected for reuse by the next round and are only disconnected once this
 * much idle time has passed. Deliberately below the platform connection pool's
 * ~5 min idle timeout so both layers never hold the same socket cost, and idle
 * connections send no packets (no radio wake-ups; no timer — expiry is checked
 * lazily at the next authenticated touch).
 */
private const val IDLE_KEEP_ALIVE_TTL_MILLIS = 90_000L
/** Attached on authenticated family requests so the server can gate minSupported later. */
internal const val CLIENT_VERSION_CODE_HEADER = "X-Lezi-Client-Version-Code"

internal fun interface SyncHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

internal fun interface TrustedEndpointResolver {
    suspend fun resolve(baseUrl: String): TrustedEndpointProfile
}

private object DefaultSyncHttpConnectionFactory : SyncHttpConnectionFactory {
    override fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
}

private data class JsonTransportResponse(
    val json: JsonObject,
    val encodedBytes: Int,
)

private data class BoundedHttpResponse(
    val code: Int,
    val bytes: ByteArray,
    val retryAfterHeader: String?,
)

private data class AuthenticatedKeepAliveIdentity(
    val origin: String,
    val spkiSha256: String?,
)

private fun shouldKeepAlive(token: String?, code: Int): Boolean =
    code in 200..299 && !token.isNullOrBlank()

class HttpSyncBackend internal constructor(
    private val connectionFactory: SyncHttpConnectionFactory,
    private val trustedEndpointResolver: TrustedEndpointResolver? = null,
    private val clientVersionCode: Int? = null,
    private val uploadWriteStallTimeoutMillis: Long = DEFAULT_UPLOAD_WRITE_STALL_TIMEOUT_MILLIS,
    private val jsonWriteStallTimeoutMillis: Long = DEFAULT_JSON_WRITE_STALL_TIMEOUT_MILLIS,
    private val nameResolver: FamilyHttpNameResolver = SystemFamilyHttpNameResolver,
    familyHttpClock: SyncRetryClock = SystemSyncRetryClock,
) : SyncBackend {
    private val familyHttpDeadlines = FamilyHttpDeadlinePolicy(familyHttpClock)
    /** Monotonic clock for the idle keep-alive TTL (never the wall clock). */
    private val keepAliveClock: SyncRetryClock = familyHttpClock

    init {
        require(uploadWriteStallTimeoutMillis > 0) {
            "上传写入停滞超时必须大于 0"
        }
        require(jsonWriteStallTimeoutMillis > 0) {
            "家庭 JSON 写入停滞超时必须大于 0"
        }
    }

    private val keepAliveLock = Any()
    /**
     * Disconnect handles for authenticated 2xx exchanges that were not torn down.
     * Anonymous requests neither join nor evict this set. [open] still runs every
     * time; this is not a connection pool.
     */
    private val undetachedKeepAliveHandles = mutableListOf<HttpURLConnection>()
    private var undetachedKeepAliveIdentity: AuthenticatedKeepAliveIdentity? = null
    /**
     * Monotonic elapsed baseline of completed exchanges, refreshed at round end.
     * Active exchanges are owned separately and are never retained in this list.
     */
    private var undetachedKeepAliveIdleSinceElapsedMillis: Long? = null

    @Inject
    constructor(
        preferences: SyncPreferences,
        clientAppVersion: ClientAppVersion,
    ) : this(
        connectionFactory = DefaultSyncHttpConnectionFactory,
        trustedEndpointResolver = TrustedEndpointResolver { baseUrl ->
            requireNotNull(preferences.verifiedEndpoint.first()) {
                "家庭服务器尚未完成安全确认"
            }.also { endpoint ->
                require(endpoint.matchesOrigin(baseUrl)) {
                    "可信服务器与当前家庭会话不一致"
                }
            }
        },
        clientVersionCode = clientAppVersion.versionCode,
    )

    override fun releaseForegroundKeepAlive() {
        synchronized(keepAliveLock) {
            evictUndetachedHandlesLocked()
        }
    }

    override fun releaseForegroundKeepAliveToIdleTtl() {
        synchronized(keepAliveLock) {
            // Round-end (0.5 W3): keep the handles connected for the next round
            // and only start the idle clock. Nothing disconnects here; expiry is
            // enforced lazily by [prepareKeepAlive] before the next request.
            if (undetachedKeepAliveHandles.isNotEmpty()) {
                undetachedKeepAliveIdleSinceElapsedMillis =
                    keepAliveClock.snapshot().elapsedRealtimeMillis
            }
        }
    }

    override suspend fun anonymousHealth(endpoint: TrustedEndpointProfile): AnonymousHealth {
        val json = get(endpoint, "/health", FamilyHttpOperation.Probe)
        require(json.requiredBoolean("ok", "health")) { "家庭服务器 health 未就绪" }
        val capabilities = json.requiredArray("capabilities", "health").mapIndexed { index, value ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("health.capabilities[$index] 无效")
        }.toSet()
        return AnonymousHealth(
            version = json.requiredNonBlankString("version", "health"),
            capabilities = capabilities,
        )
    }

    override suspend fun anonymousReady(endpoint: TrustedEndpointProfile): AnonymousReadiness {
        val json = get(endpoint, "/ready", FamilyHttpOperation.Probe)
        require(json.requiredBoolean("ok", "ready")) { "家庭服务器尚未 ready" }
        require(json.requiredString("status", "ready") == "ready") {
            "家庭服务器 readiness 状态无效"
        }
        return AnonymousReadiness(
            version = json.requiredNonBlankString("version", "ready"),
        )
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
        require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
        val json = post(
            endpoint = endpoint,
            path = "/v1/disaster-restore/batches",
            token = null,
            body = buildJsonObject {
                put("request_id", requestId)
                put("restore_authority", "v1")
                put("family_id", familyId)
                put("family_name", requireNotNull(normalizeFamilyNameForWire(familyName)))
                put("owner_display_name", requireMemberDisplayName(ownerDisplayName))
                put("device_name", requireDeviceName(deviceName))
            },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to rootPassword),
        )
        require(json.requiredLong("protocol_version", "disaster restore start") == 1L) {
            "家庭恢复协议版本不兼容"
        }
        return DisasterRestoreBatch(
            batchId = json.requiredNonBlankString("batch_id", "disaster restore start"),
            recoveryToken = json.requiredNonBlankString(
                "recovery_token",
                "disaster restore start",
            ),
            status = json.requiredNonBlankString("status", "disaster restore start"),
            expiresAtEpochSeconds = json.requiredLong(
                "expires_at",
                "disaster restore start",
            ),
        )
    }

    override suspend fun putDisasterRestoreManifest(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        entities: List<SyncEntity>,
        media: List<DisasterRestoreMediaSpec>,
        sourceRelations: List<DisasterRestoreSourceRelation>,
    ): DisasterRestoreStatus = requestJson(
        base = endpoint.origin,
        path = "/v1/disaster-restore/batches/$batchId/manifest",
        method = "PUT",
        token = recoveryToken.requireRestoreCredential(),
        body = buildJsonObject {
            put("request_id", requestId)
            put("restore_authority", "v1")
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
            put("source_relations", buildJsonArray {
                sourceRelations.forEach { relation ->
                    add(buildJsonObject {
                        put("relation_id", relation.relationId)
                        put("display_client_uuid", relation.displayClientUuid)
                        put("source_client_uuids", buildJsonArray {
                            relation.sourceClientUuids.forEach { add(JsonPrimitive(it)) }
                        })
                        put("auto_aligned", relation.autoAligned)
                    })
                }
            })
            put("media", buildJsonArray {
                media.forEach { spec ->
                    add(buildJsonObject {
                        put("client_uuid", spec.clientUuid)
                        put("byte_size", spec.byteSize)
                        put("sha256", spec.sha256)
                    })
                }
            })
        },
        trustedEndpoint = endpoint,
    ).toDisasterRestoreStatus()

    override suspend fun putDisasterRestoreMedia(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): DisasterRestoreStatus = requestJsonStream(
        base = endpoint.origin,
        path = "/v1/disaster-restore/batches/$batchId/media/$clientUuid",
        method = "PUT",
        token = recoveryToken.requireRestoreCredential(),
        source = source,
        trustedEndpoint = endpoint,
        familyOperation = FamilyHttpOperation.DisasterRestore,
    ).toDisasterRestoreStatus()

    override suspend fun disasterRestoreStatus(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus = requestJson(
        base = endpoint.origin,
        path = "/v1/disaster-restore/batches/$batchId/status",
        method = "GET",
        token = recoveryToken.requireRestoreCredential(),
        body = null,
        trustedEndpoint = endpoint,
    ).toDisasterRestoreStatus()

    override suspend fun commitDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
        requestId: String,
        rootPassword: String,
    ): SessionBootstrapResult {
        require(rootPassword.isNotBlank()) { "请输入新服务器管理员根密码" }
        val json = requestJson(
            base = endpoint.origin,
            path = "/v1/disaster-restore/batches/$batchId/commit",
            method = "POST",
            token = recoveryToken.requireRestoreCredential(),
            body = buildJsonObject { put("request_id", requestId); put("restore_authority", "v1") },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to rootPassword),
            trustedEndpoint = endpoint,
            familyOperation = FamilyHttpOperation.Session,
        )
        require((json["restore_authority"] as? JsonPrimitive)?.contentOrNull == "v1") {
            "恢复响应未证明新的家庭权威，原恢复信息已保留"
        }
        require(json.requiredLong("protocol_version", "disaster restore commit") == 1L) {
            "家庭恢复协议版本不兼容"
        }
        require(json.requiredString("status", "disaster restore commit") == "committed") {
            "家庭恢复提交状态无效"
        }
        require(json.requiredString("role", "disaster restore commit") == "owner") {
            "家庭恢复提交身份无效"
        }
        return SessionBootstrapResult(
            familyId = json.requiredNonBlankString("family_id", "disaster restore commit"),
            accessToken = json.requiredNonBlankString(
                "access_token",
                "disaster restore commit",
            ),
            refreshToken = json.requiredNonBlankString(
                "refresh_token",
                "disaster restore commit",
            ),
            accessExpiresAtEpochSeconds = json.requiredLong(
                "access_expires_at",
                "disaster restore commit",
            ),
            deviceId = json.requiredNonBlankString("device_id", "disaster restore commit"),
            role = FamilyRole.Owner,
            generation = json.requiredNonBlankString("generation", "disaster restore commit"),
            familyName = json.requiredFamilyName("disaster restore commit"),
            membershipId = json.requiredNonBlankString(
                "membership_id",
                "disaster restore commit",
            ),
        )
    }

    override suspend fun cancelDisasterRestore(
        endpoint: TrustedEndpointProfile,
        batchId: String,
        recoveryToken: String,
    ): DisasterRestoreStatus = requestJson(
        base = endpoint.origin,
        path = "/v1/disaster-restore/batches/$batchId/cancel",
        method = "POST",
        token = recoveryToken.requireRestoreCredential(),
        body = buildJsonObject {},
        trustedEndpoint = endpoint,
    ).toDisasterRestoreStatus()

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ) =
        post(baseUrl, "/v1/family/create", null, buildJsonObject {
            put("create_request_id", createRequestId)
            put("display_name", requireMemberDisplayName(displayName))
            put("device_name", requireDeviceName(deviceId))
            put("family_name", requireNotNull(normalizeFamilyNameForWire(familyName)) {
                "请填写家庭名"
            })
        }, extraHeaders = buildMap {
            bootstrapSecret?.takeIf(String::isNotBlank)?.let {
                put(BOOTSTRAP_SECRET_HEADER, it)
            }
        }, familyOperation = FamilyHttpOperation.Session).toCreateResult()

    override suspend fun refresh(baseUrl: String, refreshToken: String): SessionRefreshResult {
        val token = refreshToken.trim()
        require(token.isNotEmpty()) { "当前设备缺少 refresh token" }
        return post(
            baseUrl,
            "/v1/session/refresh",
            null,
            buildJsonObject { put("refresh_token", token) },
        ).toSessionRefreshResult()
    }

    override suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult {
        val token = refreshToken.trim()
        require(token.isNotEmpty()) { "当前设备缺少 refresh token" }
        return post(
            baseUrl,
            "/v1/session/refresh",
            null,
            buildJsonObject { put("refresh_token", token) },
            extraHeaders = refreshRequestHeaders(refreshRequestId),
            familyOperation = FamilyHttpOperation.Session,
        ).toSessionRefreshResult()
    }

    override suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
    ): SessionRefreshResult {
        val token = refreshToken.trim()
        require(token.isNotEmpty()) { "当前设备缺少 refresh token" }
        return post(
            endpoint = endpoint,
            path = "/v1/session/refresh",
            token = null,
            body = buildJsonObject { put("refresh_token", token) },
        ).toSessionRefreshResult()
    }

    override suspend fun refresh(
        endpoint: TrustedEndpointProfile,
        refreshToken: String,
        refreshRequestId: String,
    ): SessionRefreshResult {
        val token = refreshToken.trim()
        require(token.isNotEmpty()) { "当前设备缺少 refresh token" }
        return post(
            endpoint = endpoint,
            path = "/v1/session/refresh",
            token = null,
            body = buildJsonObject { put("refresh_token", token) },
            extraHeaders = refreshRequestHeaders(refreshRequestId),
            familyOperation = FamilyHttpOperation.Session,
        ).toSessionRefreshResult()
    }

    private fun refreshRequestHeaders(refreshRequestId: String): Map<String, String> {
        val normalized = refreshRequestId.trim()
        require(REFRESH_REQUEST_ID_PATTERN.matches(normalized)) {
            "refresh request id 格式无效"
        }
        return mapOf(REFRESH_REQUEST_ID_HEADER to normalized)
    }

    override suspend fun ownerLogin(
        baseUrl: String,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult {
        val secret = rootPassword
        require(secret.isNotBlank()) { "请填写管理员根密码" }
        return post(
            base = baseUrl,
            path = if (takeover) "/v1/owner/takeover" else "/v1/owner/login",
            token = null,
            body = buildJsonObject {
                put("login_request_id", loginRequestId)
                put("device_name", requireDeviceName(deviceName))
            },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to secret),
            familyOperation = FamilyHttpOperation.Session,
        ).toOwnerLoginResult()
    }

    override suspend fun ownerLogin(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        loginRequestId: String,
        rootPassword: String,
        takeover: Boolean,
    ): SessionBootstrapResult {
        require(rootPassword.isNotBlank()) { "请填写管理员根密码" }
        return post(
            endpoint = endpoint,
            path = if (takeover) "/v1/owner/takeover" else "/v1/owner/login",
            token = null,
            body = buildJsonObject {
                put("login_request_id", loginRequestId)
                put("device_name", requireDeviceName(deviceName))
            },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to rootPassword),
            familyOperation = FamilyHttpOperation.Session,
        ).toOwnerLoginResult()
    }

    override suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        val json = postMemberLoginRequest(
            baseUrl,
            "/v1/member/requests",
            null,
            buildJsonObject {
                put("display_name", requireMemberDisplayName(displayName))
                put("device_name", requireDeviceName(deviceName))
            },
        )
        require(json.requiredString("status", "member request") == "pending") {
            "member request 响应 status 无效"
        }
        return MemberLoginReceipt(
            requestId = json.requiredNonBlankString("request_id", "member request"),
            pendingSecret = json.requiredNonBlankString("pending_secret", "member request"),
            expiresAtEpochSeconds = json.requiredLong("expires_at", "member request"),
        )
    }

    override suspend fun requestMemberLogin(
        endpoint: TrustedEndpointProfile,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        val json = postMemberLoginRequest(
            endpoint = endpoint,
            path = "/v1/member/requests",
            token = null,
            body = buildJsonObject {
                put("display_name", requireMemberDisplayName(displayName))
                put("device_name", requireDeviceName(deviceName))
            },
        )
        require(json.requiredString("status", "member request") == "pending") {
            "member request 响应 status 无效"
        }
        return MemberLoginReceipt(
            requestId = json.requiredNonBlankString("request_id", "member request"),
            pendingSecret = json.requiredNonBlankString("pending_secret", "member request"),
            expiresAtEpochSeconds = json.requiredLong("expires_at", "member request"),
        )
    }

    private suspend fun postMemberLoginRequest(
        base: String,
        path: String,
        token: String?,
        body: JsonObject,
    ): JsonObject = postMemberLoginRequestOnce(base, null, path, token, body)

    private suspend fun postMemberLoginRequest(
        endpoint: TrustedEndpointProfile,
        path: String,
        token: String?,
        body: JsonObject,
    ): JsonObject = postMemberLoginRequestOnce(endpoint.origin, endpoint, path, token, body)

    private suspend fun postMemberLoginRequestOnce(
        base: String,
        endpoint: TrustedEndpointProfile?,
        path: String,
        token: String?,
        body: JsonObject,
    ): JsonObject {
        val started = AtomicBoolean(false)
        return try {
            requestJson(base, path, "POST", token, body,
                trustedEndpoint = endpoint,
                familyOperation = FamilyHttpOperation.SessionWrite,
                onRequestBodyStarted = { started.set(true) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!started.get()) throw MemberLoginRequestNotSentException(failure)
            throw failure
        }
    }

    override suspend fun memberLoginStatus(
        baseUrl: String,
        pendingSecret: String,
    ): MemberLoginStatus = post(
        baseUrl,
        "/v1/member/requests/status",
        null,
        pendingSecretBody(pendingSecret),
        familyOperation = FamilyHttpOperation.SessionRead,
    ).requiredMemberLoginStatus("member request status")

    override suspend fun memberLoginStatus(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): MemberLoginStatus = post(
        endpoint = endpoint,
        path = "/v1/member/requests/status",
        token = null,
        body = pendingSecretBody(pendingSecret),
        familyOperation = FamilyHttpOperation.SessionRead,
    ).requiredMemberLoginStatus("member request status")

    override suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        post(
            baseUrl,
            "/v1/member/requests/cancel",
            null,
            pendingSecretBody(pendingSecret),
        )
    }

    override suspend fun cancelMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ) {
        post(
            endpoint = endpoint,
            path = "/v1/member/requests/cancel",
            token = null,
            body = pendingSecretBody(pendingSecret),
        )
    }

    override suspend fun claimMemberLogin(
        baseUrl: String,
        pendingSecret: String,
    ): SessionBootstrapResult = post(
        baseUrl,
        "/v1/member/requests/claim",
        null,
        pendingSecretBody(pendingSecret),
        familyOperation = FamilyHttpOperation.Session,
    ).toMemberClaimResult()

    override suspend fun claimMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): SessionBootstrapResult = post(
        endpoint = endpoint,
        path = "/v1/member/requests/claim",
        token = null,
        body = pendingSecretBody(pendingSecret),
        familyOperation = FamilyHttpOperation.Session,
    ).toMemberClaimResult()

    override suspend fun pendingMemberLogins(
        session: SyncSession,
    ): List<PendingMemberLoginRequest> = get(
        session.baseUrl,
        "/v1/member/requests",
        session.accessToken,
        extraHeaders = mapOf(MEMBER_REQUEST_VIEW_HEADER to OPEN_MEMBER_REQUEST_VIEW),
    ).requiredArray("requests", "pending member requests").mapIndexed { index, element ->
        val request = element as? JsonObject
            ?: throw IllegalArgumentException("requests[$index] 不是对象")
        PendingMemberLoginRequest(
            requestId = request.requiredNonBlankString("request_id", "requests[$index]"),
            displayName = request.requiredNonBlankString("display_name", "requests[$index]"),
            deviceName = request.requiredNonBlankString("device_name", "requests[$index]"),
            createdAtEpochSeconds = request.requiredLong("created_at", "requests[$index]"),
            expiresAtEpochSeconds = request.requiredLong("expires_at", "requests[$index]"),
            // 0.3.3 did not expose this field and only returned pending rows. Keep that
            // response compatible during the server cutover; 0.3.5 marks approved rows.
            status = request.memberLoginReviewStatus("requests[$index]"),
        )
    }

    override suspend fun approveNewMemberLogin(session: SyncSession, requestId: String) {
        post(
            session.baseUrl,
            "/v1/member/requests/${requireRequestId(requestId)}/approve-new",
            session.accessToken,
            buildJsonObject {},
        )
    }

    override suspend fun bindExistingMemberLogin(
        session: SyncSession,
        requestId: String,
        membershipId: String,
    ) {
        post(
            session.baseUrl,
            "/v1/member/requests/${requireRequestId(requestId)}/bind-existing",
            session.accessToken,
            buildJsonObject {
                put("membership_id", requireTargetMembershipId(membershipId))
            },
        )
    }

    override suspend fun rejectMemberLogin(session: SyncSession, requestId: String) {
        post(
            session.baseUrl,
            "/v1/member/requests/${requireRequestId(requestId)}/reject",
            session.accessToken,
            buildJsonObject {},
        )
    }

    override suspend fun createMemberLoginGrant(
        session: SyncSession,
        endpoint: TrustedEndpointProfile,
        membershipId: String,
    ): MemberLoginGrant {
        require(endpoint.matchesOrigin(session.baseUrl)) { "可信服务器与当前家庭会话不一致" }
        val json = post(
            endpoint,
            "/v1/member/login-grants",
            session.accessToken,
            buildJsonObject {
                put("membership_id", requireTargetMembershipId(membershipId))
            },
        )
        return MemberLoginGrant(
            grant = json.requiredUrlSafeCapability("grant", "member login grant"),
            familyName = json.requiredFamilyName("member login grant"),
            memberDisplayName = requireMemberDisplayName(
                json.requiredNonBlankString("member_display_name", "member login grant"),
            ),
            expiresAtEpochSeconds = json.requiredLong("expires_at", "member login grant")
                .also { require(it > 0) { "member login grant 响应 expires_at 无效" } },
            landingUrl = if ("landing_url" in json) {
                json.requiredString("landing_url", "member login grant")
            } else {
                null
            },
        )
    }

    override suspend fun claimMemberLoginGrant(
        endpoint: TrustedEndpointProfile,
        grant: String,
        deviceName: String,
    ): SessionBootstrapResult = post(
        endpoint = endpoint,
        path = "/v1/member/login-grants/claim",
        token = null,
        body = buildJsonObject {
            put("grant", requireUrlSafeCapability(grant))
            put("device_name", requireDeviceName(deviceName))
        },
        familyOperation = FamilyHttpOperation.Session,
    ).toMemberClaimResult()

    override suspend fun pull(session: SyncSession, page: PullPageRequest): PullResult {
        // Blank generation is a recoverable pre-0.4.0 / cleared-checkpoint state:
        // the server answers generation_changed and the engine full-resyncs.
        session.requireCurrentReplicaTransport(allowBlankGeneration = true)
        require(page.pageIndex in 0 until page.budget.maxPages) {
            "普通 pull page_index 超出协商上限"
        }
        val generation = URLEncoder.encode(session.pullGeneration, Charsets.UTF_8.name())
        val json = requestPullJson(
            base = session.baseUrl,
            path = "/v1/pull?cursor=${session.pullCursor}&generation=$generation" +
                "&page_index=${page.pageIndex}&include_live_census=true" +
                liveKeysQuery(page.liveKeyTypes),
            token = session.accessToken,
            page = page,
        )
        val entities = json.entities("pull")
        val liveCensus = json.optionalLiveCensus()
        val result = PullResult(
            entities = entities,
            cursor = json.requiredLong("cursor", "pull"),
            generation = json.requiredNonBlankString("generation", "pull"),
            hasMore = requireNotNull(json["has_more"]?.jsonPrimitive?.booleanOrNull) {
                "pull 响应缺少 has_more"
            },
            pageIndex = json.requiredInt("page_index", "pull"),
            familyName = json.pullFamilyName(),
            liveCensus = liveCensus,
        )
        // Closed envelope: the old six keys, or exactly those plus the §1.4
        // census when the server answered our include_live_census request.
        // Any other extra key still fails closed (0.4.5/0.4.6 compat guard).
        json.requireExactKeys(
            PULL_RESPONSE_KEYS + if ("live_census" in json) setOf("live_census") else emptySet(),
            "pull",
        )
        return result.requireValidPage(page)
    }

    override suspend fun authenticatedHandshake(session: SyncSession): AuthenticatedSyncHandshake {
        val json = try {
            requestJson(
                base = session.baseUrl,
                path = "/v1/sync/handshake",
                method = "POST",
                token = session.accessToken,
                body = buildJsonObject {
                    put("protocol_version", AUTHENTICATED_SYNC_PROTOCOL_VERSION)
                    put("required_capabilities", buildJsonArray {
                        REQUIRED_CAUSAL_WIRE_CAPABILITIES.sorted().forEach {
                            add(JsonPrimitive(it))
                        }
                    })
                },
                retryOperation = SyncRetryOperation.Handshake,
            )
        } catch (failure: SyncHttpException) {
            if (failure.statusCode != 401) {
                runCatching { decodeSyncHandshakeFailure(failure) }
                    .getOrNull()
                    ?.let { throw it }
            }
            throw failure
        }
        return decodeAuthenticatedSyncHandshake(json)
    }

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalCommitBatchResult {
        val posted = try {
            postCausalBatch(
                session,
                "/v1/causal/commit",
                units,
                SyncRetryOperation.Commit,
            )
        } catch (failure: SyncHttpException) {
            // Authentication belongs to the credential decorator, even when the
            // server uses the closed commit rejection envelope.
            if (failure.statusCode == 401) throw failure
            val terminal = runCatching {
                Json.parseToJsonElement(failure.responseBody).jsonObject
            }.getOrNull() ?: throw failure
            terminal.throwIfCausalCommitRejected("/v1/causal/commit")
            throw failure
        }
        posted.response.throwIfCausalCommitRejected("/v1/causal/commit")
        return decodeCausalProof(session, posted.response) {
            parseCausalCommitBatchResult(posted, session, "/v1/causal/commit")
        }
    }

    override suspend fun declareSourceRelation(
        session: SyncSession,
        request: SourceRelationDeclareRequest,
    ): SourceRelationResult {
        session.requireCurrentReplicaTransport()
        val body = buildJsonObject {
            put("mutation_id", request.mutationId)
            put("record_client_uuid", request.recordClientUuid)
            put("equivalent_to_client_uuid", request.equivalentToClientUuid)
            put("expected_record_version", request.expectedRecordVersion)
            put("expected_other_version", request.expectedOtherVersion)
        }
        val json = post(
            session.baseUrl,
            "/v1/source-relations/declare",
            session.accessToken,
            body,
        )
        return json.toSourceRelationResult("source relation declare")
    }

    override suspend fun resolveSourceRelationGroup(
        session: SyncSession,
        request: SourceRelationResolveGroupRequest,
    ): SourceRelationResult {
        session.requireCurrentReplicaTransport()
        val body = buildJsonObject {
            put("mutation_id", request.mutationId)
            put(
                "member_client_uuids",
                buildJsonArray {
                    request.memberClientUuids.sorted().forEach { uuid ->
                        add(JsonPrimitive(uuid))
                    }
                },
            )
            put("display_client_uuid", request.displayClientUuid)
            put(
                "expected_versions",
                buildJsonObject {
                    request.expectedVersions.toSortedMap().forEach { (uuid, version) ->
                        put(uuid, JsonPrimitive(version))
                    }
                },
            )
        }
        val json = post(
            session.baseUrl,
            "/v1/source-relations/resolve-group",
            session.accessToken,
            body,
        )
        return json.toSourceRelationResult("source relation resolve-group")
    }

    override suspend fun fetchConflictSnapshotPage(
        session: SyncSession,
        conflictId: String,
        request: ConflictSnapshotPageRequest,
    ): FetchedConflictSnapshotPage {
        session.requireCurrentReplicaTransport()
        val id = conflictId.trim()
        require(id.isNotEmpty()) { "conflict_id 无效" }
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        val query = when (request) {
            ConflictSnapshotPageRequest.First -> ""
            is ConflictSnapshotPageRequest.Continuation -> {
                ConflictSnapshotValidation.requireRuntimeToken(
                    request.snapshotToken,
                    "conflict detail request.snapshot_token",
                )
                ConflictSnapshotValidation.requireRuntimeToken(
                    request.continuation,
                    "conflict detail request.continuation",
                )
                val token = URLEncoder.encode(request.snapshotToken, Charsets.UTF_8.name())
                val continuation = URLEncoder.encode(request.continuation, Charsets.UTF_8.name())
                "?snapshot_token=$token&continuation=$continuation"
            }
        }
        val response = requestJsonWithEvidence(
            base = session.baseUrl,
            path = "/v1/conflicts/$encoded$query",
            method = "GET",
            token = session.accessToken,
            body = null,
            successLimitBytes = ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES,
            successResponseKind = "冲突详情页",
            retryOperation = SyncRetryOperation.ConflictDetail,
        )
        val snapshot = response.json.toConflictSnapshot("conflict snapshot").also { snapshot ->
            require(snapshot.conflictId == id) { "conflict snapshot.conflict_id 与请求不一致" }
        }
        return FetchedConflictSnapshotPage(snapshot, response.encodedBytes)
    }

    override suspend fun resolveConflict(
        session: SyncSession,
        conflictId: String,
        request: ConflictResolveRequest,
    ): ConflictResolveResult {
        session.requireCurrentReplicaTransport()
        val id = conflictId.trim()
        require(id.isNotEmpty()) { "conflict_id 无效" }
        ConflictSnapshotValidation.requireResolutionChoices(
            snapshotToken = request.snapshotToken,
            resolutionMutationId = request.resolutionMutationId,
            choices = request.choices.map { it.path to it.choiceId },
            context = "conflict resolve request",
        )
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        val body = buildJsonObject {
            put("snapshot_token", request.snapshotToken)
            put("resolution_mutation_id", request.resolutionMutationId)
            put(
                "choices",
                buildJsonArray {
                    request.choices.forEach { choice ->
                        add(
                            buildJsonObject {
                                put("path", choice.path)
                                put("choice_id", choice.choiceId)
                            },
                        )
                    }
                },
            )
        }
        val json = requestJson(
            base = session.baseUrl,
            path = "/v1/conflicts/$encoded/resolve",
            method = "POST",
            token = session.accessToken,
            body = body,
            retryOperation = SyncRetryOperation.Resolution,
        )
        return json.toConflictResolveResult("conflict resolve").also { result ->
            val responseMutationId = when (result) {
                is ConflictResolveResult.Accepted -> result.resolutionMutationId
                is ConflictResolveResult.Rejected -> result.resolutionMutationId
            }
            require(responseMutationId == null || responseMutationId == request.resolutionMutationId) {
                "conflict resolve.resolution_mutation_id 与请求不一致"
            }
        }
    }

    override suspend fun withdrawConflictBranches(
        session: SyncSession,
        conflictId: String,
        request: ConflictWithdrawRequest,
    ): ConflictWithdrawResult {
        session.requireCurrentReplicaTransport()
        val id = conflictId.trim()
        require(id.isNotEmpty()) { "conflict_id 无效" }
        ConflictSnapshotValidation.requireUuid(
            request.withdrawalMutationId,
            "conflict withdraw request.withdrawal_mutation_id",
        )
        ConflictSnapshotValidation.requireUuid(
            request.expectedStableVersionId,
            "conflict withdraw request.expected_stable_version_id",
        )
        require(request.expectedBranchVersionIds.size in 1..64) {
            "conflict withdraw request.expected_branch_version_ids 数量无效"
        }
        require(request.expectedBranchVersionIds == request.expectedBranchVersionIds.sorted()) {
            "conflict withdraw request.expected_branch_version_ids 必须按字典序"
        }
        request.expectedBranchVersionIds.forEachIndexed { index, versionId ->
            ConflictSnapshotValidation.requireUuid(
                versionId,
                "conflict withdraw request.expected_branch_version_ids[$index]",
            )
        }
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        val body = buildJsonObject {
            put("withdrawal_mutation_id", request.withdrawalMutationId)
            put("expected_stable_version_id", request.expectedStableVersionId)
            put(
                "expected_branch_version_ids",
                buildJsonArray {
                    request.expectedBranchVersionIds.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        val json = requestJson(
            base = session.baseUrl,
            path = "/v1/conflicts/$encoded/withdraw",
            method = "POST",
            token = session.accessToken,
            body = body,
            retryOperation = SyncRetryOperation.Resolution,
        )
        return json.toConflictWithdrawResult("conflict withdraw").also { result ->
            val responseMutationId = when (result) {
                is ConflictWithdrawResult.Accepted -> result.withdrawalMutationId
                is ConflictWithdrawResult.Rejected -> result.withdrawalMutationId
            }
            require(responseMutationId == null || responseMutationId == request.withdrawalMutationId) {
                "conflict withdraw.withdrawal_mutation_id 与请求不一致"
            }
        }
    }

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
        sha256: String,
    ): CausalMediaPreimageReceipt {
        session.requireCurrentReplicaTransport()
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "因果媒体 sha256 无效"
        }
        val response = requestJsonStream(
            base = session.baseUrl,
            path = "/v1/causal/media/$mediaUuid",
            method = "PUT",
            token = session.accessToken,
            source = source,
            extraHeaders = mapOf(
                "X-Lezi-Media-Sha256" to sha256,
            ),
            retryOperation = SyncRetryOperation.MediaPrepare,
        )
        return response.toCausalMediaPreimageReceipt(mediaUuid, sha256, source.declaredByteSize)
    }

    private suspend fun postCausalBatch(
        session: SyncSession,
        path: String,
        units: List<CausalMutationUnit>,
        retryOperation: SyncRetryOperation? = null,
    ): PostedCausalBatch {
        session.requireCurrentReplicaTransport()
        require(units.isNotEmpty() && units.size <= MAX_CAUSAL_UNITS) {
            "因果同步批次必须包含 1..$MAX_CAUSAL_UNITS 个原子单元"
        }
        val expectedKeys = units.map { it.entityType to it.clientUuid }.toSet()
        require(expectedKeys.size == units.size) { "因果同步请求 key 必须唯一" }
        val expectedByMutation = units.associateBy(CausalMutationUnit::mutationId)
        require(expectedByMutation.size == units.size) { "因果同步 mutation_id 必须唯一" }
        val response = requestJson(
            base = session.baseUrl,
            path = path,
            method = "POST",
            token = session.accessToken,
            body = buildJsonObject {
                put("generation", session.pullGeneration)
                put("units", buildJsonArray {
                    units.forEach { unit ->
                        add(unit.toCausalJson())
                    }
                })
            },
            retryOperation = retryOperation,
        )
        return PostedCausalBatch(response, expectedKeys, expectedByMutation)
    }

    override suspend fun updateMyDisplayName(
        session: SyncSession,
        displayName: String,
    ): DisplayNameUpdateResult {
        val response = post(
            session.baseUrl,
            "/v1/family/display-name",
            session.accessToken,
            buildJsonObject {
                put("display_name", requireMemberDisplayName(displayName))
            },
        )
        return when (response.requiredString("status", "display-name")) {
            "updated" -> DisplayNameUpdateResult.Updated(
                response.requiredNonBlankString("display_name", "display-name"),
            )
            "pending" -> DisplayNameUpdateResult.Pending(
                response.toPendingMemberRenameRequest(
                    context = "display-name",
                    fallbackMembershipId = session.membershipId,
                ),
            )
            else -> throw IllegalArgumentException("display-name.status 无效")
        }
    }

    override suspend fun pendingMemberRenameRequests(
        session: SyncSession,
    ): List<PendingMemberRenameRequest> {
        val response = get(
            session.baseUrl,
            "/v1/family/rename-requests",
            session.accessToken,
        )
        return response.requiredArray("requests", "rename-requests").mapIndexed { index, item ->
            val request = item as? JsonObject
                ?: throw IllegalArgumentException("rename-requests[$index] 不是对象")
            request.toPendingMemberRenameRequest("rename-requests[$index]")
        }
    }

    override suspend fun approveMemberRename(session: SyncSession, requestId: String) {
        post(
            session.baseUrl,
            "/v1/family/rename-requests/${requireOpaqueActionId(requestId, "改名申请")}/approve",
            session.accessToken,
            buildJsonObject {},
        )
    }

    override suspend fun rejectMemberRename(session: SyncSession, requestId: String) {
        post(
            session.baseUrl,
            "/v1/family/rename-requests/${requireOpaqueActionId(requestId, "改名申请")}/reject",
            session.accessToken,
            buildJsonObject {},
        )
    }

    override suspend fun cancelMyMemberRename(session: SyncSession) {
        post(
            session.baseUrl,
            "/v1/family/rename-requests/cancel",
            session.accessToken,
            buildJsonObject {},
        )
    }

    override suspend fun addFamilyMember(
        session: SyncSession,
        displayName: String,
    ): FamilyMember {
        val response = post(
            session.baseUrl,
            "/v1/family/members",
            session.accessToken,
            buildJsonObject { put("display_name", requireMemberDisplayName(displayName)) },
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
        return FamilyMember(
            displayName = response.requiredNonBlankString("display_name", "member-create"),
            role = when (response.requiredString("role", "member-create")) {
                "member" -> FamilyRole.Member
                else -> throw IllegalArgumentException("member-create.role 无效")
            },
            isSelf = false,
            membershipId = response.requiredNonBlankString("membership_id", "member-create"),
            devices = emptyList(),
        )
    }

    override suspend fun renameFamilyMember(
        session: SyncSession,
        membershipId: String,
        displayName: String,
    ) {
        post(
            session.baseUrl,
            "/v1/family/members/${requireOpaqueActionId(membershipId, "家庭成员")}/display-name",
            session.accessToken,
            buildJsonObject { put("display_name", requireMemberDisplayName(displayName)) },
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun renameFamilyDevice(
        session: SyncSession,
        deviceId: String,
        deviceName: String,
    ) {
        post(
            session.baseUrl,
            "/v1/family/devices/${requireOpaqueActionId(deviceId, "家庭设备")}/display-name",
            session.accessToken,
            buildJsonObject { put("device_name", requireDeviceName(deviceName)) },
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        val normalized = requireNotNull(normalizeFamilyNameForWire(familyName)) {
            "家庭名不能为空"
        }
        post(
            session.baseUrl,
            "/v1/family/name",
            session.accessToken,
            buildJsonObject { put("family_name", normalized) },
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun readCurrentSourceRelations(
        session: SyncSession,
        request: CurrentSourceRelationsRequest,
    ): CurrentSourceRelationsSnapshot {
        session.requireCurrentReplicaTransport()
        require(request.familyId == session.familyId && request.generation == session.pullGeneration)
        val body = request.toCurrentSourceRelationsJson()
        val json = familyHttpDeadlines.execute(FamilyHttpOperation.SessionRead) {
            requestJsonWithEvidence(
                base = session.baseUrl,
                path = "/v1/source-relations/current",
                method = "POST",
                token = session.accessToken,
                body = body,
                successLimitBytes = MAX_CURRENT_SOURCE_RELATION_RESPONSE_BYTES,
                successResponseKind = "current source relations JSON",
            ).json
        }
        return json.toCurrentSourceRelationsSnapshot(request).also {
            it.validateFor(request, session.pullCursor)
        }
    }

    override suspend fun memberDirectory(session: SyncSession): FamilyMemberDirectorySnapshot {
        val json = get(session.baseUrl, "/v1/family/members", session.accessToken)
        json.requireExactKeys(setOf("directory_generation", "members"), "members")
        val members = json.requiredArray("members", "members").mapIndexed { index, memberElement ->
            val member = memberElement as? JsonObject
                ?: throw IllegalArgumentException("members[$index] 不是对象")
            val devices = member["devices"]?.let { deviceElement ->
                val array = deviceElement as? JsonArray
                    ?: throw IllegalArgumentException("members[$index].devices 不是数组")
                array.mapIndexed { deviceIndex, item ->
                    val device = item as? JsonObject ?: throw IllegalArgumentException(
                        "members[$index].devices[$deviceIndex] 不是对象",
                    )
                    FamilyDevice(
                        deviceId = device.requiredNonBlankString(
                            "device_id",
                            "members[$index].devices[$deviceIndex]",
                        ),
                        deviceName = requireDeviceName(
                            device.requiredNonBlankString(
                                "device_name",
                                "members[$index].devices[$deviceIndex]",
                            ),
                        ),
                        lastUsedAtEpochSeconds = device.requiredLong(
                            "last_used_at",
                            "members[$index].devices[$deviceIndex]",
                        ),
                        isCurrent = device.requiredBoolean(
                            "is_current",
                            "members[$index].devices[$deviceIndex]",
                        ),
                    )
                }
            }
            FamilyMember(
                displayName = member.requiredNonBlankString("display_name", "members[$index]"),
                role = when (member.requiredString("role", "members[$index]")) {
                    "owner" -> FamilyRole.Owner
                    "member" -> FamilyRole.Member
                    else -> throw IllegalArgumentException("members[$index].role 无效")
                },
                isSelf = member.requiredBoolean("is_self", "members[$index]"),
                membershipId = member.requiredNonBlankString(
                    "membership_id",
                    "members[$index]",
                ),
                devices = devices,
                lastSyncAtEpochSeconds = member.optionalNullableLong(
                    "last_sync_at",
                    "members[$index]",
                ),
            )
        }
        val generation = json.requiredNonBlankString("directory_generation", "members")
        require(SHA256_HEX_PATTERN.matches(generation)) { "members.directory_generation 无效" }
        return FamilyMemberDirectorySnapshot(
            generation = generation,
            members = members,
        )
    }

    private fun JsonObject.toPendingMemberRenameRequest(
        context: String,
        fallbackMembershipId: String? = null,
    ): PendingMemberRenameRequest = PendingMemberRenameRequest(
        requestId = requiredNonBlankString("request_id", context),
        membershipId = this["membership_id"]?.let {
            requiredNonBlankString("membership_id", context)
        } ?: fallbackMembershipId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$context 缺少 membership_id"),
        currentDisplayName = requiredNonBlankString("current_display_name", context),
        requestedDisplayName = requiredNonBlankString("requested_display_name", context),
        createdAtEpochSeconds = requiredLong("created_at", context),
        expiresAtEpochSeconds = requiredLong("expires_at", context),
    )

    private fun requireOpaqueActionId(value: String, label: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.none { it == '/' || it == '?' || it == '#' }) {
            "请选择有效的$label"
        }
        return normalized
    }

    override suspend fun leave(session: SyncSession) {
        post(
            session.baseUrl,
            "/v1/leave",
            session.accessToken,
            buildJsonObject {},
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun logoutCurrentDevice(session: SyncSession) {
        post(
            session.baseUrl,
            "/v1/device/logout",
            session.accessToken,
            buildJsonObject {},
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) {
        val id = requireOpaqueActionId(deviceId, "家庭设备")
        post(
            session.baseUrl,
            "/v1/family/devices/$id/revoke",
            session.accessToken,
            buildJsonObject {},
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun removeMember(session: SyncSession, membershipId: String) {
        val id = membershipId.trim()
        require(id.isNotEmpty()) { "请选择要移除的家人" }
        post(
            session.baseUrl,
            "/v1/family/members/remove",
            session.accessToken,
            buildJsonObject { put("membership_id", id) },
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun deleteFamily(
        session: SyncSession,
        familyName: String,
        rootPassword: String,
    ) {
        val normalizedFamilyName = requireNotNull(normalizeFamilyNameForWire(familyName)) {
            "请输入家庭名"
        }
        require(rootPassword.isNotBlank()) { "请输入管理员根密码" }
        post(
            session.baseUrl,
            "/v1/family/delete",
            session.accessToken,
            buildJsonObject { put("family_name", normalizedFamilyName) },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to rootPassword),
            familyOperation = FamilyHttpOperation.SessionWrite,
        )
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        requestBytes(
            session.baseUrl,
            "/v1/media/$clientUuid",
            "GET",
            session.accessToken,
            familyOperation = FamilyHttpOperation.MediaGet,
        )

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata {
        session.requireCurrentReplicaTransport()
        val json = get(
            session.baseUrl,
            "/v1/app-update",
            session.accessToken,
            familyOperation = FamilyHttpOperation.AppUpdateMetadata,
        )
        return json.toAppUpdateMetadata()
    }

    override suspend fun heartbeat(session: SyncSession): SyncHeartbeat {
        session.requireCurrentReplicaTransport()
        // Same trusted-endpoint (TOFU) plumbing as every other authenticated
        // call: withKeepAlive resolves the pinned endpoint and SpkiPinMismatch
        // surfaces unchanged. Probe budget: single attempt, seconds-level
        // timeouts. v1 sends no `wait` query parameter.
        val json = get(
            session.baseUrl,
            "/v1/sync/heartbeat",
            session.accessToken,
            familyOperation = FamilyHttpOperation.Probe,
        )
        return json.toSyncHeartbeat()
    }

    override suspend fun downloadAppUpdateApk(
        session: SyncSession,
        target: OutputStream,
    ): AppUpdateApkDownload {
        session.requireCurrentReplicaTransport()
        return requestBytesToSink(
            session.baseUrl,
            "/v1/app-update/apk",
            "GET",
            session.accessToken,
            target = target,
            successLimitBytes = MAX_SYNC_APP_UPDATE_APK_BYTES,
            successResponseKind = "更新包",
            readTimeoutMillis = 120_000,
            familyOperation = null,
        )
    }

    override suspend fun stageBundle(
        session: SyncSession,
        draft: AtomicBundleDraft,
    ): BundleStageStatus {
        session.requireCurrentReplicaTransport()
        val body = buildJsonObject {
            put("bundle_id", draft.bundleId)
            put("root", draft.root.toJson())
            put("media", buildJsonArray { draft.media.forEach { add(it.toJson()) } })
            put("generation", session.pullGeneration)
        }
        return post(session.baseUrl, "/v1/bundles", session.accessToken, body).toBundleStageStatus()
    }

    override suspend fun commitBundle(
        session: SyncSession,
        bundleId: String,
    ): BundleCommitResult {
        session.requireCurrentReplicaTransport()
        val body = buildJsonObject {
            put("generation", session.pullGeneration)
        }
        val json = post(
            session.baseUrl,
            "/v1/bundles/$bundleId/commit",
            session.accessToken,
            body,
        )
        return BundleCommitResult(
            bundleId = json.requiredNonBlankString("bundle_id", "bundle commit"),
            status = json.requiredNonBlankString("status", "bundle commit"),
            applied = json.requiredLong("applied", "bundle commit").toInt(),
            cursor = json.requiredLong("cursor", "bundle commit"),
            recordAuthors = json.recordAuthors(),
        )
    }

    // Writes are single-attempt unless the caller names an exact server receipt.
    private suspend fun post(
        base: String,
        path: String,
        token: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        familyOperation: FamilyHttpOperation = FamilyHttpOperation.SessionWrite,
    ): JsonObject = requestJson(
        base,
        path,
        "POST",
        token,
        body,
        extraHeaders,
        familyOperation = familyOperation,
    )

    private suspend fun post(
        endpoint: TrustedEndpointProfile,
        path: String,
        token: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        familyOperation: FamilyHttpOperation = FamilyHttpOperation.SessionWrite,
    ): JsonObject = requestJson(
        endpoint.origin,
        path,
        "POST",
        token,
        body,
        extraHeaders,
        trustedEndpoint = endpoint,
        familyOperation = familyOperation,
    )

    private suspend fun get(
        base: String,
        path: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        familyOperation: FamilyHttpOperation = FamilyHttpOperation.SessionRead,
    ): JsonObject = requestJson(
        base,
        path,
        "GET",
        token,
        null,
        extraHeaders,
        familyOperation = familyOperation,
    )

    private suspend fun get(
        endpoint: TrustedEndpointProfile,
        path: String,
        familyOperation: FamilyHttpOperation = FamilyHttpOperation.Probe,
    ): JsonObject = requestJson(
        base = endpoint.origin,
        path = path,
        method = "GET",
        token = null,
        body = null,
        trustedEndpoint = endpoint,
        familyOperation = familyOperation,
    )

    private suspend fun requestJson(
        base: String,
        path: String,
        method: String,
        token: String?,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
        retryOperation: SyncRetryOperation? = null,
        familyOperation: FamilyHttpOperation = FamilyHttpOperation.SessionWrite,
        onRequestBodyStarted: () -> Unit = {},
    ): JsonObject {
        val send = suspend {
            requestJsonWithEvidence(
                base = base,
                path = path,
                method = method,
                token = token,
                body = body,
                extraHeaders = extraHeaders,
                trustedEndpoint = trustedEndpoint,
                retryOperation = retryOperation,
                onRequestBodyStarted = onRequestBodyStarted,
            ).json
        }
        return if (retryOperation != null) {
            send()
        } else {
            familyHttpDeadlines.execute(familyOperation, send)
        }
    }

    private suspend fun requestPullJson(
        base: String,
        path: String,
        token: String,
        page: PullPageRequest,
    ): JsonObject = requestJsonWithEvidence(
        base = base,
        path = path,
        method = "GET",
        token = token,
        body = null,
        extraHeaders = mapOf(
            "Accept-Encoding" to page.encoding.wireName,
            "X-Lezi-Media-Identity" to "v1",
        ),
        successLimitBytes = page.budget.maxEncodedBytes,
        successResponseKind = "pull encoded JSON",
        retryOperation = SyncRetryOperation.Pull,
        pullPage = page,
    ).json

    private suspend fun requestJsonWithEvidence(
        base: String,
        path: String,
        method: String,
        token: String?,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
        successLimitBytes: Int = MAX_SYNC_JSON_RESPONSE_BYTES,
        successResponseKind: String = "JSON",
        retryOperation: SyncRetryOperation? = null,
        pullPage: PullPageRequest? = null,
        onRequestBodyStarted: () -> Unit = {},
    ): JsonTransportResponse = withKeepAlive(
        base = base,
        path = path,
        method = method,
        token = token,
        extraHeaders = extraHeaders,
        trustedEndpoint = trustedEndpoint,
        retryOperation = retryOperation,
        prepareConnection = { connection ->
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8",
                )
            }
        },
    ) { exchange ->
        if (body != null) {
            onRequestBodyStarted()
            val familyWrite = currentCoroutineContext()[FamilyHttpAttemptContext]
            if (familyWrite == null) {
                OutputStreamWriter(exchange.connection.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            } else {
                val writeStallMillis = currentJsonWriteStallTimeoutMillis()
                val writeWatchdog = UploadWriteWatchdog(
                    connection = exchange.connection,
                    timeoutMillis = writeStallMillis,
                    newTimeoutException = { cause ->
                        FamilyHttpWriteStallException(writeStallMillis, cause)
                    },
                )
                try {
                    writeWatchdog.arm()
                    OutputStreamWriter(exchange.connection.outputStream, Charsets.UTF_8).use {
                        it.write(body.toString())
                    }
                    writeWatchdog.checkNotTimedOut()
                } catch (error: IOException) {
                    writeWatchdog.rethrowIfTimedOut(error)
                } finally {
                    writeWatchdog.close()
                }
            }
        }
        val (code, bytes, retryAfterHeader) = exchange.readBoundedBody(
            successLimitBytes = successLimitBytes,
            successResponseKind = successResponseKind,
        )
        val decodedBytes = if (code in 200..299 && pullPage != null) {
            decodePullBody(
                encoded = bytes,
                contentEncoding = exchange.connection.getHeaderField("Content-Encoding"),
                page = pullPage,
            )
        } else {
            bytes
        }
        val text = decodedBytes.toString(Charsets.UTF_8)
        if (code !in 200..299) {
            throw SyncHttpException(
                statusCode = code,
                responseBody = text,
                retryAfterHeader = retryAfterHeader,
            )
        }
        require(text.isNotBlank()) { "家庭服务器 JSON 响应为空" }
        JsonTransportResponse(
            json = Json.parseToJsonElement(text).jsonObject,
            encodedBytes = bytes.size,
        )
    }

    private suspend fun requestBytes(
        base: String,
        path: String,
        method: String,
        token: String,
        body: ByteArray? = null,
        mime: String? = null,
        successLimitBytes: Int = MAX_SYNC_MEDIA_RESPONSE_BYTES,
        successResponseKind: String = "媒体",
        readTimeoutMillis: Int? = null,
        familyOperation: FamilyHttpOperation? = FamilyHttpOperation.Session,
    ): ByteArray {
        val send = suspend {
            requestBytesOnce(
                base = base,
                path = path,
                method = method,
                token = token,
                body = body,
                mime = mime,
                successLimitBytes = successLimitBytes,
                successResponseKind = successResponseKind,
                readTimeoutMillis = readTimeoutMillis,
            )
        }
        val operation = familyOperation
        return if (operation == null) {
            send()
        } else {
            familyHttpDeadlines.execute(operation, send)
        }
    }

    private suspend fun requestBytesOnce(
        base: String,
        path: String,
        method: String,
        token: String,
        body: ByteArray?,
        mime: String?,
        successLimitBytes: Int,
        successResponseKind: String,
        readTimeoutMillis: Int?,
    ): ByteArray = withKeepAlive(
        base = base,
        path = path,
        method = method,
        token = token,
        readTimeoutMillis = readTimeoutMillis,
        prepareConnection = { connection ->
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    mime ?: "application/octet-stream",
                )
            }
        },
    ) { exchange ->
        if (body != null) {
            exchange.connection.outputStream.use { it.write(body) }
        }
        val (code, bytes, retryAfterHeader) = exchange.readBoundedBody(
            successLimitBytes = successLimitBytes,
            successResponseKind = successResponseKind,
        )
        if (code !in 200..299) {
            throw SyncHttpException(
                code,
                bytes.toString(Charsets.UTF_8),
                retryAfterHeader,
            )
        }
        bytes
    }

    /**
     * Streaming sibling of [requestBytes] for large bounded downloads (app-update
     * APK): the success body is written chunk-by-chunk into [target] while the
     * sha256 is computed incrementally, so memory stays flat regardless of body
     * size. Same network semantics as [requestBytes]: single attempt unless a
     * deadline/retry context says otherwise, caller-controlled read timeout.
     */
    private suspend fun requestBytesToSink(
        base: String,
        path: String,
        method: String,
        token: String,
        target: OutputStream,
        successLimitBytes: Int,
        successResponseKind: String,
        readTimeoutMillis: Int? = null,
        familyOperation: FamilyHttpOperation? = FamilyHttpOperation.Session,
    ): AppUpdateApkDownload {
        val send = suspend {
            requestBytesToSinkOnce(
                base = base,
                path = path,
                method = method,
                token = token,
                target = target,
                successLimitBytes = successLimitBytes,
                successResponseKind = successResponseKind,
                readTimeoutMillis = readTimeoutMillis,
            )
        }
        val operation = familyOperation
        return if (operation == null) {
            send()
        } else {
            familyHttpDeadlines.execute(operation, send)
        }
    }

    private suspend fun requestBytesToSinkOnce(
        base: String,
        path: String,
        method: String,
        token: String,
        target: OutputStream,
        successLimitBytes: Int,
        successResponseKind: String,
        readTimeoutMillis: Int?,
    ): AppUpdateApkDownload = withKeepAlive(
        base = base,
        path = path,
        method = method,
        token = token,
        readTimeoutMillis = readTimeoutMillis,
    ) { exchange ->
        exchange.streamBoundedBodyTo(
            target = target,
            successLimitBytes = successLimitBytes,
            successResponseKind = successResponseKind,
        )
    }

    /** PUT binary body, parse JSON success response (bundle stage / causal preimage). */
    private suspend fun requestJsonStream(
        base: String,
        path: String,
        method: String,
        token: String,
        source: SyncMediaUploadSource,
        trustedEndpoint: TrustedEndpointProfile? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retryOperation: SyncRetryOperation? = null,
        familyOperation: FamilyHttpOperation? = null,
    ): JsonObject {
        require(source.contentLength in 0L..RecordPhotoResourcePolicy.maxUploadBytes) {
            "待上传媒体大小超出支持范围"
        }
        val send = suspend {
            requestJsonStreamOnce(
                base = base,
                path = path,
                method = method,
                token = token,
                source = source,
                trustedEndpoint = trustedEndpoint,
                extraHeaders = extraHeaders,
                retryOperation = retryOperation,
            )
        }
        return if (familyOperation == null) {
            send()
        } else {
            familyHttpDeadlines.execute(familyOperation, send)
        }
    }

    private suspend fun requestJsonStreamOnce(
        base: String,
        path: String,
        method: String,
        token: String,
        source: SyncMediaUploadSource,
        trustedEndpoint: TrustedEndpointProfile? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retryOperation: SyncRetryOperation? = null,
    ): JsonObject {
        return withKeepAlive(
            base = base,
            path = path,
            method = method,
            token = token,
            extraHeaders = extraHeaders,
            trustedEndpoint = trustedEndpoint,
            retryOperation = retryOperation,
            prepareConnection = { connection ->
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    // Canonical MIME is exact nullable user metadata, not a safe HTTP header.
                    "application/octet-stream",
                )
                connection.setFixedLengthStreamingMode(source.contentLength)
            },
        ) { exchange ->
            val writeStallMillis = currentCoroutineContext()[FamilyHttpAttemptContext]
                ?.boundedWriteStallTimeoutMillis()
                ?: uploadWriteStallTimeoutMillis
            val writeWatchdog = UploadWriteWatchdog(
                connection = exchange.connection,
                timeoutMillis = writeStallMillis,
            )
            try {
                writeWatchdog.arm()
                source.openStream().use { input ->
                    exchange.connection.outputStream.use { output ->
                        val buffer = ByteArray(RecordPhotoResourcePolicy.streamBufferBytes)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            written += count
                            require(written <= source.contentLength) {
                                "待上传媒体长度与声明不一致"
                            }
                            output.write(buffer, 0, count)
                            writeWatchdog.markProgress()
                        }
                        require(written == source.contentLength) {
                            "待上传媒体长度与声明不一致"
                        }
                    }
                }
                writeWatchdog.checkNotTimedOut()
            } catch (error: IOException) {
                writeWatchdog.rethrowIfTimedOut(error)
            } finally {
                writeWatchdog.close()
            }
            val (code, bytes, retryAfterHeader) = exchange.readBoundedBody(
                successLimitBytes = MAX_SYNC_JSON_RESPONSE_BYTES,
                successResponseKind = "JSON",
            )
            val text = bytes.toString(Charsets.UTF_8)
            if (code !in 200..299) {
                throw SyncHttpException(code, text, retryAfterHeader)
            }
            require(text.isNotBlank()) { "家庭服务器 JSON 响应为空" }
            Json.parseToJsonElement(text).jsonObject
        }
    }

    private suspend fun <T> withKeepAlive(
        base: String,
        path: String,
        method: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
        retryOperation: SyncRetryOperation? = null,
        readTimeoutMillis: Int? = null,
        prepareConnection: (HttpURLConnection) -> Unit = {},
        exchange: suspend (KeepAliveExchange) -> T,
    ): T {
        StartupBoundaryObservation.record("network:business-request")
        val resolvedEndpoint = trustedEndpoint ?: trustedEndpointResolver?.resolve(base)
        val identity = keepAliveIdentity(base, resolvedEndpoint)
        return withContext(Dispatchers.IO) {
            val retryAttempt = currentCoroutineContext()[SyncRetryAttemptContext]
                ?.takeIf { it.operation == retryOperation }
            val familyAttempt = currentCoroutineContext()[FamilyHttpAttemptContext]
            val cycleBudget = currentCoroutineContext()[ElapsedBudgetContext]
            if (familyAttempt != null) {
                resolveFamilyHostIfNeeded(base, familyAttempt)
            }
            fun remainingMillis(): Long? = listOfNotNull(
                retryAttempt?.remainingMillis(),
                familyAttempt?.remainingMillis(),
                cycleBudget?.remainingMillis(),
            ).minOrNull()?.also { remaining ->
                if (remaining <= 0) {
                    when {
                        cycleBudget != null && cycleBudget.remainingMillis() <= 0 ->
                            throw FamilyHttpException(cycleBudget.kind)
                        retryAttempt != null && retryAttempt.remainingMillis() <= 0 ->
                            throw SyncRetryBudgetExceededException(requireNotNull(retryOperation))
                        familyAttempt != null ->
                            throw FamilyHttpException(familyAttempt.operation.elapsedKind)
                        else -> throw FamilyHttpException(FamilyHttpFailureKind.ResponseTimedOut)
                    }
                }
            }
            prepareKeepAlive(token, identity)
            val connection = open(
                base,
                path,
                method,
                token,
                extraHeaders,
                resolvedEndpoint,
                retryOperation,
                remainingMillis(),
                familyAttempt,
            )
            val configuredReadTimeout = readTimeoutMillis ?: connection.readTimeout
            fun refreshResponseDeadline() {
                connection.readTimeout = boundedHttpTimeout(configuredReadTimeout, remainingMillis())
            }
            var deadlineWatchdog: FamilyHttpDisconnectWatchdog? = null
            val cancelHandle = currentCoroutineContext()[Job]?.cancelActiveIo { connection.disconnect() }
            val scope = KeepAliveExchange(connection, token, ::refreshResponseDeadline)
            try {
                prepareConnection(connection)
                // Opening/configuring an exchange can consume time too. Never
                // restart a relative watchdog from the pre-open snapshot.
                val left = remainingMillis()
                connection.connectTimeout = boundedHttpTimeout(connection.connectTimeout, left)
                refreshResponseDeadline()
                deadlineWatchdog = left?.let {
                    FamilyHttpDisconnectWatchdog(it) { connection.disconnect() }
                }
                if (familyAttempt != null) {
                    connection.connectFamilyHttp()
                }
                // Connect/TLS (including platform DNS) shares the same deadline.
                refreshResponseDeadline()
                exchange(scope).also { remainingMillis() }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (error.isFamilyTrustFailure()) throw error
                // A deadline watchdog can surface as an ordinary socket I/O
                // failure. Preserve its existing absolute-budget owner before
                // falling back to transport classification (including no-family
                // APK/upload exchanges). Cancellation and trust failures win.
                remainingMillis()
                if (familyAttempt != null) {
                    error.asFamilyHttpFailure(familyAttempt.operation)
                }
                throw error
            } finally {
                cancelHandle?.dispose()
                deadlineWatchdog?.close()
                finishKeepAlive(connection, scope.keepAlive, identity)
            }
        }
    }

    private inner class KeepAliveExchange(
        val connection: HttpURLConnection,
        private val token: String?,
        private val refreshResponseDeadline: () -> Unit,
    ) {
        var keepAlive: Boolean = false
            private set

        fun readBoundedBody(
            successLimitBytes: Int,
            successResponseKind: String,
        ): BoundedHttpResponse {
            refreshResponseDeadline()
            val response = readBoundedBody(
                connection = connection,
                successLimitBytes = successLimitBytes,
                successResponseKind = successResponseKind,
            )
            refreshResponseDeadline()
            keepAlive = shouldKeepAlive(token, response.code)
            return response
        }

        /**
         * Streams a 2xx body chunk-by-chunk into [target] while hashing, enforcing
         * [successLimitBytes] on the cumulative bytes actually received — never the
         * declared Content-Length. A non-2xx response is read as a bounded error
         * body and surfaces as [SyncHttpException]. The keep-alive handle is only
         * claimed after the full body reached EOF: any mid-body cap or IO failure
         * leaves the connection disconnected. Returns the digest receipt for
         * exactly the bytes handed to [target]; partial bytes remain there for the
         * caller to clean up.
         */
        fun streamBoundedBodyTo(
            target: OutputStream,
            successLimitBytes: Int,
            successResponseKind: String,
        ): AppUpdateApkDownload {
            refreshResponseDeadline()
            val code = connection.responseCode
            val retryAfterHeader = connection.getHeaderField("Retry-After")
            if (code !in 200..299) {
                keepAlive = false
                val response = readBoundedBody(
                    connection = connection,
                    successLimitBytes = successLimitBytes,
                    successResponseKind = successResponseKind,
                )
                throw SyncHttpException(
                    code,
                    response.bytes.toString(Charsets.UTF_8),
                    response.retryAfterHeader,
                )
            }
            val declaredBytes = connection.contentLengthLong
            val hasher = MediaContentDigest.newHasher()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0L
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > successLimitBytes - totalBytes) {
                        throw SyncResponseTooLargeException(
                            successResponseKind,
                            successLimitBytes,
                            declaredBytes,
                        )
                    }
                    target.write(buffer, 0, count)
                    hasher.update(buffer, 0, count)
                    totalBytes += count
                }
            }
            refreshResponseDeadline()
            keepAlive = true
            return AppUpdateApkDownload(
                sha256 = MediaContentDigest.finish(hasher),
                byteCount = totalBytes,
            )
        }
    }

    private fun keepAliveIdentity(
        base: String,
        trustedEndpoint: TrustedEndpointProfile?,
    ): AuthenticatedKeepAliveIdentity {
        val rawOrigin = trustedEndpoint?.origin ?: base
        val origin = runCatching { normalizeHttpsOrigin(rawOrigin) }
            .getOrElse { rawOrigin.trimEnd('/') }
        return AuthenticatedKeepAliveIdentity(
            origin = origin,
            spkiSha256 = trustedEndpoint?.spkiSha256,
        )
    }

    private fun prepareKeepAlive(
        token: String?,
        identity: AuthenticatedKeepAliveIdentity,
    ) {
        val authenticated = !token.isNullOrBlank()
        synchronized(keepAliveLock) {
            if (!authenticated) {
                // Anonymous mismatch is not an identity change. Probes open their
                // own connection and must not disconnect sockets still held for
                // an authenticated family session. Retry of that session still
                // sees the same handles.
                return
            }
            if (
                undetachedKeepAliveIdentity == identity &&
                undetachedKeepAliveHandles.isNotEmpty() &&
                !idleKeepAliveTtlExpiredLocked()
            ) {
                return
            }
            evictUndetachedHandlesLocked()
        }
    }

    /** Caller holds [keepAliveLock]. A null baseline means no completed exchange. */
    private fun idleKeepAliveTtlExpiredLocked(): Boolean {
        val idleSince = undetachedKeepAliveIdleSinceElapsedMillis ?: return false
        val idleMillis = keepAliveClock.snapshot().elapsedRealtimeMillis - idleSince
        return idleMillis >= IDLE_KEEP_ALIVE_TTL_MILLIS
    }

    private fun finishKeepAlive(
        connection: HttpURLConnection,
        keepAlive: Boolean,
        identity: AuthenticatedKeepAliveIdentity,
    ) {
        if (keepAlive) {
            synchronized(keepAliveLock) {
                if (undetachedKeepAliveIdentity != identity) {
                    evictUndetachedHandlesLocked()
                }
                undetachedKeepAliveIdentity = identity
                // Only completed exchanges enter this collection. A heartbeat is
                // not a data-round lease; age and bound its retained handles too.
                undetachedKeepAliveIdleSinceElapsedMillis =
                    keepAliveClock.snapshot().elapsedRealtimeMillis
                undetachedKeepAliveHandles += connection
                while (undetachedKeepAliveHandles.size > 16) {
                    undetachedKeepAliveHandles.removeAt(0).disconnect()
                }
            }
            return
        }
        synchronized(keepAliveLock) {
            undetachedKeepAliveHandles.remove(connection)
            if (undetachedKeepAliveHandles.isEmpty()) {
                undetachedKeepAliveIdentity = null
                undetachedKeepAliveIdleSinceElapsedMillis = null
            }
        }
        connection.disconnect()
    }

    private fun evictUndetachedHandlesLocked() {
        for (handle in undetachedKeepAliveHandles) {
            handle.disconnect()
        }
        undetachedKeepAliveHandles.clear()
        undetachedKeepAliveIdentity = null
        undetachedKeepAliveIdleSinceElapsedMillis = null
    }

    private fun resolveFamilyHostIfNeeded(
        base: String,
        familyAttempt: FamilyHttpAttemptContext,
    ) {
        val host = runCatching { URL(base).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        resolveFamilyHttpHost(
            host,
            familyAttempt.boundedConnectTimeoutMillis(),
            nameResolver,
        )
    }

    private suspend fun currentJsonWriteStallTimeoutMillis(): Long {
        val family = currentCoroutineContext()[FamilyHttpAttemptContext]
            ?: return jsonWriteStallTimeoutMillis
        return minOf(jsonWriteStallTimeoutMillis, family.boundedWriteStallTimeoutMillis())
    }

    private fun open(
        base: String,
        path: String,
        method: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
        retryOperation: SyncRetryOperation? = null,
        remainingMillis: Long? = null,
        familyAttempt: FamilyHttpAttemptContext? = null,
    ): HttpURLConnection =
        connectionFactory.open(URL("${base.trimEnd('/')}$path")).apply {
            trustedEndpoint?.spkiSha256?.let { pin ->
                require(this is HttpsURLConnection) {
                    "固定证书的家庭服务器必须使用 HTTPS"
                }
                sslSocketFactory = pinnedSslSocketFactory(pin)
            }
            requestMethod = method
            val configuredConnect = when {
                retryOperation != null -> retryOperation.budget.connectTimeoutMillis
                familyAttempt != null -> familyAttempt.operation.budget.connectTimeoutMillis
                else -> 8_000
            }
            val configuredRead = when {
                retryOperation != null -> retryOperation.budget.responseTimeoutMillis
                familyAttempt != null -> familyAttempt.operation.budget.responseTimeoutMillis
                else -> 8_000
            }
            connectTimeout = boundedHttpTimeout(configuredConnect, remainingMillis)
            readTimeout = boundedHttpTimeout(configuredRead, remainingMillis)
            useCaches = false
            instanceFollowRedirects = false
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            // Advertise local versionCode for server min gates. Must not depend on a
            // bearer token: disaster-restore start uses bootstrap secret only (token=null),
            // and after require_supported_client on restore writes a missing header is
            // treated as below-min client_update_required (empty-family restore deadlock).
            clientVersionCode?.takeIf { it > 0 }?.let { code ->
                setRequestProperty(CLIENT_VERSION_CODE_HEADER, code.toString())
            }
            setRequestProperty("X-Lezi-Sync-Capabilities", if (path.startsWith("/v1/disaster-restore/"))
                "nursing_plan_intent_v1,restore_authority_v1" else "nursing_plan_intent_v1")
            extraHeaders.forEach(::setRequestProperty)
        }

    private fun readBoundedBody(
        connection: HttpURLConnection,
        successLimitBytes: Int,
        successResponseKind: String,
    ): BoundedHttpResponse {
        val code = connection.responseCode
        val retryAfterHeader = connection.getHeaderField("Retry-After")
        val success = code in 200..299
        val limitBytes = if (success) successLimitBytes else MAX_SYNC_ERROR_RESPONSE_BYTES
        val responseKind = if (success) successResponseKind else "错误"
        val declaredBytes = connection.contentLengthLong
        if (declaredBytes > limitBytes) {
            throw SyncResponseTooLargeException(responseKind, limitBytes, declaredBytes)
        }
        val stream = if (success) connection.inputStream else connection.errorStream
        val bytes = stream?.use {
            it.readBytesUpTo(
                limitBytes = limitBytes,
                responseKind = responseKind,
                declaredBytes = declaredBytes,
            )
        } ?: byteArrayOf()
        return BoundedHttpResponse(code, bytes, retryAfterHeader)
    }
}

private fun decodePullBody(
    encoded: ByteArray,
    contentEncoding: String?,
    page: PullPageRequest,
): ByteArray {
    val normalizedEncoding = contentEncoding?.trim()?.lowercase().orEmpty()
    val stream = when (page.encoding) {
        PullResponseEncoding.Identity -> {
            require(normalizedEncoding.isEmpty() || normalizedEncoding == "identity") {
                "pull Content-Encoding 与协商 identity 不一致"
            }
            ByteArrayInputStream(encoded)
        }
        PullResponseEncoding.Gzip -> {
            require(normalizedEncoding == "gzip") {
                "pull Content-Encoding 与协商 gzip 不一致"
            }
            try {
                GZIPInputStream(ByteArrayInputStream(encoded))
            } catch (error: IOException) {
                throw IllegalArgumentException("pull gzip 截断或损坏", error)
            }
        }
    }
    return try {
        stream.use {
            it.readBytesUpTo(
                limitBytes = page.budget.maxDecodedBytes,
                responseKind = "pull decoded JSON",
                declaredBytes = encoded.size.toLong(),
            )
        }
    } catch (error: SyncResponseTooLargeException) {
        throw error
    } catch (error: IOException) {
        throw IllegalArgumentException("pull gzip 截断或损坏", error)
    }
}

private fun boundedHttpTimeout(configuredMillis: Int, remainingMillis: Long?): Int {
    if (remainingMillis == null) return configuredMillis
    require(remainingMillis > 0) { "同步重试剩余预算必须大于 0" }
    return minOf(configuredMillis.toLong(), remainingMillis, Int.MAX_VALUE.toLong())
        .toInt()
        .coerceAtLeast(1)
}

/**
 * HttpURLConnection has connect/read timeouts but no write timeout. A wall-clock
 * deadline remains independent of coroutine cancellation so it can disconnect a
 * socket even while write() blocks.
 */
private class UploadWriteWatchdog(
    private val connection: HttpURLConnection,
    private val timeoutMillis: Long,
    private val newTimeoutException: (IOException?) -> SocketTimeoutException = { cause ->
        SocketTimeoutException("家庭服务器上传写入超过 ${timeoutMillis}ms 无进展").also {
            if (cause != null) it.initCause(cause)
        }
    },
) : AutoCloseable {
    private val timedOut = AtomicBoolean(false)
    private var deadline: RealtimeDeadline? = null

    fun arm() {
        check(deadline == null) { "上传写入看门狗已经启动" }
        deadline = RealtimeDeadline(timeoutMillis, "lezi-sync-upload-watchdog") {
            timedOut.set(true)
            connection.disconnect()
        }
    }

    fun markProgress() {
        checkNotTimedOut()
        deadline?.reset()
    }

    fun checkNotTimedOut() {
        if (timedOut.get()) throw timeoutException()
    }

    fun rethrowIfTimedOut(error: IOException): Nothing {
        if (!timedOut.get()) throw error
        throw timeoutException(error)
    }

    override fun close() {
        deadline?.close()
        deadline = null
    }

    private fun timeoutException(cause: IOException? = null): SocketTimeoutException =
        newTimeoutException(cause)
}

private fun SyncSession.requireCurrentReplicaTransport(
    allowBlankGeneration: Boolean = false,
) {
    require(isJoined) { "当前同步会话尚未加入家庭" }
    require(deviceId.isNotBlank()) { "当前同步会话缺少 device_id" }
    if (!allowBlankGeneration) {
        require(pullGeneration.isNotBlank()) { "当前同步会话缺少 generation" }
    }
}

private fun InputStream.readBytesUpTo(
    limitBytes: Int,
    responseKind: String,
    declaredBytes: Long,
): ByteArray {
    val initialCapacity = declaredBytes
        .takeIf { it in 1..limitBytes.toLong() }
        ?.toInt()
        ?: minOf(DEFAULT_BUFFER_SIZE, limitBytes)
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > limitBytes - totalBytes) {
            throw SyncResponseTooLargeException(responseKind, limitBytes, declaredBytes)
        }
        output.write(buffer, 0, count)
        totalBytes += count
    }
    return output.toByteArray()
}

private fun SyncEntity.toJson() = buildJsonObject {
    put("type", type)
    put("client_uuid", clientUuid)
    put("payload", Json.parseToJsonElement(payloadJson))
    put("updated_at", updatedAt)
    deletedAt?.let { put("deleted_at", it) }
}

private fun JsonObject.toSyncEntity(context: String): SyncEntity = SyncEntity(
    type = requiredNonBlankString("type", context),
    clientUuid = requiredNonBlankString("client_uuid", context),
    payloadJson = (get("payload") as? JsonObject)?.toString()
        ?: throw IllegalArgumentException("$context.payload 缺失或无效"),
    updatedAt = requiredLong("updated_at", context),
    deletedAt = requiredNullableLong("deleted_at", context),
    rev = (get("rev") as? JsonPrimitive)?.longOrNull ?: 0,
    versionId = optionalNonBlankString("version_id", context),
    conflictSummary = optionalConflictSummary(context),
    sourceRelationSummary = optionalSourceRelationSummary(context),
    media = optionalCausalMedia(context),
    mediaIdentity = optionalPullMediaIdentity(context),
)

private fun JsonObject.entities(context: String): List<SyncEntity> =
    requiredArray("entities", context).mapIndexed { index, element ->
        val value = element as? JsonObject
            ?: throw IllegalArgumentException("$context.entities[$index] 不是对象")
        val entityContext = "$context.entities[$index]"
        SyncEntity(
            type = value.requiredNonBlankString("type", entityContext),
            clientUuid = value.requiredNonBlankString("client_uuid", entityContext),
            payloadJson = (value["payload"] as? JsonObject)?.toString()
                ?: throw IllegalArgumentException("$entityContext.payload 缺失或无效"),
            updatedAt = value.requiredLong("updated_at", entityContext),
            deletedAt = value.requiredNullableLong("deleted_at", entityContext),
            rev = value.requiredLong("rev", entityContext),
            versionId = value.optionalNonBlankString("version_id", entityContext),
            conflictSummary = value.optionalConflictSummary(entityContext),
            sourceRelationSummary = value.optionalSourceRelationSummary(entityContext),
            media = value.optionalCausalMedia(entityContext),
            mediaIdentity = value.optionalPullMediaIdentity(entityContext),
        )
    }

internal const val MAX_CAUSAL_UNITS = 64

private fun CausalMutationUnit.toCausalJson(): JsonObject = buildJsonObject {
    put("mutation_id", mutationId)
    if (baseVersion == null) {
        put("base_version", JsonNull)
    } else {
        put("base_version", baseVersion)
    }
    put("entity_type", entityType)
    put("client_uuid", clientUuid)
    put(
        "root",
        Json.parseToJsonElement(rootJson).jsonObject,
    )
    put(
        "media",
        buildJsonArray {
            media.sortedBy(CausalMediaItem::mediaUuid).forEach { item ->
                add(item.toJson())
            }
        },
    )
    put("deleted", deleted)
}

private fun CausalMediaItem.toJson(): JsonObject = buildJsonObject {
    put("media_uuid", mediaUuid)
    put("role", role)
    put("sha256", sha256)
    put("byte_size", byteSize)
    put("mime", requireCanonicalMediaMime(mime))
    if (width == null) put("width", JsonNull) else put("width", width)
    if (height == null) put("height", JsonNull) else put("height", height)
}

private data class PostedCausalBatch(
    val response: JsonObject,
    val expectedKeys: Set<Pair<String, String>>,
    val expectedByMutation: Map<String, CausalMutationUnit>,
)

private inline fun <T> decodeCausalProof(
    session: SyncSession,
    response: JsonObject,
    decode: () -> T,
): T = try {
    decode()
} catch (error: IllegalArgumentException) {
    val serverGeneration = response["generation"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: session.pullGeneration
    throw AuthorityProofException(serverGeneration, error)
}

private fun parseCausalCommitBatchResult(
    posted: PostedCausalBatch,
    session: SyncSession,
    context: String,
): CausalCommitBatchResult {
    val response = posted.response
    val generation = response.requiredNonBlankString("generation", context)
    require(generation == session.pullGeneration) {
        "家庭服务器在因果同步期间变更了同步代际"
    }
    response.requireExactKeys(setOf("generation", "results"), context)
    val results = response.requiredArray("results", context).mapIndexed { index, item ->
        val value = item as? JsonObject
            ?: throw IllegalArgumentException("$context.results[$index] 不是对象")
        value.toCausalCommitUnitResult("$context.results[$index]")
    }
    validateCausalResultBinding(results, posted, context)
    return CausalCommitBatchResult(generation, results)
}

private fun validateCausalResultBinding(
    results: List<CausalCommitUnitResult>,
    posted: PostedCausalBatch,
    context: String,
) {
    val byMutation = results.groupBy(CausalCommitUnitResult::mutationId)
    require(byMutation.keys == posted.expectedByMutation.keys && byMutation.values.all { it.size == 1 }) {
        "家庭服务器因果响应 mutation_id 不完整、重复或包含多余 key"
    }
    require(results.map(CausalCommitUnitResult::mutationId) == posted.expectedByMutation.keys.toList()) {
        "家庭服务器因果响应顺序与请求不一致"
    }
    val byKey = results.groupBy { result ->
        val unit = posted.expectedByMutation.getValue(result.mutationId)
        unit.entityType to unit.clientUuid
    }
    require(byKey.keys == posted.expectedKeys && byKey.values.all { it.size == 1 }) {
        "家庭服务器因果响应 key 不完整、重复或包含多余 key"
    }
}

private fun JsonObject.toCausalCommitUnitResult(
    context: String,
): CausalCommitUnitResult {
    val status = requiredNonBlankString("status", context)
    require(status in setOf(CausalCommitStatus.ACCEPTED, CausalCommitStatus.MERGED, CausalCommitStatus.BRANCHED)) {
        "$context 未知因果 commit status: $status"
    }
    val expected = mutableSetOf("status", "mutation_id", "request_hash", "replay", "stable")
    if (status == CausalCommitStatus.BRANCHED) {
        expected += "branch_version_id"
        expected += "conflict_id"
    } else if ("conflict_id" in keys) {
        expected += "conflict_id"
    }
    requireExactKeys(expected, context)
    val stable = get("stable") as? JsonObject
        ?: throw IllegalArgumentException("$context.stable 不是对象")
    stable.requireExactKeys(setOf("version_id", "root", "media", "deleted", "deleted_at"), "$context.stable")
    val root = stable["root"] as? JsonObject
        ?: throw IllegalArgumentException("$context.stable.root 不是对象")
    val media = (stable["media"] as? JsonArray)?.mapIndexed { index, element ->
        (element as? JsonObject)?.toCausalMediaItem("$context.stable.media[$index]")
            ?: throw IllegalArgumentException("$context.stable.media[$index] 不是对象")
    } ?: throw IllegalArgumentException("$context.stable.media 不是数组")
    val deleted = stable.requiredBoolean("deleted", "$context.stable")
    val deletedAt = stable.requiredNullableLong("deleted_at", "$context.stable")
    require(deleted == (deletedAt != null)) { "$context.stable delete evidence 不一致" }
    return CausalCommitUnitResult(
        status = status,
        mutationId = requiredNonBlankString("mutation_id", context),
        requestHash = requiredNonBlankString("request_hash", context),
        stableVersionId = stable.requiredNonBlankString("version_id", "$context.stable"),
        stableRootJson = root.toString(),
        stableMedia = media,
        stableDeleted = deleted,
        stableDeletedAt = deletedAt,
        replay = requiredBoolean("replay", context),
        branchVersionId = optionalNonBlankString("branch_version_id", context),
        conflictId = optionalNonBlankString("conflict_id", context),
    )
}

private fun JsonObject.throwIfCausalCommitRejected(context: String) {
    if (get("status")?.jsonPrimitive?.contentOrNull != "rejected") return
    requireExactKeys(
        if ("mutation_id" in this) setOf("status", "mutation_id", "error") else setOf("status", "error"),
        context,
    )
    val error = get("error") as? JsonObject
        ?: throw IllegalArgumentException("$context.error 不是对象")
    error.requireExactKeys(setOf("code", "retryable"), "$context.error")
    require(!error.requiredBoolean("retryable", "$context.error")) {
        "$context terminal rejection 不得标记 retryable"
    }
    val code = error.requiredNonBlankString("code", "$context.error")
    require(code in CAUSAL_COMMIT_TERMINAL_CODES) {
        "$context terminal rejection code 未冻结: $code"
    }
    throw CausalCommitRejectedException(
        mutationId = optionalNonBlankString("mutation_id", context),
        code = code,
    )
}

private val CAUSAL_COMMIT_TERMINAL_CODES = setOf(
    "unknown_field",
    "missing_field",
    "wrong_type",
    "non_canonical_value",
    "invalid_domain",
    "content_drift",
    "unauthenticated",
    "forbidden",
    "capability_mismatch",
    "not_ready",
    "invalid_snapshot_token",
    "snapshot_expired",
    "snapshot_stale",
    "invalid_choice",
    "duplicate_choice",
    "incomplete_choices",
    "missing_restore_base",
    "incomplete_restore_base",
    "missing_restore_media",
    "cas_mismatch",
    "media_preimage_expired",
    "media_membership_mismatch",
    "media_sha256_mismatch",
    "media_byte_size_mismatch",
    "missing_media_bytes",
    "media_uuid_conflict",
)

private fun JsonObject.toCausalMediaItem(context: String): CausalMediaItem = CausalMediaItem(
    mediaUuid = requiredNonBlankString("media_uuid", context),
    role = requiredNonBlankString("role", context),
    sha256 = requiredNonBlankString("sha256", context),
    byteSize = requiredLong("byte_size", context),
    mime = parseCanonicalMediaMime(get("mime")),
    width = requiredNullableLong("width", context),
    height = requiredNullableLong("height", context),
)

private fun JsonObject.toCausalMediaPreimageReceipt(
    expectedMediaUuid: String,
    expectedSha256: String,
    expectedByteSize: Long,
): CausalMediaPreimageReceipt {
    val context = "causal media preimage"
    requireExactKeys(
        setOf("media_uuid", "status", "byte_size", "sha256", "expires_at"),
        context,
    )
    val receipt = CausalMediaPreimageReceipt(
        mediaUuid = requiredNonBlankString("media_uuid", context),
        status = requiredNonBlankString("status", context),
        byteSize = requiredLong("byte_size", context),
        sha256 = requiredNonBlankString("sha256", context),
        expiresAtEpochSeconds = requiredLong("expires_at", context),
    )
    require(receipt.mediaUuid == expectedMediaUuid) { "$context.media_uuid 与请求不一致" }
    require(receipt.status == "staged" || receipt.status == "consumed") {
        "$context.status 无效"
    }
    require(receipt.byteSize == expectedByteSize && receipt.byteSize >= 0L) {
        "$context.byte_size 与请求不一致"
    }
    require(receipt.sha256 == expectedSha256) { "$context.sha256 与请求不一致" }
    require(receipt.expiresAtEpochSeconds > 0L) { "$context.expires_at 无效" }
    return receipt
}

private fun JsonObject.optionalPullMediaIdentity(context: String): PullMediaIdentity? {
    val raw = get("media_identity") ?: return null
    val identity = raw as? JsonObject
        ?: throw IllegalArgumentException("$context.media_identity 必须是对象")
    val identityContext = "$context.media_identity"
    identity.requireExactKeys(setOf("media_uuid", "role", "sha256", "byte_size"), identityContext)
    require(requiredNonBlankString("type", context) == "media" &&
        requiredNullableLong("deleted_at", context) == null
    ) { "$identityContext 只能属于 live media" }
    val uuid = identity.requiredNonBlankString("media_uuid", identityContext)
    require(runCatching { java.util.UUID.fromString(uuid).toString() }.getOrNull() == uuid &&
        uuid == requiredNonBlankString("client_uuid", context)
    ) { "$identityContext.media_uuid 与媒体不一致" }
    val role = identity.requiredNonBlankString("role", identityContext)
    require(role in setOf("avatar", "log", "plan", "wake")) { "$identityContext.role 无效" }
    val digest = MediaContentDigest.requireValid(identity.requiredNonBlankString("sha256", identityContext))
    val byteSize = identity.requiredLong("byte_size", identityContext)
    require(byteSize > 0L) { "$identityContext.byte_size 无效" }
    return PullMediaIdentity(uuid, role, digest, byteSize)
}

private fun JsonObject.optionalCausalMedia(context: String): List<CausalMediaItem> {
    val raw = get("media") ?: return emptyList()
    if (raw is JsonNull) return emptyList()
    val array = raw as? JsonArray
        ?: throw IllegalArgumentException("$context.media 不是数组")
    return array.mapIndexed { index, element ->
        val item = element as? JsonObject
            ?: throw IllegalArgumentException("$context.media[$index] 不是对象")
        item.toConflictAcceptedMedia("$context.media[$index]")
    }
}

private fun JsonObject.optionalNonBlankString(key: String, context: String): String? =
    when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty).also {
            require(value.isString) { "$context.$key 无效" }
        }
        else -> throw IllegalArgumentException("$context.$key 无效")
    }

private fun JsonObject.optionalConflictSummary(context: String): PullConflictSummary? {
    val raw = get("conflict_summary") ?: return null
    if (raw is JsonNull) return null
    val value = raw as? JsonObject
        ?: throw IllegalArgumentException("$context.conflict_summary 不是对象")
    val summaryContext = "$context.conflict_summary"
    val branchIds = when (val branches = value["branch_version_ids"]) {
        null -> throw IllegalArgumentException("$summaryContext.branch_version_ids 缺失")
        JsonNull -> emptyList()
        is JsonArray -> branches.mapIndexed { index, element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException(
                    "$summaryContext.branch_version_ids[$index] 无效",
                )
        }
        else -> throw IllegalArgumentException("$summaryContext.branch_version_ids 无效")
    }
    return PullConflictSummary(
        conflictId = value.requiredNonBlankString("conflict_id", summaryContext),
        entityType = value.requiredNonBlankString("entity_type", summaryContext),
        clientUuid = value.requiredNonBlankString("client_uuid", summaryContext),
        stableVersionId = value.requiredNonBlankString("stable_version_id", summaryContext),
        branchVersionIds = branchIds,
    ).takeIfOpenBranches()
}

private fun JsonObject.optionalSourceRelationSummary(context: String): PullSourceRelationSummary? {
    val raw = get("source_relation_summary") ?: return null
    if (raw is JsonNull) return null
    val value = raw as? JsonObject
        ?: throw IllegalArgumentException("$context.source_relation_summary 不是对象")
    val summaryContext = "$context.source_relation_summary"
    val role = value.requiredNonBlankString("role", summaryContext)
    require(role == "display" || role == "source") {
        "$summaryContext.role 必须是 display 或 source"
    }
    val peers = when (val peerRaw = value["peer_ids"]) {
        null -> emptyList()
        JsonNull -> emptyList()
        is JsonArray -> peerRaw.mapIndexed { index, element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$summaryContext.peer_ids[$index] 无效")
        }
        else -> throw IllegalArgumentException("$summaryContext.peer_ids 无效")
    }
    return PullSourceRelationSummary(
        relationId = value.requiredNonBlankString("relation_id", summaryContext),
        role = role,
        peerIds = peers,
        autoAligned = when (val flag = value["auto_aligned"]) {
            null, JsonNull -> false
            is JsonPrimitive -> flag.booleanOrNull == true
            else -> throw IllegalArgumentException("$summaryContext.auto_aligned 无效")
        },
    )
}

/** Wire §8.2 closed resolution terminal: accepted or rejected. */
private fun JsonObject.toConflictResolveResult(context: String): ConflictResolveResult {
    val status = requiredNonBlankString("status", context)
    return when (status) {
        "accepted" -> {
            val required = setOf(
                "status",
                "resolution_mutation_id",
                "stable_version_id",
                "stable_root",
                "replay",
            )
            val allowed = required + "stable_media"
            require(keys.containsAll(required) && keys.all(allowed::contains)) {
                "$context accepted keys 非 closed shape"
            }
            val resolutionMutationId = requiredNonBlankString(
                "resolution_mutation_id",
                context,
            )
            val stableVersionId = requiredNonBlankString("stable_version_id", context)
            val stableRoot = when (val root = get("stable_root")) {
                is JsonObject -> root.toString()
                else -> throw IllegalArgumentException("$context.stable_root 无效")
            }
            val media = when (val raw = get("stable_media")) {
                null -> emptyList()
                is JsonArray -> raw.mapIndexed { index, element ->
                    (element as? JsonObject)
                        ?.toConflictAcceptedMedia("$context.stable_media[$index]")
                        ?: throw IllegalArgumentException(
                            "$context.stable_media[$index] 不是对象",
                        )
                }
                else -> throw IllegalArgumentException("$context.stable_media 无效")
            }
            ConflictResolveResult.Accepted(
                stableVersionId = stableVersionId,
                resolutionMutationId = resolutionMutationId,
                stableRootJson = stableRoot,
                stableMedia = media,
                replay = requiredBoolean("replay", context),
            )
        }
        "rejected" -> {
            require(keys == setOf("status", "error") ||
                keys == setOf("status", "resolution_mutation_id", "error")) {
                "$context rejected keys 非 closed shape"
            }
            val error = get("error") as? JsonObject
                ?: throw IllegalArgumentException("$context.error 无效")
            require(error.keys == setOf("code", "retryable")) {
                "$context.error keys 非 closed shape"
            }
            val code = error.requiredNonBlankString("code", "$context.error")
            require(code in CONFLICT_TERMINAL_REJECTION_CODES) {
                "$context.error.code 非 closed value: $code"
            }
            val retryable = error.requiredBoolean("retryable", "$context.error")
            require(!retryable) { "$context.error.retryable 必须为 false" }
            ConflictResolveResult.Rejected(
                code = code,
                resolutionMutationId = optionalNonBlankString(
                    "resolution_mutation_id",
                    context,
                ),
                retryable = retryable,
            )
        }
        else -> throw IllegalArgumentException("$context.status 无效: $status")
    }
}

private fun JsonObject.toConflictWithdrawResult(context: String): ConflictWithdrawResult {
    val status = requiredNonBlankString("status", context)
    return when (status) {
        "accepted" -> {
            val required = setOf(
                "status",
                "withdrawal_mutation_id",
                "stable_version_id",
                "conflict_status",
                "remaining_branch_version_ids",
                "replay",
            )
            require(keys == required) { "$context accepted keys 非 closed shape" }
            val remaining = when (val raw = get("remaining_branch_version_ids")) {
                is JsonArray -> raw.mapIndexed { index, element ->
                    val id = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    require(id.isNotEmpty()) {
                        "$context.remaining_branch_version_ids[$index] 无效"
                    }
                    id
                }
                else -> throw IllegalArgumentException("$context.remaining_branch_version_ids 无效")
            }
            require(remaining == remaining.sorted()) {
                "$context.remaining_branch_version_ids 必须按字典序"
            }
            ConflictWithdrawResult.Accepted(
                stableVersionId = requiredNonBlankString("stable_version_id", context),
                withdrawalMutationId = requiredNonBlankString("withdrawal_mutation_id", context),
                conflictStatus = requiredNonBlankString("conflict_status", context),
                remainingBranchVersionIds = remaining,
                replay = requiredBoolean("replay", context),
            )
        }
        "rejected" -> {
            val allowed = setOf(
                "status",
                "withdrawal_mutation_id",
                "remaining_branch_version_ids",
                "error",
            )
            require("error" in keys && keys.all(allowed::contains)) {
                "$context rejected keys 非 closed shape"
            }
            val error = get("error") as? JsonObject
                ?: throw IllegalArgumentException("$context.error 无效")
            require(error.keys == setOf("code", "retryable")) {
                "$context.error keys 非 closed shape"
            }
            val code = error.requiredNonBlankString("code", "$context.error")
            require(code in CONFLICT_TERMINAL_REJECTION_CODES) {
                "$context.error.code 非 closed value: $code"
            }
            val retryable = error.requiredBoolean("retryable", "$context.error")
            require(!retryable) { "$context.error.retryable 必须为 false" }
            ConflictWithdrawResult.Rejected(
                code = code,
                withdrawalMutationId = optionalNonBlankString(
                    "withdrawal_mutation_id",
                    context,
                ),
                retryable = retryable,
            )
        }
        else -> throw IllegalArgumentException("$context.status 无效: $status")
    }
}

private fun JsonObject.toConflictAcceptedMedia(context: String): CausalMediaItem {
    require(keys == setOf("media_uuid", "role", "sha256", "byte_size", "mime", "width", "height")) {
        "$context keys 非 closed shape"
    }
    return toCausalMediaItem(context)
}

private fun JsonObject.optionalLong(key: String, context: String): Long? =
    when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.longOrNull
            ?: throw IllegalArgumentException("$context.$key 无效")
        else -> throw IllegalArgumentException("$context.$key 无效")
    }

private fun JsonObject.toSourceRelationResult(context: String): SourceRelationResult {
    val status = requiredNonBlankString("status", context)
    val sources = when (val raw = get("source_client_uuids")) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw.mapIndexed { index, element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$context.source_client_uuids[$index] 无效")
        }
        else -> throw IllegalArgumentException("$context.source_client_uuids 无效")
    }
    val latest = when (val raw = get("latest_versions")) {
        null, JsonNull -> emptyMap()
        is JsonObject -> raw.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$context.latest_versions 值无效")
        }
        else -> throw IllegalArgumentException("$context.latest_versions 无效")
    }
    val mediaRetained = when (val raw = get("media_retained")) {
        null, JsonNull -> null
        is JsonPrimitive -> raw.booleanOrNull
        else -> throw IllegalArgumentException("$context.media_retained 无效")
    }
    return SourceRelationResult(
        status = status,
        relationId = optionalNonBlankString("relation_id", context),
        displayClientUuid = optionalNonBlankString("display_client_uuid", context),
        sourceClientUuids = sources,
        mediaRetained = mediaRetained,
        code = optionalNonBlankString("code", context),
        latestVersions = latest,
    )
}

private fun JsonObject.recordAuthors(): List<CanonicalRecordAuthor> =
    requiredArray("record_authors", "sync response").mapIndexed { index, element ->
        val value = element as? JsonObject
            ?: throw IllegalArgumentException("record_authors[$index] 不是对象")
        val clientUuid = value.requiredNonBlankString(
            "client_uuid",
            "record_authors[$index]",
        )
        val membershipId = value.requiredNonBlankString(
            "created_by_membership_id",
            "record_authors[$index]",
        )
        CanonicalRecordAuthor(clientUuid, membershipId)
    }

private fun JsonObject.toBundleStageStatus(): BundleStageStatus = BundleStageStatus(
    bundleId = requiredNonBlankString("bundle_id", "bundle stage"),
    status = requiredNonBlankString("status", "bundle stage"),
    missingMedia = requiredStringArray("missing_media", "bundle stage"),
    stagedMedia = requiredStringArray("staged_media", "bundle stage"),
)

private fun JsonObject.toDisasterRestoreStatus(): DisasterRestoreStatus {
    require(requiredLong("protocol_version", "disaster restore status") == 1L) {
        "家庭恢复协议版本不兼容"
    }
    return DisasterRestoreStatus(
        batchId = requiredNonBlankString("batch_id", "disaster restore status"),
        status = requiredNonBlankString("status", "disaster restore status"),
        expiresAtEpochSeconds = requiredLong("expires_at", "disaster restore status"),
    )
}

private fun String.requireRestoreCredential(): String = trim().also {
    require(it.isNotEmpty()) { "家庭恢复凭据已丢失，请取消后重新开始" }
}

private fun JsonObject.toCreateResult(): SessionBootstrapResult {
    require(requiredString("role", "create") == "owner") { "create 响应 role 无效" }
    return SessionBootstrapResult(
        familyId = requiredNonBlankString("family_id", "create"),
        accessToken = requiredNonBlankString("access_token", "create"),
        refreshToken = requiredNonBlankString("refresh_token", "create"),
        accessExpiresAtEpochSeconds = requiredLong("access_expires_at", "create"),
        deviceId = requiredNonBlankString("device_id", "create"),
        role = FamilyRole.Owner,
        generation = requiredNonBlankString("generation", "create"),
        familyName = requiredFamilyName("create"),
        membershipId = requiredNonBlankString("membership_id", "create"),
        reclaimed = false,
    )
}

private fun JsonObject.toOwnerLoginResult(): SessionBootstrapResult {
    require(requiredString("role", "owner login") == "owner") {
        "owner login 响应 role 无效"
    }
    return SessionBootstrapResult(
        familyId = requiredNonBlankString("family_id", "owner login"),
        accessToken = requiredNonBlankString("access_token", "owner login"),
        refreshToken = requiredNonBlankString("refresh_token", "owner login"),
        accessExpiresAtEpochSeconds = requiredLong("access_expires_at", "owner login"),
        deviceId = requiredNonBlankString("device_id", "owner login"),
        role = FamilyRole.Owner,
        generation = requiredNonBlankString("generation", "owner login"),
        familyName = requiredFamilyName("owner login"),
        membershipId = requiredNonBlankString("membership_id", "owner login"),
    )
}

private fun JsonObject.toMemberClaimResult(): SessionBootstrapResult {
    require(requiredString("role", "member claim") == "member") {
        "member claim 响应 role 无效"
    }
    return SessionBootstrapResult(
        familyId = requiredNonBlankString("family_id", "member claim"),
        accessToken = requiredNonBlankString("access_token", "member claim"),
        refreshToken = requiredNonBlankString("refresh_token", "member claim"),
        accessExpiresAtEpochSeconds = requiredLong("access_expires_at", "member claim"),
        deviceId = requiredNonBlankString("device_id", "member claim"),
        role = FamilyRole.Member,
        generation = requiredNonBlankString("generation", "member claim"),
        familyName = requiredFamilyName("member claim"),
        membershipId = requiredNonBlankString("membership_id", "member claim"),
    )
}

private fun pendingSecretBody(pendingSecret: String): JsonObject {
    require(pendingSecret.isNotBlank()) { "等待确认凭据已丢失，请重新申请" }
    return buildJsonObject { put("pending_secret", pendingSecret) }
}

private fun parseMemberLoginStatus(raw: String, context: String): MemberLoginStatus =
    when (raw) {
        "pending" -> MemberLoginStatus.Pending
        "approved" -> MemberLoginStatus.Approved
        "rejected" -> MemberLoginStatus.Rejected
        "cancelled" -> MemberLoginStatus.Cancelled
        "expired" -> MemberLoginStatus.Expired
        "claimed" -> MemberLoginStatus.Claimed
        else -> throw IllegalArgumentException("$context 响应 status 无效")
    }

private fun JsonObject.requiredMemberLoginStatus(context: String): MemberLoginStatus =
    parseMemberLoginStatus(requiredString("status", context), context)

private fun JsonObject.memberLoginReviewStatus(context: String): MemberLoginStatus {
    val element = this["status"] ?: return MemberLoginStatus.Pending
    val raw = (element as? JsonPrimitive)?.contentOrNull
        ?: throw IllegalArgumentException("$context 响应 status 无效")
    return parseMemberLoginStatus(raw, context).also { status ->
        require(status == MemberLoginStatus.Pending || status == MemberLoginStatus.Approved) {
            "$context 响应 status 不是可处理状态"
        }
    }
}

private fun requireUrlSafeCapability(value: String): String = value.trim().also {
    require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "成员登录授权格式无效" }
}

private fun JsonObject.requiredUrlSafeCapability(key: String, context: String): String =
    requireUrlSafeCapability(requiredNonBlankString(key, context))

private fun requireRequestId(requestId: String): String = requestId.trim().also {
    require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "待确认申请 ID 无效" }
}

private fun requireTargetMembershipId(membershipId: String): String = membershipId.trim().also {
    require(it.isNotEmpty() && it.length <= 128 && it.none(Char::isWhitespace)) {
        "目标家庭成员 ID 无效"
    }
}

private fun JsonObject.toSessionRefreshResult(): SessionRefreshResult = SessionRefreshResult(
    familyId = requiredNonBlankString("family_id", "refresh"),
    membershipId = requiredNonBlankString("membership_id", "refresh"),
    deviceId = requiredNonBlankString("device_id", "refresh"),
    role = when (requiredString("role", "refresh")) {
        "owner" -> FamilyRole.Owner
        "member" -> FamilyRole.Member
        else -> throw IllegalArgumentException("refresh 响应 role 无效")
    },
    accessToken = requiredNonBlankString("access_token", "refresh"),
    refreshToken = requiredNonBlankString("refresh_token", "refresh"),
    accessExpiresAtEpochSeconds = requiredLong("access_expires_at", "refresh"),
    generation = requiredNonBlankString("generation", "refresh"),
    familyName = requiredFamilyName("refresh"),
)

private val PULL_RESPONSE_KEYS = setOf(
    "entities",
    "cursor",
    "generation",
    "page_index",
    "has_more",
    "family_name",
)

/** Wire §1.4 additive field: missing → null (pre-0.4.7 servers). */
private fun JsonObject.optionalLiveCensus(): LiveCensus? {
    val value = this["live_census"] ?: return null
    val entries = value as? JsonObject
        ?: throw IllegalArgumentException("pull live_census 不是对象")
    return LiveCensus(
        entries = entries.entries.associate { (entityType, entry) ->
            val context = "pull live_census.$entityType"
            val item = entry as? JsonObject
                ?: throw IllegalArgumentException("$context 不是对象")
            entityType to LiveCensusEntry(
                count = item.requiredLong("count", context),
                keyDigest = item.requiredString("key_digest", context).also {
                    require(it.matches(SHA256_HEX_PATTERN)) { "$context.key_digest 无效" }
                },
                keys = item.optionalLiveKeys(context),
            )
        },
    )
}

internal fun liveKeysQuery(liveKeyTypes: Set<String>): String {
    if (liveKeyTypes.isEmpty()) return ""
    val types = liveKeyTypes.sorted().joinToString(",") { type ->
        URLEncoder.encode(type, Charsets.UTF_8.name())
    }
    return "&include_live_keys=true&live_key_types=$types"
}

private fun JsonObject.optionalLiveKeys(context: String): List<String>? {
    val raw = this["keys"] ?: return null
    val array = raw as? JsonArray
        ?: throw IllegalArgumentException("$context.keys 不是数组")
    return array.mapIndexed { index, value ->
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("$context.keys[$index] 不是字符串")
        primitive.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$context.keys[$index] 不能为空")
    }
}

private fun JsonObject.pullFamilyName(): String? {
    return requiredFamilyName("pull")
}

private fun JsonObject.requiredFamilyName(context: String): String? {
    require("family_name" in this) { "$context 响应缺少 family_name" }
    val value = when (val raw = get("family_name")) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            require(raw.isString) { "family_name must be a string or null" }
            raw.contentOrNull
        }
        else -> error("family_name must be a string or null")
    }
    return normalizeFamilyNameForWire(value)
}

private fun JsonObject.requiredArray(key: String, context: String): JsonArray =
    get(key) as? JsonArray
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")

private fun JsonObject.requiredObject(key: String, context: String): JsonObject =
    get(key) as? JsonObject
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")

private fun JsonObject.requiredString(key: String, context: String): String {
    val primitive = get(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")
    require(primitive.isString) { "$context 响应 $key 必须是字符串" }
    return primitive.content
}

private fun JsonObject.requiredNonBlankString(key: String, context: String): String =
    requiredString(key, context).trim().also {
        require(it.isNotEmpty()) { "$context 响应 $key 为空" }
    }

private fun JsonObject.requiredLong(key: String, context: String): Long {
    val primitive = get(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")
    require(!primitive.isString) { "$context 响应 $key 必须是数字" }
    return primitive.longOrNull
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")
}

private fun JsonObject.requiredInt(key: String, context: String): Int {
    val value = requiredLong(key, context)
    require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$context 响应 $key 超出 Int 范围" }
    return value.toInt()
}

private fun JsonObject.toSyncHeartbeat(): SyncHeartbeat {
    val context = "sync_heartbeat"
    requireExactKeys(setOf("generation", "head_rev", "directory_generation"), context)
    return SyncHeartbeat(
        generation = requiredNonBlankString("generation", context),
        headRev = requiredLong("head_rev", context),
        directoryGeneration = requiredNonBlankString("directory_generation", context),
    )
}

private fun JsonObject.toAppUpdateMetadata(): AppUpdateMetadata {
    val context = "app-update"
    val versionCode = requiredLong("version_code", context)
    require(versionCode in 1..Int.MAX_VALUE) { "$context version_code 无效" }
    val minSupported = requiredLong("min_supported_version_code", context)
    require(minSupported in 0..Int.MAX_VALUE) { "$context min_supported_version_code 无效" }
    val releaseNotes = when (val raw = get("release_notes")) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            require(raw.isString) { "$context release_notes 必须是字符串" }
            raw.content.trim().takeIf(String::isNotEmpty)
        }
        else -> throw IllegalArgumentException("$context release_notes 必须是字符串")
    }
    return AppUpdateMetadata(
        packageName = requiredNonBlankString("package_name", context),
        versionCode = versionCode.toInt(),
        versionName = requiredNonBlankString("version_name", context),
        minSupportedVersionCode = minSupported.toInt(),
        sha256 = requiredNonBlankString("sha256", context),
        releaseNotes = releaseNotes,
    )
}

private fun JsonObject.requiredBoolean(key: String, context: String): Boolean {
    val primitive = get(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")
    require(!primitive.isString) { "$context 响应 $key 必须是布尔值" }
    return primitive.booleanOrNull
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")
}

private fun JsonObject.requiredNullableString(key: String, context: String): String? {
    require(key in this) { "$context 响应缺少 $key" }
    return when (val value = get(key)) {
        JsonNull -> null
        is JsonPrimitive -> {
            require(value.isString) { "$context 响应 $key 必须是字符串或 null" }
            value.content
        }
        else -> throw IllegalArgumentException("$context 响应 $key 必须是字符串或 null")
    }
}

private fun JsonObject.requiredNullableLong(key: String, context: String): Long? {
    require(key in this) { "$context 响应缺少 $key" }
    return when (val value = get(key)) {
        JsonNull -> null
        is JsonPrimitive -> value.longOrNull
            ?: throw IllegalArgumentException("$context 响应 $key 必须是整数或 null")
        else -> throw IllegalArgumentException("$context 响应 $key 必须是整数或 null")
    }
}

/** Additive field: missing or null → null. */
private fun JsonObject.optionalNullableLong(key: String, context: String): Long? {
    if (key !in this) return null
    return when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.longOrNull
            ?: throw IllegalArgumentException("$context 响应 $key 必须是整数或 null")
        else -> throw IllegalArgumentException("$context 响应 $key 必须是整数或 null")
    }
}

private fun JsonObject.requiredStringArray(key: String, context: String): List<String> =
    requiredArray(key, context).mapIndexed { index, element ->
        val value = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$context 响应 $key[$index] 不是字符串")
        require(value.isString) { "$context 响应 $key[$index] 不是字符串" }
        value.content.trim().also {
            require(it.isNotEmpty()) { "$context 响应 $key[$index] 为空" }
        }
    }

internal class SyncHttpException(
    val statusCode: Int,
    val responseBody: String = "",
    val retryAfterHeader: String? = null,
) : IllegalStateException(formatSyncHttpFailure(statusCode, responseBody))

/**
 * Product-facing message for non-2xx family-server responses.
 * Prefer JSON `detail` when present so operators can act without logcat.
 */
internal fun formatSyncHttpFailure(statusCode: Int, responseBody: String): String {
    val detail = syncHttpDetailOrNull(responseBody)
    return if (detail.isNullOrBlank()) {
        "家庭服务器请求失败（HTTP $statusCode）"
    } else {
        "家庭服务器请求失败（HTTP $statusCode）：$detail"
    }
}

internal fun syncHttpDetailOrNull(responseBody: String): String? {
    val trimmed = responseBody.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val root = Json.parseToJsonElement(trimmed).jsonObject
        val detail = root["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        detail.takeIf { it.isNotEmpty() }?.take(240)
    }.getOrNull()
}

internal fun syncHttpCodeOrNull(responseBody: String): String? {
    val trimmed = responseBody.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        Json.parseToJsonElement(trimmed).jsonObject["code"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(64)
    }.getOrNull()
}

internal fun decodeSyncHandshakeFailure(failure: SyncHttpException): SyncHandshakeRejectedException {
    val root = runCatching { Json.parseToJsonElement(failure.responseBody).jsonObject }
        .getOrElse { throw failure }
    root.requireExactKeys(setOf("status", "error"), "sync_handshake rejection")
    require(root.requiredString("status", "sync_handshake rejection") == "rejected") {
        "sync_handshake rejection.status 无效"
    }
    val error = root.requiredObject("error", "sync_handshake rejection")
    error.requireExactKeys(setOf("code", "retryable"), "sync_handshake rejection.error")
    require(!error.requiredBoolean("retryable", "sync_handshake rejection.error")) {
        "sync_handshake rejection.error.retryable 必须为 false"
    }
    val code = error.requiredString("code", "sync_handshake rejection.error")
    val expectedStatus = when (code) {
        "capability_mismatch" -> 409
        "not_ready" -> 503
        else -> throw failure
    }
    require(failure.statusCode == expectedStatus) {
        "sync_handshake rejection HTTP/code 映射无效"
    }
    return SyncHandshakeRejectedException(code)
}

internal fun decodeAuthenticatedSyncHandshake(json: JsonObject): AuthenticatedSyncHandshake {
    val context = "sync_handshake"
    json.requireExactKeys(
        setOf(
            "protocol_version",
            "server_version",
            "ready",
            "capabilities",
            "principal",
            "directory_generation",
            "limits",
            "compression",
            "retry_hints",
        ),
        context,
    )
    require(json.requiredInt("protocol_version", context) == AUTHENTICATED_SYNC_PROTOCOL_VERSION) {
        "$context.protocol_version 无效"
    }
    require(json.requiredBoolean("ready", context)) { "$context.ready 必须为 true" }
    val serverVersion = json.requiredNonBlankString("server_version", context)
    require(serverVersion.length <= 64) { "$context.server_version 过长" }
    val capabilities = json.requiredUniqueStringSet("capabilities", context, maxItems = 64)
    require(capabilities == REQUIRED_CAUSAL_WIRE_CAPABILITIES) {
        "$context.capabilities 必须精确匹配 source causal 能力"
    }
    val principal = json.requiredObject("principal", context).also {
        it.requireExactKeys(setOf("membership_id", "device_id", "role"), "$context.principal")
    }
    val limits = json.requiredObject("limits", context).also {
        it.requireExactKeys(
            setOf(
                "pull_page_max_entities",
                "pull_page_max_encoded_bytes",
                "pull_page_max_decoded_bytes",
                "pull_max_pages",
                "commit_batch_max_units",
                "media_max_bytes",
            ),
            "$context.limits",
        )
    }
    val pullLimit = limits.requiredInt("pull_page_max_entities", "$context.limits")
    val pullEncodedLimit = limits.requiredInt("pull_page_max_encoded_bytes", "$context.limits")
    val pullDecodedLimit = limits.requiredInt("pull_page_max_decoded_bytes", "$context.limits")
    val pullMaxPages = limits.requiredInt("pull_max_pages", "$context.limits")
    val commitLimit = limits.requiredInt("commit_batch_max_units", "$context.limits")
    val mediaLimit = limits.requiredLong("media_max_bytes", "$context.limits")
    require(pullLimit in 1..200) { "$context.limits.pull_page_max_entities 无效" }
    require(pullEncodedLimit in 1..FROZEN_PULL_PAGE_BUDGET.maxEncodedBytes) {
        "$context.limits.pull_page_max_encoded_bytes 无效"
    }
    require(pullDecodedLimit in 1..FROZEN_PULL_PAGE_BUDGET.maxDecodedBytes) {
        "$context.limits.pull_page_max_decoded_bytes 无效"
    }
    require(pullEncodedLimit >= pullDecodedLimit) {
        "$context.limits pull encoded 上限不得小于 decoded 上限"
    }
    require(pullMaxPages in 1..FROZEN_PULL_PAGE_BUDGET.maxPages) {
        "$context.limits.pull_max_pages 无效"
    }
    require(commitLimit in 1..64) { "$context.limits.commit_batch_max_units 无效" }
    require(mediaLimit in 1..MAX_SYNC_MEDIA_RESPONSE_BYTES.toLong()) {
        "$context.limits.media_max_bytes 无效"
    }
    val compression = json.requiredObject("compression", context).also {
        it.requireExactKeys(setOf("pull_response"), "$context.compression")
    }
    val pullCompression = compression.requiredUniqueStringSet(
        "pull_response",
        "$context.compression",
        maxItems = 2,
    )
    require(pullCompression == setOf("gzip", "identity")) {
        "$context.compression.pull_response 无效"
    }
    val retryHints = json.requiredObject("retry_hints", context).also {
        it.requireExactKeys(setOf("retry_after"), "$context.retry_hints")
    }
    require(retryHints.requiredBoolean("retry_after", "$context.retry_hints")) {
        "$context.retry_hints.retry_after 必须为 true"
    }
    val directoryGeneration = json.requiredNonBlankString("directory_generation", context)
    require(SHA256_HEX_PATTERN.matches(directoryGeneration)) {
        "$context.directory_generation 无效"
    }
    return AuthenticatedSyncHandshake(
        protocolVersion = AUTHENTICATED_SYNC_PROTOCOL_VERSION,
        serverVersion = serverVersion,
        ready = true,
        capabilities = capabilities,
        principal = SyncHandshakePrincipal(
            membershipId = principal.requiredNonBlankString("membership_id", "$context.principal"),
            deviceId = principal.requiredNonBlankString("device_id", "$context.principal"),
            role = when (principal.requiredString("role", "$context.principal")) {
                "owner" -> FamilyRole.Owner
                "member" -> FamilyRole.Member
                else -> throw IllegalArgumentException("$context.principal.role 无效")
            },
        ),
        directoryGeneration = directoryGeneration,
        limits = SyncHandshakeLimits(
            pullPageMaxEntities = pullLimit,
            pullPageMaxEncodedBytes = pullEncodedLimit,
            pullPageMaxDecodedBytes = pullDecodedLimit,
            pullMaxPages = pullMaxPages,
            commitBatchMaxUnits = commitLimit,
            mediaMaxBytes = mediaLimit,
        ),
        compression = SyncHandshakeCompression(pullCompression),
        retryHints = SyncHandshakeRetryHints(retryAfter = true),
    )
}

private val SHA256_HEX_PATTERN = Regex("[0-9a-f]{64}")

private fun JsonObject.requireExactKeys(expected: Set<String>, context: String) {
    require(keys == expected) { "$context keys 无效" }
}

private fun JsonObject.requiredUniqueStringSet(
    key: String,
    context: String,
    maxItems: Int,
): Set<String> {
    val values = requiredArray(key, context).mapIndexed { index, value ->
        val string = (value as? JsonPrimitive)?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("$context.$key[$index] 无效")
        require((value as JsonPrimitive).isString && string.isNotEmpty() && string.length <= 64) {
            "$context.$key[$index] 无效"
        }
        string
    }
    require(values.size in 1..maxItems && values.distinct().size == values.size) {
        "$context.$key 重复或越界"
    }
    return values.toSet()
}

internal class SyncResponseTooLargeException(
    val responseKind: String,
    val limitBytes: Int,
    val declaredBytes: Long,
) : IllegalStateException("家庭服务器$responseKind 响应过大（上限 $limitBytes 字节）")
