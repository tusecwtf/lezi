package com.lezi.babylog.sync

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    @Named("syncBaseUrl")
    fun provideSyncBaseUrl(): String = DEFAULT_SYNC_BASE_URL

    @Provides
    @Singleton
    fun provideSyncBackend(@Named("syncBaseUrl") baseUrl: String): SyncBackend =
        HttpSyncBackend(baseUrl)

    @Provides
    @Singleton
    fun provideSyncPort(impl: RealSyncPort): SyncPort = impl

    const val DEFAULT_SYNC_BASE_URL: String = "http://10.0.2.2:8765"
}
