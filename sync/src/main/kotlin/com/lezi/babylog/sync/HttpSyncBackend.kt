package com.lezi.babylog.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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

private const val BOOTSTRAP_SECRET_HEADER = "X-Lezi-Bootstrap-Secret"
internal const val MAX_SYNC_JSON_RESPONSE_BYTES = 16 * 1024 * 1024
internal const val MAX_SYNC_MEDIA_RESPONSE_BYTES = 10 * 1024 * 1024
private const val MAX_SYNC_ERROR_RESPONSE_BYTES = 64 * 1024
private const val MILLIS_PER_SECOND = 1_000L

/** Local-only UI placeholder; must never be uploaded as a real family 称呼. */
/** Local-only display placeholder; never treated as a real caregiver name. */
const val LOCAL_DEVICE_DISPLAY_NAME = "我（本机）"

/**
 * Product-required family 称呼 for create / join / self-rename.
 * Blank, whitespace-only, and the device-local placeholder all fail hard.
 */
internal fun requireMemberDisplayName(displayName: String?): String {
    require(!displayName.isNullOrBlank()) { "请填写家庭称呼" }
    require(displayName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "家庭称呼不能包含控制字符或双向格式控制符"
    }
    val normalized = displayName.trim()
    require(normalized.isNotEmpty()) { "请填写家庭称呼" }
    require(normalized != LOCAL_DEVICE_DISPLAY_NAME) {
        "请填写家庭称呼，不能使用本机占位名"
    }
    require(normalized.codePointCount(0, normalized.length) <= 128) {
        "家庭称呼最多 128 个字符"
    }
    return normalized
}

/**
 * Optional shared family name for create / owner rename.
 * Blank becomes null (server stores null; client applies fallback display).
 */
internal fun normalizeFamilyNameForWire(familyName: String?): String? {
    if (familyName == null) return null
    require(familyName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "家庭名不能包含控制字符或双向格式控制符"
    }
    val normalized = familyName.trim()
    if (normalized.isEmpty()) return null
    require(normalized.codePointCount(0, normalized.length) <= 64) {
        "家庭名最多 64 个字符"
    }
    return normalized
}

private fun Char.isBidirectionalControl(): Boolean =
    this == '\u061c' ||
        this in '\u200e'..'\u200f' ||
        this in '\u202a'..'\u202e' ||
        this in '\u2066'..'\u206f'

