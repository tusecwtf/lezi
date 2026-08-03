package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
