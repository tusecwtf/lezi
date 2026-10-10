package com.lezi.babylog.sync.backend.deadline

import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SystemSyncRetryClock
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class FamilyHttpDeadlinePolicy(
    private val clock: SyncRetryClock = SystemSyncRetryClock,
) {
    suspend fun <T> execute(
        operation: FamilyHttpOperation,
        request: suspend () -> T,
    ): T {
        val parent = currentCoroutineContext()[ElapsedBudgetContext]
        if (parent != null && parent.remainingMillis() <= 0) {
            throw FamilyHttpException(parent.kind)
        }
        val startedAt = clock.snapshot().elapsedRealtimeMillis
        val attemptContext = FamilyHttpAttemptContext(operation, startedAt, clock, parent)
        var attempts = 0
        while (true) {
            attempts += 1
            val remaining = attemptContext.remainingMillis()
            if (remaining <= 0) {
                throw FamilyHttpException(parent?.kind ?: operation.elapsedKind)
            }
            try {
                val result = withContext(attemptContext) { request() }
                if (attemptContext.remainingMillis() <= 0) {
                    throw FamilyHttpException(parent?.kind ?: operation.elapsedKind)
                }
                return result
            } catch (failure: Throwable) {
                failure.cancellationCauseOrNull()?.let { throw it }
                if (failure !is IOException || failure.isFamilyTrustFailure()) throw failure
                val classified = classifyFamilyHttpFailure(failure, operation)
                if (
                    operation.replay == FamilyHttpReplay.Never ||
                    attempts >= operation.budget.maxAttempts ||
                    !classified.kind.isImmediateRetryable
                ) {
                    throw classified
                }
            }
        }
    }
}

internal suspend fun <T> withElapsedBudget(
    kind: FamilyHttpFailureKind,
    maxElapsedMillis: Long,
    clock: SyncRetryClock,
    block: suspend () -> T,
): T {
    val startedAt = clock.snapshot().elapsedRealtimeMillis
    val budget = ElapsedBudgetContext(kind, startedAt, clock, maxElapsedMillis)
    return withContext(budget) {
        val remaining = budget.remainingMillis()
        if (remaining <= 0) throw FamilyHttpException(kind)
        try {
            withTimeout(remaining) { block() }
        } catch (cancelled: TimeoutCancellationException) {
            throw FamilyHttpException(kind, cancelled)
        }
    }
}

internal fun IOException.asFamilyHttpFailure(operation: FamilyHttpOperation? = null): Nothing {
    if (isFamilyTrustFailure()) throw this
    throw classifyFamilyHttpFailure(this, operation)
}

internal fun Throwable.isFamilyTrustFailure(): Boolean =
    generateSequence(this) { it.cause }.any { current ->
        current is SSLException || current is CertificateException
    }
