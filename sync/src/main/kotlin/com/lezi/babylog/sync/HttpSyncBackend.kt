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
internal const val LOCAL_DEVICE_DISPLAY_NAME = "我（本机）"

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

/** @deprecated Prefer [requireMemberDisplayName]; kept name for existing call sites. */
internal fun memberDisplayNameForWire(displayName: String?): String =
    requireMemberDisplayName(displayName)

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
            put("display_name", memberDisplayNameForWire(displayName))
            normalizeFamilyNameForWire(familyName)?.let { put("family_name", it) }
        }, extraHeaders = buildMap {
            bootstrapSecret?.takeIf(String::isNotBlank)?.let {
                put(BOOTSTRAP_SECRET_HEADER, it)
            }
        }).toJoinResult()

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>): PushResult {
        val json = post(session.baseUrl, "/v1/push", session.familyToken, buildJsonObject {
            put("device_id", session.deviceId)
            session.pullGeneration.takeIf(String::isNotBlank)?.let {
                put("generation", it)
            }
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
        })
        return PushResult(
            applied = json["applied"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            recordAuthors = json.recordAuthors(),
        )
    }

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
            hasMore = json["has_more"]?.jsonPrimitive?.booleanOrNull,
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
            put("display_name", memberDisplayNameForWire(displayName))
        }).toJoinResult()

    override suspend fun updateMyDisplayName(session: SyncSession, displayName: String) {
        post(
            session.baseUrl,
            "/v1/family/display-name",
            session.familyToken,
            buildJsonObject {
                put("display_name", memberDisplayNameForWire(displayName))
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
        return (json["members"] as? JsonArray).orEmpty().mapNotNull { memberElement ->
            val member = memberElement as? JsonObject ?: return@mapNotNull null
            FamilyMember(
                displayName = (member["display_name"] as? JsonPrimitive)?.contentOrNull,
                role = when ((member["role"] as? JsonPrimitive)?.contentOrNull) {
                    "owner" -> FamilyRole.Owner
                    else -> FamilyRole.Member
                },
                isSelf = (member["is_self"] as? JsonPrimitive)?.booleanOrNull ?: false,
                // Link key for created_by_device_id → 称呼; never shown in UI.
                deviceId = (member["device_id"] as? JsonPrimitive)?.contentOrNull,
                // Soft-parse: legacy NAS may omit membership_id before upgrade.
                membershipId = (member["membership_id"] as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

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
        val body = buildJsonObject {
            put("bundle_id", draft.bundleId)
            put("root", draft.root.toJson())
            put("media", buildJsonArray { draft.media.forEach { add(it.toJson()) } })
            session.pullGeneration.takeIf(String::isNotBlank)?.let {
                put("generation", it)
            }
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
        val body = buildJsonObject {
            session.pullGeneration.takeIf(String::isNotBlank)?.let {
                put("generation", it)
            }
        }
        val json = post(
            session.baseUrl,
            "/v1/bundles/$bundleId/commit",
            session.familyToken,
            body,
        )
        return BundleCommitResult(
            bundleId = json["bundle_id"]?.jsonPrimitive?.contentOrNull ?: bundleId,
            status = json["status"]?.jsonPrimitive?.contentOrNull ?: "committed",
            applied = json["applied"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            cursor = json["cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
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
            Json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
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
        return text.ifBlank { "{}" }
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

private fun JsonObject.recordAuthors(): List<CanonicalRecordAuthor> =
    (get("record_authors") as? JsonArray).orEmpty().mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val clientUuid = (value["client_uuid"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val membershipId = (value["created_by_membership_id"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        CanonicalRecordAuthor(clientUuid, membershipId)
    }

private fun JsonObject.toBundleStageStatus(): BundleStageStatus = BundleStageStatus(
    bundleId = get("bundle_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
    status = get("status")?.jsonPrimitive?.contentOrNull.orEmpty(),
    missingMedia = (get("missing_media") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    },
    stagedMedia = (get("staged_media") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    },
)

private fun JsonObject.toJoinResult(): JoinResult = JoinResult(
    familyId = get("family_id")!!.jsonPrimitive.content,
    token = get("token")!!.jsonPrimitive.content,
    role = if (get("role")?.jsonPrimitive?.content == "owner") {
        FamilyRole.Owner
    } else {
        FamilyRole.Member
    },
    entities = entities(),
    cursor = get("cursor")?.jsonPrimitive?.longOrNull ?: 0,
    generation = get("generation")?.jsonPrimitive?.contentOrNull.orEmpty(),
    // Legacy NAS may omit the field or send null; treat blank as null for client fallbacks.
    familyName = (get("family_name") as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    // Soft-parse: older NAS without membership_id must not crash mixed upgrade paths.
    membershipId = (get("membership_id") as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
)

internal class SyncHttpException(
    val statusCode: Int,
    val responseBody: String = "",
) : IllegalStateException("家庭服务器请求失败（HTTP $statusCode）")

internal class SyncResponseTooLargeException(
    val responseKind: String,
    val limitBytes: Int,
    val declaredBytes: Long,
) : IllegalStateException("家庭服务器$responseKind 响应过大（上限 $limitBytes 字节）")
