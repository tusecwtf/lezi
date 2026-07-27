package com.lezi.babylog.domain

import com.lezi.babylog.sync.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.LocalClearRecoveryGate
import com.lezi.babylog.sync.LocalClearRecoveryScope
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    @Binds
    @Singleton
    abstract fun bindExportPort(impl: TxtExportPort): ExportPort

    @Binds
    @Singleton
    internal abstract fun bindLocalDataClearCoordinator(
        impl: DefaultLocalDataClearCoordinator,
    ): LocalDataClearCoordinator

    @Binds
    @Singleton
    internal abstract fun bindLocalDataClearPersistence(
        impl: DaoLocalDataClearPersistence,
    ): LocalDataClearPersistence

    @Binds
    @Singleton
    internal abstract fun bindLocalDataClearSettings(
        impl: StoreLocalDataClearSettings,
    ): LocalDataClearSettings

    @Binds
    @Singleton
    internal abstract fun bindSystemCalendarConfigurationCoordinator(
        impl: DefaultSystemCalendarConfigurationCoordinator,
    ): SystemCalendarConfigurationCoordinator

    @Binds
    @Singleton
    internal abstract fun bindJoinFamilyUseCase(
        impl: DefaultJoinFamilyUseCase,
    ): JoinFamilyUseCase
}

/**
 * Binds plan-package side effects (reminders / system calendar) without a
 * RealSyncPort → CareLog constructor cycle. [dagger.Lazy] defers CareLog init.
 */
@Module
@InstallIn(SingletonComponent::class)
object CarePlanFamilyProjectionModule {
    @Provides
    @Singleton
    fun carePlanFamilyAppliedListener(
        careLog: dagger.Lazy<CareLog>,
    ): CarePlanFamilyAppliedListener = CarePlanFamilyAppliedListener { planClientUuids ->
        careLog.get().onFamilyCarePlansApplied(planClientUuids)
    }

    /** Breaks RealSyncPort → coordinator → SyncPort construction while gating every remote op. */
    @Provides
    @Singleton
    fun localClearRecoveryGate(
        coordinator: dagger.Lazy<LocalDataClearCoordinator>,
    ): LocalClearRecoveryGate = LocalClearRecoveryGate {
        when (coordinator.get().recoverPendingReminderCleanup()) {
            LocalDataClearScope.RecordsOnly -> LocalClearRecoveryScope.RecordsOnly
            LocalDataClearScope.AllLocalData -> LocalClearRecoveryScope.AllLocal
            null -> null
        }
    }
}
