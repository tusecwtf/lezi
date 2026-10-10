package com.lezi.babylog.sync.session
import com.lezi.babylog.core.common.failure.AlbumReadStalledException
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.LocalPersistException
import com.lezi.babylog.sync.BootstrapSecretRejectedException
import com.lezi.babylog.sync.MemberLoginQrTrustChangedException
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.OwnerRootPasswordRejectedException
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.CausalCommitRejectedException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.backend.RemoteFamilyDeletedException
import com.lezi.babylog.sync.backend.RemoteMembershipDeletedException
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.clientUpdateRequiredOrNull
import com.lezi.babylog.sync.backend.syncHttpCodeOrNull
import com.lezi.babylog.sync.backend.deadline.FamilyHttpConnectTimeoutException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpWriteStallException
import com.lezi.babylog.sync.availability.causeChainContains
import com.lezi.babylog.sync.backend.deadline.toCatalogKind
import com.lezi.babylog.sync.backend.retry.SyncRetryBudgetExceededException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * One classifier for family and local bounded failures.
 * User cancel is not a failure. Form/invite leftovers stay [FailureKind.InvalidInput].
 * Local persist failures are [FailureKind.LocalSaveFailed], never a form problem.
 * Removal outcomes ([FailureKind.DeviceRemoved], ServerHasNoFamily) end rejoining.
 * Anything unmatched is [FailureKind.UnexpectedError] — never "check your input".
 * A replica cycle that did not converge is never a nickname/invite problem.
 */
fun familyFailureKind(error: Throwable): FailureKind? {
    if (error is CancellationException && error !is TimeoutCancellationException) {
        return null
    }
    // Sync while unconfigured is a precondition, not a failure; the port status
    // machine already projects it as SyncStatus.Disabled.
    if (error is SyncNotEnabledException) {
        return null
    }
    if (error.causeChainContains<SpkiPinMismatchException>()) {
        return FailureKind.CertificateChanged
    }
    generateSequence(error) { it.cause }.forEach { current ->
        when (current) {
            is FamilyHttpException -> return current.kind.toCatalogKind()
            is FamilyHttpWriteStallException -> return FailureKind.SendStalled
            is FamilyHttpConnectTimeoutException -> return FailureKind.Unreachable
            is LocalPersistException -> return FailureKind.LocalSaveFailed
            is RemoteDeviceRemovedException,
            is RemoteMembershipDeletedException,
            -> return FailureKind.DeviceRemoved
            is RemoteFamilyDeletedException -> return FailureKind.ServerHasNoFamily
            is BootstrapSecretRejectedException,
            is OwnerRootPasswordRejectedException,
            -> return FailureKind.SessionExpired
            is AlbumReadStalledException -> return FailureKind.AlbumReadStalled
            is SyncRetryBudgetExceededException -> return FailureKind.ResponseTimedOut
            is TimeoutCancellationException -> return FailureKind.ResponseTimedOut
            is ClientUpdateRequiredException -> return FailureKind.AppUpdateRequired
            is ServerUpdateRequiredException -> return FailureKind.ServerUpdateRequired
            is MediaIdentityProtocolException -> return FailureKind.UnexpectedError
            is ReauthRequiredException -> return FailureKind.SessionExpired
            is MemberLoginQrUnavailableException -> return FailureKind.QrExpired
            is MemberLoginQrTrustChangedException -> return FailureKind.CertificateChanged
            is ReplicaSyncNotConvergedException -> return FailureKind.HouseholdStateChanged
            is ReplicaCycleStateException -> return FailureKind.HouseholdStateChanged
            is AuthorityProofException -> return FailureKind.HouseholdStateChanged
            is CausalCommitRejectedException -> return when (current.code) {
                "capability_mismatch" -> FailureKind.AppUpdateRequired
                "unauthenticated" -> FailureKind.SessionExpired
                "not_ready" -> FailureKind.HouseholdUnavailable
                else -> FailureKind.HouseholdFactRejected
            }
            is SyncHandshakeRejectedException -> return when (current.code) {
                "capability_mismatch" -> FailureKind.AppUpdateRequired
                "unauthenticated" -> FailureKind.SessionExpired
                "not_ready" -> FailureKind.HouseholdUnavailable
                else -> FailureKind.HouseholdStateChanged
            }
            is SyncHttpException -> {
                if (current.clientUpdateRequiredOrNull() != null) {
                    return FailureKind.AppUpdateRequired
                }
                if (syncHttpCodeOrNull(current.responseBody) == "invalid_stored_payload") {
                    return FailureKind.HouseholdStateChanged
                }
                return when (current.statusCode) {
                    401, 403 -> FailureKind.SessionExpired
                    408, 504 -> FailureKind.ResponseTimedOut
                    409 -> FailureKind.HouseholdStateChanged
                    422 -> if (isFormOrInviteValidation(current.responseBody)) {
                        FailureKind.InvalidInput
                    } else {
                        FailureKind.HouseholdStateChanged
                    }
                    429 -> FailureKind.TooFast
                    in 500..599 -> FailureKind.HouseholdUnavailable
                    else -> FailureKind.Unreachable
                }
            }
            is UnknownHostException -> return FailureKind.AddressNotFound
            is ConnectException,
            is NoRouteToHostException,
            -> return FailureKind.Unreachable
            is SocketTimeoutException -> return FailureKind.ResponseTimedOut
            is IOException -> return FailureKind.Unreachable
            is IllegalArgumentException,
            is IllegalStateException,
            -> {
                if (current.message?.contains("睡眠状态已变化") == true) {
                    return FailureKind.HouseholdStateChanged
                }
                if (isPullValidationStateFailure(current.message)) {
                    return FailureKind.HouseholdFactRejected
                }
                if (isReplicaCycleStateFailure(current.message)) {
                    return FailureKind.HouseholdStateChanged
                }
            }
        }
    }
    val text = error.message ?: ""
    return if (isFormOrInviteValidation(text)) {
        FailureKind.InvalidInput
    } else {
        FailureKind.UnexpectedError
    }
}

