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
    fun hungCandidateProbeClearsBusyWithActionableTimeoutCopy() = runTest {
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun probeReconnectEndpoint(endpointDraft: String): SetupProbeResult {
                awaitCancellation()
            }
        }
        val host = FamilyNetworkSettingsHost(sync, actionTimeoutMillis = 1_000L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.ui.collect() }
        host.updateEndpointDraft("https://nas.home:8765")

        host.probeCandidate()
        runCurrent()
        assertThat(host.ui.value.busy).isTrue()

        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(host.ui.value.busy).isFalse()
        assertThat(host.ui.value.feedback)
            .isEqualTo("连接检查超时，请检查家庭网络后重试")
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
        val host = FamilyNetworkSettingsHost(sync, actionTimeoutMillis = 1_000L)
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
    }

    @Test
    fun disasterRecoveryStatusCopyNeverPrintsMachineTokens() {
        assertThat(disasterRecoveryStatusCopy("manifest_received"))
            .isEqualTo("正在校验已上传的家庭数据")

        val unknown = disasterRecoveryStatusCopy("upload_phase_v9_internal")

        assertThat(unknown).isEqualTo("恢复状态暂时无法确认，请重试查询")
        assertThat(unknown).doesNotContain("upload_phase_v9_internal")
    }

    @Test
    fun networkFailureCopyDoesNotExposeHttpOrServerDetail() {
        val copy = familyNetworkFailureCopy(
            IllegalStateException("HTTP 500: sqlite locked at /data/lezi.db"),
        )

        assertThat(copy).isEqualTo("家庭同步服务暂未连接，请稍后重试")
        assertThat(copy).doesNotContain("HTTP")
        assertThat(copy).doesNotContain("/data")
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
