package com.lezi.babylog.sync

import android.content.Context
import android.content.pm.PackageManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
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
    @Binds @Singleton abstract fun appUpdateInstaller(impl: AndroidAppUpdateInstaller): AppUpdateInstaller
    @Binds @Singleton abstract fun appUpdateApkIdentityReader(
        impl: AndroidAppUpdateApkIdentityReader,
    ): AppUpdateApkIdentityReader

    companion object {
        @Provides
        @Singleton
        fun syncBackend(
            http: HttpSyncBackend,
            preferences: SyncPreferences,
            clock: PolicyClock,
        ): SyncBackend = RefreshingSyncBackend(http, preferences, clock)

        @Provides
        @Singleton
        @Named("appUpdateCacheDir")
        fun appUpdateCacheDir(@ApplicationContext context: Context): File = context.cacheDir

        @Provides
        @Singleton
        fun clientAppVersion(@ApplicationContext context: Context): ClientAppVersion {
            val packageInfo = context.packageManager.getPackageInfoCompat(
                context.packageName,
                flags = PackageManager.GET_META_DATA,
            )
            return ClientAppVersion(
                versionCode = packageInfo.versionCodeCompat().coerceAtLeast(1),
                versionName = packageInfo.versionName?.takeIf { it.isNotBlank() }
                    ?: ClientAppVersion.FALLBACK.versionName,
                packageName = context.packageName,
                localDataContractVersion = packageInfo.applicationInfo?.metaData?.getInt(
                    LOCAL_DATA_CONTRACT_VERSION_METADATA,
                    ClientAppVersion.FALLBACK.localDataContractVersion,
                ) ?: ClientAppVersion.FALLBACK.localDataContractVersion,
            )
        }
    }
}
