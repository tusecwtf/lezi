package com.lezi.babylog.sync.availability

import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.media.MediaPrepareException
import com.lezi.babylog.sync.session.SpkiPinMismatchException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Failure classification shared by the two `FamilyServerAvailability` feeders —
 * the anonymous setup-status/health probe (RealSyncPort) and the 0.4.8
 * authenticated foreground heartbeat (SyncHeartbeatEngine) — so one transport
 * failure has exactly one availability meaning regardless of who observed it.
 */

internal inline fun <reified T : Throwable> Throwable.causeChainContains(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

internal fun Throwable.toAvailabilityUnavailableReason(): FamilyServerUnavailableReason = when {
    causeChainContains<SpkiPinMismatchException>() -> FamilyServerUnavailableReason.TrustChanged
    this is FamilyHttpException && this.kind == FamilyHttpFailureKind.ResponseTimedOut ->
        FamilyServerUnavailableReason.ResponseTimedOut
    this is TimeoutCancellationException -> FamilyServerUnavailableReason.ResponseTimedOut
    this is SyncHttpException && statusCode >= 500 -> FamilyServerUnavailableReason.Maintenance
    this is SyncHttpException && statusCode == 404 -> FamilyServerUnavailableReason.NotLezi
    this is IllegalArgumentException -> FamilyServerUnavailableReason.Incompatible
    else -> FamilyServerUnavailableReason.Unreachable
}

internal fun Throwable.isAvailabilityTransportFailure(): Boolean = when {
    causeChainContains<MediaPrepareException>() -> false
    this is SyncHttpException -> statusCode == 408 || statusCode in 500..599
    else -> causeChainContains<IOException>()
}
