package com.lezi.babylog.feature.family.networksettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.availability.AvailabilityProbeReason
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.availability.FamilyServerUnavailableReason
import com.lezi.babylog.sync.session.CertificateTrustCandidate
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.sync.session.familyFailureKind
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface FamilyNetworkCandidate {
    data class Ready(
        val endpoint: TrustedEndpointProfile,
        val familyState: SetupFamilyState,
    ) : FamilyNetworkCandidate

    data class CertificateApproval(
        val candidate: CertificateTrustCandidate,
    ) : FamilyNetworkCandidate

    data class Failed(val reason: SetupProbeResult.Failed) : FamilyNetworkCandidate {
        val failureKind: FailureKind get() = familyFailureKind(reason)
    }
}

data class NetworkAvailabilityCopy(
    val status: String,
    val lastHealthyAtMillis: Long?,
    val trustRecoveryRequired: Boolean = false,
)

fun networkAvailabilityCopy(availability: FamilyServerAvailability): NetworkAvailabilityCopy =
    when (availability) {
        FamilyServerAvailability.Disabled -> NetworkAvailabilityCopy("尚未检测", null)
        is FamilyServerAvailability.Checking -> NetworkAvailabilityCopy(
            "正在检查家庭服务器",
            availability.lastHealthyAtMillis,
        )
        is FamilyServerAvailability.Available -> NetworkAvailabilityCopy(
            "可连接",
            availability.lastHealthyAtMillis,
        )
        is FamilyServerAvailability.Unavailable -> NetworkAvailabilityCopy(
            if (availability.reason == FamilyServerUnavailableReason.TrustChanged) {
                "服务器安全信息已变化，请核对证书并重新登录"
            } else {
                "当前不可连接，本机护理可继续使用"
            },
            availability.lastHealthyAtMillis,
            trustRecoveryRequired =
                availability.reason == FamilyServerUnavailableReason.TrustChanged,
        )
    }

class FamilyNetworkSettingsActions(
    private val sync: SyncPort,
) {
    suspend fun probeCandidate(endpointDraft: String): FamilyNetworkCandidate =
        sync.probeReconnectEndpoint(endpointDraft).toNetworkCandidate()

    suspend fun trustCandidate(
        candidate: CertificateTrustCandidate,
    ): FamilyNetworkCandidate = sync.trustReconnectCertificate(candidate).toNetworkCandidate()

    suspend fun reconnectOwner(
        endpoint: TrustedEndpointProfile,
        deviceName: String,
        rootPassword: String,
    ) = sync.reconnectOwner(endpoint, deviceName, rootPassword)

    suspend fun requestReconnectMember(
        endpoint: TrustedEndpointProfile,
        displayName: String,
        deviceName: String,
    ) = sync.requestReconnectMember(endpoint, displayName, deviceName)

    suspend fun checkReconnectMember() = sync.checkReconnectMember()

    suspend fun cancelReconnectMember() = sync.cancelReconnectMember()

    suspend fun prepareDisasterRecovery() = sync.prepareDisasterRecovery()

    suspend fun startDisasterRecovery(
        endpoint: TrustedEndpointProfile,
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ) = sync.startDisasterRecovery(
        endpoint,
        ownerDisplayName,
        deviceName,
        rootPassword,
    )

    suspend fun resumeDisasterRecovery() = sync.resumeDisasterRecovery()

    suspend fun commitDisasterRecovery(rootPassword: String) =
        sync.commitDisasterRecovery(rootPassword)

    suspend fun cancelDisasterRecovery() = sync.cancelDisasterRecovery()
}

private fun SetupProbeResult.toNetworkCandidate(): FamilyNetworkCandidate = when (this) {
    is SetupProbeResult.Ready -> FamilyNetworkCandidate.Ready(endpoint, familyState)
    is SetupProbeResult.CertificateApprovalRequired ->
        FamilyNetworkCandidate.CertificateApproval(candidate)
    is SetupProbeResult.Failed -> FamilyNetworkCandidate.Failed(this)
}

private data class FamilyNetworkActionState(
    val endpointDraft: String = "",
    val candidate: FamilyNetworkCandidate? = null,
    val busy: Boolean = false,
    val disasterRecoveryBusy: Boolean = false,
    val cancellingRecovery: Boolean = false,
    val feedback: String? = null,
    val pendingMember: PendingMemberLogin? = null,
    val recoverySummary: DisasterRecoverySummary? = null,
    val recoveryStatus: String? = null,
    val recoveryExpiresAtEpochSeconds: Long? = null,
    val failureKind: FailureKind? = null,
)

data class FamilyNetworkSettingsUi(
    val currentEndpoint: String = "",
    val currentFingerprint: String? = null,
    val role: FamilyRole = FamilyRole.None,
    val availabilityStatus: String = "尚未检测",
    val lastHealthyAtMillis: Long? = null,
    val endpointDraft: String = "",
    val candidate: FamilyNetworkCandidate? = null,
    val busy: Boolean = false,
    val disasterRecoveryBusy: Boolean = false,
    val cancellingRecovery: Boolean = false,
    val feedback: String? = null,
    val pendingMember: PendingMemberLogin? = null,
    val recoverySummary: DisasterRecoverySummary? = null,
    val recoveryStatus: String? = null,
    val recoveryExpiresAtEpochSeconds: Long? = null,
    val trustRecoveryRequired: Boolean = false,
    val failureKind: FailureKind? = null,
)

