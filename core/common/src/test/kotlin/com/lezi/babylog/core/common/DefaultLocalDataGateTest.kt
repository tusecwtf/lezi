package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultLocalDataGateTest {
    @Test
    fun concurrentEnsureReadyRunsTheExpensiveInspectionOnlyOnce() = runTest {
        val inspectCalls = AtomicInteger()
        val inspectionStarted = CompletableDeferred<Unit>()
        val releaseInspection = CompletableDeferred<Unit>()
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    inspectCalls.incrementAndGet()
                    inspectionStarted.complete(Unit)
                    releaseInspection.await()
                    return LocalDataInspection(1)
                }

                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "concurrent"
            },
        )

        val first = async { gate.ensureReady() }
        inspectionStarted.await()
        val second = async { gate.ensureReady() }
        yield()
        releaseInspection.complete(Unit)

        assertThat(first.await()).isTrue()
        assertThat(second.await()).isTrue()
        assertThat(inspectCalls.get()).isEqualTo(1)
    }

    @Test
    fun retryRunsPersistenceInspectionOffTheCallingThread() = runTest {
        val callingThread = Thread.currentThread()
        var inspectionThread: Thread? = null
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    inspectionThread = Thread.currentThread()
                    return LocalDataInspection(1)
                }

                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "dispatcher"
            },
        )

        assertThat(gate.retry()).isEqualTo(LocalDataUpgradeState.Ready(1))
        assertThat(inspectionThread).isNotSameInstanceAs(callingThread)
    }

    @Test
    fun migrationSnapshotsBeforeMutationAndPublishesReadyOnlyAfterVerification() = runTest {
        val events = mutableListOf<String>()
        val step = object : LocalDataUpgradeStep {
            override val fromContractVersion = 1
            override val toContractVersion = 2
            override val affectedDomains = setOf(LocalDataDomain.Room, LocalDataDomain.Settings)

            override suspend fun migrate() {
                events += "migrate"
            }

            override suspend fun verify() {
                events += "verify-step"
            }
        }
        val environment = RecordingEnvironment(events, sourceContractVersion = 1)
        val gate = DefaultLocalDataGate(
            currentContractVersion = 2,
            minimumMigratableContractVersion = 1,
            steps = setOf(step),
            environment = environment,
        )

        assertThat(gate.ensureReady()).isTrue()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(2))
        assertThat(events).containsExactly(
            "inspect",
            "snapshot-1-2-Room,Settings",
            "migrate",
            "verify-step",
            "commit-2",
            "verify-current",
            "cleanup",
        ).inOrder()
    }

    @Test
    fun readyStateIsVisibleBeforeAnUndispatchedWaiterResumes() = runBlocking {
        val verificationStarted = CompletableDeferred<Unit>()
        val releaseVerification = CompletableDeferred<Unit>()
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect() = LocalDataInspection(1)
                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() {
                    verificationStarted.complete(Unit)
                    releaseVerification.await()
                }
                override fun diagnosticContext(): String = "ready-publication"
            },
        )
        // Unconfined resumes inline in deferred.complete(), exposing any gap
        // between waking a caller and publishing the same StateFlow outcome.
        val observation = async(Dispatchers.Unconfined) {
            val result = gate.retry()
            result to gate.state.value
        }
        verificationStarted.await()
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Checking)
        releaseVerification.complete(Unit)

        val (returned, observed) = observation.await()
        assertThat(returned).isEqualTo(LocalDataUpgradeState.Ready(1))
        assertThat(observed).isEqualTo(returned)
    }

    @Test
    fun timeoutStateIsVisibleBeforeAnUndispatchedWaiterResumes() = runBlocking {
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = HangingEnvironment(hangOn = HangPoint.Inspect),
            maxElapsedMillis = 150L,
        )
        val observation = async(Dispatchers.Unconfined) {
            val result = gate.retry()
            result to gate.state.value
        }

        val (returned, observed) = observation.await()
        assertThat(returned).isEqualTo(
            LocalDataUpgradeState.Blocked(
                LocalDataUpgradeBlockReason.TimedOut,
                "本地数据安全检查超时",
            ),
        )
        assertThat(observed).isEqualTo(returned)
    }

    @Test
    fun cancellationIsNeverConvertedIntoARecoveryBlock() {
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection = throw CancellationException()
                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "cancelled"
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { gate.ensureReady() }
        }
    }

    @Test
    fun migrationFailureKeepsSnapshotAndNeverPublishesReady() = runTest {
        val events = mutableListOf<String>()
        val step = object : LocalDataUpgradeStep {
            override val fromContractVersion = 1
            override val toContractVersion = 2
            override val affectedDomains = setOf(LocalDataDomain.Room)
            override suspend fun migrate() {
                events += "migrate"
                error("fixture migration failed")
            }
            override suspend fun verify() = Unit
        }
        val gate = DefaultLocalDataGate(
            currentContractVersion = 2,
            minimumMigratableContractVersion = 1,
            steps = setOf(step),
            environment = RecordingEnvironment(events, sourceContractVersion = 1),
        )

        assertThat(gate.ensureReady()).isFalse()
        assertThat(gate.state.value).isEqualTo(
            LocalDataUpgradeState.Blocked(
                LocalDataUpgradeBlockReason.MigrationFailed,
                "fixture migration failed",
            ),
        )
        assertThat(events).containsExactly(
            "inspect",
            "snapshot-1-2-Room",
            "migrate",
        ).inOrder()
    }

    @Test
    fun diagnosticReportNeverThrowsFromARecoveryScreen() {
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect() = LocalDataInspection(1)
                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = error("corrupt journal")
            },
        )

        assertThat(gate.diagnosticReport()).contains("diagnostic=unavailable")
    }

    @Test
    fun cancelledFirstWaiterDoesNotDisarmAttemptDeadlineForJoiner() = runBlocking {
        val inspectStarted = CompletableDeferred<Unit>()
        val releaseInspect = CompletableDeferred<Unit>()
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    inspectStarted.complete(Unit)
                    withContext(NonCancellable) { releaseInspect.await() }
                    return LocalDataInspection(1)
                }
                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "cancelled-waiter"
            },
            maxElapsedMillis = 150L,
        )
        val previousThreads = Thread.getAllStackTraces().keys
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.retry() }
            inspectStarted.await()
            val deadlineThreads = Thread.getAllStackTraces().keys.filter {
                it.name == "lezi-local-data-gate" && it !in previousThreads
            }
            assertThat(deadlineThreads).hasSize(1)
            first.cancelAndJoin()

            // This bound detects a lost product watchdog; it does not drive it.
            val joined = withTimeoutOrNull(5_000L) { gate.retry() }
            assertThat(joined).isEqualTo(
                LocalDataUpgradeState.Blocked(
                    LocalDataUpgradeBlockReason.TimedOut,
                    "本地数据安全检查超时",
                ),
            )
            assertThat(gate.state.value).isEqualTo(joined)
            assertThat(releaseInspect.isCompleted).isFalse()
            deadlineThreads.single().join(5_000L)
            assertThat(deadlineThreads.single().isAlive).isFalse()
        } finally {
            releaseInspect.complete(Unit)
        }
    }

    @Test
    fun cancellingWaitersKeepsCommittedMigrationAndSuccessClosesDeadline() = runBlocking {
        val events = mutableListOf<String>()
        val migrationCommitted = CompletableDeferred<Unit>()
        val releaseVerification = CompletableDeferred<Unit>()
        val step = object : LocalDataUpgradeStep {
            override val fromContractVersion = 1
            override val toContractVersion = 2
            override val affectedDomains = setOf(LocalDataDomain.Room)
            override suspend fun migrate() {
                events += "migrate-committed"
                migrationCommitted.complete(Unit)
            }
            override suspend fun verify() {
                releaseVerification.await()
                events += "verify-step"
            }
        }
        val gate = DefaultLocalDataGate(
            currentContractVersion = 2,
            minimumMigratableContractVersion = 1,
            steps = setOf(step),
            environment = RecordingEnvironment(events, sourceContractVersion = 1),
        )
        val previousThreads = Thread.getAllStackTraces().keys
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.retry() }
            migrationCommitted.await()
            val deadlineThreads = Thread.getAllStackTraces().keys.filter {
                it.name == "lezi-local-data-gate" && it !in previousThreads
            }
            assertThat(deadlineThreads).hasSize(1)
            first.cancelAndJoin()
            val cancelledJoiner = async(start = CoroutineStart.UNDISPATCHED) { gate.retry() }
            cancelledJoiner.cancelAndJoin()
            val remainingJoiner = async(start = CoroutineStart.UNDISPATCHED) { gate.retry() }
            assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Migrating(1, 2))
            releaseVerification.complete(Unit)

            val result = withTimeout(5_000L) { remainingJoiner.await() }
            assertThat(result).isEqualTo(LocalDataUpgradeState.Ready(2))
            assertThat(gate.state.value).isEqualTo(result)
            assertThat(events).containsExactly(
                "inspect", "snapshot-1-2-Room", "migrate-committed", "verify-step",
                "commit-2", "verify-current", "cleanup",
            ).inOrder()
            // The successful attempt has a 45s product deadline. Joining its
            // timer thread verifies cleanup without sleeping until expiry.
            deadlineThreads.single().join(5_000L)
            assertThat(deadlineThreads.single().isAlive).isFalse()
        } finally {
            releaseVerification.complete(Unit)
        }
    }

    @Test
    fun timedOutInspectionCannotPublishLateProgressOverNewReadyGeneration() = runBlocking {
        val inspectCalls = AtomicInteger()
        val staleMutations = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val firstWorkerFinished = CompletableDeferred<Unit>()
        val releaseFirstInspection = CountDownLatch(1)
        val step = object : LocalDataUpgradeStep {
            override val fromContractVersion = 1
            override val toContractVersion = 2
            override val affectedDomains = setOf(LocalDataDomain.Room)
            override suspend fun migrate() { staleMutations.incrementAndGet() }
            override suspend fun verify() = Unit
        }
        val gate = DefaultLocalDataGate(
            currentContractVersion = 2,
            minimumMigratableContractVersion = 1,
            steps = setOf(step),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    if (inspectCalls.incrementAndGet() == 1) {
                        currentCoroutineContext().job.invokeOnCompletion { firstWorkerFinished.complete(Unit) }
                        firstStarted.complete(Unit)
                        // Model a blocking storage call that ignores coroutine cancellation.
                        check(releaseFirstInspection.await(5, TimeUnit.SECONDS)) { "inspection not released" }
                        return LocalDataInspection(1)
                    }
                    return LocalDataInspection(2)
                }
                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) { staleMutations.incrementAndGet() }
                override suspend fun commitContract(contractVersion: Int) { staleMutations.incrementAndGet() }
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "late-inspection"
            },
            maxElapsedMillis = 150L,
        )
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.retry() }
            firstStarted.await()
            assertThat(withTimeout(5_000L) { first.await() }).isEqualTo(
                LocalDataUpgradeState.Blocked(LocalDataUpgradeBlockReason.TimedOut, "本地数据安全检查超时"),
            )
            assertThat(gate.retry()).isEqualTo(LocalDataUpgradeState.Ready(2))
            releaseFirstInspection.countDown()
            withTimeout(5_000L) { firstWorkerFinished.await() }

            assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(2))
            assertThat(inspectCalls.get()).isEqualTo(2)
            assertThat(staleMutations.get()).isEqualTo(0)
        } finally {
            releaseFirstInspection.countDown()
        }
    }

    @Test
    fun uncancelableInspectDoesNotBlockTheNextRetryGeneration() = runBlocking {
        val inspectStarts = AtomicInteger()
        val firstInspectStarted = CompletableDeferred<Unit>()
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    val started = inspectStarts.incrementAndGet()
                    if (started == 1) firstInspectStarted.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        awaitCancellation()
                    }
                }

                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "uncancelable"
            },
            maxElapsedMillis = 150L,
        )

        assertThat(gate.ensureReady()).isFalse()
        firstInspectStarted.await()
        assertThat(gate.state.value).isEqualTo(
            LocalDataUpgradeState.Blocked(
                LocalDataUpgradeBlockReason.TimedOut,
                "本地数据安全检查超时",
            ),
        )

        val second = gate.retry()
        assertThat(inspectStarts.get()).isEqualTo(2)
        assertThat(second).isInstanceOf(LocalDataUpgradeState.Blocked::class.java)
        assertThat(gate.state.value).isEqualTo(second)
    }

    @Test
    fun inspectFinishingAtTheDeadlineKeepsReadyInsteadOfOverwritingWithTimeout() = runBlocking {
        val inspectStarted = CompletableDeferred<Unit>()
        val releaseInspect = CompletableDeferred<Unit>()
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = object : LocalDataUpgradeEnvironment {
                override suspend fun inspect(): LocalDataInspection {
                    inspectStarted.complete(Unit)
                    releaseInspect.await()
                    return LocalDataInspection(1)
                }

                override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
                override suspend fun commitContract(contractVersion: Int) = Unit
                override suspend fun cleanupSnapshots() = Unit
                override suspend fun verifyCurrent() = Unit
                override fun diagnosticContext(): String = "deadline-race"
            },
            maxElapsedMillis = 80L,
        )

        val attempt = async { gate.retry() }
        inspectStarted.await()
        Thread.sleep(20)
        releaseInspect.complete(Unit)
        val result = attempt.await()

        assertThat(result).isEqualTo(gate.state.value)
        assertThat(result).isEqualTo(LocalDataUpgradeState.Ready(1))
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Ready(1))
    }

    @Test
    fun inspectThatNeverReturnsEntersBlockedWithRetryableRecovery() = runBlocking {
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = HangingEnvironment(hangOn = HangPoint.Inspect),
            maxElapsedMillis = 150L,
        )

        assertThat(gate.ensureReady()).isFalse()
        val blocked = gate.state.value as LocalDataUpgradeState.Blocked
        assertThat(blocked.reason).isEqualTo(LocalDataUpgradeBlockReason.TimedOut)
        assertThat(gate.diagnosticReport()).contains("state=")
        assertThat(gate.retry()).isInstanceOf(LocalDataUpgradeState.Blocked::class.java)
    }

    @Test
    fun keystoreThatNeverReturnsEntersBlocked() = runBlocking {
        val environment = HangingEnvironment(hangOn = HangPoint.VerifyCurrent)
        val gate = DefaultLocalDataGate(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = environment,
            maxElapsedMillis = 150L,
        )

        assertThat(gate.ensureReady()).isFalse()
        assertThat(gate.state.value).isEqualTo(
            LocalDataUpgradeState.Blocked(
                LocalDataUpgradeBlockReason.TimedOut,
                "本地数据安全检查超时",
            ),
        )
    }
}

