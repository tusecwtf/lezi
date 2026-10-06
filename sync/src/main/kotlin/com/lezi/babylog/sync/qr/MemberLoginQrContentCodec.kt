package com.lezi.babylog.sync.qr

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class MemberLoginQrCode(
    val payload: MemberLoginQrPayload,
    val landingUrl: String?,
) {
    init {
        landingUrl?.let { requireValidLandingUrl(payload, it) }
    }
}

object MemberLoginQrContentCodec {
    fun encode(code: MemberLoginQrCode): String {
        val rawPayload = MemberLoginQrPayloadCodec.encode(code.payload)
        val landingUrl = code.landingUrl
            ?: return rawPayload.also(::requireQrContentCapacity)
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(rawPayload.toByteArray(Charsets.UTF_8))
        return "$landingUrl#v1.$encodedPayload".also(::requireQrContentCapacity)
    }

    fun decode(raw: String): MemberLoginQrCode {
        val content = raw.trim()
        requireQrContentCapacity(content)
        if (content.startsWith("{")) {
            return MemberLoginQrCode(
                payload = MemberLoginQrPayloadCodec.decode(content),
                landingUrl = null,
            )
        }
        val separator = "#v1."
        val landingUrl = content.substringBeforeLast(separator)
        val encodedPayload = content.substringAfterLast(separator)
        require(landingUrl != content && encodedPayload.isNotEmpty()) {
            "这个二维码不是有效的成员登录二维码"
        }
        require(encodedPayload.matches(UNPADDED_BASE64_URL)) {
            "成员登录二维码的内容编码无效"
        }
        val payloadBytes = runCatching {
            Base64.getUrlDecoder().decode(encodedPayload)
        }.getOrElse {
            throw IllegalArgumentException("这个二维码不是有效的成员登录二维码", it)
        }
        require(
            Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) == encodedPayload,
        ) { "成员登录二维码的内容编码无效" }
        val payloadJson = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payloadBytes))
                .toString()
        }.getOrElse {
            throw IllegalArgumentException("成员登录二维码的内容不是有效 UTF-8", it)
        }
        return MemberLoginQrCode(
            payload = MemberLoginQrPayloadCodec.decode(payloadJson),
            landingUrl = landingUrl,
        )
    }
}

private fun requireValidLandingUrl(payload: MemberLoginQrPayload, landingUrl: String) {
    val uri = runCatching { URI(landingUrl) }.getOrElse {
        throw IllegalArgumentException("成员登录二维码的下载地址无效", it)
    }
    require(uri.rawUserInfo == null) { "成员登录二维码的下载地址不能包含用户信息" }
    val host = uri.host?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("成员登录二维码的下载地址缺少主机")
    require(!host.contains(':')) {
        "成员登录二维码的下载地址仅支持 IPv4 或 DNS 主机"
    }
    require(host.equals(payload.endpoint.host, ignoreCase = true)) {
        "成员登录二维码的下载地址与家庭服务器不一致"
    }
    require(uri.rawPath == MEMBER_LOGIN_DOWNLOAD_PATH) {
        "成员登录二维码的下载地址路径无效"
    }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "成员登录二维码的下载地址不能包含查询参数或片段"
    }
    when {
        host.equals(PUBLIC_INVITE_INSTALL_HOST, ignoreCase = true) -> {
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "公网邀请下载地址必须使用 HTTPS"
            }
            require(uri.port == -1 || uri.port == 443) {
                "成员登录二维码的下载地址端口无效"
            }
        }
        else -> {
            require(uri.scheme.equals("http", ignoreCase = true)) {
                "成员登录二维码的下载地址必须使用 HTTP"
            }
            require(uri.port == MEMBER_LOGIN_DOWNLOAD_PORT) {
                "成员登录二维码的下载地址端口无效"
            }
        }
    }
}

private fun requireQrContentCapacity(content: String) {
    require(content.toByteArray(Charsets.UTF_8).size <= MAX_QR_CONTENT_UTF8_BYTES) {
        "成员登录二维码内容过长"
    }
}

private const val MEMBER_LOGIN_DOWNLOAD_PORT = 8767
private const val MEMBER_LOGIN_DOWNLOAD_PATH = "/join"
private const val PUBLIC_INVITE_INSTALL_HOST = "invite.example.invalid"
// QR Code version 40-L byte-mode capacity. Keep generated and scanned content renderable as one QR.
private const val MAX_QR_CONTENT_UTF8_BYTES = 2_953
private val UNPADDED_BASE64_URL = Regex("[A-Za-z0-9_-]+")
