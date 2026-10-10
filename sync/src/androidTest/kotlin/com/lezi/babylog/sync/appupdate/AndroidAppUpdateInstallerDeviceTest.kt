package com.lezi.babylog.sync.appupdate

import android.content.pm.PackageInstaller
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppUpdateInstallerDeviceTest {
    @Test
    fun reconstructedInstallerRecoversMarkedRealApkStagingAndPreservesUnmarkedSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val system = context.packageManager.packageInstaller
        val oldPlatform = AndroidPackageInstallerPlatform(context)
        val sourceApk = File(context.applicationInfo.sourceDir)
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(sourceApk.path, 0)
        assertThat(requireNotNull(archive).packageName).isEqualTo(context.packageName)
        val owned = oldPlatform.createSession(context.packageName)
        val unmarked = system.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
            },
        )
        try {
            // Read back OS state before opening/writing: includes the create-to-open crash window.
            val created = requireNotNull(system.getSessionInfo(owned))
            assertThat(created.installerPackageName).isEqualTo(context.packageName)
            assertThat(created.appPackageName).isEqualTo(context.packageName)
            assertThat(created.appLabel.toString()).isEqualTo("乐记应用更新")
            assertThat(created.isSealed).isFalse()
            val submittedDigest = MessageDigest.getInstance("SHA-256")
            var submittedBytes = 0L
            oldPlatform.openSession(owned).use { session ->
                session.openWrite(sourceApk.length()).use { output ->
                    sourceApk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            // Count/hash only writes accepted by the real platform stream.
                            submittedDigest.update(buffer, 0, count)
                            submittedBytes += count
                        }
                    }
                    session.fsync(output)
                }
            }
            assertThat(submittedBytes).isEqualTo(sourceApk.length())
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            sourceApk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sourceDigest.update(buffer, 0, count)
                }
            }
            assertThat(submittedDigest.digest()).isEqualTo(sourceDigest.digest())
            val staged = requireNotNull(system.getSessionInfo(owned))
            assertThat(staged.appLabel.toString()).isEqualTo("乐记应用更新")
            assertThat(staged.isSealed).isFalse()
            // Evidence is submitted-stream-not-staged-readback. API26 may deny staging-file
            // read descriptors under enforcing SELinux; do not retry via privileged/raw reads.
            // Actual OS session retirement below remains an independent required assertion.

            val restarted: AppUpdateInstaller = AndroidAppUpdateInstaller(context)
            restarted.recoverInterruptedSessions()
            restarted.recoverInterruptedSessions()

            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (system.getSessionInfo(owned) != null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10)
            }
            assertThat(system.getSessionInfo(owned)).isNull()
            assertThat(system.getSessionInfo(unmarked)).isNotNull()
        } finally {
            runCatching { system.abandonSession(owned) }
            runCatching { system.abandonSession(unmarked) }
        }
        // This reconstruction contract does not claim a real process kill or committed-session
        // proof. Those require an isolated emulator with an explicit two-process test driver.
    }


    @Test
    fun statusReceiverFailureReleasesRealSystemStagingAndLeavesOtherSessionsAlone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInstaller = context.packageManager.packageInstaller
        val failure = IOException("cannot prepare the install status receiver")
        val delegate = AndroidPackageInstallerPlatform(context)
        var updateSessionId: Int? = null
        val platform = object : PackageInstallerPlatform by delegate {
            override fun createSession(expectedPackageName: String): Int =
                delegate.createSession(expectedPackageName).also { updateSessionId = it }
            override fun openSession(sessionId: Int): PackageInstallerPlatform.Session {
                val real = delegate.openSession(sessionId)
                return object : PackageInstallerPlatform.Session by real {
                    override fun commit() = throw failure
                }
            }
        }
        val apk = File.createTempFile("installer-failure-", ".apk", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val otherSessionId = packageInstaller.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
            },
        )
        try {
            val thrown = runCatching {
                AndroidAppUpdateInstaller(platform).installFromFile(apk, context.packageName)
            }.exceptionOrNull()

            assertThat(thrown).isSameInstanceAs(failure)
            val failedSessionId = requireNotNull(updateSessionId)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (packageInstaller.getSessionInfo(failedSessionId) != null &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(10)
            }
            assertThat(packageInstaller.getSessionInfo(failedSessionId)).isNull()
            assertThat(packageInstaller.getSessionInfo(otherSessionId)).isNotNull()
        } finally {
            // Only clean up sessions created by this test. No APK is ever committed.
            updateSessionId?.let { runCatching { packageInstaller.abandonSession(it) } }
            packageInstaller.abandonSession(otherSessionId)
            apk.delete()
        }
    }
}
