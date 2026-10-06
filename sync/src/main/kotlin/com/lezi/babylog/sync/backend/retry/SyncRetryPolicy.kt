package com.lezi.babylog.sync.backend.retry

import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.syncHttpCodeOrNull
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class SyncRetryBudget(
    val connectTimeoutMillis: Int,
    val responseTimeoutMillis: Int,
    val maxAttempts: Int,
    val maxElapsedMillis: Long,
) {
    init {
        require(connectTimeoutMillis > 0)
        require(responseTimeoutMillis > 0)
        require(maxAttempts > 0)
        require(maxElapsedMillis > 0)
    }
}

internal enum class SyncRetryOperation(
    val budget: SyncRetryBudget,
) {
    Handshake(SyncRetryBudget(3_000, 10_000, 3, 30_000)),
    ConflictDetail(SyncRetryBudget(3_000, 10_000, 3, 30_000)),
    Pull(SyncRetryBudget(3_000, 20_000, 3, 60_000)),
    Commit(SyncRetryBudget(3_000, 20_000, 3, 60_000)),
    Resolution(SyncRetryBudget(3_000, 20_000, 3, 60_000)),
    /** Reserved for H17. H15 freezes its budget but does not connect media prepare. */
    MediaPrepare(SyncRetryBudget(5_000, 90_000, 3, 240_000)),
}

internal fun interface SyncRetryClock {
    fun snapshot(): SyncRetryTime
}

internal data class SyncRetryTime(
    val epochMillis: Long,
    val elapsedRealtimeMillis: Long,
)

internal fun interface SyncRetryRandom {
    fun nextLong(boundExclusive: Long): Long
}

internal fun interface SyncRetryDelay {
    suspend fun sleep(millis: Long)
}

internal object SystemSyncRetryClock : SyncRetryClock {
    override fun snapshot() = SyncRetryTime(
        epochMillis = System.currentTimeMillis(),
        elapsedRealtimeMillis = System.nanoTime() / 1_000_000L,
    )
}

internal object DefaultSyncRetryRandom : SyncRetryRandom {
    override fun nextLong(boundExclusive: Long): Long = Random.nextLong(boundExclusive)
}

internal object CoroutineSyncRetryDelay : SyncRetryDelay {
    override suspend fun sleep(millis: Long) = delay(millis)
}

internal enum class SyncRetryFailureCategory {
    Timeout,
    Transport,
    Throttled,
    Unavailable,
}

internal data class SyncRetryEvent(
    val operation: SyncRetryOperation,
    val category: SyncRetryFailureCategory,
    val completedAttempts: Int,
    val delayMillis: Long,
)

internal fun interface SyncRetryEventSink {
    fun record(event: SyncRetryEvent)
}

internal object NoOpSyncRetryEventSink : SyncRetryEventSink {
    override fun record(event: SyncRetryEvent) = Unit
}

/** Carries the retry owner's monotonic deadline into the blocking HTTP adapter. */
internal class SyncRetryAttemptContext(
    val operation: SyncRetryOperation,
    private val startedAtMillis: Long,
    private val clock: SyncRetryClock,
    private val parentRemainingMillis: () -> Long = { Long.MAX_VALUE },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncRetryAttemptContext>

    fun remainingMillis(): Long = minOf(
        operation.budget.maxElapsedMillis - elapsedSince(
            startedAtMillis,
            clock.snapshot().elapsedRealtimeMillis,
        ),
        parentRemainingMillis(),
    )
}

/**
 * One foreground retry owner for idempotent sync transport operations.
 *
 * Product facts never enter this module. Its interface only accepts an operation category,
 * clock/random boundaries and a suspend call, so telemetry cannot contain family content.
 */
