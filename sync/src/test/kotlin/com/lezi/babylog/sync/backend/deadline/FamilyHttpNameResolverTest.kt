package com.lezi.babylog.sync.backend.deadline

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import org.junit.Test

/** Finite synthetic resolver stalls, not an Android DNS recovery measurement. */
class FamilyHttpNameResolverTest {
    @Test
    fun repeatedTimeoutsUnlinkQueuedTasksWhileBothWorkersIgnoreInterrupts() {
        withBlockedWorkers { executor, release ->
            val invoked = AtomicInteger()
            repeat(40) {
                val failure = runCatching {
                    resolveFamilyHttpHost("synthetic.example", 5, FamilyHttpNameResolver {
                        invoked.incrementAndGet()
                        arrayOf(InetAddress.getLoopbackAddress())
                    }, executor)
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
                assertThat((failure as FamilyHttpException).kind).isEqualTo(FamilyHttpFailureKind.AddressNotFound)
                assertThat(executor.queue).isEmpty()
                assertThat(executor.largestPoolSize).isEqualTo(2)
            }
            release.countDown()
            val addresses = resolveFamilyHttpHost("synthetic.example", 2_000, FamilyHttpNameResolver {
                arrayOf(InetAddress.getLoopbackAddress())
            }, executor)
            assertThat(addresses.toList()).containsExactly(InetAddress.getLoopbackAddress())
            assertThat(invoked.get()).isEqualTo(0)
        }
    }

    @Test
    fun fullWaitingQueueRejectsWithoutCallerRunsOrAnExtraWorker() {
        withBlockedWorkers { executor, _ ->
            val first = FutureTask { Unit }
            val second = FutureTask { Unit }
            executor.execute(first)
            executor.execute(second)
            val invoked = AtomicBoolean()
            val failure = runCatching {
                resolveFamilyHttpHost("synthetic.example", 2_000, FamilyHttpNameResolver {
                    invoked.set(true)
                    arrayOf(InetAddress.getLoopbackAddress())
                }, executor)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
            assertThat((failure as FamilyHttpException).kind).isEqualTo(FamilyHttpFailureKind.AddressNotFound)
            assertThat(failure.cause).isInstanceOf(RejectedExecutionException::class.java)
            assertThat(executor.queue).hasSize(2)
            assertThat(executor.largestPoolSize).isEqualTo(2)
            assertThat(invoked.get()).isFalse()
            first.cancel(false)
            second.cancel(false)
            executor.remove(first)
            executor.remove(second)
        }
    }

    @Test
    fun interruptedWaitUnlinksItsTaskAndPreservesCancellationAndInterruptFlag() {
        withBlockedWorkers { executor, _ ->
            val failure = AtomicReference<Throwable?>()
            val interrupted = AtomicBoolean()
            val invoked = AtomicBoolean()
            val caller = thread(name = "synthetic-dns-caller") {
                failure.set(runCatching {
                    resolveFamilyHttpHost("synthetic.example", 10_000, FamilyHttpNameResolver {
                        invoked.set(true)
                        arrayOf(InetAddress.getLoopbackAddress())
                    }, executor)
                }.exceptionOrNull())
                interrupted.set(Thread.currentThread().isInterrupted)
            }
            try {
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (executor.queue.isEmpty() && caller.isAlive && System.nanoTime() < limit) Thread.sleep(1)
                assertThat(executor.queue).hasSize(1)
                caller.interrupt()
                caller.join(2_000)
                assertThat(caller.isAlive).isFalse()
                assertThat(failure.get()).isInstanceOf(CancellationException::class.java)
                assertThat(interrupted.get()).isTrue()
                assertThat(executor.queue).isEmpty()
                assertThat(invoked.get()).isFalse()
            } finally {
                caller.interrupt()
                caller.join(2_000)
            }
        }
    }

    @Test
    fun exhaustedAllowanceDoesNotSubmitOrStartAResolver() {
        val executor = newFamilyHttpDnsExecutor()
        try {
            val failure = runCatching {
                resolveFamilyHttpHost("synthetic.example", 0, FamilyHttpNameResolver {
                    error("expired caller must not invoke resolver")
                }, executor)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
            assertThat((failure as FamilyHttpException).kind).isEqualTo(FamilyHttpFailureKind.AddressNotFound)
            assertThat(failure.cause).isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            assertThat(executor.taskCount).isEqualTo(0)
            assertThat(executor.queue).isEmpty()
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun literalAddressesBypassASaturatedPoolAndUnknownHostKeepsItsClassification() {
        withBlockedWorkers { executor, _ ->
            repeat(2) { executor.execute(FutureTask { Unit }) }
            val literal = resolveFamilyHttpHost("127.0.0.1", 5, FamilyHttpNameResolver {
                error("literal IP must not invoke resolver")
            }, executor)
            assertThat(literal.single().hostAddress).isEqualTo("127.0.0.1")
        }
        val executor = newFamilyHttpDnsExecutor()
        try {
            val failure = runCatching {
                resolveFamilyHttpHost("synthetic.example", 2_000, FamilyHttpNameResolver {
                    throw UnknownHostException("synthetic NXDOMAIN")
                }, executor)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
            assertThat((failure as FamilyHttpException).kind).isEqualTo(FamilyHttpFailureKind.AddressNotFound)
            assertThat(executor.queue).isEmpty()
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun withBlockedWorkers(block: (ThreadPoolExecutor, CountDownLatch) -> Unit) {
        val executor = newFamilyHttpDnsExecutor()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            repeat(2) {
                executor.execute {
                    entered.countDown()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* synthetic non-cooperative DNS */ }
                    }
                }
            }
            check(entered.await(2, TimeUnit.SECONDS)) { "synthetic DNS workers did not start" }
            block(executor, release)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }
}
