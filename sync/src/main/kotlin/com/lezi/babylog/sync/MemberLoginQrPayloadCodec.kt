package com.lezi.babylog.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Volatile QR capability. Callers must never put [grant] in SavedState or persistent storage. */
data class MemberLoginQrPayload(
    val endpoint: TrustedEndpointProfile,
    val grant: String,
    val familyName: String?,
    val memberDisplayName: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "MemberLoginQrPayload(endpoint=$endpoint, familyName=$familyName, " +
            "memberDisplayName=$memberDisplayName, expiresAtEpochSeconds=$expiresAtEpochSeconds, " +
            "grant=<redacted>)"
}

object MemberLoginQrPayloadCodec {
    fun encode(payload: MemberLoginQrPayload): String {
        val normalized = normalize(payload)
        return buildJsonObject {
            put("v", MEMBER_LOGIN_QR_VERSION)
            put("type", MEMBER_LOGIN_QR_TYPE)
            put("endpoint", normalized.endpoint.origin)
            when (normalized.endpoint.trustMode) {
                EndpointTrustMode.SystemPki -> put("trust", SYSTEM_PKI_WIRE)
                EndpointTrustMode.TofuSpki -> {
                    put("trust", TOFU_SPKI_WIRE)
                    put("spki_sha256", requireNotNull(normalized.endpoint.spkiSha256))
                }
            }
            put("grant", normalized.grant)
            put("family_name", normalized.familyName)
            put("member_display_name", normalized.memberDisplayName)
            put("expires_at", normalized.expiresAtEpochSeconds)
        }.toString()
    }

    fun decode(raw: String): MemberLoginQrPayload {
        val json = runCatching { Json.parseToJsonElement(raw.trim()).jsonObject }
            .getOrElse { throw IllegalArgumentException("这个二维码不是有效的成员登录二维码", it) }
        require(json["v"]?.jsonPrimitive?.intOrNull == MEMBER_LOGIN_QR_VERSION) {
            "不支持的成员登录二维码版本"
        }
        require(json.string("type") == MEMBER_LOGIN_QR_TYPE) {
            "这个二维码不是成员登录二维码"
        }
        val trust = json.string("trust")
        val expectedFields = BASE_FIELDS + if (trust == TOFU_SPKI_WIRE) setOf("spki_sha256") else emptySet()
        require(json.keys == expectedFields) { "成员登录二维码包含未知或缺失字段" }
        val endpoint = when (trust) {
            SYSTEM_PKI_WIRE -> TrustedEndpointProfile.systemPki(json.string("endpoint"))
            TOFU_SPKI_WIRE -> TrustedEndpointProfile.tofuSpki(
                json.string("endpoint"),
                json.string("spki_sha256"),
            )
            else -> throw IllegalArgumentException("成员登录二维码的证书信任方式无效")
        }
        val familyName = when (val value = json["family_name"]) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(value.isString) { "成员登录二维码的家庭名无效" }
                value.contentOrNull
            }
            else -> throw IllegalArgumentException("成员登录二维码缺少家庭名")
        }
        return normalize(
            MemberLoginQrPayload(
                endpoint = endpoint,
                grant = json.string("grant"),
                familyName = familyName,
                memberDisplayName = json.string("member_display_name"),
                expiresAtEpochSeconds = json["expires_at"]?.jsonPrimitive?.longOrNull
                    ?: throw IllegalArgumentException("成员登录二维码的有效期无效"),
            ),
        )
    }

    private fun normalize(payload: MemberLoginQrPayload): MemberLoginQrPayload {
        val grant = payload.grant.trim()
        require(grant.matches(URL_SAFE_CAPABILITY)) { "成员登录授权格式无效" }
        require(payload.expiresAtEpochSeconds > 0) { "成员登录二维码的有效期无效" }
        return payload.copy(
            grant = grant,
            familyName = normalizeFamilyNameForWire(payload.familyName),
            memberDisplayName = requireMemberDisplayName(payload.memberDisplayName),
        )
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("成员登录二维码缺少 $key")
        require(value.isString) { "成员登录二维码的 $key 无效" }
        return value.content
    }
}

private const val MEMBER_LOGIN_QR_VERSION = 1
private const val MEMBER_LOGIN_QR_TYPE = "member_login"
private const val SYSTEM_PKI_WIRE = "system_pki"
private const val TOFU_SPKI_WIRE = "tofu_spki"
private val URL_SAFE_CAPABILITY = Regex("[A-Za-z0-9_-]{32,128}")
private val BASE_FIELDS = setOf(
    "v",
    "type",
    "endpoint",
    "trust",
    "grant",
    "family_name",
    "member_display_name",
    "expires_at",
)
