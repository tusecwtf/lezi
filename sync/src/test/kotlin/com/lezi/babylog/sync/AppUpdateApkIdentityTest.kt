package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure identity gate for staged APKs (public seam; no PackageManager).
 * Expected literals from product package id and ticket 05 acceptance.
 */
class AppUpdateApkIdentityTest {
    private val local = ClientAppVersion(
        versionCode = 6,
        versionName = "0.3.0",
        packageName = "com.lezi.babylog",
    )
    private val metadata = AppUpdateMetadata(
        packageName = "com.lezi.babylog",
        versionCode = 7,
        versionName = "0.3.1",
        minSupportedVersionCode = 1,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    private val certA = "aa".repeat(32)
    private val certB = "bb".repeat(32)

    @Test
    fun acceptsMatchingPackageVersionAndSigner() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = setOf(certA),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isNull()
    }

    @Test
    fun rejectsTargetApkThatCannotMigrateInstalledLocalDataContract() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = setOf(certA),
            localDataContractVersion = 2,
            minimumMigratableLocalDataContractVersion = 2,
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsNullArchive() {
        assertThat(
            verifyStagedApkIdentity(
                archive = null,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsPackageNameNotEqualLocalApplicationId() {
        val archive = StagedApkIdentity(
            packageName = "com.evil.other",
            versionCode = 7,
            signingCertSha256 = setOf(certA),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsPackageNameNotEqualMetadata() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = setOf(certA),
        )
        val foreignMetadata = metadata.copy(packageName = "com.other.app")

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = foreignMetadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsVersionCodeNotEqualMetadata() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 9,
            signingCertSha256 = setOf(certA),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsVersionCodeNotHigherThanLocal() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 6,
            signingCertSha256 = setOf(certA),
        )
        val sameVersionMetadata = metadata.copy(versionCode = 6, versionName = "0.3.0")

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = sameVersionMetadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsSigningCertificateMismatch() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = setOf(certB),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsMissingArchiveCerts() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = emptySet(),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = setOf(certA),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }

    @Test
    fun rejectsEmptyInstalledCertsFailClosed() {
        val archive = StagedApkIdentity(
            packageName = local.packageName,
            versionCode = 7,
            signingCertSha256 = setOf(certA),
        )

        assertThat(
            verifyStagedApkIdentity(
                archive = archive,
                installedCerts = emptySet(),
                local = local,
                metadata = metadata,
            ),
        ).isEqualTo(APP_UPDATE_PACKAGE_INVALID_MESSAGE)
    }
}