class HttpSyncBackend @Inject constructor() : SyncBackend {
    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
        familyName: String?,
    ) =
        post(baseUrl, "/v1/family/create", null, buildJsonObject {
            put("device_id", deviceId)
            put("create_request_id", createRequestId)
            put("display_name", requireMemberDisplayName(displayName))
            normalizeFamilyNameForWire(familyName)?.let { put("family_name", it) }
        }, extraHeaders = buildMap {
            bootstrapSecret?.takeIf(String::isNotBlank)?.let {
                put(BOOTSTRAP_SECRET_HEADER, it)
            }
        }).toCreateResult()

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>): PushResult {
        session.requireCurrentReplicaTransport()
        val json = post(session.baseUrl, "/v1/push", session.familyToken, buildJsonObject {
            put("device_id", session.deviceId)
            put("generation", session.pullGeneration)
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
        })
        return PushResult(
            applied = json.requiredLong("applied", "push").toInt(),
            recordAuthors = json.recordAuthors(),
        )
    }

    override suspend fun pull(session: SyncSession): PullResult {
        session.requireCurrentReplicaTransport()
        val generation = URLEncoder.encode(session.pullGeneration, Charsets.UTF_8.name())
        val json = get(
            session.baseUrl,
            "/v1/pull?cursor=${session.pullCursor}&generation=$generation",
            session.familyToken,
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

    override suspend fun invite(session: SyncSession): Invite {
        val json = post(session.baseUrl, "/v1/invite", session.familyToken, buildJsonObject {})
        return inviteFromWire(json)
    }

    override suspend fun join(
        baseUrl: String,
        code: String,
        deviceId: String,
        displayName: String?,
    ): JoinResult =
        post(baseUrl, "/v1/join", null, buildJsonObject {
            put("code", code)
            put("device_id", deviceId)
            put("display_name", requireMemberDisplayName(displayName))
        }).toJoinResult()

    override suspend fun updateMyDisplayName(session: SyncSession, displayName: String) {
        post(
            session.baseUrl,
            "/v1/family/display-name",
            session.familyToken,
            buildJsonObject {
                put("display_name", requireMemberDisplayName(displayName))
            },
        )
    }

    override suspend fun renameFamily(session: SyncSession, familyName: String?) {
        val normalized = normalizeFamilyNameForWire(familyName)
        post(
            session.baseUrl,
            "/v1/family/name",
            session.familyToken,
            buildJsonObject {
                if (normalized == null) put("family_name", JsonNull) else put("family_name", normalized)
            },
        )
    }

    override suspend fun members(session: SyncSession): List<FamilyMember> {
        val json = get(session.baseUrl, "/v1/family/members", session.familyToken)
        return json.requiredArray("members", "members").mapIndexed { index, memberElement ->
            val member = memberElement as? JsonObject
                ?: throw IllegalArgumentException("members[$index] 不是对象")
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
            )
        }
    }

    override suspend fun leave(session: SyncSession) {
        post(session.baseUrl, "/v1/leave", session.familyToken, buildJsonObject {})
    }

    override suspend fun removeMember(session: SyncSession, membershipId: String) {
        val id = membershipId.trim()
        require(id.isNotEmpty()) { "请选择要移除的家人" }
        post(
            session.baseUrl,
            "/v1/family/members/remove",
            session.familyToken,
            buildJsonObject { put("membership_id", id) },
        )
    }

    override suspend fun deleteFamily(session: SyncSession) {
        post(session.baseUrl, "/v1/family/delete", session.familyToken, buildJsonObject {})
    }

    override suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) = requestBytes(
        session.baseUrl,
        "/v1/media/$clientUuid",
        "PUT",
        session.familyToken,
        bytes,
        mime,
    ).let { Unit }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray =
        requestBytes(session.baseUrl, "/v1/media/$clientUuid", "GET", session.familyToken)

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
        return post(session.baseUrl, "/v1/bundles", session.familyToken, body).toBundleStageStatus()
    }

    override suspend fun putBundleMedia(
        session: SyncSession,
        bundleId: String,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ): BundleStageStatus {
        val json = requestJsonBytes(
            session.baseUrl,
            "/v1/bundles/$bundleId/media/$clientUuid",
            "PUT",
            session.familyToken,
            bytes,
            mime,
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
            session.familyToken,
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

    private suspend fun get(base: String, path: String, token: String?): JsonObject =
        requestJson(base, path, "GET", token, null)

    private suspend fun requestJson(
        base: String,
        path: String,
        method: String,
        token: String?,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        val connection = open(base, path, method, token, extraHeaders)
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

    private suspend fun requestBytes(
        base: String,
        path: String,
        method: String,
        token: String,
        body: ByteArray? = null,
        mime: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(base, path, method, token)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", mime ?: "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            val (code, bytes) = readBoundedBody(
                connection = connection,
                successLimitBytes = MAX_SYNC_MEDIA_RESPONSE_BYTES,
                successResponseKind = "媒体",
            )
            if (code !in 200..299) {
                throw SyncHttpException(code, bytes.toString(Charsets.UTF_8))
            }
            bytes
        } finally {
            connection.disconnect()
        }
    }

    /** PUT binary body, parse JSON success response (bundle stage status). */
    private suspend fun requestJsonBytes(
        base: String,
        path: String,
        method: String,
        token: String,
        body: ByteArray,
        mime: String?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val connection = open(base, path, method, token)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", mime ?: "application/octet-stream")
            connection.outputStream.use { it.write(body) }
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

    private fun open(
        base: String,
        path: String,
        method: String,
        token: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection =
        (URL("${base.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
            instanceFollowRedirects = false
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
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

private fun JsonObject.toCreateResult(): JoinResult {
    require(requiredString("role", "create") == "owner") { "create 响应 role 无效" }
    val reclaimed = requireNotNull(get("reclaimed")?.jsonPrimitive?.booleanOrNull) {
        "create 响应缺少 reclaimed"
    }
    return JoinResult(
        familyId = requiredNonBlankString("family_id", "create"),
        token = requiredNonBlankString("token", "create"),
        role = FamilyRole.Owner,
        generation = requiredNonBlankString("generation", "create"),
        familyName = requiredFamilyName("create"),
        membershipId = requiredNonBlankString("membership_id", "create"),
        reclaimed = reclaimed,
    )
}

private fun JsonObject.toJoinResult(): JoinResult {
    require(requiredString("role", "join") == "member") { "join 响应 role 无效" }
    return JoinResult(
        familyId = requiredNonBlankString("family_id", "join"),
        token = requiredNonBlankString("token", "join"),
        role = FamilyRole.Member,
        entities = entities("join"),
        cursor = requiredLong("cursor", "join"),
        generation = requiredNonBlankString("generation", "join"),
        familyName = requiredFamilyName("join"),
        membershipId = requiredNonBlankString("membership_id", "join"),
    )
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

internal class SyncResponseTooLargeException(
    val responseKind: String,
    val limitBytes: Int,
    val declaredBytes: Long,
) : IllegalStateException("家庭服务器$responseKind 响应过大（上限 $limitBytes 字节）")
