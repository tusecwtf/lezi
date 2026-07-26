package com.lezi.babylog.sync

import java.net.URI

/** Max SSIDs for 2.4G / 5G names of the same home AP. */
const val MAX_ALLOWED_SSIDS = 2

const val DEFAULT_SERVER_HOST = "192.168.50.4"
const val DEFAULT_SERVER_PORT = 8765

/**
 * Single NAS endpoint + local SSID allowlist (not synced to the server).
 */
data class HomeLanServerConfig(
    val host: String = "",
    val port: Int = DEFAULT_SERVER_PORT,
    val allowedSsids: List<String> = emptyList(),
) {
    val baseUrl: String
        get() {
            val h = host.trim()
            if (h.isEmpty()) return ""
            val p = if (port in 1..65535) port else DEFAULT_SERVER_PORT
            return "http://$h:$p"
        }

    val isServerConfigured: Boolean
        get() = host.trim().isNotEmpty() && port in 1..65535

    val hasSsidAllowlist: Boolean
        get() = normalizeSsids(allowedSsids).isNotEmpty()

    fun withNormalized(): HomeLanServerConfig = copy(
        host = host.trim(),
        port = port.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT,
        allowedSsids = normalizeSsids(allowedSsids),
    )

    companion object {
        fun normalizeSsids(ssids: List<String>): List<String> =
            ssids.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_ALLOWED_SSIDS)

        /**
         * Parse user input that may be a bare host, host:port, or full URL.
         * Scheme is forced to http for home-LAN cleartext product default.
         */
        fun parseHostPort(
            rawHostOrUrl: String,
            defaultPort: Int = DEFAULT_SERVER_PORT,
        ): Pair<String, Int> {
            val raw = rawHostOrUrl.trim()
            if (raw.isEmpty()) return "" to defaultPort
            val withScheme = when {
                raw.contains("://") -> raw
                else -> "http://$raw"
            }
            val uri = runCatching { URI(withScheme) }.getOrNull()
            if (uri != null && !uri.host.isNullOrBlank()) {
                val host = uri.host.trim()
                val port = when {
                    uri.port > 0 -> uri.port
                    else -> defaultPort
                }
                return host to port
            }
            // Fall back to splitting host:port when URI parsing cannot resolve a host.
            val hostPart = raw.substringBefore('/').substringBefore('?')
            if (hostPart.contains(':') && !hostPart.startsWith('[')) {
                val idx = hostPart.lastIndexOf(':')
                val h = hostPart.substring(0, idx).trim()
                val p = hostPart.substring(idx + 1).toIntOrNull()
                if (h.isNotEmpty() && p != null && p in 1..65535) return h to p
            }
            return hostPart.trim() to defaultPort
        }

        fun fromBaseUrl(baseUrl: String): HomeLanServerConfig {
            val (host, port) = parseHostPort(baseUrl, DEFAULT_SERVER_PORT)
            return HomeLanServerConfig(host = host, port = port, allowedSsids = emptyList())
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
