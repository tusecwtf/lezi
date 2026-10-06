package com.lezi.babylog.sync.backend.deadline

import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Short typed budgets for family HTTP that is not owned by the H15 foreground
 * retry table. H15 handshake/pull/commit/resolution/media-prepare numbers stay
 * pinned elsewhere.
 */
internal data class FamilyHttpBudget(
    val connectTimeoutMillis: Int,
    val responseTimeoutMillis: Int,
    val maxAttempts: Int,
    val maxElapsedMillis: Long,
    val writeStallTimeoutMillis: Long,
) {
    init {
        require(connectTimeoutMillis > 0)
        require(responseTimeoutMillis > 0)
        require(maxAttempts > 0)
        require(maxElapsedMillis > 0)
        require(writeStallTimeoutMillis > 0)
    }
}

internal enum class FamilyHttpOperation(
    val budget: FamilyHttpBudget,
    val elapsedKind: FamilyHttpFailureKind,
) {
    Probe(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 5_000,
            maxAttempts = 1,
            maxElapsedMillis = 8_000,
            writeStallTimeoutMillis = 3_000,
        ),
        elapsedKind = FamilyHttpFailureKind.ResponseTimedOut,
    ),
    Session(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 8_000,
            maxAttempts = 2,
            maxElapsedMillis = 12_000,
            writeStallTimeoutMillis = 5_000,
        ),
        elapsedKind = FamilyHttpFailureKind.ResponseTimedOut,
    ),
    SessionWrite(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 8_000,
            maxAttempts = 1,
            maxElapsedMillis = 12_000,
            writeStallTimeoutMillis = 5_000,
        ),
        elapsedKind = FamilyHttpFailureKind.ResponseTimedOut,
    ),
    AppUpdateMetadata(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 5_000,
            maxAttempts = 1,
            maxElapsedMillis = 8_000,
            writeStallTimeoutMillis = 3_000,
        ),
        elapsedKind = FamilyHttpFailureKind.ResponseTimedOut,
    ),
    MediaGet(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 8_000,
            maxAttempts = 1,
            maxElapsedMillis = 30_000,
            writeStallTimeoutMillis = 5_000,
        ),
        elapsedKind = FamilyHttpFailureKind.SyncTookTooLong,
    ),
    DisasterRestore(
        FamilyHttpBudget(
            connectTimeoutMillis = 3_000,
            responseTimeoutMillis = 8_000,
            maxAttempts = 1,
            maxElapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS,
            writeStallTimeoutMillis = 5_000,
        ),
        elapsedKind = FamilyHttpFailureKind.SyncTookTooLong,
    ),
}

/**
 * Foreground / pull-to-refresh whole-cycle cap. H15 per-request numbers stay
 * pinned on [com.lezi.babylog.sync.backend.retry.SyncRetryOperation].
 */
internal object ForegroundSyncCycle {
    const val MAX_ELAPSED_MILLIS = 120_000L
}

/**
 * LocalWrite whole-cycle stall cap. Prevents a wedged push from hanging forever;
 * it is not a cut for a legitimate large-media upload (single-request MediaPrepare
 * budgets stay on [com.lezi.babylog.sync.backend.retry.SyncRetryOperation]).
 */
internal const val LOCAL_WRITE_MAX_ELAPSED_MILLIS = 10 * 60_000L

internal class ElapsedBudgetContext(
    val kind: FamilyHttpFailureKind,
    private val startedAtMillis: Long,
    private val clock: SyncRetryClock,
    val maxElapsedMillis: Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ElapsedBudgetContext>

    fun remainingMillis(): Long {
        val elapsed = clock.snapshot().elapsedRealtimeMillis - startedAtMillis
        val spent = if (elapsed >= 0) elapsed else 0L
        return maxElapsedMillis - spent
    }

    fun requireRemaining() {
        if (remainingMillis() <= 0) {
            throw FamilyHttpException(kind)
        }
    }
}

internal class FamilyHttpAttemptContext(
    val operation: FamilyHttpOperation,
    private val startedAtMillis: Long,
    private val clock: SyncRetryClock,
    private val parent: ElapsedBudgetContext? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FamilyHttpAttemptContext>

    fun remainingMillis(): Long {
        val elapsed = clock.snapshot().elapsedRealtimeMillis - startedAtMillis
        val spent = if (elapsed >= 0) elapsed else 0L
        val operationLeft = operation.budget.maxElapsedMillis - spent
        val parentLeft = parent?.remainingMillis() ?: Long.MAX_VALUE
        return minOf(operationLeft, parentLeft)
    }
}

internal fun FamilyHttpAttemptContext.boundedConnectTimeoutMillis(): Int =
    boundedFamilyHttpTimeout(operation.budget.connectTimeoutMillis, remainingMillis())

internal fun FamilyHttpAttemptContext.boundedResponseTimeoutMillis(): Int =
    boundedFamilyHttpTimeout(operation.budget.responseTimeoutMillis, remainingMillis())

internal fun FamilyHttpAttemptContext.boundedWriteStallTimeoutMillis(): Long =
    minOf(operation.budget.writeStallTimeoutMillis, remainingMillis().coerceAtLeast(1L))

internal fun boundedFamilyHttpTimeout(configuredMillis: Int, remainingMillis: Long): Int {
    require(remainingMillis > 0) { "家庭 HTTP 剩余预算必须大于 0" }
    return minOf(configuredMillis.toLong(), remainingMillis, Int.MAX_VALUE.toLong())
        .toInt()
        .coerceAtLeast(1)
}
