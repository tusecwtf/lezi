package com.lezi.babylog.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds @Singleton abstract fun syncPreferences(impl: DataStoreSyncPreferences): SyncPreferences
    @Binds @Singleton abstract fun networkState(impl: AndroidNetworkState): NetworkState
    @Binds @Singleton abstract fun healthProbe(impl: HttpHealthProbe): HealthProbe
    @Binds @Singleton abstract fun policyClock(impl: SystemPolicyClock): PolicyClock
    @Binds @Singleton abstract fun foregroundState(impl: ProcessForegroundState): ForegroundState
    @Binds @Singleton abstract fun syncBackend(impl: HttpSyncBackend): SyncBackend
    @Binds @Singleton abstract fun syncPort(impl: RealSyncPort): SyncPort
    @Binds @Singleton abstract fun mediaFileStore(impl: AndroidSyncMediaFileStore): SyncMediaFileStore
}