private class RecordingEnvironment(
    private val events: MutableList<String>,
    private val sourceContractVersion: Int,
) : LocalDataUpgradeEnvironment {
    override suspend fun inspect(): LocalDataInspection {
        events += "inspect"
        return LocalDataInspection(sourceContractVersion)
    }

    override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) {
        events += "snapshot-${step.fromContractVersion}-${step.toContractVersion}-" +
            step.affectedDomains.joinToString(",")
    }

    override suspend fun commitContract(contractVersion: Int) {
        events += "commit-$contractVersion"
    }

    override suspend fun cleanupSnapshots() {
        events += "cleanup"
    }

    override suspend fun verifyCurrent() {
        events += "verify-current"
    }

    override fun diagnosticContext(): String = "recording"
}

private enum class HangPoint { Inspect, VerifyCurrent }

private class HangingEnvironment(
    private val hangOn: HangPoint,
) : LocalDataUpgradeEnvironment {
    override suspend fun inspect(): LocalDataInspection {
        if (hangOn == HangPoint.Inspect) awaitCancellation()
        return LocalDataInspection(1)
    }

    override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
    override suspend fun commitContract(contractVersion: Int) = Unit
    override suspend fun cleanupSnapshots() = Unit

    override suspend fun verifyCurrent() {
        if (hangOn == HangPoint.VerifyCurrent) awaitCancellation()
    }

    override fun diagnosticContext(): String = "hanging"
}
