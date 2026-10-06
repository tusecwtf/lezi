package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.BootstrapSecretRejectedException
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.MemberLoginCheckResult
import com.lezi.babylog.sync.OwnerRootPasswordRejectedException
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.DisplayNameUpdateResult
import com.lezi.babylog.sync.backend.MemberLoginReceipt
import com.lezi.babylog.sync.backend.MemberLoginStatus
import com.lezi.babylog.sync.backend.PendingMemberLoginRequest
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.qr.MemberLoginQrPayload
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.RecordingSyncBackend

internal fun coordinator(
    preferences: MemorySyncPreferences,
    backend: RecordingSyncBackend = RecordingSyncBackend(),
    replica: RecordingFamilySessionReplica = RecordingFamilySessionReplica(),
    barrier: Mutex = Mutex(),
    requireRemoteAllowed: suspend (FamilyEndpointConfig) -> Unit = {},
    onSessionChanged: (SyncSession) -> Unit = {},
    onSessionObserved: (SyncSession) -> Unit = {},
    requestSync: (SyncTrigger) -> Unit = {},
    launchBestEffort: ((suspend () -> Unit) -> Unit) = {},
): FamilySessionCoordinator = FamilySessionCoordinator(
    backend = backend,
    preferences = preferences,
    replica = replica,
    barrier = barrier,
    requireRemoteAllowed = requireRemoteAllowed,
    onSessionChanged = onSessionChanged,
    onSessionObserved = onSessionObserved,
    requestSync = requestSync,
    launchBestEffort = launchBestEffort,
)

internal class RecordingFamilySessionReplica(
    internal val onReset: suspend (SyncSession) -> Unit = {},
    internal val onApply: suspend (SyncSession, List<SyncEntity>) -> Unit = { _, _ -> },
    internal val onPersistMembership:
        suspend (SyncSession, List<FamilyMember>) -> SyncSession = { session, _ -> session },
) : FamilySessionReplica {
    val resetCalls = mutableListOf<ReceiptResetCall>()

    override suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean,
        crossingFamilyBoundary: Boolean,
        recoveryTarget: FamilySessionReplica.RecoveryTarget?,
    ): FamilySessionReplica.ResetReceipt {
        resetCalls += ReceiptResetCall(
            previous = previous,
            invalidateCurrentReceipts = invalidateCurrentReceipts,
            crossingFamilyBoundary = crossingFamilyBoundary,
        )
        onReset(previous)
        return FamilySessionReplica.ResetReceipt(
            previousFamilyId = previous.familyId,
            previousMembershipId = previous.membershipId,
            previousDeviceId = previous.deviceId,
            crossingFamilyBoundary = crossingFamilyBoundary,
            recoveryTarget = recoveryTarget,
            roots = emptyList(),
        )
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
        resetReceipt: FamilySessionReplica.ResetReceipt?,
    ) {
        onApply(session, entities)
    }

    override suspend fun completeLocalSyncReset(receipt: FamilySessionReplica.ResetReceipt) = Unit

    override suspend fun convergeAuthenticatedSelfMembership(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession = onPersistMembership(session, members)
}

internal data class ReceiptResetCall(
    val previous: SyncSession,
    val invalidateCurrentReceipts: Boolean,
    val crossingFamilyBoundary: Boolean,
)

internal fun joinedFamilySession(
    role: FamilyRole = FamilyRole.Owner,
): SyncSession = SyncSession(
    familyId = "family-a",
    accessToken = "token-a",
    deviceId = "device-a",
    role = role,
    pullCursor = 0,
    pullGeneration = "generation-a",
    serverHost = "192.168.1.20",
    serverPort = 8787,
    familyName = "乐乐一家",
)
