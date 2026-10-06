package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
        // The timeout watchdog completes `published` before publishing state, so the
        // flow can lag retry()/ensureReady()'s return by a scheduling quantum:
        // await the value instead of racing it.
        withTimeout(5_000) {
            gate.state.first { it is LocalDataUpgradeState.Blocked }
        }

        val second = gate.retry()
        assertThat(inspectStarts.get()).isEqualTo(2)
        assertThat(second).isInstanceOf(LocalDataUpgradeState.Blocked::class.java)
        val settledState = withTimeoutOrNull(5_000) {
            gate.state.first {
                it == LocalDataUpgradeState.Blocked(
                    LocalDataUpgradeBlockReason.TimedOut,
                    "本地数据安全检查超时",
                )
            }
        }
        assertThat(settledState).isNotNull()
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
