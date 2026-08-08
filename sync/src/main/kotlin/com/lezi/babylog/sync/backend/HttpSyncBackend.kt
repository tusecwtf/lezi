package com.lezi.babylog.sync.backend
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
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
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_VERSIONS
import com.lezi.babylog.sync.session.CAPABILITY_SOURCE_RELATIONS
import com.lezi.babylog.sync.session.CAPABILITY_WAKE_OBSERVATION
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.matchesOrigin
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.pinnedSslContext
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName

private const val BOOTSTRAP_SECRET_HEADER = "X-Lezi-Bootstrap-Secret"
internal const val REFRESH_REQUEST_ID_HEADER = "X-Lezi-Refresh-Request-Id"
internal const val MEMBER_REQUEST_VIEW_HEADER = "X-Lezi-Member-Request-View"
internal const val OPEN_MEMBER_REQUEST_VIEW = "open-v1"
private val REFRESH_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
internal const val MAX_SYNC_JSON_RESPONSE_BYTES = 16 * 1024 * 1024
private const val MAX_RECONCILE_UNITS = 64
private const val MAX_RECONCILE_MEDIA_ENTITIES = 8
internal const val MAX_SYNC_MEDIA_RESPONSE_BYTES = 10 * 1024 * 1024
/** Self-hosted release APK download bound (full package, not media). */
internal const val MAX_SYNC_APP_UPDATE_APK_BYTES = 100 * 1024 * 1024
private const val MAX_SYNC_ERROR_RESPONSE_BYTES = 64 * 1024
private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_UPLOAD_WRITE_STALL_TIMEOUT_MILLIS = 30_000L
/** Attached on authenticated family requests so the server can gate minSupported later. */
internal const val CLIENT_VERSION_CODE_HEADER = "X-Lezi-Client-Version-Code"

/** Wire §1 causal capability keys required for [HttpSyncBackend.supportsCausalWire]. */
internal val REQUIRED_CAUSAL_WIRE_CAPABILITIES = setOf(
    CAPABILITY_CAUSAL_VERSIONS,
    CAPABILITY_WAKE_OBSERVATION,
    CAPABILITY_SOURCE_RELATIONS,
)

internal fun interface SyncHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

internal fun interface TrustedEndpointResolver {
    suspend fun resolve(baseUrl: String): TrustedEndpointProfile
}

private object DefaultSyncHttpConnectionFactory : SyncHttpConnectionFactory {
    override fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
}

