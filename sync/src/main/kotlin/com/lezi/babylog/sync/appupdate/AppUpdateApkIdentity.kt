package com.lezi.babylog.sync.appupdate
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.ClientAppVersion

/**
 * Identity parsed from a staged APK archive before [AppUpdateInstaller] commit.
 * Used so the client never hands PackageInstaller a different app or wrong version.
 */
data class StagedApkIdentity(
    val packageName: String,
    val versionCode: Int,
    /**
     * Lowercase hex SHA-256 digests of the archive signing certificates.
     * Empty only when the platform could not surface certificates (fail-closed).
     */
    val signingCertSha256: Set<String>,
    val localDataContractVersion: Int = 1,
    val minimumMigratableLocalDataContractVersion: Int = 1,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(localDataContractVersion > 0) { "localDataContractVersion must be positive" }
        require(minimumMigratableLocalDataContractVersion in 1..localDataContractVersion) {
            "minimumMigratableLocalDataContractVersion must be within the target contract range"
        }
    }
}

/**
 * Platform seam for reading APK archive identity without committing PackageInstaller.
 * Unit tests inject fakes; production uses [AndroidAppUpdateApkIdentityReader].
 */
interface AppUpdateApkIdentityReader {
    /** Parses [apkFile]; null when unreadable / not a package archive. */
    fun readArchive(apkFile: File): StagedApkIdentity?

    /** Signing-certificate digests of the currently installed host package. */
    fun installedSigningCertSha256(): Set<String>
}

/** Product copy when staged APK fails package / version / signature identity checks. */
const val APP_UPDATE_PACKAGE_INVALID_MESSAGE = "更新包无效或不匹配，已取消安装"

/** Metadata packageName does not match this process applicationId. */
const val APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE = "更新包与本应用不匹配"

/**
 * Validates a staged archive against the local app identity and server metadata.
 *
 * Rules (all required; fail-closed):
 * - archive readable
 * - archive packageName == local applicationId
 * - archive packageName == metadata packageName
 * - archive versionCode == metadata versionCode
 * - archive versionCode > local versionCode
 * - archive declares a local-data range that contains the installed contract
 * - installed and archive signing cert digests both non-empty and intersect
 *
 * @return null when ok; Chinese product message when rejected.
 */
fun verifyStagedApkIdentity(
    archive: StagedApkIdentity?,
    installedCerts: Set<String>,
    local: ClientAppVersion,
    metadata: AppUpdateMetadata,
): String? {
    if (archive == null) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (archive.packageName != local.packageName) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (archive.packageName != metadata.packageName) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (archive.versionCode != metadata.versionCode) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (archive.versionCode <= local.versionCode) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (local.localDataContractVersion !in
        archive.minimumMigratableLocalDataContractVersion..archive.localDataContractVersion
    ) {
        return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    }
    // Host package must always present signing digests; empty = unreadable → reject.
    if (installedCerts.isEmpty()) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (archive.signingCertSha256.isEmpty()) return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    if (installedCerts.intersect(archive.signingCertSha256).isEmpty()) {
        return APP_UPDATE_PACKAGE_INVALID_MESSAGE
    }
    return null
}

@Singleton
class AndroidAppUpdateApkIdentityReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppUpdateApkIdentityReader {
    override fun readArchive(apkFile: File): StagedApkIdentity? {
        if (!apkFile.isFile || apkFile.length() <= 0L) return null
        val path = apkFile.absolutePath
        val packageInfo = context.packageManager.getPackageArchiveInfoCompat(
            path,
            packageSigningInfoFlags() or PackageManager.GET_META_DATA,
        ) ?: return null
        // Some platform builds need sourceDir set for subsequent field reads.
        packageInfo.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
        }
        val packageName = packageInfo.packageName?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = packageInfo.versionCodeCompat()
        if (versionCode <= 0) return null
        val appMetadata = packageInfo.applicationInfo?.metaData ?: return null
        val localDataContractVersion = appMetadata.getInt(
            LOCAL_DATA_CONTRACT_VERSION_METADATA,
            0,
        )
        val minimumMigratableLocalDataContractVersion = appMetadata.getInt(
            MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION_METADATA,
            0,
        )
        if (localDataContractVersion <= 0 ||
            minimumMigratableLocalDataContractVersion !in 1..localDataContractVersion
        ) {
            return null
        }
        return StagedApkIdentity(
            packageName = packageName,
            versionCode = versionCode,
            signingCertSha256 = packageInfo.signingCertSha256Digests(),
            localDataContractVersion = localDataContractVersion,
            minimumMigratableLocalDataContractVersion =
                minimumMigratableLocalDataContractVersion,
        )
    }

    override fun installedSigningCertSha256(): Set<String> {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfoCompat(
                context.packageName,
                packageSigningInfoFlags(),
            )
            packageInfo.signingCertSha256Digests()
        }.getOrDefault(emptySet())
    }
}

const val LOCAL_DATA_CONTRACT_VERSION_METADATA =
    "com.lezi.babylog.LOCAL_DATA_CONTRACT_VERSION"
const val MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION_METADATA =
    "com.lezi.babylog.MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION"

/** Fail-closed default for non-DI construction; production binds the Android reader. */
internal object UnreadableAppUpdateApkIdentityReader : AppUpdateApkIdentityReader {
    override fun readArchive(apkFile: File): StagedApkIdentity? = null
    override fun installedSigningCertSha256(): Set<String> = emptySet()
}
