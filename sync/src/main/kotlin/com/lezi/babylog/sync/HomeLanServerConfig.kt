package com.lezi.babylog.sync

import java.net.URI

/** Max SSIDs for 2.4G / 5G names of the same home AP. */
const val MAX_ALLOWED_SSIDS = 2

const val DEFAULT_SERVER_HOST = "192.168.50.4"
const val DEFAULT_SERVER_PORT = 8765
const val DEFAULT_SERVER_SCHEME = "http"

/**
 * Single NAS endpoint + local SSID allowlist (not synced to the server).
 */
data class HomeLanServerConfig(
    val host: String = "",
    val port: Int = DEFAULT_SERVER_PORT,
    val allowedSsids: List<String> = emptyList(),
    val scheme: String = DEFAULT_SERVER_SCHEME,
) {
    val baseUrl: String
        get() {
            val h = host.trim()
            if (h.isEmpty()) return ""
            val p = if (port in 1..65535) port else DEFAULT_SERVER_PORT
            val renderedHost = if (h.contains(':') && !h.startsWith('[')) "[$h]" else h
            return "${normalizeScheme(scheme)}://$renderedHost:$p"
        }

    val isServerConfigured: Boolean
        get() = host.trim().isNotEmpty() && port in 1..65535

    val hasSsidAllowlist: Boolean
        get() = normalizeSsids(allowedSsids).isNotEmpty()

    fun withNormalized(): HomeLanServerConfig = copy(
        host = host.trim(),
        port = port.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT,
        allowedSsids = normalizeSsids(allowedSsids),
        scheme = normalizeScheme(scheme),
    )

    companion object {
        fun normalizeSsids(ssids: List<String>): List<String> =
            ssids.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_ALLOWED_SSIDS)

        fun normalizeScheme(scheme: String): String =
            scheme.trim().lowercase().takeIf { it == "http" || it == "https" }
                ?: DEFAULT_SERVER_SCHEME

        /**
         * Parse user input that may be a bare host, host:port, or full URL.
         * Bare hosts use HTTP; full URLs retain their HTTP or HTTPS scheme.
         */
        fun parseHostPort(
            rawHostOrUrl: String,
            defaultPort: Int = DEFAULT_SERVER_PORT,
        ): Pair<String, Int> {
            val raw = rawHostOrUrl.trim()
            if (raw.isEmpty()) return "" to defaultPort
            val uri = validatedEndpointUri(raw)
            val host = requireNotNull(uri.host)
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
            return host to (uri.port.takeIf { it > 0 } ?: defaultPort)
        }

        fun fromBaseUrl(baseUrl: String): HomeLanServerConfig {
            val raw = baseUrl.trim()
            if (raw.isEmpty()) return HomeLanServerConfig()
            val uri = validatedEndpointUri(raw)
            val (host, port) = parseHostPort(raw, DEFAULT_SERVER_PORT)
            return HomeLanServerConfig(
                host = host,
                port = port,
                allowedSsids = emptyList(),
                scheme = normalizeScheme(uri.scheme),
            )
        }

        /**
         * Build a config from the address field without silently losing HTTPS.
         * A port embedded in the address wins over the separate port field; a
         * bare host inherits the currently saved scheme when one is supplied.
         */
        fun fromUserInput(
            rawHostOrUrl: String,
            explicitPort: Int?,
            allowedSsids: List<String>,
            fallbackScheme: String = DEFAULT_SERVER_SCHEME,
        ): HomeLanServerConfig {
            val raw = rawHostOrUrl.trim()
            require(!(!raw.contains("://") && raw.count { it == ':' } > 1 && !raw.contains('['))) {
                "IPv6 地址请使用方括号，例如 [2001:db8::1]"
            }
            val parsed = fromBaseUrl(raw)
            val uri = raw.takeIf { it.isNotEmpty() }?.let(::validatedEndpointUri)
            val embeddedPort = uri?.port?.takeIf { it in 1..65535 }
            if (embeddedPort == null && explicitPort != null) {
                require(explicitPort in 1..65535) { "服务器端口需为 1–65535" }
            }
            val explicitScheme = uri?.scheme
                ?.takeIf { raw.contains("://") }
                ?.let(::normalizeScheme)
            return HomeLanServerConfig(
                host = parsed.host,
                port = embeddedPort
                    ?: explicitPort?.takeIf { it in 1..65535 }
                    ?: parsed.port,
                allowedSsids = allowedSsids,
                scheme = explicitScheme ?: normalizeScheme(fallbackScheme),
            ).withNormalized()
        }

        private fun validatedEndpointUri(raw: String): URI {
            val hasExplicitScheme = raw.contains("://")
            val candidate = if (hasExplicitScheme) raw else "$DEFAULT_SERVER_SCHEME://$raw"
            val uri = try {
                URI(candidate)
            } catch (error: Exception) {
                throw IllegalArgumentException("服务器地址格式不正确", error)
            }
            if (hasExplicitScheme) {
                require(uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) {
                    "服务器地址只支持 HTTP 或 HTTPS"
                }
            }
            require(uri.rawUserInfo == null) { "服务器地址不能包含用户名或密码" }
            require(!uri.host.isNullOrBlank()) { "服务器地址格式不正确" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "服务器地址不能包含路径"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "服务器地址不能包含查询参数或片段"
            }

            val authority = uri.rawAuthority.orEmpty()
            val explicitPortText = if (authority.startsWith("[")) {
                val closingBracket = authority.indexOf(']')
                require(closingBracket > 0) { "IPv6 地址格式不正确" }
                val suffix = authority.substring(closingBracket + 1)
                when {
                    suffix.isEmpty() -> null
                    suffix.startsWith(':') -> suffix.drop(1)
                    else -> throw IllegalArgumentException("服务器地址格式不正确")
                }
            } else if (authority.contains(':')) {
                authority.substringAfterLast(':')
            } else {
                null
            }
            explicitPortText?.let { text ->
                val port = text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                require(port != null && port in 1..65535) { "服务器端口需为 1–65535" }
            }
            return uri
        }

        /** Unsaved defaults for an empty setup form. */
        fun noviceUiDefaults(currentSsid: String?): HomeLanServerConfig =
            HomeLanServerConfig(
                host = DEFAULT_SERVER_HOST,
                port = DEFAULT_SERVER_PORT,
                allowedSsids = listOfNotNull(currentSsid?.trim()?.takeIf { it.isNotEmpty() }),
            )

        fun ssidMatches(current: String?, allowed: List<String>): Boolean {
            val cur = current?.trim().orEmpty()
            if (cur.isEmpty()) return false
            return normalizeSsids(allowed).any { it == cur }
        }
    }
}
