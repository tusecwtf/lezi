package com.lezi.babylog.sync

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Invite QR / paste payload.
 *
 * Carries the server address plus a one-time invitation code.
 */
data class InvitePayload(
    val baseUrl: String,
    val code: String,
    val host: String = "",
    val port: Int = DEFAULT_SERVER_PORT,
) {
    val homeLanConfig: HomeLanServerConfig
        get() {
            val parsedBaseUrl = HomeLanServerConfig.fromBaseUrl(baseUrl)
            return HomeLanServerConfig(
                host = host.ifBlank { parsedBaseUrl.host },
                port = port.takeIf { it in 1..65535 }
                    ?: parsedBaseUrl.port,
                scheme = parsedBaseUrl.scheme,
            ).withNormalized()
        }
}

object InvitePayloadCodec {
    fun encode(payload: InvitePayload): String {
        val normalized = normalize(payload)
        return buildJsonObject {
            put("v", 1)
            put("baseUrl", normalized.baseUrl)
            if (normalized.host.isNotBlank()) {
                put("host", normalized.host)
                put("port", normalized.port)
            }
            put("code", normalized.code)
        }.toString()
    }

    fun decode(raw: String): InvitePayload {
        val value = raw.trim()
        if (!value.startsWith("{")) {
            return normalize(InvitePayload(baseUrl = "", code = value))
        }
        val json = Json.parseToJsonElement(value).jsonObject
        val version = json["v"]?.jsonPrimitive?.intOrNull
        require(version == 1) { "不支持的邀请版本" }
        val baseUrl = json["baseUrl"]?.jsonPrimitive?.content.orEmpty()
        val host = json["host"]?.jsonPrimitive?.content.orEmpty()
        val port = json["port"]?.jsonPrimitive?.intOrNull
        require(baseUrl.isNotBlank() && host.isNotBlank() && port != null) {
            "当前邀请缺少服务器地址字段"
        }
        return normalize(
            InvitePayload(
                baseUrl = baseUrl,
                code = json["code"]?.jsonPrimitive?.content.orEmpty(),
                host = host,
                port = port,
            ),
        )
    }

    private fun normalize(payload: InvitePayload): InvitePayload {
        val code = payload.code.trim().uppercase()
        require(code.matches(Regex("[A-Z0-9]{8,32}"))) { "邀请码格式无效" }
        var host = payload.host.trim()
        var port = payload.port.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT
        var rawBaseUrl = payload.baseUrl.trim().trimEnd('/')

        if (rawBaseUrl.isNotEmpty()) {
            val uri = runCatching { URI(rawBaseUrl) }
                .getOrElse { throw IllegalArgumentException("家庭服务器地址无效") }
            require(uri.scheme == "https") { "家庭服务器仅支持 HTTPS" }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                "家庭服务器地址无效"
            }
            require(uri.rawQuery == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "家庭服务器地址不能包含路径或查询参数"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "服务器端口无效" }
            if (host.isBlank()) host = uri.host.trim()
            if (uri.port > 0) port = uri.port
            rawBaseUrl = rawBaseUrl
        } else if (host.isNotBlank()) {
            rawBaseUrl = HomeLanServerConfig(host = host, port = port).baseUrl
        }

        return InvitePayload(
            baseUrl = rawBaseUrl,
            code = code,
            host = host,
            port = port,
        )
    }
}
