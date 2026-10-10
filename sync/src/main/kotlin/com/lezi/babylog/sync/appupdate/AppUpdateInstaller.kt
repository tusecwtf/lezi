package com.lezi.babylog.sync.appupdate
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.lezi.babylog.core.common.PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.OutputStream
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

    /** Releases only identifiable, unsubmitted update sessions left by a previous process. */
    fun recoverInterruptedSessions() {
        throw UnsupportedOperationException("Installer recovery is not implemented")
    }

    /** Opens the system screen to grant this app install-unknown-apps permission. */
    fun createManageUnknownSourcesIntent(): Intent
}

// Reserved to this component. Keep stable across versions: this is OS-owned recovery identity
// as well as a natural label the system may show. Never reuse it for another install purpose.
internal const val APP_UPDATE_SESSION_LABEL = "乐记应用更新"
private val appUpdateSessionLock = Any()

internal const val APP_UPDATE_STAGING_DIR = "app-update"
internal const val APP_UPDATE_STAGING_APK_NAME = "pending-update.apk"
internal const val APP_UPDATE_INSTALL_ACTION =
    "com.lezi.babylog.sync.APP_UPDATE_INSTALL_STATUS"

internal fun appUpdateInstallCompletionBroadcastAction(status: Int): String? =
    PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION.takeIf {
        status == PackageInstaller.STATUS_SUCCESS
    }

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

internal data class InstallSessionInfo(
    val id: Int,
    val installerPackageName: String?,
    val targetPackageName: String?,
    val label: String?,
    val sealed: Boolean,
)

/** Narrow SDK boundary; session ownership stays with [AndroidAppUpdateInstaller]. */
internal interface PackageInstallerPlatform {
    val installerPackageName: String
    fun mySessions(): List<InstallSessionInfo>
    fun canRequestPackageInstalls(): Boolean
    fun createManageUnknownSourcesIntent(): Intent
    fun createSession(expectedPackageName: String): Int
    fun openSession(sessionId: Int): Session
    fun abandonSession(sessionId: Int)

    interface Session : Closeable {
        fun openWrite(lengthBytes: Long): OutputStream
        fun fsync(output: OutputStream)
        /** Creates the status receiver and submits the session to the system. */
        fun commit()
    }
}

@Singleton
class AndroidAppUpdateInstaller internal constructor(
    private val platform: PackageInstallerPlatform,
) : AppUpdateInstaller {
    @Inject
    constructor(@ApplicationContext context: Context) : this(AndroidPackageInstallerPlatform(context))

    override fun canRequestPackageInstalls(): Boolean = platform.canRequestPackageInstalls()

    override fun createManageUnknownSourcesIntent(): Intent = platform.createManageUnknownSourcesIntent()

    override fun recoverInterruptedSessions() = synchronized(appUpdateSessionLock) {
        // API26 isSealed is the documented commit boundary, including pending user action.
        // Unmarked legacy sessions cannot be attributed to this purpose and remain untouched.
        platform.mySessions().filter { session ->
            session.installerPackageName == platform.installerPackageName &&
                session.targetPackageName == platform.installerPackageName &&
                session.label == APP_UPDATE_SESSION_LABEL && !session.sealed
        }.forEach { platform.abandonSession(it.id) }
    }

    override fun installFromFile(apkFile: File, expectedPackageName: String) =
        synchronized(appUpdateSessionLock) {
            require(expectedPackageName == platform.installerPackageName) { "更新包目标不匹配" }
            recoverInterruptedSessions()
            require(apkFile.isFile && apkFile.length() > 0L) { "更新包无效" }
            val sessionId = platform.createSession(expectedPackageName)
            var submitted = false
            try {
                platform.openSession(sessionId).use { session ->
                    apkFile.inputStream().use { input ->
                        session.openWrite(apkFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    session.commit()
                    submitted = true
                }
            } catch (failure: Throwable) {
                if (!submitted) {
                    runCatching {
                        // A Binder reply can fail after the OS sealed commit. Preserve that
                        // hand-off; an unreadable OS state is also not permission to abandon it.
                        if (platform.mySessions().firstOrNull { it.id == sessionId }?.sealed != true) {
                            platform.abandonSession(sessionId)
                        }
                    }.exceptionOrNull()?.takeUnless { it === failure }?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
}

internal class AndroidPackageInstallerPlatform(
    private val context: Context,
) : PackageInstallerPlatform {
    override val installerPackageName: String get() = context.packageName

    override fun mySessions(): List<InstallSessionInfo> =
        context.packageManager.packageInstaller.mySessions.map { session ->
            InstallSessionInfo(
                session.sessionId, session.installerPackageName, session.appPackageName,
                session.appLabel?.toString(), session.isSealed,
            )
        }

    override fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun createManageUnknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun createSession(expectedPackageName: String): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(expectedPackageName)
            setAppLabel(APP_UPDATE_SESSION_LABEL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        return packageInstaller.createSession(params)
    }

    override fun openSession(sessionId: Int): PackageInstallerPlatform.Session {
        val session = context.packageManager.packageInstaller.openSession(sessionId)
        return object : PackageInstallerPlatform.Session {
            override fun openWrite(lengthBytes: Long): OutputStream =
                session.openWrite("lezi-update.apk", 0, lengthBytes)

            override fun fsync(output: OutputStream) = session.fsync(output)

            override fun commit() {
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

            override fun close() = session.close()
        }
    }

    override fun abandonSession(sessionId: Int) =
        context.packageManager.packageInstaller.abandonSession(sessionId)
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
                appUpdateInstallCompletionBroadcastAction(status)?.let { action ->
                    context.sendBroadcast(Intent(action).setPackage(context.packageName))
                }
            }
        }
    }
}

/** Test / default installer so JVM unit tests need no PackageInstaller. */
internal object NoOpAppUpdateInstaller : AppUpdateInstaller {
    override fun recoverInterruptedSessions() = Unit
    override fun canRequestPackageInstalls(): Boolean = true
    override fun installFromFile(apkFile: File, expectedPackageName: String) = Unit
    override fun createManageUnknownSourcesIntent(): Intent = Intent()
}
