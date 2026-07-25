package com.lezi.babylog.sync

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Invite QR / paste payload.
 *
 * Carries server address (host+port via [baseUrl] and explicit [host]/[port]) plus optional
 * home Wi‑Fi SSID allowlist hints (max 2) so joiners can prefill without typing.
 * SSID list is advisory and only stored on the scanning device after save/join.
 */
data class InvitePayload(
    val baseUrl: String,
    val code: String,
    val host: String = "",
    val port: Int = DEFAULT_SERVER_PORT,
    val ssids: List<String> = emptyList(),
) {
    val homeLanConfig: HomeLanServerConfig
        get() = HomeLanServerConfig(
            host = host.ifBlank { HomeLanServerConfig.fromBaseUrl(baseUrl).host },
            port = port.takeIf { it in 1..65535 }
                ?: HomeLanServerConfig.fromBaseUrl(baseUrl).port,
            allowedSsids = ssids,
        ).withNormalized()
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
            if (normalized.ssids.isNotEmpty()) {
                put(
                    "ssids",
                    buildJsonArray {
                        normalized.ssids.forEach { add(it) }
                    },
                )
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
        require(version == null || version == 1) { "不支持的邀请版本" }
        val ssids = json["ssids"]?.jsonArray
            ?.mapNotNull { el -> runCatching { el.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
        val host = json["host"]?.jsonPrimitive?.content.orEmpty()
        val port = json["port"]?.jsonPrimitive?.intOrNull
        return normalize(
            InvitePayload(
                baseUrl = json["baseUrl"]?.jsonPrimitive?.content.orEmpty(),
                code = json["code"]?.jsonPrimitive?.content.orEmpty(),
                host = host,
                port = port ?: DEFAULT_SERVER_PORT,
                ssids = ssids,
            ),
        )
    }

    private fun normalize(payload: InvitePayload): InvitePayload {
        val code = payload.code.trim().uppercase()
        require(code.matches(Regex("[A-Z0-9]{8,32}"))) { "邀请码格式无效" }
        val ssids = HomeLanServerConfig.normalizeSsids(payload.ssids)

        var host = payload.host.trim()
        var port = payload.port.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT
        var rawBaseUrl = payload.baseUrl.trim().trimEnd('/')

        if (rawBaseUrl.isNotEmpty()) {
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
            ssids = ssids,
        )
    }
}
