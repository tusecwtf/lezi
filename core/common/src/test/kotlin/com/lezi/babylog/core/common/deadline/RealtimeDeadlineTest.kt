package com.lezi.babylog.core.common.deadline

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RealtimeDeadlineTest {
    @Test
    fun wallClockExpiryFiresTheCallbackOnce() {
        val fires = AtomicInteger()
        RealtimeDeadline(80L) { fires.incrementAndGet() }.use {
            assertThat(awaitUntil(400L) { fires.get() > 0 }).isTrue()
        }
        assertThat(fires.get()).isEqualTo(1)
    }

    @Test
    fun closeBeforeExpiryDoesNotFire() {
        val expired = AtomicBoolean(false)
        RealtimeDeadline(200L) { expired.set(true) }.close()
        Thread.sleep(350)
        assertThat(expired.get()).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun virtualCoroutineTimeDoesNotExpireAHealthyDeadline() = runTest {
        val expired = AtomicBoolean(false)
        RealtimeDeadline(5_000L) { expired.set(true) }.use {
            advanceTimeBy(10_000)
            runCurrent()
            assertThat(expired.get()).isFalse()
        }
    }

    @Test
    fun resetPostponesExpiryUntilProgressStops() {
        val expired = AtomicBoolean(false)
        RealtimeDeadline(150L) { expired.set(true) }.use { deadline ->
            repeat(4) {
                Thread.sleep(50)
                deadline.reset()
                assertThat(expired.get()).isFalse()
            }
            assertThat(awaitUntil(400L) { expired.get() }).isTrue()
        }
    }

    private fun awaitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val latch = CountDownLatch(1)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) {
                latch.countDown()
                return true
            }
            Thread.sleep(10)
        }
        return predicate()
    }
}