internal fun canCancelDisasterRecovery(ui: FamilyNetworkSettingsUi): Boolean =
    ui.recoveryStatus != null &&
        ui.recoveryStatus != "committed" &&
        (ui.recoveryStatus != "summary_ready" || ui.disasterRecoveryBusy)

@HiltViewModel
class FamilyNetworkSettingsHost @Inject constructor(
    private val sync: SyncPort,
) : ViewModel() {

    private val actions = FamilyNetworkSettingsActions(sync)
    private val actionState = MutableStateFlow(FamilyNetworkActionState())
    private var actionGeneration = 0L
    private var activeBusyJob: Job? = null
    private val availabilitySurface = combine(
        sync.availability(),
        sync.lastServerHealthyAt(),
    ) { availability, storedLastHealthyAt ->
        networkAvailabilityCopy(availability).let { copy ->
            copy.copy(lastHealthyAtMillis = copy.lastHealthyAtMillis ?: storedLastHealthyAt)
        }
    }

    val ui: StateFlow<FamilyNetworkSettingsUi> = combine(
        sync.session(),
        sync.verifiedEndpoint(),
        availabilitySurface,
        actionState,
    ) { session, endpoint, availability, action ->
        FamilyNetworkSettingsUi(
            currentEndpoint = endpoint?.origin ?: session.baseUrl,
            currentFingerprint = endpoint?.fingerprint,
            role = session.role,
            availabilityStatus = availability.status,
            lastHealthyAtMillis = availability.lastHealthyAtMillis,
            endpointDraft = action.endpointDraft,
            candidate = action.candidate,
            busy = action.busy,
            disasterRecoveryBusy = action.disasterRecoveryBusy,
            cancellingRecovery = action.cancellingRecovery,
            feedback = action.feedback,
            pendingMember = action.pendingMember,
            recoverySummary = action.recoverySummary,
            recoveryStatus = action.recoveryStatus,
            recoveryExpiresAtEpochSeconds = action.recoveryExpiresAtEpochSeconds,
            trustRecoveryRequired = availability.trustRecoveryRequired,
            failureKind = action.failureKind,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FamilyNetworkSettingsUi(),
    )

    fun entered() {
        if (actionState.value.endpointDraft.isBlank()) {
            actionState.value = actionState.value.copy(endpointDraft = ui.value.currentEndpoint)
        }
        refreshAvailability()
        launchBusy {
            actions.resumeDisasterRecovery().getOrNull()?.let { progress ->
                return@launchBusy actionState.value.copy(
                    recoverySummary = progress.summary,
                    recoveryStatus = progress.status,
                    recoveryExpiresAtEpochSeconds = progress.expiresAtEpochSeconds,
                    feedback = "已找到可继续的家庭恢复批次",
                )
            }
            actionState.value
        }
    }

    fun updateEndpointDraft(value: String) {
        actionState.value = actionState.value.copy(
            endpointDraft = value,
            candidate = null,
            feedback = null,
            pendingMember = null,
            failureKind = null,
        )
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            sync.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        }
    }

    fun probeCandidate() = launchBusy {
        val candidate = actions.probeCandidate(actionState.value.endpointDraft)
        actionState.value.withCandidateResult(candidate)
    }

    fun trustCandidate(candidate: CertificateTrustCandidate) = launchBusy {
        val result = actions.trustCandidate(candidate)
        actionState.value.withCandidateResult(result)
    }

    fun reconnectOwner(deviceName: String, rootPassword: String) = launchBusy {
        val endpoint = (actionState.value.candidate as? FamilyNetworkCandidate.Ready)?.endpoint
            ?: return@launchBusy actionState.value.copy(feedback = "请先检查候选地址")
        actions.reconnectOwner(endpoint, deviceName, rootPassword).fold(
            onSuccess = {
                actionState.value.copy(
                    candidate = null,
                    endpointDraft = it.session.baseUrl,
                    feedback = "家庭服务器地址已更新",
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun requestReconnectMember(displayName: String, deviceName: String) = launchBusy {
        val endpoint = (actionState.value.candidate as? FamilyNetworkCandidate.Ready)?.endpoint
            ?: return@launchBusy actionState.value.copy(feedback = "请先检查候选地址")
        actions.requestReconnectMember(endpoint, displayName, deviceName).fold(
            onSuccess = {
                actionState.value.copy(
                    pendingMember = it,
                    feedback = "加入申请已提交，等待管理员确认",
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun checkReconnectMember() = launchBusy {
        actions.checkReconnectMember().fold(
            onSuccess = { result ->
                when (result) {
                    is MemberLoginCheckResult.Waiting -> actionState.value.copy(
                        pendingMember = result.request,
                        feedback = "管理员尚未确认",
                        failureKind = null,
                    )
                    is MemberLoginCheckResult.Joined -> actionState.value.copy(
                        candidate = null,
                        pendingMember = null,
                        endpointDraft = result.session.baseUrl,
                        feedback = "家庭服务器地址已更新",
                    )
                    is MemberLoginCheckResult.Terminal -> actionState.value.copy(
                        pendingMember = null,
                        feedback = "加入申请已结束，请重新提交",
                    )
                }
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun cancelReconnectMember() = launchBusy {
        actions.cancelReconnectMember().fold(
            onSuccess = {
                actionState.value.copy(pendingMember = null, feedback = "已取消候选服务器申请")
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun prepareDisasterRecovery() = launchBusy {
        actions.prepareDisasterRecovery().fold(
            onSuccess = { summary ->
                actionState.value.copy(
                    recoverySummary = summary,
                    recoveryStatus = "summary_ready",
                    feedback = "请核对恢复摘要，再输入新服务器根密码",
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun startDisasterRecovery(
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ) = launchBusy(
        disasterRecovery = true,
    ) {
        val endpoint = (actionState.value.candidate as? FamilyNetworkCandidate.Ready)?.endpoint
            ?: return@launchBusy actionState.value.copy(feedback = "请先检查空服务器地址")
        actions.startDisasterRecovery(
            endpoint,
            ownerDisplayName,
            deviceName,
            rootPassword,
        ).fold(
            onSuccess = { progress ->
                actionState.value.copy(
                    recoverySummary = progress.summary ?: actionState.value.recoverySummary,
                    recoveryStatus = progress.status,
                    recoveryExpiresAtEpochSeconds = progress.expiresAtEpochSeconds,
                    feedback = "本机数据和照片已完整暂存，等待最终确认",
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun commitDisasterRecovery(rootPassword: String) = launchBusy(
        disasterRecovery = true,
    ) {
        actions.commitDisasterRecovery(rootPassword).fold(
            onSuccess = { result ->
                actionState.value.copy(
                    candidate = null,
                    endpointDraft = result.session.baseUrl,
                    recoverySummary = null,
                    recoveryStatus = null,
                    recoveryExpiresAtEpochSeconds = null,
                    feedback = "家庭已从本机恢复，当前设备成为新管理员",
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun cancelDisasterRecovery() = launchBusy(
        disasterRecovery = true,
        cancellingRecovery = true,
        preemptCurrent = actionState.value.disasterRecoveryBusy,
    ) {
        actions.cancelDisasterRecovery().fold(
            onSuccess = {
                actionState.value.copy(
                    recoverySummary = null,
                    recoveryStatus = null,
                    recoveryExpiresAtEpochSeconds = null,
                    feedback = "已取消家庭恢复批次",
                    failureKind = null,
                )
            },
            onFailure = { actionState.value.withNetworkFailure(it) },
        )
    }

    fun clearCandidate() {
        actionState.value = actionState.value.copy(
            candidate = null,
            feedback = null,
            failureKind = null,
        )
    }

    fun consumeFailureKind() {
        actionState.value = actionState.value.copy(failureKind = null)
    }

    fun forgetEndpointAndReconnect() {
        viewModelScope.launch {
            sync.forgetEndpoint()
            actionState.value = actionState.value.copy(
                candidate = null,
                failureKind = null,
                feedback = null,
            )
        }
    }

    private fun launchBusy(
        disasterRecovery: Boolean = false,
        cancellingRecovery: Boolean = false,
        preemptCurrent: Boolean = false,
        block: suspend () -> FamilyNetworkActionState,
    ) {
        if (actionState.value.busy && !preemptCurrent) return
        val generation = actionGeneration + 1L
        actionGeneration = generation
        if (preemptCurrent) activeBusyJob?.cancel()
        actionState.value = actionState.value.copy(
            busy = true,
            disasterRecoveryBusy = disasterRecovery,
            cancellingRecovery = cancellingRecovery,
            feedback = null,
            failureKind = null,
        )
        val job = viewModelScope.launch {
            var next: FamilyNetworkActionState? = null
            try {
                next = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                next = actionState.value.withNetworkFailure(error)
            } finally {
                if (actionGeneration == generation) {
                    actionState.value = (next ?: actionState.value).copy(
                        busy = false,
                        disasterRecoveryBusy = false,
                        cancellingRecovery = false,
                    )
                    activeBusyJob = null
                }
            }
        }
        activeBusyJob = job.takeIf(Job::isActive)
    }
}

private fun FamilyNetworkActionState.withCandidateResult(
    candidate: FamilyNetworkCandidate,
): FamilyNetworkActionState {
    val failed = candidate as? FamilyNetworkCandidate.Failed
    return if (failed?.reason == SetupProbeResult.Failed.InvalidAddress) {
        copy(
            candidate = candidate,
            feedback = "请输入完整的 HTTPS 地址",
            failureKind = null,
        )
    } else {
        copy(
            candidate = candidate,
            feedback = null,
            failureKind = failed?.failureKind,
        )
    }
}

private fun FamilyNetworkActionState.withNetworkFailure(error: Throwable) = copy(
    feedback = null,
    failureKind = familyFailureKind(error),
)
