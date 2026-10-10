package com.lezi.babylog.validation.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** One selected real DAO result waits here; all other reads remain untouched. */
class HeldRouteRead {
    val entered = CompletableDeferred<Unit>()
    // The actual caller Job has finished, not merely returned from this DAO.
    // If a caller uses withContext, its outer owner still needs a UI terminal oracle.
    val completed = CompletableDeferred<Throwable?>()
    private val released = CompletableDeferred<Unit>()

    suspend fun pause() {
        check(entered.complete(Unit)) { "A held read can be consumed only once" }
        currentCoroutineContext().job.invokeOnCompletion { completed.complete(it) }
        released.await()
    }

    fun release() { released.complete(Unit) }
}
