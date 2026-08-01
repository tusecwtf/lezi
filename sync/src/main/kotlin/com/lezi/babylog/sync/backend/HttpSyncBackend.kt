package com.lezi.babylog.sync.backend
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
internal const val MAX_SYNC_JSON_RESPONSE_BYTES = 16 * 1024 * 1024
internal const val MAX_SYNC_MEDIA_RESPONSE_BYTES = 10 * 1024 * 1024
/** Self-hosted release APK download bound (full package, not media). */
internal const val MAX_SYNC_APP_UPDATE_APK_BYTES = 100 * 1024 * 1024
private const val MAX_SYNC_ERROR_RESPONSE_BYTES = 64 * 1024
private const val MILLIS_PER_SECOND = 1_000L
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

class HttpSyncBackend internal constructor(
    private val connectionFactory: SyncHttpConnectionFactory,
    private val trustedEndpointResolver: TrustedEndpointResolver? = null,
    private val clientVersionCode: Int? = null,
) : SyncBackend {
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

    override suspend fun memberLoginStatus(
        baseUrl: String,
        pendingSecret: String,
    ): MemberLoginStatus = post(
        baseUrl,
        "/v1/member/requests/status",
        null,
        pendingSecretBody(pendingSecret),
    ).requiredMemberLoginStatus("member request status")

    override suspend fun cancelMemberLogin(baseUrl: String, pendingSecret: String) {
        post(
            baseUrl,
            "/v1/member/requests/cancel",
            null,
            pendingSecretBody(pendingSecret),
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

    override suspend fun pendingMemberLogins(
        session: SyncSession,
    ): List<PendingMemberLoginRequest> = get(
        session.baseUrl,
        "/v1/member/requests",
        session.accessToken,
    ).requiredArray("requests", "pending member requests").mapIndexed { index, element ->
        val request = element as? JsonObject
            ?: throw IllegalArgumentException("requests[$index] 不是对象")
        PendingMemberLoginRequest(
            requestId = request.requiredNonBlankString("request_id", "requests[$index]"),
            displayName = request.requiredNonBlankString("display_name", "requests[$index]"),
            deviceName = request.requiredNonBlankString("device_name", "requests[$index]"),
            createdAtEpochSeconds = request.requiredLong("created_at", "requests[$index]"),
            expiresAtEpochSeconds = request.requiredLong("expires_at", "requests[$index]"),
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
    ): JsonObject = requestJson(endpoint.origin, path, "POST", token, body, trustedEndpoint = endpoint)

    private suspend fun get(base: String, path: String, token: String?): JsonObject =
        requestJson(base, path, "GET", token, null)

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

    /** PUT binary body, parse JSON success response (bundle stage status). */
    private suspend fun requestJsonStream(
        base: String,
        path: String,
        method: String,
        token: String,
        source: SyncMediaUploadSource,
    ): JsonObject {
        val resolvedEndpoint = trustedEndpointResolver?.resolve(base)
        return withContext(Dispatchers.IO) {
            require(source.contentLength in 1L..RecordPhotoResourcePolicy.maxUploadBytes) {
                "待上传媒体大小超出支持范围"
            }
            val connection = open(base, path, method, token, trustedEndpoint = resolvedEndpoint)
            try {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    source.mime ?: "application/octet-stream",
                )
                connection.setFixedLengthStreamingMode(source.contentLength)
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
                        }
                        require(written == source.contentLength) {
                            "待上传媒体长度与声明不一致"
                        }
                    }
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
                // Protected family requests: advertise local versionCode for server gates.
                clientVersionCode?.takeIf { it > 0 }?.let { code ->
                    setRequestProperty(CLIENT_VERSION_CODE_HEADER, code.toString())
                }
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

private fun JsonObject.entities(context: String): List<SyncEntity> =
    requiredArray("entities", context).mapIndexed { index, element ->
        val value = element as? JsonObject
            ?: throw IllegalArgumentException("$context.entities[$index] 不是对象")
        SyncEntity(
            type = value.requiredNonBlankString("type", "$context.entities[$index]"),
            clientUuid = value.requiredNonBlankString(
                "client_uuid",
                "$context.entities[$index]",
            ),
            payloadJson = (value["payload"] as? JsonObject)?.toString()
                ?: throw IllegalArgumentException("$context.entities[$index].payload 缺失或无效"),
            updatedAt = value.requiredLong("updated_at", "$context.entities[$index]"),
            deletedAt = value.requiredNullableLong("deleted_at", "$context.entities[$index]"),
            rev = value.requiredLong("rev", "$context.entities[$index]"),
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

private fun JsonObject.requiredMemberLoginStatus(context: String): MemberLoginStatus =
    when (requiredString("status", context)) {
        "pending" -> MemberLoginStatus.Pending
        "approved" -> MemberLoginStatus.Approved
        "rejected" -> MemberLoginStatus.Rejected
        "cancelled" -> MemberLoginStatus.Cancelled
        "expired" -> MemberLoginStatus.Expired
        "claimed" -> MemberLoginStatus.Claimed
        else -> throw IllegalArgumentException("$context 响应 status 无效")
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
