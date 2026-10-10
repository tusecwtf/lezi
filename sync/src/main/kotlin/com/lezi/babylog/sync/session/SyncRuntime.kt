package com.lezi.babylog.sync.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

fun interface PolicyClock {
    fun nowMillis(): Long
}

interface ForegroundState {
    fun isForeground(): Boolean
    fun setForeground(value: Boolean)
    suspend fun <T> whileForeground(block: suspend () -> T): T
}

/** Distinct control flow so the process-long signal consumer survives a residency ending. */
internal class ForegroundSessionEnded : CancellationException("Application left foreground")

@Singleton
class ProcessForegroundState @Inject constructor() : ForegroundState {
    private val lock = Any()
    private var foreground = false
    private val jobs = mutableSetOf<Job>()

    override fun isForeground(): Boolean = synchronized(lock) { foreground }

    override fun setForeground(value: Boolean) {
        val retired = synchronized(lock) {
            foreground = value
            if (value) emptyList() else jobs.toList().also { jobs.clear() }
        }
        // Cancellation can synchronously run user finalizers. Do it outside the registry
        // lock and over a snapshot, so finalizers cannot mutate the collection being visited.
        retired.forEach { it.cancel(ForegroundSessionEnded()) }
    }

    override suspend fun <T> whileForeground(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            if (!foreground) throw ForegroundSessionEnded()
            jobs.add(job)
        }
        try {
            block()
        } finally {
            synchronized(lock) { jobs.remove(job) }
        }
    }
}

class SystemPolicyClock @Inject constructor() : PolicyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
