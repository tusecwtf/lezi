package com.lezi.babylog.sync.backend.deadline

import com.lezi.babylog.core.common.failure.FailureKind
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Spec 0.4.4 network 细类 for later dialog wiring.
 * [HouseholdSyncing] is the session-lock miss, not an HTTP classify outcome.
 * Callers must use [kind], never [cause] English text, on a product surface.
 */
enum class FamilyHttpFailureKind {
    AddressNotFound,
    Unreachable,
    SendStalled,
    ResponseTimedOut,
    SyncTookTooLong,
    HouseholdSyncing,
    ;

    internal val isImmediateRetryable: Boolean
        get() = this == Unreachable || this == ResponseTimedOut
}

fun FamilyHttpFailureKind.toCatalogKind(): FailureKind = when (this) {
    FamilyHttpFailureKind.AddressNotFound -> FailureKind.AddressNotFound
    FamilyHttpFailureKind.Unreachable -> FailureKind.Unreachable
    FamilyHttpFailureKind.SendStalled -> FailureKind.SendStalled
    FamilyHttpFailureKind.ResponseTimedOut -> FailureKind.ResponseTimedOut
    FamilyHttpFailureKind.SyncTookTooLong -> FailureKind.SyncTookTooLong
    FamilyHttpFailureKind.HouseholdSyncing -> FailureKind.HouseholdSyncing
}

class FamilyHttpException(
    val kind: FamilyHttpFailureKind,
    cause: Throwable? = null,
) : IOException("family-http:${kind.name}", cause)

internal class FamilyHttpWriteStallException(
    timeoutMillis: Long,
    cause: Throwable? = null,
) : SocketTimeoutException("家庭服务器写入超过 ${timeoutMillis}ms 无进展") {
    init {
        if (cause != null) initCause(cause)
    }
}

internal class FamilyHttpConnectTimeoutException(
    cause: Throwable? = null,
) : SocketTimeoutException("家庭服务器连接超时") {
    init {
        if (cause != null) initCause(cause)
    }
}

internal fun classifyFamilyHttpFailure(
    failure: Throwable,
    operation: FamilyHttpOperation? = null,
): FamilyHttpException {
    if (failure is FamilyHttpException) return failure
    val kind = familyHttpFailureKindOf(failure, operation)
    return FamilyHttpException(kind, failure)
}

internal fun HttpURLConnection.connectFamilyHttp() {
    try {
        connect()
    } catch (error: SocketTimeoutException) {
        if (error is FamilyHttpWriteStallException || error is FamilyHttpConnectTimeoutException) {
            throw error
        }
        throw FamilyHttpConnectTimeoutException(error)
    }
}

internal fun familyHttpFailureKindOf(
    failure: Throwable,
    operation: FamilyHttpOperation? = null,
): FamilyHttpFailureKind {
    generateSequence(failure) { it.cause }.forEach { current ->
        when (current) {
            is FamilyHttpException -> return current.kind
            is FamilyHttpWriteStallException -> return FamilyHttpFailureKind.SendStalled
            is FamilyHttpConnectTimeoutException -> return FamilyHttpFailureKind.Unreachable
            is UnknownHostException -> return FamilyHttpFailureKind.AddressNotFound
            is ConnectException,
            is NoRouteToHostException,
            -> return FamilyHttpFailureKind.Unreachable
            is SocketTimeoutException -> {
                return if (operation == FamilyHttpOperation.MediaGet) {
                    FamilyHttpFailureKind.SyncTookTooLong
                } else {
                    FamilyHttpFailureKind.ResponseTimedOut
                }
            }
        }
    }
    return when (operation) {
        FamilyHttpOperation.MediaGet -> FamilyHttpFailureKind.SyncTookTooLong
        else -> FamilyHttpFailureKind.Unreachable
    }
}
