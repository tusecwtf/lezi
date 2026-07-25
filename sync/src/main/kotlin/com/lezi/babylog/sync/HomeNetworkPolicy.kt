package com.lezi.babylog.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HomeNetworkDecision {
    Allowed,
    MissingServer,
    NotOnWifi,
    ServerUnavailable,
    BackingOff,
    Background,
}

fun interface NetworkState {
    fun isWifiConnected(): Boolean
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

    suspend fun evaluate(baseUrl: String, isForeground: Boolean): HomeNetworkDecision {
        if (baseUrl.isBlank()) return HomeNetworkDecision.MissingServer
        if (!isForeground) return HomeNetworkDecision.Background
        if (!networkState.isWifiConnected()) return HomeNetworkDecision.NotOnWifi
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        if (normalizedBaseUrl != backoffBaseUrl) {
            backoffBaseUrl = normalizedBaseUrl
            consecutiveFailures = 0
            retryAfterMillis = 0
        }
        if (clock.nowMillis() < retryAfterMillis) return HomeNetworkDecision.BackingOff
        return if (healthProbe.isHealthy(normalizedBaseUrl)) {
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
}

class HttpHealthProbe @Inject constructor() : HealthProbe {
    override suspend fun isHealthy(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.useCaches = false
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}

class SystemPolicyClock @Inject constructor() : PolicyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
