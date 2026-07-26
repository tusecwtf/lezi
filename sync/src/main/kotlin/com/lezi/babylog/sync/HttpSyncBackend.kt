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

/** Do not publish a device-local UI placeholder as another person's name. */
internal fun memberDisplayNameForWire(displayName: String?): String? {
    if (displayName == null) return null
    require(displayName.none { it.isISOControl() || it.isBidirectionalControl() }) {
        "家庭称呼不能包含控制字符或双向格式控制符"
    }
    val normalized = displayName.trim()
    if (normalized.isEmpty() || normalized == "我（本机）") return null
    require(normalized.codePointCount(0, normalized.length) <= 128) {
        "家庭称呼最多 128 个字符"
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
    ) =
        post(baseUrl, "/v1/family/create", null, buildJsonObject {
            put("device_id", deviceId)
            put("create_request_id", createRequestId)
            memberDisplayNameForWire(displayName)?.let { put("display_name", it) }
        }, extraHeaders = buildMap {
            bootstrapSecret?.takeIf(String::isNotBlank)?.let {
                put(BOOTSTRAP_SECRET_HEADER, it)
            }
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
            memberDisplayNameForWire(displayName)?.let { put("display_name", it) }
        }).toJoinResult()

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
