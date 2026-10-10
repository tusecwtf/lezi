package com.lezi.babylog.sync.backend.transport

import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A failed short-bound assertion must not strand the instrumentation runner inside runBlocking. */
internal class PlatformTransportCall(block: suspend () -> Any?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val completed = CountDownLatch(1)
    val cancelReturned = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val successes = AtomicInteger()
    val startedAt = SystemClock.elapsedRealtime()
    @Volatile var finishedAt = 0L
        private set
    @Volatile var cancelledAt = 0L
        private set
    @Volatile var cancelFinishedAt = 0L
        private set
    val cancellation = CancellationException("manual loopback transport cancellation")
    private val job = scope.launch {
        try {
            block()
            successes.incrementAndGet()
        } catch (error: Throwable) {
            failure.set(error)
        }
    }.also { operation ->
        operation.invokeOnCompletion {
            finishedAt = SystemClock.elapsedRealtime()
            completed.countDown()
        }
    }

    @Synchronized
    fun cancel() {
        if (cancelledAt != 0L) return
        cancelledAt = SystemClock.elapsedRealtime()
        // cancelActiveIo may itself enter blocking disconnect(). Measure that too.
        thread(name = "transport-cancel-caller", isDaemon = true) {
            try {
                job.cancel(cancellation)
            } finally {
                cancelFinishedAt = SystemClock.elapsedRealtime()
                cancelReturned.countDown()
            }
        }
    }

    fun awaitCompletion(milliseconds: Long): Boolean = completed.await(milliseconds, TimeUnit.MILLISECONDS)

    fun assertCleanupCompleted() {
        check(awaitCompletion(3_000)) { "Client call survived fixture cleanup" }
        check(cancelReturned.await(3_000, TimeUnit.MILLISECONDS)) { "Cancellation caller survived cleanup" }
    }
}
