package com.lezi.babylog.domain

import com.lezi.babylog.sync.CarePlanFamilyAppliedListener
import com.lezi.babylog.sync.FamilyBabyAuthorityAppliedListener
import com.lezi.babylog.sync.LocalClearRecoveryGate
import com.lezi.babylog.sync.RemovedDeviceLocalClearGate
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

    @Provides
    @Singleton
    fun familyBabyAuthorityAppliedListener(
        careLog: dagger.Lazy<CareLog>,
    ): FamilyBabyAuthorityAppliedListener = FamilyBabyAuthorityAppliedListener {
        careLog.get().reconcileMemberLocalBabiesAfterFamilyApply()
    }

    /** Breaks RealSyncPort → coordinator → SyncPort construction while gating every remote op. */
    @Provides
    @Singleton
    fun localClearRecoveryGate(
        coordinator: dagger.Lazy<LocalDataClearCoordinator>,
    ): LocalClearRecoveryGate = LocalClearRecoveryGate {
        coordinator.get().recoverPendingReminderCleanup()
    }

    @Provides
    @Singleton
    fun removedDeviceLocalClearGate(
        coordinator: dagger.Lazy<LocalDataClearCoordinator>,
    ): RemovedDeviceLocalClearGate = RemovedDeviceLocalClearGate {
        coordinator.get().clear(LocalDataClearScope.AllLocalData)
    }
}
