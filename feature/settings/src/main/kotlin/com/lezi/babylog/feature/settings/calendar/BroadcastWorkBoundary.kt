package com.lezi.babylog.feature.settings.calendar

import com.lezi.babylog.core.common.cancellation.cancellationCauseOrNull
import kotlinx.coroutines.CancellationException

/**
 * Process boundary for asynchronous [android.content.BroadcastReceiver] work.
 * Operational failures are reported instead of reaching the process default handler,
 * while coroutine cancellation keeps its structured-concurrency meaning.
 */
internal suspend fun runBroadcastWork(
    finish: () -> Unit,
    reportFailure: (Exception) -> Unit,
    work: suspend () -> Unit,
) {
    try {
        work()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure.cancellationCauseOrNull()?.let { throw it }
        reportFailure(failure)
    } finally {
        finish()
    }
}
