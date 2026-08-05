package com.lezi.gf.syncsession

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Member-login QR envelope (ADR-0015 / tech.md §4.3 intent).
 *
 * Product shape for system-camera + uninstalled path:
 * `http(s)://<host>[:port]/join#v1.<base64url(json)>`
 *
 * Installed app also accepts:
 * - raw grant JSON `{"code":"...","endpoint":"..."}`
 * - bare grant code string from createMemberQr
 *
 * Never embeds bootstrap secrets or long-lived refresh tokens.
 */
object MemberLoginQrEnvelope {
    const val FRAGMENT_PREFIX = "v1."
    const val JOIN_PATH = "/join"

    data class MemberLoginGrant(
        val code: String,
        val endpoint: String? = null,
        val trustedSpkiSha256: String? = null,
    )

    sealed class ParseResult {
        data class Ok(val grant: MemberLoginGrant, val isInviteUrl: Boolean) : ParseResult()
        data class Err(val message: String) : ParseResult()
    }

    /**
     * System-camera / browser landing URL. Uses the family API origin + `/join` fragment
     * (GF serves invite/join on the same LAN listener; PRD 8767 isolation = no family data on page).
     */
    fun buildLandingUrl(apiEndpoint: String, grant: MemberLoginGrant): String {
        val origin = apiEndpoint.trim().trimEnd('/')
        val fragment = encodeFragment(grant)
        return "$origin$JOIN_PATH#$FRAGMENT_PREFIX$fragment"
    }

    fun encodeFragment(grant: MemberLoginGrant): String {
        require(grant.code.isNotBlank()) { "grant code blank" }
        val json = buildString {
            append('{')
            append("\"code\":").append(jsonStr(grant.code))
            grant.endpoint?.takeIf { it.isNotBlank() }?.let {
                append(',').append("\"endpoint\":").append(jsonStr(it))
            }
            grant.trustedSpkiSha256?.takeIf { it.isNotBlank() }?.let {
                append(',').append("\"trusted_spki_sha256\":").append(jsonStr(it))
            }
            append('}')
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeFragment(fragmentBody: String): ParseResult {
        val raw = fragmentBody.trim().removePrefix("#")
        val b64 = when {
            raw.startsWith(FRAGMENT_PREFIX) -> raw.removePrefix(FRAGMENT_PREFIX)
            else -> raw
        }
        if (b64.isBlank()) return ParseResult.Err("授权码片段为空")
        return try {
            val bytes = Base64.getUrlDecoder().decode(padBase64Url(b64))
            val json = String(bytes, StandardCharsets.UTF_8)
            parseGrantJson(json)
        } catch (_: Exception) {
            ParseResult.Err("授权码片段无法解码")
        }
    }

    /**
     * Classify any scanned/pasted string into invite URL (install path) or claimable grant.
     */
    fun parseScan(raw: String): ParseResult {
        val s = raw.trim()
        if (s.isEmpty()) return ParseResult.Err("扫描内容为空")

        // Invite / join URL with optional #v1.grant fragment
        if (looksLikeHttpUrl(s) && (s.contains(JOIN_PATH) || s.contains("/invite"))) {
            val hash = s.indexOf('#')
            if (hash < 0 || hash == s.lastIndex) {
                // Landing page only — no grant in URL; invite-install surface
                return ParseResult.Ok(
                    MemberLoginGrant(code = "", endpoint = originOf(s)),
                    isInviteUrl = true,
                )
            }
            val frag = s.substring(hash + 1)
            return when (val g = decodeFragment(frag)) {
                is ParseResult.Ok -> ParseResult.Ok(g.grant, isInviteUrl = true)
                is ParseResult.Err -> g
            }
        }

        // Raw JSON grant
        if (s.startsWith("{")) {
            return when (val g = parseGrantJson(s)) {
                is ParseResult.Ok -> ParseResult.Ok(g.grant, isInviteUrl = false)
                is ParseResult.Err -> g
            }
        }

        // Bare code (legacy / FakeWire)
        if (s.matches(Regex("^[A-Za-z0-9._-]{3,128}$"))) {
            return ParseResult.Ok(MemberLoginGrant(code = s), isInviteUrl = false)
        }

        return ParseResult.Err("无法识别的二维码内容")
    }

    /** Invite page URL without fragment (browser install only). */
    fun invitePageUrl(apiEndpoint: String): String =
        apiEndpoint.trim().trimEnd('/') + JOIN_PATH

    fun isClaimable(grant: MemberLoginGrant): Boolean = grant.code.isNotBlank()

    private fun parseGrantJson(json: String): ParseResult {
        return try {
            val code = stringField(json, "code")
                ?: return ParseResult.Err("缺少授权码 code")
            if (code.isBlank()) return ParseResult.Err("授权码为空")
            val endpoint = stringField(json, "endpoint")
            val spki = stringField(json, "trusted_spki_sha256")
                ?: stringField(json, "spki")
            ParseResult.Ok(
                MemberLoginGrant(
                    code = code,
                    endpoint = endpoint,
                    trustedSpkiSha256 = spki,
                ),
                isInviteUrl = false,
            )
        } catch (_: Exception) {
            ParseResult.Err("授权 JSON 无效")
        }
    }

    private fun stringField(json: String, key: String): String? {
        val re = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val m = re.find(json) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun jsonStr(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(c)
                }
            }
            append('"')
        }

    private fun padBase64Url(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }

    private fun looksLikeHttpUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true)

    private fun originOf(url: String): String {
        val noHash = url.substringBefore('#')
        val schemeEnd = noHash.indexOf("://")
        if (schemeEnd < 0) return noHash
        val rest = noHash.substring(schemeEnd + 3)
        val path = rest.indexOf('/')
        return if (path < 0) noHash else noHash.substring(0, schemeEnd + 3 + path)
    }
}