class HttpSyncBackend internal constructor(
    private val connectionFactory: SyncHttpConnectionFactory,
    private val trustedEndpointResolver: TrustedEndpointResolver? = null,
    private val clientVersionCode: Int? = null,
    private val uploadWriteStallTimeoutMillis: Long = DEFAULT_UPLOAD_WRITE_STALL_TIMEOUT_MILLIS,
) : SyncBackend {
    init {
        require(uploadWriteStallTimeoutMillis > 0) {
            "上传写入停滞超时必须大于 0"
        }
    }

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

    override suspend fun anonymousHealth(endpoint: TrustedEndpointProfile): AnonymousHealth {
        val json = get(endpoint, "/health")
        require(json.requiredBoolean("ok", "health")) { "家庭服务器 health 未就绪" }
        val capabilities = json.requiredArray("capabilities", "health").mapIndexed { index, value ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("health.capabilities[$index] 无效")
        }.toSet()
        lastAdvertisedCapabilities = capabilities
        return AnonymousHealth(
            version = json.requiredNonBlankString("version", "health"),
            capabilities = capabilities,
        )
    }

    override suspend fun anonymousReady(endpoint: TrustedEndpointProfile): AnonymousReadiness {
        val json = get(endpoint, "/ready")
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
    ): DisasterRestoreStatus = requestJson(
        base = endpoint.origin,
        path = "/v1/disaster-restore/batches/$batchId/manifest",
        method = "PUT",
        token = recoveryToken.requireRestoreCredential(),
        body = buildJsonObject {
            put("request_id", requestId)
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
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
            body = buildJsonObject { put("request_id", requestId) },
            extraHeaders = mapOf(BOOTSTRAP_SECRET_HEADER to rootPassword),
            trustedEndpoint = endpoint,
        )
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
        }).toCreateResult()

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
        ).toOwnerLoginResult()
    }

    override suspend fun requestMemberLogin(
        baseUrl: String,
        displayName: String,
        deviceName: String,
    ): MemberLoginReceipt {
        val json = post(
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
        val json = post(
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

    override suspend fun memberLoginStatus(
        baseUrl: String,
        pendingSecret: String,
    ): MemberLoginStatus = post(
        baseUrl,
        "/v1/member/requests/status",
        null,
        pendingSecretBody(pendingSecret),
    ).requiredMemberLoginStatus("member request status")

    override suspend fun memberLoginStatus(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): MemberLoginStatus = post(
        endpoint = endpoint,
        path = "/v1/member/requests/status",
        token = null,
        body = pendingSecretBody(pendingSecret),
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
    ).toMemberClaimResult()

    override suspend fun claimMemberLogin(
        endpoint: TrustedEndpointProfile,
        pendingSecret: String,
    ): SessionBootstrapResult = post(
        endpoint = endpoint,
        path = "/v1/member/requests/claim",
        token = null,
        body = pendingSecretBody(pendingSecret),
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
    ).toMemberClaimResult()

    override suspend fun pull(session: SyncSession): PullResult {
        session.requireCurrentReplicaTransport()
        val generation = URLEncoder.encode(session.pullGeneration, Charsets.UTF_8.name())
        val json = get(
            session.baseUrl,
            "/v1/pull?cursor=${session.pullCursor}&generation=$generation",
            session.accessToken,
        )
        return PullResult(
            entities = json.entities("pull"),
            cursor = json.requiredLong("cursor", "pull"),
            generation = json.requiredNonBlankString("generation", "pull"),
            hasMore = requireNotNull(json["has_more"]?.jsonPrimitive?.booleanOrNull) {
                "pull 响应缺少 has_more"
            },
            familyName = json.pullFamilyName(),
        )
    }

    override suspend fun reconcile(
        session: SyncSession,
        units: List<ReconcileUnitDraft>,
    ): ReconcileResult {
        session.requireCurrentReplicaTransport()
        require(units.isNotEmpty() && units.size <= MAX_RECONCILE_UNITS) {
            "家庭权威裁决批次必须包含 1..$MAX_RECONCILE_UNITS 个原子单元"
        }
        val expected = units.associateBy { it.root.type to it.root.clientUuid }
        require(expected.size == units.size) { "家庭权威裁决请求 key 必须唯一" }
        val response = post(
            session.baseUrl,
            "/v1/reconcile",
            session.accessToken,
            buildJsonObject {
                put("generation", session.pullGeneration)
                put("units", buildJsonArray {
                    units.forEach { unit ->
                        add(buildJsonObject {
                            put("content_hash", unit.contentHash)
                            put("root", unit.root.toJson())
                            put("media", buildJsonArray {
                                unit.media.forEach { add(it.toJson()) }
                            })
                        })
                    }
                })
            },
        )
        return try {
            val generation = response.requiredNonBlankString("generation", "reconcile")
            require(generation == session.pullGeneration) {
                "家庭服务器在权威裁决期间变更了同步代际"
            }
            val cursor = response.requiredLong("cursor", "reconcile")
            require(cursor >= session.pullCursor) {
                "家庭服务器权威裁决游标早于本机已拉取检查点"
            }
            val results = response.requiredArray("results", "reconcile").mapIndexed { index, item ->
                val value = item as? JsonObject
                    ?: throw IllegalArgumentException("reconcile.results[$index] 不是对象")
                val context = "reconcile.results[$index]"
                val type = value.requiredNonBlankString("entity_type", context)
                val clientUuid = value.requiredNonBlankString("client_uuid", context)
                val disposition = when (value.requiredString("disposition", context)) {
                    "confirmed" -> AuthorityDisposition.Confirmed
                    "publish" -> AuthorityDisposition.Publish
                    "adopt_remote" -> AuthorityDisposition.AdoptRemote
                    "remote_absent_rejected" -> AuthorityDisposition.RemoteAbsentRejected
                    "retry_authority" -> AuthorityDisposition.RetryAuthority
                    else -> throw IllegalArgumentException("$context.disposition 无效")
                }
                val remoteRoot = when (val root = value["remote_root"]) {
                    null, JsonNull -> null
                    is JsonObject -> root.toSyncEntity("$context.remote_root")
                    else -> throw IllegalArgumentException("$context.remote_root 无效")
                }
                val remoteMedia = value.requiredArray("remote_media", context)
                    .mapIndexed { mediaIndex, media ->
                        (media as? JsonObject)?.toSyncEntity(
                            "$context.remote_media[$mediaIndex]",
                        ) ?: throw IllegalArgumentException(
                            "$context.remote_media[$mediaIndex] 不是对象",
                        )
                    }
                validateAuthorityRemoteMedia(remoteRoot, remoteMedia, context)
                if (disposition == AuthorityDisposition.AdoptRemote) {
                    require(remoteRoot?.type == type && remoteRoot.clientUuid == clientUuid) {
                        "$context adopt_remote 缺少匹配的 canonical root"
                    }
                }
                AuthorityResult(
                    type = type,
                    clientUuid = clientUuid,
                    requestContentHash = value.requiredNonBlankString(
                        "request_content_hash",
                        context,
                    ),
                    disposition = disposition,
                    reason = value.requiredNonBlankString("reason", context),
                    remoteContentHash = value.optionalString("remote_content_hash", context),
                    remoteRoot = remoteRoot,
                    remoteMedia = remoteMedia,
                )
            }
            val grouped = results.groupBy { it.type to it.clientUuid }
            require(grouped.keys == expected.keys && grouped.values.all { it.size == 1 }) {
                "家庭服务器权威裁决响应不完整、重复或包含多余 key"
            }
            results.forEach { result ->
                val expectedUnit = expected.getValue(result.type to result.clientUuid)
                require(
                    expectedUnit.contentHash == result.requestContentHash,
                ) {
                    "家庭服务器权威裁决响应不匹配冻结内容"
                }
                when (result.disposition) {
                    AuthorityDisposition.Confirmed -> {
                        require(
                            result.remoteRoot?.authorityEquivalentTo(expectedUnit.root) == true,
                        ) {
                            "家庭服务器 confirmed 未返回匹配的 canonical root"
                        }
                        require(
                            result.remoteMedia.sortedBy(SyncEntity::clientUuid)
                                .zip(expectedUnit.media.sortedBy(SyncEntity::clientUuid))
                                .let { pairs ->
                                    pairs.size == expectedUnit.media.size &&
                                        pairs.size == result.remoteMedia.size &&
                                        pairs.all { (remote, frozen) ->
                                            remote.authorityEquivalentTo(frozen)
                                        }
                                },
                        ) {
                            "家庭服务器 confirmed 未返回完整的 canonical media manifest"
                        }
                        require(!result.remoteContentHash.isNullOrBlank()) {
                            "家庭服务器 confirmed 未证明 canonical content hash"
                        }
                    }
                    AuthorityDisposition.AdoptRemote -> require(
                        !result.remoteContentHash.isNullOrBlank(),
                    ) {
                        "家庭服务器 adopt_remote 未证明 canonical content hash"
                    }
                    AuthorityDisposition.Publish,
                    AuthorityDisposition.RemoteAbsentRejected,
                    AuthorityDisposition.RetryAuthority,
                    -> Unit
                }
            }
            ReconcileResult(
                generation = generation,
                cursor = cursor,
                results = results,
            )
        } catch (error: IllegalArgumentException) {
            val serverGeneration = response["generation"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: session.pullGeneration
            throw AuthorityProofException(serverGeneration, error)
        }
    }

    /**
     * Last health/setup capabilities observed by this backend. Null until the first
     * successful probe — fail closed (no causal publish) rather than assume the wire.
     */
    @Volatile
    private var lastAdvertisedCapabilities: Set<String>? = null

    override fun supportsCausalWire(): Boolean {
        val caps = lastAdvertisedCapabilities ?: return false
        return caps.containsAll(REQUIRED_CAUSAL_WIRE_CAPABILITIES)
    }

    override suspend fun causalReconcile(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalBatchResult = postCausalBatch(session, "/v1/causal/reconcile", units)

    override suspend fun causalCommit(
        session: SyncSession,
        units: List<CausalMutationUnit>,
    ): CausalBatchResult = postCausalBatch(session, "/v1/causal/commit", units)

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

    override suspend fun putCausalMediaPreimage(
        session: SyncSession,
        mediaUuid: String,
        source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
        sha256: String,
    ) {
        session.requireCurrentReplicaTransport()
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "因果媒体 sha256 无效"
        }
        requestJsonStream(
            base = session.baseUrl,
            path = "/v1/causal/media/$mediaUuid",
            method = "PUT",
            token = session.accessToken,
            source = source,
            extraHeaders = mapOf(
                "X-Lezi-Media-Sha256" to sha256,
            ),
        )
    }

    private suspend fun postCausalBatch(
        session: SyncSession,
        path: String,
        units: List<CausalMutationUnit>,
    ): CausalBatchResult {
        session.requireCurrentReplicaTransport()
        require(units.isNotEmpty() && units.size <= MAX_CAUSAL_UNITS) {
            "因果同步批次必须包含 1..$MAX_CAUSAL_UNITS 个原子单元"
        }
        val expectedKeys = units.map { it.entityType to it.clientUuid }.toSet()
        require(expectedKeys.size == units.size) { "因果同步请求 key 必须唯一" }
        val expectedByMutation = units.associateBy(CausalMutationUnit::mutationId)
        require(expectedByMutation.size == units.size) { "因果同步 mutation_id 必须唯一" }
        val response = post(
            session.baseUrl,
            path,
            session.accessToken,
            buildJsonObject {
                put("generation", session.pullGeneration)
                put("units", buildJsonArray {
                    units.forEach { unit ->
                        add(unit.toCausalJson())
                    }
                })
            },
        )
        return try {
            parseCausalBatchResult(
                response = response,
                session = session,
                expectedKeys = expectedKeys,
                expectedByMutation = expectedByMutation,
                context = path,
            )
        } catch (error: IllegalArgumentException) {
            val serverGeneration = response["generation"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: session.pullGeneration
            throw AuthorityProofException(serverGeneration, error)
        }
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
        )
    }

    override suspend fun members(session: SyncSession): List<FamilyMember> {
        val json = get(session.baseUrl, "/v1/family/members", session.accessToken)
        return json.requiredArray("members", "members").mapIndexed { index, memberElement ->
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
        post(session.baseUrl, "/v1/leave", session.accessToken, buildJsonObject {})
    }

    override suspend fun logoutCurrentDevice(session: SyncSession) {
        post(session.baseUrl, "/v1/device/logout", session.accessToken, buildJsonObject {})
    }

    override suspend fun revokeFamilyDevice(session: SyncSession, deviceId: String) {
        val id = requireOpaqueActionId(deviceId, "家庭设备")
        post(
            session.baseUrl,
            "/v1/family/devices/$id/revoke",
            session.accessToken,
            buildJsonObject {},
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
        )
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        requestBytes(session.baseUrl, "/v1/media/$clientUuid", "GET", session.accessToken)

    override suspend fun getAppUpdateMetadata(session: SyncSession): AppUpdateMetadata {
        session.requireCurrentReplicaTransport()
        val json = get(session.baseUrl, "/v1/app-update", session.accessToken)
        return json.toAppUpdateMetadata()
    }

    override suspend fun downloadAppUpdateApk(session: SyncSession): ByteArray {
        session.requireCurrentReplicaTransport()
        return requestBytes(
            session.baseUrl,
            "/v1/app-update/apk",
            "GET",
            session.accessToken,
            successLimitBytes = MAX_SYNC_APP_UPDATE_APK_BYTES,
            successResponseKind = "更新包",
            readTimeoutMillis = 120_000,
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

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        source: SyncMediaUploadSource,
    ): BundleStageStatus {
        val json = requestJsonStream(
            session.baseUrl,
            "/v1/bundles/$bundleId/media/$clientUuid",
            "PUT",
            session.accessToken,
            source,
        )
        return json.toBundleStageStatus()
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
            neighborLosers = json.neighborLosers(),
        )
    }

    private suspend fun post(
        base: String,
        path: String,
        token: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = requestJson(base, path, "POST", token, body, extraHeaders)

    private suspend fun post(
        endpoint: TrustedEndpointProfile,
        path: String,
        token: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = requestJson(
        endpoint.origin,
        path,
        "POST",
        token,
        body,
        extraHeaders,
        trustedEndpoint = endpoint,
    )

    private suspend fun get(
        base: String,
        path: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = requestJson(base, path, "GET", token, null, extraHeaders)

    private suspend fun get(endpoint: TrustedEndpointProfile, path: String): JsonObject =
        requestJson(
            base = endpoint.origin,
            path = path,
            method = "GET",
            token = null,
            body = null,
            trustedEndpoint = endpoint,
        )

    private suspend fun requestJson(
        base: String,
        path: String,
        method: String,
        token: String?,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
    ): JsonObject {
        val resolvedEndpoint = trustedEndpoint ?: trustedEndpointResolver?.resolve(base)
        return withContext(Dispatchers.IO) {
            val connection = open(base, path, method, token, extraHeaders, resolvedEndpoint)
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                        it.write(body.toString())
                    }
                }
                readResponse(connection).let { Json.parseToJsonElement(it).jsonObject }
            } finally {
                connection.disconnect()
            }
        }
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
    ): ByteArray {
        val resolvedEndpoint = trustedEndpointResolver?.resolve(base)
        return withContext(Dispatchers.IO) {
            val connection = open(base, path, method, token, trustedEndpoint = resolvedEndpoint)
            try {
                if (readTimeoutMillis != null) {
                    connection.readTimeout = readTimeoutMillis
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        mime ?: "application/octet-stream",
                    )
                    connection.outputStream.use { it.write(body) }
                }
                val (code, bytes) = readBoundedBody(
                    connection = connection,
                    successLimitBytes = successLimitBytes,
                    successResponseKind = successResponseKind,
                )
                if (code !in 200..299) {
                    throw SyncHttpException(code, bytes.toString(Charsets.UTF_8))
                }
                bytes
            } finally {
                connection.disconnect()
            }
        }
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
    ): JsonObject {
        val resolvedEndpoint = trustedEndpoint ?: trustedEndpointResolver?.resolve(base)
        return withContext(Dispatchers.IO) {
            require(source.contentLength in 1L..RecordPhotoResourcePolicy.maxUploadBytes) {
                "待上传媒体大小超出支持范围"
            }
            val connection = open(
                base,
                path,
                method,
                token,
                extraHeaders = extraHeaders,
                trustedEndpoint = resolvedEndpoint,
            )
            try {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    source.mime ?: "application/octet-stream",
                )
                connection.setFixedLengthStreamingMode(source.contentLength)
                val writeWatchdog = UploadWriteWatchdog(
                    connection = connection,
                    timeoutMillis = uploadWriteStallTimeoutMillis,
                )
                try {
                    writeWatchdog.arm()
                    source.openStream().use { input ->
                        connection.outputStream.use { output ->
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
                val (code, bytes) = readBoundedBody(
                    connection = connection,
                    successLimitBytes = MAX_SYNC_JSON_RESPONSE_BYTES,
                    successResponseKind = "JSON",
                )
                val text = bytes.toString(Charsets.UTF_8)
                if (code !in 200..299) throw SyncHttpException(code, text)
                require(text.isNotBlank()) { "家庭服务器 JSON 响应为空" }
                Json.parseToJsonElement(text).jsonObject
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(
        base: String,
        path: String,
        method: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        trustedEndpoint: TrustedEndpointProfile? = null,
    ): HttpURLConnection =
        connectionFactory.open(URL("${base.trimEnd('/')}$path")).apply {
            trustedEndpoint?.spkiSha256?.let { pin ->
                require(this is HttpsURLConnection) {
                    "固定证书的家庭服务器必须使用 HTTPS"
                }
                sslSocketFactory = pinnedSslContext(pin).socketFactory
            }
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
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
            extraHeaders.forEach(::setRequestProperty)
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val (code, bytes) = readBoundedBody(
            connection = connection,
            successLimitBytes = MAX_SYNC_JSON_RESPONSE_BYTES,
            successResponseKind = "JSON",
        )
        val text = bytes.toString(Charsets.UTF_8)
        if (code !in 200..299) throw SyncHttpException(code, text)
        require(text.isNotBlank()) { "家庭服务器 JSON 响应为空" }
        return text
    }

    private fun readBoundedBody(
        connection: HttpURLConnection,
        successLimitBytes: Int,
        successResponseKind: String,
    ): Pair<Int, ByteArray> {
        val code = connection.responseCode
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
        return code to bytes
    }
}

/**
 * HttpURLConnection has connect/read timeouts but no write timeout. A daemon timer remains
 * independent of coroutine cancellation so it can disconnect a socket even while write() blocks.
 */
private class UploadWriteWatchdog(
    private val connection: HttpURLConnection,
    private val timeoutMillis: Long,
) : AutoCloseable {
    private val lock = Any()
    private val timedOut = AtomicBoolean(false)
    private val timer = Timer("lezi-sync-upload-watchdog", true)
    private var pendingTask: TimerTask? = null
    private var closed = false

    fun arm() {
        val task = object : TimerTask() {
            override fun run() {
                fireIfCurrent(this)
            }
        }
        synchronized(lock) {
            check(!closed) { "上传写入看门狗已经关闭" }
            pendingTask?.cancel()
            pendingTask = task
            timer.schedule(task, timeoutMillis)
        }
    }

    fun markProgress() {
        checkNotTimedOut()
        arm()
    }

    fun checkNotTimedOut() {
        if (timedOut.get()) throw timeoutException()
    }

    fun rethrowIfTimedOut(error: IOException): Nothing {
        if (!timedOut.get()) throw error
        throw timeoutException(error)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingTask?.cancel()
            pendingTask = null
            timer.cancel()
        }
    }

    private fun fireIfCurrent(task: TimerTask) {
        val shouldDisconnect = synchronized(lock) {
            if (closed || pendingTask !== task) {
                false
            } else {
                pendingTask = null
                timedOut.set(true)
                true
            }
        }
        if (shouldDisconnect) connection.disconnect()
    }

    private fun timeoutException(cause: IOException? = null): SocketTimeoutException =
        SocketTimeoutException("家庭服务器上传写入超过 ${timeoutMillis}ms 无进展").also {
            if (cause != null) it.initCause(cause)
        }
}

private fun SyncSession.requireCurrentReplicaTransport() {
    require(isJoined) { "当前同步会话尚未加入家庭" }
    require(deviceId.isNotBlank()) { "当前同步会话缺少 device_id" }
    require(pullGeneration.isNotBlank()) { "当前同步会话缺少 generation" }
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

private fun SyncEntity.authorityEquivalentTo(other: SyncEntity): Boolean =
    type == other.type &&
        clientUuid == other.clientUuid &&
        updatedAt == other.updatedAt &&
        deletedAt == other.deletedAt &&
        Json.parseToJsonElement(payloadJson) == Json.parseToJsonElement(other.payloadJson)

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
)

private fun validateAuthorityRemoteMedia(
    root: SyncEntity?,
    media: List<SyncEntity>,
    context: String,
) {
    require(media.size <= MAX_RECONCILE_MEDIA_ENTITIES) {
        "$context.remote_media 超过原子清单上限"
    }
    require(media.map(SyncEntity::clientUuid).toSet().size == media.size) {
        "$context.remote_media 包含重复 client_uuid"
    }
    if (root == null) {
        require(media.isEmpty()) { "$context 缺少 remote_root 时不得携带 remote_media" }
        return
    }
    if (root.type in setOf("custom_item", "fulfillment_candidate")) {
        require(media.isEmpty()) { "$context 的 ${root.type} 根不得携带媒体" }
        return
    }
    val rootPayload = Json.parseToJsonElement(root.payloadJson).jsonObject
    val rootBaby = rootPayload.authorityOptionalString("baby_client_uuid", context)
    val selectedAvatar = if (root.type == "baby") {
        rootPayload.authorityOptionalString("avatar_media_uuid", context)
    } else {
        null
    }
    media.forEachIndexed { index, entity ->
        val mediaContext = "$context.remote_media[$index]"
        require(entity.type == "media") { "$mediaContext.type 必须是 media" }
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val kind = payload.authorityOptionalString("kind", mediaContext)
        val record = payload.authorityOptionalString("record_client_uuid", mediaContext)
        val baby = payload.authorityOptionalString("baby_client_uuid", mediaContext)
        val carePlan = payload.authorityOptionalString("care_plan_client_uuid", mediaContext)
        val matches = when (root.type) {
            "record" -> kind == "log" && record == root.clientUuid && carePlan == null &&
                (baby == null || baby == rootBaby)
            "care_plan" -> kind == "log" && carePlan == root.clientUuid && record == null &&
                (baby == null || baby == rootBaby)
            "baby" -> kind == "avatar" && baby == root.clientUuid && record == null &&
                carePlan == null
            else -> false
        }
        require(matches) { "$mediaContext 不属于对应的 canonical root" }
    }
    if (root.type == "baby") {
        val liveAvatarUuids = media
            .filter { it.deletedAt == null }
            .mapTo(mutableSetOf(), SyncEntity::clientUuid)
        require(liveAvatarUuids == setOfNotNull(selectedAvatar)) {
            "$context 的 live avatar manifest 与 avatar_media_uuid 不一致"
        }
    }
}

private fun JsonObject.authorityOptionalString(key: String, context: String): String? =
    when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.also {
            require(value.isString && it.isNotBlank()) { "$context.$key 无效" }
        }
        else -> throw IllegalArgumentException("$context.$key 无效")
    }

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
        )
    }

private const val MAX_CAUSAL_UNITS = 64

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
    put("mime", mime)
    if (width == null) put("width", JsonNull) else put("width", width)
    if (height == null) put("height", JsonNull) else put("height", height)
}

private fun parseCausalBatchResult(
    response: JsonObject,
    session: SyncSession,
    expectedKeys: Set<Pair<String, String>>,
    expectedByMutation: Map<String, CausalMutationUnit>,
    context: String,
): CausalBatchResult {
    // Per-unit generation is echoed; batch may omit top-level generation.
    val results = response.requiredArray("results", context).mapIndexed { index, item ->
        val value = item as? JsonObject
            ?: throw IllegalArgumentException("$context.results[$index] 不是对象")
        val unitContext = "$context.results[$index]"
        value.toCausalUnitResult(unitContext, session.pullGeneration)
    }
    val byMutation = results.groupBy(CausalUnitResult::mutationId)
    require(byMutation.keys == expectedByMutation.keys && byMutation.values.all { it.size == 1 }) {
        "家庭服务器因果响应 mutation_id 不完整、重复或包含多余 key"
    }
    val byKey = results.groupBy { result ->
        val unit = expectedByMutation.getValue(result.mutationId)
        unit.entityType to unit.clientUuid
    }
    require(byKey.keys == expectedKeys && byKey.values.all { it.size == 1 }) {
        "家庭服务器因果响应 key 不完整、重复或包含多余 key"
    }
    results.forEach { result ->
        require(result.generation == session.pullGeneration) {
            "家庭服务器在因果同步期间变更了同步代际"
        }
        when (result.status) {
            CausalReconcileStatus.CONFIRMED,
            CausalReconcileStatus.PUBLISH,
            CausalReconcileStatus.CONFLICT_PREVIEW,
            CausalCommitStatus.ACCEPTED,
            CausalCommitStatus.MERGED,
            -> {
                // stable projection required for successful non-branch outcomes that settle.
            }
            CausalReconcileStatus.CONFIRMED,
            CausalCommitStatus.ACCEPTED,
            CausalCommitStatus.MERGED,
            CausalCommitStatus.BRANCHED,
            -> {
                require(!result.stableVersionId.isNullOrBlank()) {
                    "$context ${result.status} 缺少 stable_version_id"
                }
                if (result.status == CausalCommitStatus.BRANCHED) {
                    require(!result.branchVersionId.isNullOrBlank()) {
                        "branched 响应缺少 branch_version_id"
                    }
                    require(!result.conflictId.isNullOrBlank()) {
                        "branched 响应缺少 conflict_id"
                    }
                }
            }
            CausalReconcileStatus.PUBLISH,
            CausalReconcileStatus.CONFLICT_PREVIEW,
            -> Unit
            CausalReconcileStatus.REJECTED, CausalCommitStatus.REJECTED -> Unit
            else -> throw IllegalArgumentException("$context 未知因果 status: ${result.status}")
        }
    }
    val generation = results.firstOrNull()?.generation ?: session.pullGeneration
    val cursor = response.requiredLong("cursor", context)
    require(cursor >= session.pullCursor) {
        "家庭服务器因果游标早于本机已拉取检查点"
    }
    return CausalBatchResult(
        generation = generation,
        cursor = cursor,
        results = results,
    )
}

private fun JsonObject.toCausalUnitResult(
    context: String,
    fallbackGeneration: String,
): CausalUnitResult {
    val status = requiredNonBlankString("status", context)
    val generation = optionalNonBlankString("generation", context) ?: fallbackGeneration
    val stableRoot = when (val root = get("stable_root")) {
        null, JsonNull -> "{}"
        is JsonObject -> root.toString()
        else -> throw IllegalArgumentException("$context.stable_root 无效")
    }
    val media = when (val raw = get("stable_media")) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw.mapIndexed { index, element ->
            (element as? JsonObject)?.toCausalMediaItem("$context.stable_media[$index]")
                ?: throw IllegalArgumentException("$context.stable_media[$index] 不是对象")
        }
        else -> throw IllegalArgumentException("$context.stable_media 无效")
    }
    val conflictingPaths = when (val raw = get("conflicting_paths")) {
        null, JsonNull -> emptyList()
        is JsonArray -> raw.mapIndexed { index, element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$context.conflicting_paths[$index] 无效")
        }
        else -> throw IllegalArgumentException("$context.conflicting_paths 无效")
    }
    return CausalUnitResult(
        status = status,
        mutationId = requiredNonBlankString("mutation_id", context),
        requestHash = requiredNonBlankString("request_hash", context),
        generation = generation,
        stableVersionId = optionalNonBlankString("stable_version_id", context),
        stableRootJson = stableRoot,
        stableMedia = media,
        branchVersionId = optionalNonBlankString("branch_version_id", context),
        conflictId = optionalNonBlankString("conflict_id", context),
        code = optionalNonBlankString("code", context),
        reason = optionalNonBlankString("reason", context),
        conflictingPaths = conflictingPaths,
    )
}

private fun JsonObject.toCausalMediaItem(context: String): CausalMediaItem = CausalMediaItem(
    mediaUuid = requiredNonBlankString("media_uuid", context),
    role = requiredNonBlankString("role", context),
    sha256 = requiredNonBlankString("sha256", context),
    byteSize = requiredLong("byte_size", context),
    mime = requiredNonBlankString("mime", context),
    width = requiredNullableLong("width", context),
    height = requiredNullableLong("height", context),
)

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
    )
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
    )
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

/** Additive 0.3.10 field; missing/null → empty (older servers). */
private fun JsonObject.neighborLosers(): List<String> {
    val element = this["neighbor_losers"] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    val array = element as? JsonArray
        ?: throw IllegalArgumentException("neighbor_losers 不是数组")
    return array.mapIndexed { index, item ->
        val primitive = item as? JsonPrimitive
            ?: throw IllegalArgumentException("neighbor_losers[$index] 不是字符串")
        primitive.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("neighbor_losers[$index] 不能为空")
    }
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

private fun JsonObject.optionalString(key: String, context: String): String? =
    when (val value = get(key)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.also {
            require(value.isString && it.isNotBlank()) { "$context.$key 无效" }
        }
        else -> throw IllegalArgumentException("$context.$key 无效")
    }

private fun JsonObject.requiredLong(key: String, context: String): Long =
    get(key)?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")

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

private fun JsonObject.requiredBoolean(key: String, context: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("$context 响应缺少或无效 $key")

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

internal class SyncResponseTooLargeException(
    val responseKind: String,
    val limitBytes: Int,
    val declaredBytes: Long,
) : IllegalStateException("家庭服务器$responseKind 响应过大（上限 $limitBytes 字节）")
