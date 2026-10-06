package com.lezi.babylog.core.common.deadline

import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real wall-clock deadline. Must not use coroutine [kotlinx.coroutines.delay]
 * or [kotlinx.coroutines.withTimeout]: those clocks are virtual under `runTest`
 * and would expire a healthy blocking call.
 *
 * [reset] only moves the expire instant. It does not allocate a TimerTask per
 * progress tick; a follow-up task is scheduled only if the current one fires
 * while progress still has remaining time.
 */
class RealtimeDeadline(
    timeoutMillis: Long,
    threadName: String = "lezi-realtime-deadline",
    private val onExpired: () -> Unit,
) : AutoCloseable {
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val expiresAtNanos = AtomicLong(System.nanoTime() + timeoutNanos)
    private val active = AtomicBoolean(true)
    private val timer = Timer(threadName, true)

    init {
        require(timeoutMillis > 0)
        scheduleNext(timeoutMillis)
    }

    fun reset() {
        if (!active.get()) return
        expiresAtNanos.set(System.nanoTime() + timeoutNanos)
    }

    override fun close() {
        active.set(false)
        timer.cancel()
    }

    private fun scheduleNext(delayMillis: Long) {
        if (!active.get()) return
        try {
            timer.schedule(
                object : TimerTask() {
                    override fun run() {
                        if (!active.get()) return
                        val remainingNanos = expiresAtNanos.get() - System.nanoTime()
                        if (remainingNanos <= 0L) {
                            if (active.compareAndSet(true, false)) onExpired()
                        } else {
                            scheduleNext(
                                TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                            )
                        }
                    }
                },
                delayMillis.coerceAtLeast(1L),
            )
        } catch (_: IllegalStateException) {
            // Timer already cancelled by [close].
        }
    }
}
