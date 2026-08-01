package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class DefaultLocalDataGateTest {
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
