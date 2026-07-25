package com.lezi.babylog.sync

import java.net.URI

/**
 * Helpers for home-LAN cleartext policy.
 *
 * Android [networkSecurityConfig](https://developer.android.com/training/articles/security-config)
 * cannot express RFC1918 CIDR ranges, so the platform config keeps cleartext
 * permitted for product home-LAN HTTP while the app surfaces a warning when the
 * configured host is clearly a public cleartext endpoint.
 */
fun isPublicCleartextBaseUrl(baseUrl: String): Boolean {
    val raw = baseUrl.trim()
    if (raw.isEmpty()) return false
    val uri = runCatching {
        val withScheme = if (':' in raw.substringBefore('/')) raw else "http://$raw"
        URI(withScheme)
    }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "https") return false
    if (scheme != "http") return false
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty()) return true
    return isLikelyPublicHost(host)
}

fun isLikelyPublicHost(host: String): Boolean {
    val normalized = host.trim().lowercase().trimEnd('.')
    if (normalized.isEmpty()) return true
    if (normalized == "localhost") return false
    if (normalized.endsWith(".local")) return false
    if (normalized.endsWith(".lan")) return false
    if (normalized.endsWith(".home")) return false
    if (isPrivateOrLoopbackIpv4(normalized)) return false
    // Single-label hostnames (e.g. "nas") are treated as LAN nicknames.
    if (!normalized.contains('.')) return false
    // Dotted non-IP hostnames (example.com) and public IPs are public.
    return true
}

fun isPrivateOrLoopbackIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val a = octets[0]
    val b = octets[1]
    // Loopback 127.0.0.0/8
    if (a == 127) return true
    // "This" network 0.0.0.0/8
    if (a == 0) return true
    // RFC1918
    if (a == 10) return true
    if (a == 172 && b in 16..31) return true
    if (a == 192 && b == 168) return true
    // Link-local 169.254.0.0/16
    if (a == 169 && b == 254) return true
    // CGNAT 100.64.0.0/10 (common on carrier / some home gateways)
    if (a == 100 && b in 64..127) return true
    return false
}

/** Product-facing copy when a cleartext public host is configured. */
const val PUBLIC_CLEARTEXT_WARNING: String =
    "当前地址使用公网 HTTP，家庭令牌与育儿数据可能被途经网络窃听。请优先使用家庭局域网地址，或在 NAS 上配置 HTTPS 反代。"
