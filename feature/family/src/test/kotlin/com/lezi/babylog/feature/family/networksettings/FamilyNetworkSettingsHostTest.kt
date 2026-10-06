package com.lezi.babylog.feature.family.networksettings

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.feature.family.FamilyMainDispatcherRule
import com.lezi.babylog.sync.DisasterRecoveryProgress
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyNetworkSettingsHostTest {
    @get:Rule
    val mainDispatcherRule = FamilyMainDispatcherRule()

    @Test
    fun candidateProbeKeepsTheTransportKindInsteadOfASecondUiClock() = runTest {
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult =
                SetupProbeResult.Failed.AddressNotFound
        }
        val host = FamilyNetworkSettingsHost(sync)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.ui.collect() }
        host.updateEndpointDraft("https://nas.home:8765")

        host.probeCandidate()
        advanceUntilIdle()

        assertThat(host.ui.value.busy).isFalse()
        assertThat(host.ui.value.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound)
        assertThat(host.ui.value.failureKind)
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.Unreachable)
        assertThat(host.ui.value.feedback).isNull()
        assertThat(
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.AddressNotFound,
            ).title,
        ).isEqualTo("找不到家里的服务器")
    }

    @Test
    fun disasterRecoveryCancelPreemptsHungUploadAndClearsBusyState() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home:8765")
        val uploadStarted = CompletableDeferred<Unit>()
        var cancelCalls = 0
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult =
                SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty)

            override suspend fun prepareDisasterRecovery() = Result.success(sampleSummary())

            override suspend fun startDisasterRecovery(
                endpoint: TrustedEndpointProfile,
                ownerDisplayName: String,
                deviceName: String,
                rootPassword: String,
            ): Result<DisasterRecoveryProgress> {
                uploadStarted.complete(Unit)
                awaitCancellation()
            }

            override suspend fun cancelDisasterRecovery(): Result<Unit> {
                cancelCalls += 1
                return Result.success(Unit)
            }
        }
        val host = FamilyNetworkSettingsHost(sync)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.ui.collect() }
        host.updateEndpointDraft(endpoint.origin)
        host.probeCandidate()
        advanceUntilIdle()
        host.prepareDisasterRecovery()
        advanceUntilIdle()

        host.startDisasterRecovery("妈妈", "Pixel", "root-password")
        uploadStarted.await()
        runCurrent()
        assertThat(host.ui.value.busy).isTrue()
        assertThat(canCancelDisasterRecovery(host.ui.value)).isTrue()

        host.cancelDisasterRecovery()
        advanceUntilIdle()

        assertThat(cancelCalls).isEqualTo(1)
        assertThat(host.ui.value.busy).isFalse()
        assertThat(host.ui.value.recoveryStatus).isNull()
        assertThat(host.ui.value.feedback).isEqualTo("已取消家庭恢复批次")
        assertThat(host.ui.value.failureKind).isNull()
    }

    @Test
    fun disasterRecoveryUploadKeepsTheTransportKind() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home:8765")
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult =
                SetupProbeResult.Ready(endpoint, SetupFamilyState.Empty)

            override suspend fun prepareDisasterRecovery() = Result.success(sampleSummary())

            override suspend fun startDisasterRecovery(
                endpoint: TrustedEndpointProfile,
                ownerDisplayName: String,
                deviceName: String,
                rootPassword: String,
            ): Result<DisasterRecoveryProgress> = Result.failure(
                com.lezi.babylog.sync.backend.deadline.FamilyHttpException(
                    com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind.SyncTookTooLong,
                ),
            )
        }
        val host = FamilyNetworkSettingsHost(sync)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.ui.collect() }
        host.updateEndpointDraft(endpoint.origin)
        host.probeCandidate()
        advanceUntilIdle()
        host.prepareDisasterRecovery()
        advanceUntilIdle()

        host.startDisasterRecovery("妈妈", "Pixel", "root-password")
        advanceUntilIdle()

        assertThat(host.ui.value.busy).isFalse()
        assertThat(host.ui.value.disasterRecoveryBusy).isFalse()
        assertThat(host.ui.value.feedback).isNull()
        assertThat(host.ui.value.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.SyncTookTooLong)
    }

    @Test
    fun disasterRecoveryStatusCopyNeverPrintsMachineTokens() {
        assertThat(disasterRecoveryStatusCopy("manifest_received"))
            .isEqualTo("正在校验已上传的家庭数据")

        val unknown = disasterRecoveryStatusCopy("upload_phase_v9_internal")

        assertThat(unknown).isEqualTo("暂时查不到恢复进度，稍后再点一次「查询恢复进度」")
        assertThat(unknown).doesNotContain("upload_phase_v9_internal")
    }

    @Test
    fun forgetEndpointClearsCertificateChangedCandidate() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://nas.home:8765")
        var forgetCalls = 0
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult =
                SetupProbeResult.Failed.CertificateChanged

            override suspend fun forgetEndpoint(): Result<Unit> {
                forgetCalls += 1
                return Result.success(Unit)
            }
        }
        val host = FamilyNetworkSettingsHost(sync)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.ui.collect() }
        host.updateEndpointDraft(endpoint.origin)
        host.probeCandidate()
        advanceUntilIdle()

        assertThat(host.ui.value.failureKind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.CertificateChanged)

        host.forgetEndpointAndReconnect()
        advanceUntilIdle()

        assertThat(forgetCalls).isEqualTo(1)
        assertThat(host.ui.value.candidate).isNull()
        assertThat(host.ui.value.failureKind).isNull()
    }

    @Test
    fun networkFailureKindDoesNotExposeHttpOrServerDetail() {
        val kind = com.lezi.babylog.sync.session.familyFailureKind(
            IllegalStateException("HTTP 500: sqlite locked at /data/lezi.db"),
        )
        val copy = com.lezi.babylog.core.common.failure.failureExplanation(kind!!)

        assertThat(kind)
            .isEqualTo(com.lezi.babylog.core.common.failure.FailureKind.UnexpectedError)
        assertThat(copy.dialogTitle).doesNotContain("HTTP")
        assertThat(copy.body).doesNotContain("/data")
    }

    private fun sampleSummary() = DisasterRecoverySummary(
        babies = 1,
        records = 2,
        carePlans = 0,
        fulfillmentRelations = 0,
        customItems = 0,
        photos = 0,
        mediaBytes = 0L,
    )
}
