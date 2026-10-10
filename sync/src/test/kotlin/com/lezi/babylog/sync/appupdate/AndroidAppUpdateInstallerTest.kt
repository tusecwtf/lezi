package com.lezi.babylog.sync.appupdate

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidAppUpdateInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun newInstallerRecoversOnlyItsUnsealedUpdateSessions() {
        val platform = RecordingPackageInstallerPlatform()
        platform.sessions += listOf(
            InstallSessionInfo(51, "com.lezi.test.update", "com.lezi.test.update", "乐记应用更新", false),
            InstallSessionInfo(52, "com.lezi.test.update", "com.lezi.test.update", "乐记应用更新", true),
            InstallSessionInfo(53, "com.lezi.test.update", "com.lezi.test.update", null, false),
            InstallSessionInfo(54, "another.installer", "com.lezi.test.update", "乐记应用更新", false),
            InstallSessionInfo(55, "com.lezi.test.update", "another.target", "乐记应用更新", false),
        )
        platform.liveSessions += platform.sessions.map { it.id }
        val restarted: AppUpdateInstaller = AndroidAppUpdateInstaller(platform)

        restarted.recoverInterruptedSessions()
        restarted.recoverInterruptedSessions()

        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION, 52, 53, 54, 55)
    }

    @Test
    fun recoveryWaitsForActiveStagingAndThenPreservesItsSubmittedSession() {
        val staging = CountDownLatch(1)
        val releaseStaging = CountDownLatch(1)
        val recoveryStarted = CountDownLatch(1)
        val platform = RecordingPackageInstallerPlatform(beforeOpen = {
            staging.countDown()
            check(releaseStaging.await(5, TimeUnit.SECONDS))
        })
        val executor = Executors.newFixedThreadPool(2)
        val file = apk()
        try {
            val install = executor.submit {
                AndroidAppUpdateInstaller(platform).installFromFile(file, "com.lezi.test.update")
            }
            check(staging.await(5, TimeUnit.SECONDS))
            val recovery = executor.submit {
                recoveryStarted.countDown()
                AndroidAppUpdateInstaller(platform).recoverInterruptedSessions()
            }
            check(recoveryStarted.await(5, TimeUnit.SECONDS))
            releaseStaging.countDown()
            install.get(5, TimeUnit.SECONDS)
            recovery.get(5, TimeUnit.SECONDS)
            assertThat(platform.liveSessions).containsExactly(OTHER_SESSION, CREATED_SESSION)
            assertThat(platform.submittedSessions).containsExactly(CREATED_SESSION)
        } finally {
            releaseStaging.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun lostCommitResponseDoesNotAbandonAnAlreadySealedSession() {
        val failure = IOException("commit reply lost after system sealed the session")
        val platform = RecordingPackageInstallerPlatform(afterSealFailure = failure)
        val thrown = runCatching {
            AndroidAppUpdateInstaller(platform).installFromFile(apk(), "com.lezi.test.update")
        }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(failure)
        AndroidAppUpdateInstaller(platform).recoverInterruptedSessions()
        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION, CREATED_SESSION)
    }

    @Test
    fun cancellationBeforeSubmissionIsRethrownAndReleasesStaging() {
        val cancellation = CancellationException("staging cancelled")
        val platform = RecordingPackageInstallerPlatform(openFailure = cancellation)
        val thrown = runCatching {
            AndroidAppUpdateInstaller(platform).installFromFile(apk(), "com.lezi.test.update")
        }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION)
    }

    @Test
    fun failureOpeningTheCreatedSessionReleasesOnlyThatSessionAndPreservesTheError() {
        val failure = IOException("cannot open created session")
        val platform = RecordingPackageInstallerPlatform(openFailure = failure)
        val installer: AppUpdateInstaller = AndroidAppUpdateInstaller(platform)

        val thrown = runCatching {
            installer.installFromFile(apk(), "com.lezi.test.update")
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION)
    }

    @Test
    fun successfulCommitKeepsThePendingInstallationEvenWhenClosingTheHandleFails() {
        val failure = IOException("cannot close submitted session handle")
        val platform = RecordingPackageInstallerPlatform(closeFailure = failure)
        val installer: AppUpdateInstaller = AndroidAppUpdateInstaller(platform)

        val thrown = runCatching {
            installer.installFromFile(apk(), "com.lezi.test.update")
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION, CREATED_SESSION)
        assertThat(platform.submittedSessions).containsExactly(CREATED_SESSION)
    }

    @Test
    fun failuresBeforeSubmissionReleaseTheSessionAndDoNotReplaceTheOriginalError() {
        FailurePoint.entries.forEach { point ->
            val failure = IOException("failed at $point")
            val platform = RecordingPackageInstallerPlatform(failurePoint = point, failure = failure)
            val installer: AppUpdateInstaller = AndroidAppUpdateInstaller(platform)

            val thrown = runCatching {
                installer.installFromFile(apk(), "com.lezi.test.update")
            }.exceptionOrNull()

            assertThat(thrown).isSameInstanceAs(failure)
            assertThat(platform.liveSessions).containsExactly(OTHER_SESSION)
            assertThat(platform.submittedSessions).isEmpty()
        }
    }

    @Test
    fun abandonFailureIsSuppressedOnTheOriginalInstallationFailure() {
        val failure = IOException("cannot open created session")
        val cleanupFailure = IOException("cannot release staging")
        val platform = RecordingPackageInstallerPlatform(
            openFailure = failure,
            abandonFailure = cleanupFailure,
        )

        val thrown = runCatching {
            AndroidAppUpdateInstaller(platform).installFromFile(apk(), "com.lezi.test.update")
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(failure.suppressed.toList()).containsExactly(cleanupFailure)
    }

    @Test
    fun successfulSubmissionKeepsTheCopiedApkAvailableToTheSystem() {
        val platform = RecordingPackageInstallerPlatform()

        AndroidAppUpdateInstaller(platform).installFromFile(apk(), "com.lezi.test.update")

        assertThat(platform.liveSessions).containsExactly(OTHER_SESSION, CREATED_SESSION)
        assertThat(platform.submittedSessions).containsExactly(CREATED_SESSION)
        assertThat(platform.stagedBytes.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    private fun apk(): File = temporaryFolder.newFile().apply {
        writeBytes(byteArrayOf(1, 2, 3, 4))
    }
}

private class RecordingPackageInstallerPlatform(
    val afterSealFailure: Throwable? = null,
    val beforeOpen: () -> Unit = {},
    val openFailure: Throwable? = null,
    val closeFailure: Throwable? = null,
    val abandonFailure: Throwable? = null,
    val failurePoint: FailurePoint? = null,
    val failure: Throwable = IOException("failed to stage APK"),
) : PackageInstallerPlatform {
    override val installerPackageName = "com.lezi.test.update"
    val sessions = mutableListOf<InstallSessionInfo>()
    override fun mySessions(): List<InstallSessionInfo> = sessions.filter { it.id in liveSessions }
    val liveSessions = mutableSetOf(OTHER_SESSION)
    val submittedSessions = mutableSetOf<Int>()
    val stagedBytes = ByteArrayOutputStream()

    override fun canRequestPackageInstalls(): Boolean = true

    override fun createManageUnknownSourcesIntent(): Intent = error("Not used by install")

    override fun createSession(expectedPackageName: String): Int {
        failAt(FailurePoint.Create)
        liveSessions += CREATED_SESSION
        sessions += InstallSessionInfo(CREATED_SESSION, installerPackageName, expectedPackageName,
            APP_UPDATE_SESSION_LABEL, false)
        return CREATED_SESSION
    }

    override fun openSession(sessionId: Int): PackageInstallerPlatform.Session {
        beforeOpen()
        openFailure?.let { throw it }
        return object : PackageInstallerPlatform.Session {
            override fun openWrite(lengthBytes: Long): OutputStream {
                failAt(FailurePoint.OpenWrite)
                return object : OutputStream() {
                    override fun write(value: Int) {
                        failAt(FailurePoint.Write)
                        stagedBytes.write(value)
                    }
                    override fun close() {
                        failAt(FailurePoint.CloseOutput)
                    }
                }
            }
            override fun fsync(output: OutputStream) {
                failAt(FailurePoint.Fsync)
            }
            override fun commit() {
                // Receiver creation and system commit are both owned by this SDK operation.
                failAt(FailurePoint.Commit)
                submittedSessions += sessionId
                val index = sessions.indexOfFirst { it.id == sessionId }
                sessions[index] = sessions[index].copy(sealed = true)
                afterSealFailure?.let { throw it }
            }
            override fun close() {
                closeFailure?.let { throw it }
            }
        }
    }

    override fun abandonSession(sessionId: Int) {
        abandonFailure?.let { throw it }
        liveSessions -= sessionId
    }

    private fun failAt(point: FailurePoint) {
        if (failurePoint == point) throw failure
    }
}

private enum class FailurePoint { Create, OpenWrite, Write, Fsync, CloseOutput, Commit }

private const val CREATED_SESSION = 41
private const val OTHER_SESSION = 29
