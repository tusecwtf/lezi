package com.lezi.babylog.feature.family.members

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.LocalFamilyIdentity
import com.lezi.babylog.feature.family.FamilyMainDispatcherRule
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SourceCommandLogoutConsent
import com.lezi.babylog.sync.SourceCommandLogoutConsentChangedException
import com.lezi.babylog.sync.SourceCommandLogoutState
import com.lezi.babylog.sync.SyncPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceCommandLogoutHostTest {
    @get:Rule
    val mainDispatcherRule = FamilyMainDispatcherRule()

    @Test
    fun unsupportedSourceCheckNeverAuthorizesOrdinaryLogout() =
        runTest(mainDispatcherRule.testDispatcher) {
            var loggedOut = false
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun logoutCurrentDevice(): Result<Unit> {
                    loggedOut = true
                    return Result.success(Unit)
                }
            }
            val host = MembersDevicesHost(sync, LocalFamilyIdentity("device-1", "管理员", 1))

            host.openLogoutConfirmation()
            host.logoutCurrentDevice(host.logoutSourcePreview.value)
            runCurrent()
            host.logoutCurrentDevice(host.logoutSourcePreview.value)
            runCurrent()

            assertThat(host.logoutSourcePreview.value)
                .isInstanceOf(SourceCommandLogoutPreview.Failed::class.java)
            assertThat(loggedOut).isFalse()
            assertThat(host.command.value).isNull()
        }

    @Test
    fun sourceChangeRequiresReopeningAndAnotherConfirmationInsteadOfRefreshingBehindTheClick() =
        runTest(mainDispatcherRule.testDispatcher) {
            val first = sourceConsent("request-a")
            val second = sourceConsent("request-b")
            var current = first
            var previewReads = 0
            var loggedOut = false
            val submitted = mutableListOf<SourceCommandLogoutConsent>()
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun prepareSourceCommandLogout(): Result<SourceCommandLogoutConsent?> {
                    previewReads++
                    return Result.success(current)
                }

                override suspend fun logoutCurrentDevice(consent: SourceCommandLogoutConsent): Result<Unit> {
                    submitted += consent
                    if (consent !== current) return Result.failure(SourceCommandLogoutConsentChangedException())
                    loggedOut = true
                    return Result.success(Unit)
                }
            }
            val host = MembersDevicesHost(sync, LocalFamilyIdentity("device-1", "管理员", 1))
            host.openLogoutConfirmation()
            runCurrent()
            val shownFirst = host.logoutSourcePreview.value
            current = second

            host.logoutCurrentDevice(shownFirst)
            runCurrent()

            assertThat(previewReads).isEqualTo(1)
            assertThat(submitted).containsExactly(first)
            assertThat(loggedOut).isFalse()
            assertThat(host.command.value?.success).isFalse()
            assertThat(host.logoutSourcePreview.value)
                .isInstanceOf(SourceCommandLogoutPreview.Failed::class.java)
            host.consumeCommand(host.command.value!!.id)
            host.closeLogoutConfirmation()
            host.openLogoutConfirmation()
            runCurrent()

            // A callback retained from the old dialog cannot authorize its replacement.
            host.logoutCurrentDevice(shownFirst)
            runCurrent()
            assertThat(host.command.value).isNull()
            assertThat(loggedOut).isFalse()
            val shownSecond = host.logoutSourcePreview.value as SourceCommandLogoutPreview.Ready
            assertThat(shownSecond.consent).isSameInstanceAs(second)
            host.logoutCurrentDevice(shownSecond)
            runCurrent()

            assertThat(previewReads).isEqualTo(2)
            assertThat(submitted).containsExactly(first, second).inOrder()
            assertThat(loggedOut).isTrue()
            assertThat(host.command.value?.success).isTrue()
        }

    @Test
    fun latePreviewFromClosedDialogCannotReplaceTheNewDialogProof() =
        runTest(mainDispatcherRule.testDispatcher) {
            val releaseOldRead = CompletableDeferred<Unit>()
            val oldConsent = sourceConsent("request-old")
            var previewReads = 0
            var loggedOut = false
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun prepareSourceCommandLogout(): Result<SourceCommandLogoutConsent?> {
                    previewReads++
                    return if (previewReads == 1) {
                        withContext(NonCancellable) {
                            releaseOldRead.await()
                            Result.success(oldConsent)
                        }
                    } else {
                        Result.success(null)
                    }
                }

                override suspend fun logoutCurrentDevice(): Result<Unit> {
                    loggedOut = true
                    return Result.success(Unit)
                }
            }
            val host = MembersDevicesHost(sync, LocalFamilyIdentity("device-1", "管理员", 1))
            host.openLogoutConfirmation()
            runCurrent()
            host.closeLogoutConfirmation()
            host.openLogoutConfirmation()
            runCurrent()
            val newPreview = host.logoutSourcePreview.value as SourceCommandLogoutPreview.Ready
            assertThat(newPreview.consent).isNull()

            releaseOldRead.complete(Unit)
            runCurrent()

            assertThat(host.logoutSourcePreview.value).isSameInstanceAs(newPreview)
            host.logoutCurrentDevice(newPreview)
            runCurrent()
            assertThat(loggedOut).isTrue()
        }

    @Test
    fun finalClickRemainsSingleFlightAndDoesNotReloadTheShownConsent() =
        runTest(mainDispatcherRule.testDispatcher) {
            val releaseLogout = CompletableDeferred<Unit>()
            val consent = sourceConsent("request-a")
            var previewReads = 0
            var logoutAttempts = 0
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun prepareSourceCommandLogout(): Result<SourceCommandLogoutConsent?> {
                    previewReads++
                    return Result.success(consent)
                }

                override suspend fun logoutCurrentDevice(consent: SourceCommandLogoutConsent): Result<Unit> {
                    logoutAttempts++
                    releaseLogout.await()
                    return Result.success(Unit)
                }
            }
            val host = MembersDevicesHost(sync, LocalFamilyIdentity("device-1", "管理员", 1))
            host.openLogoutConfirmation()
            runCurrent()
            val shown = host.logoutSourcePreview.value

            host.logoutCurrentDevice(shown)
            host.logoutCurrentDevice(shown)
            runCurrent()

            assertThat(host.command.value?.pending).isTrue()
            assertThat(logoutAttempts).isEqualTo(1)
            assertThat(previewReads).isEqualTo(1)
            releaseLogout.complete(Unit)
            runCurrent()
            assertThat(host.command.value?.success).isTrue()
            assertThat(host.logoutSourcePreview.value).isEqualTo(SourceCommandLogoutPreview.Checking)
        }
}

/** Fake sync mints opaque test previews; product UI cannot construct a consent object. */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private fun sourceConsent(requestId: String) = SourceCommandLogoutConsent(
    requestIds = listOf(requestId),
    serverOrigin = "https://nas.example:8765",
    state = SourceCommandLogoutState.Unknown,
    exactEvidence = Any(),
)
