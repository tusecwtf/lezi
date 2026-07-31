package com.lezi.babylog.sync

import java.net.URI

const val DEFAULT_SERVER_HOST = "192.168.50.4"
const val DEFAULT_SERVER_PORT = 8765
const val DEFAULT_SERVER_SCHEME = "https"

/** Single endpoint origin. Trust identity is stored separately as [TrustedEndpointProfile]. */
data class HomeLanServerConfig(
    val host: String = "",
    val port: Int = DEFAULT_SERVER_PORT,
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

    fun withNormalized(): HomeLanServerConfig = copy(
        host = host.trim(),
        port = port.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT,
        scheme = normalizeScheme(scheme),
    )

    companion object {
        fun normalizeScheme(scheme: String): String =
            scheme.trim().lowercase().takeIf { it == "https" }
                ?: DEFAULT_SERVER_SCHEME

        /**
         * Parse user input that may be a bare host, host:port, or full URL.
         * Bare hosts use HTTPS; current endpoints do not accept cleartext HTTP.
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
            val implicitPort = if (raw.contains("://") && uri.scheme.equals("https", true)) {
                443
            } else {
                DEFAULT_SERVER_PORT
            }
            val (host, port) = parseHostPort(raw, implicitPort)
            return HomeLanServerConfig(
                host = host,
                port = port,
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
                require(uri.scheme.equals("https", ignoreCase = true)) {
                    "家庭服务器仅支持 HTTPS 地址"
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
        fun noviceUiDefaults(): HomeLanServerConfig =
            HomeLanServerConfig(
                host = DEFAULT_SERVER_HOST,
                port = DEFAULT_SERVER_PORT,
            )
    }
}
