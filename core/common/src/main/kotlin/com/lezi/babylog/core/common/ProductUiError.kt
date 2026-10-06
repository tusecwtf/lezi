package com.lezi.babylog.core.common

// Hoisted once: looksTechnicalDetail runs per surfaced error message across feature VMs.
private val TECHNICAL_IPV4 = Regex("""/?\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?""")
private val TECHNICAL_HOST_FRAGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.[A-Za-z]{2,}(?::\d+)?""")
private val TECHNICAL_PORT_SUFFIX = Regex(""":\d{2,5}\b""")
private val TECHNICAL_PATH_FRAGMENT = Regex("""(?:^|[\s「『(])/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]*""")

/**
 * Map throwables to user-visible product Chinese.
 *
 * Never surface stack traces, hosts, paths, or English exception text in UI.
 * Known product-facing Chinese messages (without technical markers) pass through;
 * everything else falls back to [fallback].
 */
fun productUiError(error: Throwable, fallback: String): String {
    val message = error.message?.trim().orEmpty()
    if (message.isEmpty()) return fallback
    if (looksTechnicalDetail(message)) return fallback
    if (isProductFacingChinese(message)) return message
    return fallback
}

/** True when [message] looks like host/path/stack/exception detail. */
fun looksTechnicalDetail(message: String): Boolean {
    val lower = message.lowercase()
    val markers = listOf(
        "http://",
        "https://",
        "failed to connect",
        "connection refused",
        "timed out",
        "timeout",
        "java.",
        "kotlin.",
        "exception",
        "error:",
        "errno",
        "sqlite",
        "room.",
        "nullpointer",
        "illegalstate",
        "illegalargument",
        "cancellation",
        "/data/",
        "/storage/",
        "file://",
        "content://",
    )
    if (markers.any { lower.contains(it) }) return true
    // IPv4 with optional leading slash and port
    if (TECHNICAL_IPV4.containsMatchIn(message)) return true
    // host.domain or host:port fragments (e.g. nas.local:8765)
    if (TECHNICAL_HOST_FRAGMENT.containsMatchIn(message)) {
        return true
    }
    if (TECHNICAL_PORT_SUFFIX.containsMatchIn(message)) return true
    // Absolute / relative filesystem-ish paths
    if (TECHNICAL_PATH_FRAGMENT.containsMatchIn(message)) {
        return true
    }
    return false
}

/** True when [message] is primarily product Chinese (may include short nicknames). */
fun isProductFacingChinese(message: String): Boolean {
    if (!message.any { it.code in 0x4E00..0x9FFF }) return false
    val latinLetters = message.count { it in 'A'..'Z' || it in 'a'..'z' }
    // Allow short latin in nicknames; block English exception fragments.
    if (latinLetters > 12) return false
    return true
}
