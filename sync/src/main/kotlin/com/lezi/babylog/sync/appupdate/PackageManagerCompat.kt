package com.lezi.babylog.sync.appupdate
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Shared PackageManager flag / versionCode / signing helpers for
 * [SyncModule.clientAppVersion] and [AndroidAppUpdateApkIdentityReader].
 */

internal fun PackageManager.getPackageInfoCompat(
    packageName: String,
    flags: Int,
): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, flags)
    }
}

internal fun PackageManager.getPackageArchiveInfoCompat(
    path: String,
    flags: Int,
): PackageInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(path, flags)
    }
}

internal fun PackageInfo.versionCodeCompat(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        versionCode
    }
}

/** Lowercase hex SHA-256 digests of signing certificates (empty if platform omits them). */
internal fun PackageInfo.signingCertSha256Digests(): Set<String> {
    val certBytes: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = signingInfo ?: return emptySet()
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        signers?.map { it.toByteArray() }.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        signatures?.map { it.toByteArray() }.orEmpty()
    }
    return certBytes
        .filter { it.isNotEmpty() }
        .map { sha256Hex(it) }
        .toSet()
}

/** Flags for reading package signing certificates from installed or archive PackageInfo. */
internal fun packageSigningInfoFlags(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
}
