package com.lezi.babylog.sync

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds @Singleton abstract fun syncPreferences(impl: DataStoreSyncPreferences): SyncPreferences
    @Binds @Singleton abstract fun secureRefreshTokenStore(
        impl: EncryptedSecureRefreshTokenStore,
    ): SecureRefreshTokenStore
    @Binds @Singleton abstract fun setupProbe(impl: HttpSetupProbe): SetupProbe
    @Binds @Singleton abstract fun policyClock(impl: SystemPolicyClock): PolicyClock
    @Binds @Singleton abstract fun foregroundState(impl: ProcessForegroundState): ForegroundState
    @Binds @Singleton abstract fun syncPort(impl: RealSyncPort): SyncPort
    @Binds @Singleton abstract fun mediaFileStore(impl: AndroidSyncMediaFileStore): SyncMediaFileStore

    companion object {
        @Provides
        @Singleton
        fun syncBackend(
            http: HttpSyncBackend,
            preferences: SyncPreferences,
            clock: PolicyClock,
        ): SyncBackend = RefreshingSyncBackend(http, preferences, clock)
    }
}