internal class SyncRetryPolicy(
    private val clock: SyncRetryClock = SystemSyncRetryClock,
    private val random: SyncRetryRandom = DefaultSyncRetryRandom,
    private val delay: SyncRetryDelay = CoroutineSyncRetryDelay,
    private val events: SyncRetryEventSink = NoOpSyncRetryEventSink,
) {
    suspend fun <T> execute(
        operation: SyncRetryOperation,
        request: suspend () -> T,
    ): T {
        val startedAt = clock.snapshot().elapsedRealtimeMillis
        val parent = currentCoroutineContext()[ElapsedBudgetContext]
        val attemptContext = SyncRetryAttemptContext(operation, startedAt, clock) {
            parent?.remainingMillis() ?: Long.MAX_VALUE
        }
        var attempts = 0
        while (true) {
            attempts += 1
            val beforeAttempt = clock.snapshot().elapsedRealtimeMillis
            val remainingBeforeAttempt = minOf(
                operation.budget.maxElapsedMillis - elapsedSince(startedAt, beforeAttempt),
                parent?.remainingMillis() ?: Long.MAX_VALUE,
            )
            if (remainingBeforeAttempt <= 0) {
                if (parent != null && parent.remainingMillis() <= 0) {
                    throw FamilyHttpException(parent.kind)
                }
                throw SyncRetryBudgetExceededException(operation)
            }
            try {
                val result = withContext(attemptContext) {
                    withTimeout(remainingBeforeAttempt) { request() }
                }
                if (attemptContext.remainingMillis() <= 0) {
                    throw SyncRetryBudgetExceededException(operation)
                }
                return result
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw cancelled.originalCancellationOrSelf()
                }
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (attempts >= operation.budget.maxAttempts) {
                    throw cancelled.asBudgetExceededIfTimeout(operation)
                }
                val retry = ClassifiedRetryFailure(SyncRetryFailureCategory.Timeout, null)
                retryOrThrow(operation, startedAt, attempts, retry, cancelled)
            } catch (failure: Throwable) {
                failure.cancellationCauseOrNull()?.let { throw it }
                val retry = classifyRetryable(failure) ?: throw failure
                if (attempts >= operation.budget.maxAttempts) throw failure
                retryOrThrow(operation, startedAt, attempts, retry, failure)
            }
        }
    }

    private suspend fun retryOrThrow(
        operation: SyncRetryOperation,
        startedAt: Long,
        completedAttempts: Int,
        failure: ClassifiedRetryFailure,
        original: Throwable,
    ) {
        val now = clock.snapshot()
        val remaining = operation.budget.maxElapsedMillis -
            elapsedSince(startedAt, now.elapsedRealtimeMillis)
        val retryAfter = failure.retryAfterHeader?.let {
            parseRetryAfterMillis(it, now.epochMillis)
        }
        val selectedDelay = retryAfter ?: fullJitterMillis(completedAttempts)
        if (selectedDelay > remaining) throw original.asBudgetExceededIfTimeout(operation)
        events.record(
            SyncRetryEvent(
                operation = operation,
                category = failure.category,
                completedAttempts = completedAttempts,
                delayMillis = selectedDelay,
            ),
        )
        delay.sleep(selectedDelay)
        if (
            elapsedSince(startedAt, clock.snapshot().elapsedRealtimeMillis) >=
            operation.budget.maxElapsedMillis
        ) {
            throw original.asBudgetExceededIfTimeout(operation)
        }
    }

    private fun fullJitterMillis(completedAttempts: Int): Long {
        val exponent = (completedAttempts - 1).coerceIn(0, 20)
        val ceiling = min(FULL_JITTER_CAP_MILLIS, FULL_JITTER_BASE_MILLIS shl exponent)
        return random.nextLong(ceiling + 1).also { value ->
            require(value in 0..ceiling) { "retry random returned an out-of-range value" }
        }
    }
}

private fun Throwable.asBudgetExceededIfTimeout(
    operation: SyncRetryOperation,
): Throwable = if (this is TimeoutCancellationException) {
    SyncRetryBudgetExceededException(operation)
} else {
    this
}

private fun CancellationException.originalCancellationOrSelf(): CancellationException {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = this
    while (visited.add(current)) {
        val nested = try {
            current.cause as? CancellationException
        } catch (_: Throwable) {
            null
        } ?: return current
        current = nested
    }
    return current
}

internal class SyncRetryBudgetExceededException(operation: SyncRetryOperation) :
    IOException("${operation.name} retry elapsed budget exhausted")

private data class ClassifiedRetryFailure(
    val category: SyncRetryFailureCategory,
    val retryAfterHeader: String?,
)

private fun classifyRetryable(failure: Throwable): ClassifiedRetryFailure? = when (failure) {
    is ReauthRequiredException,
    is ClientUpdateRequiredException,
    is SyncHandshakeRejectedException,
    is SyncRetryBudgetExceededException,
    -> null
    is SyncHttpException -> when {
        failure.statusCode == 408 -> ClassifiedRetryFailure(
            SyncRetryFailureCategory.Timeout,
            failure.retryAfterHeader,
        )
        failure.statusCode == 429 -> ClassifiedRetryFailure(
            SyncRetryFailureCategory.Throttled,
            failure.retryAfterHeader,
        )
        failure.statusCode in 500..599 && !failure.hasTerminalServerCode() ->
            ClassifiedRetryFailure(
                SyncRetryFailureCategory.Unavailable,
                failure.retryAfterHeader,
            )
        else -> null
    }
    is java.net.SocketTimeoutException ->
        ClassifiedRetryFailure(SyncRetryFailureCategory.Timeout, null)
    is IOException -> {
        ClassifiedRetryFailure(SyncRetryFailureCategory.Transport, null)
    }
    else -> null
}

private fun SyncHttpException.hasTerminalServerCode(): Boolean =
    syncHttpCodeOrNull(responseBody) in TERMINAL_SERVER_CODES

internal fun parseRetryAfterMillis(header: String, nowEpochMillis: Long): Long? {
    val value = header.trim()
    if (value.isEmpty()) return null
    value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
        return runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrNull()
    }
    return try {
        val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        max(0L, retryAt - nowEpochMillis)
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

private fun elapsedSince(startedAt: Long, now: Long): Long =
    if (now >= startedAt) now - startedAt else 0L

private const val FULL_JITTER_BASE_MILLIS = 1_000L
private const val FULL_JITTER_CAP_MILLIS = 10_000L

private val TERMINAL_SERVER_CODES = setOf(
    "unauthenticated",
    "forbidden",
    "capability_mismatch",
    "not_ready",
    "unknown_field",
    "missing_field",
    "wrong_type",
    "non_canonical_value",
    "invalid_domain",
    "content_drift",
    "invalid_snapshot_token",
    "snapshot_expired",
    "snapshot_stale",
    "invalid_choice",
    "duplicate_choice",
    "incomplete_choices",
    "missing_restore_base",
    "incomplete_restore_base",
    "missing_restore_media",
    "cas_mismatch",
)