/** Pull or commit-first did not finish the captured dirty set. Room retains pending units. */
class ReplicaSyncNotConvergedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Replica cycle progress/state invariant failed (duplicate entities, stale or
 * non-advancing cursor, generation drift, budget exhaustion). Classified by
 * type, not message text; the legacy markers below remain a backstop only.
 */
class ReplicaCycleStateException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun isReplicaCycleStateFailure(message: String?): Boolean {
    val text = message ?: return false
    return REPLICA_CYCLE_STATE_MARKERS.any(text::contains)
}

internal fun isPullValidationStateFailure(message: String?): Boolean {
    val text = message ?: return false
    return PULL_VALIDATION_MARKERS.any(text::contains)
}

private val PULL_VALIDATION_MARKERS = listOf(
    "同步数据引用尚未就绪",
    "第一组闭包超过页预算",
    "第一组超过页预算",
    "超大第一组",
    "应用单元引用未就绪",
    "引用尚未就绪",
)

private val REPLICA_CYCLE_STATE_MARKERS = listOf(
    "重复返回实体",
    "家庭同步依赖在",
    "非当前同步代际",
    "倒退的同步 cursor",
    "分页 cursor 未推进",
    "非 current 实体类型",
    "家庭同步尚未收敛",
    "家庭服务器同步超过",
    "页上限",
    "page_index",
    "权威裁决证明无效",
)

private val FORM_INVITE_VALIDATION_MARKERS = listOf(
    "称呼",
    "display_name",
    "device_name",
    "invite",
    "邀请",
    "口令",
    "密码",
)

private fun isFormOrInviteValidation(body: String): Boolean =
    FORM_INVITE_VALIDATION_MARKERS.any(body::contains)

/** Incomplete replica cycle: continue in the foreground, never a form leftover. */
internal fun isIncompleteForegroundCycle(error: Throwable): Boolean {
    if (error is ReplicaSyncNotConvergedException) return true
    if (error is FamilyHttpException && error.kind == FamilyHttpFailureKind.SyncTookTooLong) {
        return true
    }
    return when (familyFailureKind(error)) {
        FailureKind.HouseholdStateChanged,
        FailureKind.HouseholdFactRejected,
        FailureKind.SyncTookTooLong,
        -> true
        else -> false
    }
}

internal fun isUnrecoverableForegroundStop(error: Throwable): Boolean {
    return when (familyFailureKind(error)) {
        FailureKind.SessionExpired,
        FailureKind.CertificateChanged,
        FailureKind.AppUpdateRequired,
        FailureKind.ServerUpdateRequired,
        FailureKind.QrExpired,
        FailureKind.QrWrongHousehold,
        FailureKind.ServerHasNoFamily,
        FailureKind.DeviceRemoved,
        -> true
        else -> false
    }
}

internal fun isTransientTransportFailure(error: Throwable): Boolean {
    return when (familyFailureKind(error)) {
        FailureKind.Unreachable,
        FailureKind.AddressNotFound,
        FailureKind.ResponseTimedOut,
        FailureKind.HouseholdUnavailable,
        FailureKind.SendStalled,
        -> true
        else -> false
    }
}

internal fun shouldContinueIncompleteForegroundCycle(
    error: Throwable,
    progressMade: Boolean,
): Boolean {
    if (isUnrecoverableForegroundStop(error)) return false
    if (isIncompleteForegroundCycle(error)) return true
    return progressMade && isTransientTransportFailure(error)
}

fun familyFailureKind(probe: SetupProbeResult.Failed): FailureKind = when (probe) {
    SetupProbeResult.Failed.AddressNotFound -> FailureKind.AddressNotFound
    SetupProbeResult.Failed.Unreachable -> FailureKind.Unreachable
    SetupProbeResult.Failed.ResponseTimedOut -> FailureKind.ResponseTimedOut
    SetupProbeResult.Failed.NotLezi -> FailureKind.InvalidInput
    SetupProbeResult.Failed.CertificateChanged -> FailureKind.CertificateChanged
    SetupProbeResult.Failed.Maintenance -> FailureKind.HouseholdUnavailable
    SetupProbeResult.Failed.InvalidAddress -> FailureKind.InvalidInput
    SetupProbeResult.Failed.Incompatible -> FailureKind.AppUpdateRequired
    SetupProbeResult.Failed.Unexpected -> FailureKind.UnexpectedError
}
