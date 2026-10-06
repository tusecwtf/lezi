package com.lezi.babylog

import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataUpgradeEnvironment
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalDataUpgradeModule {
    @Binds
    abstract fun bindEnvironment(
        implementation: AndroidLocalDataUpgradeEnvironment,
    ): LocalDataUpgradeEnvironment

    @Multibinds
    abstract fun upgradeSteps(): Set<LocalDataUpgradeStep>

    @Binds
    @IntoSet
    abstract fun customItemClientUuidIndexUpgradeStep(
        implementation: CustomItemClientUuidIndexUpgradeStep,
    ): LocalDataUpgradeStep

    @Binds
    @IntoSet
    abstract fun outboxRetirementUpgradeStep(
        implementation: OutboxRetirementUpgradeStep,
    ): LocalDataUpgradeStep

    @Binds
    @IntoSet
    abstract fun causalRoomUpgradeStep(
        implementation: CausalRoomUpgradeStep,
    ): LocalDataUpgradeStep

    @Binds
    @IntoSet
    abstract fun finalCausalRoomUpgradeStep(
        implementation: FinalCausalRoomUpgradeStep,
    ): LocalDataUpgradeStep

    @Binds
    @IntoSet
    abstract fun mediaSha256ColumnUpgradeStep(
        implementation: MediaSha256ColumnUpgradeStep,
    ): LocalDataUpgradeStep

    companion object {
        @Provides
        @Singleton
        fun localDataGate(
            environment: LocalDataUpgradeEnvironment,
            steps: Set<@JvmSuppressWildcards LocalDataUpgradeStep>,
        ): DefaultLocalDataGate = DefaultLocalDataGate(
            currentContractVersion = BuildConfig.LOCAL_DATA_CONTRACT_VERSION,
            minimumMigratableContractVersion =
                BuildConfig.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION,
            steps = steps,
            environment = environment,
        )
    }
}
