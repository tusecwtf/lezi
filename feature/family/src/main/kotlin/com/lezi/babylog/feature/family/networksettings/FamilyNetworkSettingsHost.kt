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
import com.lezi.babylog.sync.session.familySyncError
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val FAMILY_NETWORK_ACTION_TIMEOUT_MILLIS = 24_000L

sealed interface FamilyNetworkCandidate {
    data class Ready(
        val endpoint: TrustedEndpointProfile,
        val familyState: SetupFamilyState,
    ) : FamilyNetworkCandidate

    data class CertificateApproval(
        val candidate: CertificateTrustCandidate,
    ) : FamilyNetworkCandidate

    data class Failed(val message: String) : FamilyNetworkCandidate
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
    SetupProbeResult.Failed.InvalidAddress -> FamilyNetworkCandidate.Failed("请输入完整的 HTTPS 地址")
    SetupProbeResult.Failed.Unreachable -> FamilyNetworkCandidate.Failed("无法连接候选服务器")
    SetupProbeResult.Failed.NotLezi -> FamilyNetworkCandidate.Failed("这不是兼容的乐记服务器")
    SetupProbeResult.Failed.Incompatible -> FamilyNetworkCandidate.Failed("服务器协议与当前版本不兼容")
    SetupProbeResult.Failed.Maintenance -> FamilyNetworkCandidate.Failed("服务器尚未就绪，请稍后重试")
    SetupProbeResult.Failed.CertificateChanged ->
        FamilyNetworkCandidate.Failed("服务器证书已变化，需要重新检查地址")
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
)

internal fun canCancelDisasterRecovery(ui: FamilyNetworkSettingsUi): Boolean =
    ui.recoveryStatus != null &&
        ui.recoveryStatus != "committed" &&
        (ui.recoveryStatus != "summary_ready" || ui.disasterRecoveryBusy)

@HiltViewModel
class FamilyNetworkSettingsHost internal constructor(
    private val sync: SyncPort,
    private val actionTimeoutMillis: Long,
) : ViewModel() {
    @Inject
    constructor(sync: SyncPort) : this(sync, FAMILY_NETWORK_ACTION_TIMEOUT_MILLIS)

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
        launchBusy(bounded = false) {
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
        )
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            sync.probeServerAvailability(AvailabilityProbeReason.PullToRefresh)
        }
    }

    fun probeCandidate() = launchBusy {
        val candidate = actions.probeCandidate(actionState.value.endpointDraft)
        actionState.value.copy(
            candidate = candidate,
            feedback = (candidate as? FamilyNetworkCandidate.Failed)?.message,
        )
    }

    fun trustCandidate(candidate: CertificateTrustCandidate) = launchBusy {
        val result = actions.trustCandidate(candidate)
        actionState.value.copy(
            candidate = result,
            feedback = (result as? FamilyNetworkCandidate.Failed)?.message,
        )
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
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
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
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun checkReconnectMember() = launchBusy {
        actions.checkReconnectMember().fold(
            onSuccess = { result ->
                when (result) {
                    is MemberLoginCheckResult.Waiting -> actionState.value.copy(
                        pendingMember = result.request,
                        feedback = "管理员尚未确认",
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
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun cancelReconnectMember() = launchBusy {
        actions.cancelReconnectMember().fold(
            onSuccess = {
                actionState.value.copy(pendingMember = null, feedback = "已取消候选服务器申请")
            },
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun prepareDisasterRecovery() = launchBusy(bounded = false) {
        actions.prepareDisasterRecovery().fold(
            onSuccess = { summary ->
                actionState.value.copy(
                    recoverySummary = summary,
                    recoveryStatus = "summary_ready",
                    feedback = "请核对恢复摘要，再输入新服务器根密码",
                )
            },
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun startDisasterRecovery(
        ownerDisplayName: String,
        deviceName: String,
        rootPassword: String,
    ) = launchBusy(bounded = false, disasterRecovery = true) {
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
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun commitDisasterRecovery(rootPassword: String) = launchBusy(
        bounded = false,
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
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun cancelDisasterRecovery() = launchBusy(
        bounded = false,
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
                )
            },
            onFailure = { actionState.value.copy(feedback = familyNetworkFailureCopy(it)) },
        )
    }

    fun clearCandidate() {
        actionState.value = actionState.value.copy(candidate = null, feedback = null)
    }

    private fun launchBusy(
        bounded: Boolean = true,
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
        )
        val job = viewModelScope.launch {
            var next: FamilyNetworkActionState? = null
            try {
                next =
                if (bounded) {
                    withTimeout(actionTimeoutMillis) { block() }
                } else {
                    block()
                }
            } catch (_: TimeoutCancellationException) {
                next = actionState.value.copy(
                    feedback = "连接检查超时，请检查家庭网络后重试",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                next = actionState.value.copy(feedback = familyNetworkFailureCopy(error))
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

internal fun familyNetworkFailureCopy(error: Throwable): String =
    familySyncError(error, "家庭网络操作失败，请稍后重试")
