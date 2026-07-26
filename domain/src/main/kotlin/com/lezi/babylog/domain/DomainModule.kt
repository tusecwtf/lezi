package com.lezi.babylog.domain

import com.lezi.babylog.sync.CarePlanFamilyAppliedListener
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
}
