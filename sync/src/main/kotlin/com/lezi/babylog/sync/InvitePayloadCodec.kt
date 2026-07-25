package com.lezi.babylog.sync

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class InvitePayload(
    val baseUrl: String,
    val code: String,
)

object InvitePayloadCodec {
    fun encode(payload: InvitePayload): String {
        val normalized = normalize(payload)
        return buildJsonObject {
            put("v", 1)
            put("baseUrl", normalized.baseUrl)
            put("code", normalized.code)
        }.toString()
    }

    fun decode(raw: String): InvitePayload {
        val value = raw.trim()
        if (!value.startsWith("{")) {
            return normalize(InvitePayload(baseUrl = "", code = value))
        }
        val json = Json.parseToJsonElement(value).jsonObject
        require(json["v"]?.jsonPrimitive?.intOrNull == 1) { "不支持的邀请版本" }
        return normalize(
            InvitePayload(
                baseUrl = json["baseUrl"]?.jsonPrimitive?.content.orEmpty(),
                code = json["code"]?.jsonPrimitive?.content.orEmpty(),
            ),
        )
    }

    private fun normalize(payload: InvitePayload): InvitePayload {
        val code = payload.code.trim().uppercase()
        require(code.matches(Regex("[A-Z0-9]{8,32}"))) { "邀请码格式无效" }
        val rawBaseUrl = payload.baseUrl.trim().trimEnd('/')
        if (rawBaseUrl.isEmpty()) return InvitePayload(baseUrl = "", code = code)
        val uri = runCatching { URI(rawBaseUrl) }
            .getOrElse { throw IllegalArgumentException("家庭服务器地址无效") }
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "家庭服务器地址无效"
        }
        require(uri.rawQuery == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "家庭服务器地址不能包含路径或查询参数"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "服务器端口无效" }
        return InvitePayload(baseUrl = rawBaseUrl, code = code)
    }
}
