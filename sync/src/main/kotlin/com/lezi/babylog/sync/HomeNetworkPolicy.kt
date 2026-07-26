package com.lezi.babylog.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_HEALTH_RESPONSE_BYTES = 64 * 1024

/** Capability strings advertised by lezi-sync on `GET /health`. */
const val CAPABILITY_ATOMIC_BUNDLE = "atomic_bundle"
const val CAPABILITY_RECORD_MEMBERSHIP_AUTHOR = "record_membership_author"

enum class HomeNetworkDecision {
    Allowed,
    MissingServer,
    MissingSsidAllowlist,
    NotOnWifi,
    SsidUnavailable,
    SsidNotMatched,
    ServerUnavailable,
    BackingOff,
    Background,
}

/**
 * Result of probing NAS `/health`.
 *
 * [capabilities] is empty for legacy servers that omit the additive field;
 * clients that require atomic packages must treat missing
 * [CAPABILITY_ATOMIC_BUNDLE] as unsupported (no silent metadata-first fallback).
 */
data class HealthStatus(
    val ok: Boolean,
    val version: String? = null,
    val capabilities: Set<String> = emptySet(),
) {
    val supportsAtomicBundle: Boolean
        get() = CAPABILITY_ATOMIC_BUNDLE in capabilities

    val supportsRecordMembershipAuthor: Boolean
        get() = CAPABILITY_RECORD_MEMBERSHIP_AUTHOR in capabilities
}

interface NetworkState {
    fun isWifiConnected(): Boolean
    fun currentWifiSsid(): String?
}

fun interface HealthProbe {
    suspend fun probe(baseUrl: String): HealthStatus
}

suspend fun HealthProbe.isHealthy(baseUrl: String): Boolean = probe(baseUrl).ok

fun interface PolicyClock {
    fun nowMillis(): Long
}

interface ForegroundState {
    fun isForeground(): Boolean
    fun setForeground(value: Boolean)
}

@Singleton
class ProcessForegroundState @Inject constructor() : ForegroundState {
    private val foreground = AtomicBoolean(false)

    override fun isForeground(): Boolean = foreground.get()

    override fun setForeground(value: Boolean) {
        foreground.set(value)
    }
}

@Singleton
class HomeNetworkPolicy @Inject constructor(
    private val networkState: NetworkState,
    private val healthProbe: HealthProbe,
    private val clock: PolicyClock,
) {
    private var consecutiveFailures = 0
    private var retryAfterMillis = 0L
    private var backoffBaseUrl = ""

    /** Last successful health status (capabilities for atomic-bundle gating). */
    @Volatile
    var lastHealthStatus: HealthStatus = HealthStatus(ok = false)
        private set

    val supportsAtomicBundle: Boolean
        get() = lastHealthStatus.supportsAtomicBundle

    val supportsRecordMembershipAuthor: Boolean
        get() = lastHealthStatus.supportsRecordMembershipAuthor

    /**
     * Gate for create / invite / join / push / pull.
     * @param config local home-LAN server + SSID allowlist
     */
    suspend fun evaluate(
        config: HomeLanServerConfig,
        isForeground: Boolean,
    ): HomeNetworkDecision {
        val normalized = config.withNormalized()
        if (!normalized.isServerConfigured) return HomeNetworkDecision.MissingServer
        if (!normalized.hasSsidAllowlist) return HomeNetworkDecision.MissingSsidAllowlist
        if (!isForeground) return HomeNetworkDecision.Background
        if (!networkState.isWifiConnected()) return HomeNetworkDecision.NotOnWifi
        val currentSsid = networkState.currentWifiSsid()?.trim().orEmpty()
        if (currentSsid.isEmpty() || isUnknownSsid(currentSsid)) {
            return HomeNetworkDecision.SsidUnavailable
        }
        if (!HomeLanServerConfig.ssidMatches(currentSsid, normalized.allowedSsids)) {
            return HomeNetworkDecision.SsidNotMatched
        }
        val baseUrl = normalized.baseUrl
        if (baseUrl != backoffBaseUrl) {
            backoffBaseUrl = baseUrl
            consecutiveFailures = 0
            retryAfterMillis = 0
        }
        if (clock.nowMillis() < retryAfterMillis) return HomeNetworkDecision.BackingOff
        val health = healthProbe.probe(baseUrl)
        return if (health.ok) {
            consecutiveFailures = 0
            retryAfterMillis = 0
            lastHealthStatus = health
            HomeNetworkDecision.Allowed
        } else {
            val delays = longArrayOf(30_000, 120_000, 600_000)
            val delay = delays[consecutiveFailures.coerceAtMost(delays.lastIndex)]
            consecutiveFailures++
            retryAfterMillis = clock.nowMillis() + delay
            HomeNetworkDecision.ServerUnavailable
        }
    }

    /** Legacy helper: evaluate with baseUrl only (empty SSID list → MissingSsidAllowlist). */
    suspend fun evaluate(baseUrl: String, isForeground: Boolean): HomeNetworkDecision {
        val config = HomeLanServerConfig.fromBaseUrl(baseUrl)
        return evaluate(config, isForeground)
    }

    companion object {
        fun isUnknownSsid(ssid: String): Boolean {
            val s = ssid.trim().removePrefix("\"").removeSuffix("\"")
            return s.isEmpty() ||
                s.equals("<unknown ssid>", ignoreCase = true) ||
                s.equals("unknown ssid", ignoreCase = true)
        }
    }
}

class AndroidNetworkState @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkState {
    override fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun currentWifiSsid(): String? {
        if (!isWifiConnected()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"")
            if (HomeNetworkPolicy.isUnknownSsid(cleaned)) null else cleaned
        }.getOrNull()
    }
}

class HttpHealthProbe @Inject constructor() : HealthProbe {
    override suspend fun probe(baseUrl: String): HealthStatus = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@runCatching HealthStatus(ok = false)
                val body = connection.readBodyWithinLimit(MAX_HEALTH_RESPONSE_BYTES)
                    ?: return@runCatching HealthStatus(ok = false)
                parseHealthStatus(body)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(HealthStatus(ok = false))
    }
}

internal fun parseHealthStatus(body: ByteArray): HealthStatus {
    if (body.isEmpty()) return HealthStatus(ok = true)
    return runCatching {
        val json = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: true
        val version = json["version"]?.jsonPrimitive?.contentOrNull
        val capabilities = (json["capabilities"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
            .toSet()
        HealthStatus(ok = ok, version = version, capabilities = capabilities)
    }.getOrDefault(HealthStatus(ok = true))
}

private fun HttpURLConnection.readBodyWithinLimit(limitBytes: Int): ByteArray? {
    if (contentLengthLong > limitBytes) return null
    return inputStream.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, limitBytes))
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limitBytes) return null
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

class SystemPolicyClock @Inject constructor() : PolicyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
