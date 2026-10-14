package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class MediaLocalPathGateTest {
    @Test
    fun returnsResultAndReleasesPath() = runBlocking {
        val gate = MediaLocalPathGate()

        val result = gate.withLock(" media/owned-photo ") {
            assertThat(gate.retainedPathCount).isEqualTo(1)
            "result"
        }

        assertThat(result).isEqualTo("result")
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test
    fun rejectsBlankPathsWithoutRetainingThem() = runBlocking {
        val gate = MediaLocalPathGate()

        for (path in listOf("", " ", "\t\n")) {
            val failure = runCatching { gate.withLock(path) { error("must not run") } }
                .exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }

        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun ownerHandoffKeepsWaitersAndNewArrivalsOnTheSamePath() = runBlocking {
        val gate = MediaLocalPathGate()
        val releaseOwner = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()
        val releaseWaiter = CompletableDeferred<Unit>()
        val newcomerEntered = CompletableDeferred<Unit>()

        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock(" media/shared ") { releaseOwner.await() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") {
                waiterEntered.complete(Unit)
                releaseWaiter.await()
            }
        }
        assertThat(waiterEntered.isCompleted).isFalse()
        assertThat(gate.retainedPathCount).isEqualTo(1)

        releaseOwner.complete(Unit)
        owner.join()
        waiterEntered.await()
        assertThat(gate.retainedPathCount).isEqualTo(1)

        val newcomer = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared\n") { newcomerEntered.complete(Unit) }
        }
        assertThat(newcomerEntered.isCompleted).isFalse()
        releaseWaiter.complete(Unit)
        joinAll(waiter, newcomer)

        assertThat(newcomerEntered.isCompleted).isTrue()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun cancellingQueuedWaiterKeepsOwnerAndRemainingWaitersProtected() = runBlocking {
        val gate = MediaLocalPathGate()
        val releaseOwner = CompletableDeferred<Unit>()
        val cancelledEntered = CompletableDeferred<Unit>()
        val survivorEntered = CompletableDeferred<Unit>()
        val releaseSurvivor = CompletableDeferred<Unit>()
        val newcomerEntered = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") { releaseOwner.await() }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") { cancelledEntered.complete(Unit) }
        }
        val survivor = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") {
                survivorEntered.complete(Unit)
                releaseSurvivor.await()
            }
        }

        cancelled.cancelAndJoin()
        assertThat(cancelledEntered.isCompleted).isFalse()
        assertThat(gate.retainedPathCount).isEqualTo(1)
        val newcomer = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") { newcomerEntered.complete(Unit) }
        }
        assertThat(survivorEntered.isCompleted).isFalse()
        assertThat(newcomerEntered.isCompleted).isFalse()

        releaseOwner.complete(Unit)
        owner.join()
        survivorEntered.await()
        assertThat(newcomerEntered.isCompleted).isFalse()
        releaseSurvivor.complete(Unit)
        joinAll(survivor, newcomer)
        assertThat(newcomerEntered.isCompleted).isTrue()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun failingOwnerPropagatesFailureAndReleasesForItsWaiter() = runBlocking {
        val gate = MediaLocalPathGate()
        val failure = IllegalStateException("file operation failed")
        val failOwner = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()
        val releaseWaiter = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                gate.withLock("media/shared") {
                    failOwner.await()
                    throw failure
                }
            }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") {
                waiterEntered.complete(Unit)
                releaseWaiter.await()
            }
        }
        assertThat(waiterEntered.isCompleted).isFalse()

        failOwner.complete(Unit)
        assertThat(owner.await().exceptionOrNull()).isSameInstanceAs(failure)
        waiterEntered.await()
        assertThat(gate.retainedPathCount).isEqualTo(1)
        releaseWaiter.complete(Unit)
        waiter.join()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun cancellingOwnerReleasesForItsWaiter() = runBlocking {
        val gate = MediaLocalPathGate()
        val waiterEntered = CompletableDeferred<Unit>()
        val releaseWaiter = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") { CompletableDeferred<Unit>().await() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/shared") {
                waiterEntered.complete(Unit)
                releaseWaiter.await()
            }
        }

        owner.cancelAndJoin()
        waiterEntered.await()
        assertThat(gate.retainedPathCount).isEqualTo(1)
        releaseWaiter.complete(Unit)
        waiter.join()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun distinctPathsCanRunTogetherEvenWhenTheirHashesCollide() = runBlocking {
        val gate = MediaLocalPathGate()
        val releaseOwners = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        assertThat("media/Aa".hashCode()).isEqualTo("media/BB".hashCode())
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/Aa") { releaseOwners.await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/BB") {
                secondEntered.complete(Unit)
                releaseOwners.await()
            }
        }

        assertThat(secondEntered.isCompleted).isTrue()
        assertThat(gate.retainedPathCount).isEqualTo(2)
        releaseOwners.complete(Unit)
        joinAll(first, second)
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun distinctNestedPathsWithCollidingHashesKeepSeparateLocks() = runBlocking {
        val gate = MediaLocalPathGate()

        gate.withLock("media/Aa") {
            gate.withLock("media/BB") {
                assertThat(gate.retainedPathCount).isEqualTo(2)
            }
            assertThat(gate.retainedPathCount).isEqualTo(1)
        }

        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun multiplePathsAreNormalizedDeduplicatedAndAcquiredInSortedOrder() = runBlocking {
        val gate = MediaLocalPathGate()
        val releaseB = CompletableDeferred<Unit>()
        val reversedEntered = CompletableDeferred<Unit>()
        val sortedEntered = CompletableDeferred<Unit>()
        val aEntered = CompletableDeferred<Unit>()
        val ownerB = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/b") { releaseB.await() }
        }
        val reversed = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLocks(listOf(" media/b ", "", "media/a", " media/a ")) {
                reversedEntered.complete(Unit)
            }
        }
        val onlyA = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/a") { aEntered.complete(Unit) }
        }
        // The reversed input must already hold a while waiting for b.
        assertThat(aEntered.isCompleted).isFalse()
        assertThat(reversedEntered.isCompleted).isFalse()
        val sorted = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLocks(listOf("media/a", "media/b")) { sortedEntered.complete(Unit) }
        }
        assertThat(sortedEntered.isCompleted).isFalse()
        assertThat(gate.retainedPathCount).isEqualTo(2)

        releaseB.complete(Unit)
        joinAll(ownerB, reversed, sorted, onlyA)
        assertThat(reversedEntered.isCompleted).isTrue()
        assertThat(sortedEntered.isCompleted).isTrue()
        assertThat(aEntered.isCompleted).isTrue()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun cancellingMultiPathWaiterReleasesOnlyItsAcquiredAndQueuedReferences() = runBlocking {
        val gate = MediaLocalPathGate()
        val releaseB = CompletableDeferred<Unit>()
        val cancelledEntered = CompletableDeferred<Unit>()
        val nextBEntered = CompletableDeferred<Unit>()
        val ownerB = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/b") { releaseB.await() }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLocks(listOf("media/b", "media/a")) { cancelledEntered.complete(Unit) }
        }
        assertThat(gate.retainedPathCount).isEqualTo(2)

        cancelled.cancelAndJoin()
        assertThat(cancelledEntered.isCompleted).isFalse()
        assertThat(gate.retainedPathCount).isEqualTo(1)
        gate.withLock("media/a") { assertThat(gate.retainedPathCount).isEqualTo(2) }
        val nextB = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock("media/b") { nextBEntered.complete(Unit) }
        }
        assertThat(nextBEntered.isCompleted).isFalse()
        releaseB.complete(Unit)
        joinAll(ownerB, nextB)
        assertThat(nextBEntered.isCompleted).isTrue()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test
    fun emptyMultiPathInputRunsOnceWithoutRetainingLocks() = runBlocking {
        val gate = MediaLocalPathGate()
        var invocations = 0

        for (paths in listOf(emptyList(), listOf("", " ", "\t\n"))) {
            val result = gate.withLocks(paths) {
                invocations++
                assertThat(gate.retainedPathCount).isEqualTo(0)
                "result"
            }
            assertThat(result).isEqualTo("result")
        }

        assertThat(invocations).isEqualTo(2)
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun completedUniquePathsLeaveNoRetainedEntries() = runBlocking {
        val gate = MediaLocalPathGate()
        repeat(2_000) { index ->
            val failure = IllegalStateException("failed media attempt $index")
            val result = runCatching {
                gate.withLock("media/owned-$index") {
                    assertThat(gate.retainedPathCount).isEqualTo(1)
                    if (index % 2 == 0) throw failure
                }
            }
            assertThat(result.exceptionOrNull()).isSameInstanceAs(if (index % 2 == 0) failure else null)
            assertThat(gate.retainedPathCount).isEqualTo(0)
        }

        val releaseBatch = CompletableDeferred<Unit>()
        val batch = List(128) { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withLock("media/batch-$index") { releaseBatch.await() }
            }
        }
        assertThat(gate.retainedPathCount).isEqualTo(128)
        releaseBatch.complete(Unit)
        batch.joinAll()
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }

    @Test(timeout = 10_000)
    fun concurrentArrivalsOnWorkerThreadsKeepOneOwnerPerPath() = runBlocking {
        val gate = MediaLocalPathGate()
        val start = CompletableDeferred<Unit>()
        val activeOwners = AtomicInteger()
        val completed = AtomicInteger()
        val workers = List(32) {
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                start.await()
                repeat(64) {
                    gate.withLock("media/shared") {
                        assertThat(activeOwners.incrementAndGet()).isEqualTo(1)
                        try {
                            yield()
                            completed.incrementAndGet()
                        } finally {
                            activeOwners.decrementAndGet()
                        }
                    }
                }
            }
        }

        start.complete(Unit)
        workers.awaitAll()
        assertThat(completed.get()).isEqualTo(2_048)
        assertThat(activeOwners.get()).isEqualTo(0)
        assertThat(gate.retainedPathCount).isEqualTo(0)
    }
}
