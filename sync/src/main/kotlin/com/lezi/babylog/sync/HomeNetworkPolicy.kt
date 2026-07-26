package com.lezi.babylog.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_HEALTH_RESPONSE_BYTES = 64 * 1024

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

interface NetworkState {
    fun isWifiConnected(): Boolean
    fun currentWifiSsid(): String?
}

fun interface HealthProbe {
    suspend fun isHealthy(baseUrl: String): Boolean
}

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
        return if (healthProbe.isHealthy(baseUrl)) {
            consecutiveFailures = 0
            retryAfterMillis = 0
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
    override suspend fun isHealthy(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            try {
                val successful = connection.responseCode in 200..299
                successful && connection.hasBodyWithinLimit(MAX_HEALTH_RESPONSE_BYTES)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}

private fun HttpURLConnection.hasBodyWithinLimit(limitBytes: Int): Boolean {
    if (contentLengthLong > limitBytes) return false
    inputStream.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > limitBytes) return false
        }
    }
}

class SystemPolicyClock @Inject constructor() : PolicyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
