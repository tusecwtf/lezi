package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalDataUpgradePlannerTest {
    @Test
    fun adjacentRegisteredStepsProduceAnUpgradePath() {
        val first = FakeStep(fromContractVersion = 1, toContractVersion = 2)
        val second = FakeStep(fromContractVersion = 2, toContractVersion = 3)

        val result = LocalDataUpgradePlanner(
            currentContractVersion = 3,
            minimumMigratableContractVersion = 1,
            steps = setOf(second, first),
        ).planFrom(1)

        assertThat(result).isEqualTo(
            LocalDataUpgradePlan.Upgrade(listOf(first, second)),
        )
    }

    @Test
    fun sourceBeforePermanentCompatibilityBaselineIsBlocked() {
        val result = LocalDataUpgradePlanner(
            currentContractVersion = 3,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
        ).planFrom(0)

        assertThat(result).isEqualTo(
            LocalDataUpgradePlan.Blocked(LocalDataUpgradeBlockReason.UnsupportedLegacy),
        )
    }
}

private data class FakeStep(
    override val fromContractVersion: Int,
    override val toContractVersion: Int,
) : LocalDataUpgradeStep {
    override val affectedDomains = setOf(LocalDataDomain.Room)

    override suspend fun migrate() = Unit

    override suspend fun verify() = Unit
}
