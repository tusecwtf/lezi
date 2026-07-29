package com.lezi.babylog.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Foreground-only automatic retry policy for durable outbox publication. */
internal object ForegroundSyncRetryPolicy {
    private val delaysMillis = longArrayOf(30_000L, 120_000L, 600_000L)

    fun delayMillis(failure: Throwable, consecutiveFailures: Int): Long? {
        if (failure is CancellationException) return null
        val retryable = when (failure) {
            is SyncHttpException ->
                failure.statusCode == 408 ||
                    failure.statusCode == 429 ||
                    failure.statusCode in 500..599
            is HomeNetworkBlockedException -> failure.decision in setOf(
                HomeNetworkDecision.ServerUnavailable,
                HomeNetworkDecision.BackingOff,
            )
            else -> failure.hasIoCause()
        }
        if (!retryable) return null
        return delaysMillis[consecutiveFailures.coerceIn(0, delaysMillis.lastIndex)]
    }
}

private fun Throwable.hasIoCause(): Boolean {
    var cursor: Throwable? = this
    while (cursor != null) {
        if (cursor is IOException) return true
        cursor = cursor.cause
    }
    return false
}
