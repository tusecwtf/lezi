package com.lezi.babylog.sync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Platform seam for PackageInstaller; unit tests substitute a recording backend. */
interface AppUpdateInstaller {
    fun canRequestPackageInstalls(): Boolean

    /**
     * Writes [apkFile] into a PackageInstaller session and commits it.
     * Caller is responsible for deleting [apkFile] afterward.
     */
    fun installFromFile(apkFile: File, expectedPackageName: String)

    /** Opens the system screen to grant this app install-unknown-apps permission. */
    fun createManageUnknownSourcesIntent(): Intent
}

internal const val APP_UPDATE_STAGING_DIR = "app-update"
internal const val APP_UPDATE_STAGING_APK_NAME = "pending-update.apk"
internal const val APP_UPDATE_INSTALL_ACTION =
    "com.lezi.babylog.sync.APP_UPDATE_INSTALL_STATUS"

/** Digests [bytes] as lowercase hex sha256 (matches wire metadata). */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        digest.forEach { byte -> append("%02x".format(byte)) }
    }
}

/** Private cache dir used for APK staging; never public Download. */
internal fun appUpdateStagingDir(cacheDir: File): File =
    File(cacheDir, APP_UPDATE_STAGING_DIR)

internal fun appUpdateStagingApk(cacheDir: File): File =
    File(appUpdateStagingDir(cacheDir), APP_UPDATE_STAGING_APK_NAME)

/** Deletes staged APKs under the private app-update cache directory. */
internal fun cleanupAppUpdateStagingFiles(cacheDir: File) {
    val dir = appUpdateStagingDir(cacheDir)
    if (!dir.exists()) return
    dir.listFiles()?.forEach { file ->
        runCatching { if (file.isFile) file.delete() }
    }
    runCatching {
        val remaining = dir.listFiles()
        if (remaining == null || remaining.isEmpty()) {
            dir.delete()
        }
    }
}

@Singleton
class AndroidAppUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppUpdateInstaller {
    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun createManageUnknownSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun installFromFile(apkFile: File, expectedPackageName: String) {
        require(apkFile.isFile && apkFile.length() > 0L) { "更新包无效" }
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(expectedPackageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("lezi-update.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent = Intent(APP_UPDATE_INSTALL_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/**
 * Receives PackageInstaller session status. On pending user action, launches the
 * system confirm UI. Staging cleanup is owned by the download/install path.
 */
class AppUpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != APP_UPDATE_INSTALL_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            else -> {
                // Success and failure: no private staging left to clear here; the
                // install path deletes the cache APK after session commit.
            }
        }
    }
}
