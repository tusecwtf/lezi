package com.lezi.babylog.sync.engine
import java.io.IOException
import kotlinx.coroutines.CancellationException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.syncHttpCodeOrNull

/** Foreground-only automatic retry policy for durable outbox publication. */
internal object ForegroundSyncRetryPolicy {
    private val delaysMillis = longArrayOf(30_000L, 120_000L, 600_000L)

    fun delayMillis(failure: Throwable, consecutiveFailures: Int): Long? {
        if (failure is CancellationException) return null
        val retryable = when (failure) {
            // Force-upgrade is terminal until the user installs a newer APK.
            is ClientUpdateRequiredException -> false
            is SyncHttpException ->
                syncHttpCodeOrNull(failure.responseBody) != "client_update_required" &&
                    (
                        failure.statusCode == 408 ||
                            failure.statusCode == 429 ||
                            failure.statusCode in 500..599
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
