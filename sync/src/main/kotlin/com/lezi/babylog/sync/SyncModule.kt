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
import com.lezi.babylog.sync.appupdate.AndroidAppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AndroidAppUpdateInstaller
import com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityReader
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.appupdate.LOCAL_DATA_CONTRACT_VERSION_METADATA
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.RefreshingSyncBackend
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.retry.withForegroundRetryPolicy
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import com.lezi.babylog.sync.media.CausalMediaPolicy
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.DataStoreSyncPreferences
import com.lezi.babylog.sync.session.EncryptedSecureRefreshTokenStore
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.session.HttpSetupProbe
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.ProcessForegroundState
import com.lezi.babylog.sync.session.SecureRefreshTokenStore
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SystemPolicyClock
import com.lezi.babylog.sync.appupdate.getPackageInfoCompat
import com.lezi.babylog.sync.appupdate.versionCodeCompat

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
            .withForegroundRetryPolicy()

        @Provides
        @Singleton
        @Named("appUpdateCacheDir")
        fun appUpdateCacheDir(@ApplicationContext context: Context): File = context.cacheDir

        @Provides
        @Singleton
        @Named("causalMediaSpoolDir")
        fun causalMediaSpoolDir(@ApplicationContext context: Context): File =
            File(context.filesDir, "causal-media-spool")

        @Provides
        @Singleton
        fun immutableMediaSpool(
            mediaFiles: SyncMediaFileStore,
            @Named("causalMediaSpoolDir") root: File,
            @Named("causalMediaSpoolCapacityBytes") capacityBytes: Long,
            @Named("causalMediaSpoolSlotReservationBytes") slotReservationBytes: Long,
        ): ImmutableMediaSpool = FileImmutableMediaSpool(
            mediaFiles = mediaFiles,
            root = root,
            capacityBytes = capacityBytes,
            slotReservationBytes = slotReservationBytes,
        )

        @Provides
        @Named("restoreSnapshotsDir")
        fun restoreSnapshotsDir(@ApplicationContext context: Context): File =
            // Android may expose filesDir through /data/user/0. Resolve only that trusted
            // platform base; the store still rejects symlinks in every owned descendant.
            File(context.filesDir.canonicalFile, "restore-snapshots")

        @Provides
        @Named("causalMediaSpoolCapacityBytes")
        fun causalMediaSpoolCapacityBytes(): Long = 512L * 1024L * 1024L

        @Provides
        @Named("causalMediaSpoolSlotReservationBytes")
        fun causalMediaSpoolSlotReservationBytes(): Long = CausalMediaPolicy.maxSpoolSlotBytes

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
