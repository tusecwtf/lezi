package com.lezi.babylog.domain.family
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.OwnerLoginResult
import com.lezi.babylog.sync.PendingMemberLogin
import com.lezi.babylog.sync.InitialFamilyDataRecovery
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncFamilyWizardGatewayTest {
    @Test
    fun createPreparesLocalScaffoldBeforeRemoteReclaim() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events)
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync)

        val result = gateway.createFamily(
            config = configuredEndpoint(),
            displayName = "妈妈",
            bootstrapSecret = "bootstrap-secret",
            familyName = "乐乐家",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(events).containsExactly("scaffold", "create", "cache:妈妈").inOrder()
    }

    @Test
    fun scaffoldFailurePreventsRemoteFamilyMutation() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events).apply {
            scaffoldFailure = IllegalStateException("database unavailable")
        }
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync)

        val result = gateway.createFamily(
            config = configuredEndpoint(),
            displayName = "妈妈",
            bootstrapSecret = "bootstrap-secret",
            familyName = null,
        )

        assertThat(result.isFailure).isTrue()
        assertThat(events).containsExactly("scaffold")
        assertThat(sync.createCalls).isEqualTo(0)
    }

    @Test
    fun ownerLoginPreparesScaffoldButNeverCachesARootOrMemberName() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events)
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync)

        val result = gateway.ownerLogin(
            config = configuredEndpoint(),
            deviceName = "Pixel",
            rootPassword = "root-password-secret",
            takeover = true,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(events).containsExactly("scaffold", "owner-login").inOrder()
        assertThat(sync.lastOwnerRootPassword).isEqualTo("root-password-secret")
        assertThat(sync.lastOwnerTakeover).isTrue()
    }

    @Test
    fun memberRequestKeepsExistingLocalScaffoldAndCallsSyncExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events)
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync)

        val result = gateway.requestMemberLogin(
            config = configuredEndpoint(),
            displayName = "爸爸",
            deviceName = "Pixel",
        )

        assertThat(result.getOrThrow()).isEqualTo(sync.pendingMemberLogin)
        assertThat(events).containsExactly("scaffold", "member-request").inOrder()
        assertThat(sync.memberRequestCalls).isEqualTo(1)
    }

    @Test
    fun retryReclaimedDataRecoverySchedulesReconcileWithoutWaitingForTheCycle() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events)
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync)

        val result = gateway.retryReclaimedDataRecovery()

        assertThat(result.isSuccess).isTrue()
        assertThat(events).containsExactly("request-sync")
        assertThat(sync.lastRequestSyncTrigger).isEqualTo(SyncTrigger.PullToRefresh)
        assertThat(sync.syncWhenAvailableCalls).isEqualTo(0)
    }
}

private class RecordingFamilyWizardLocalStore(
    private val events: MutableList<String>,
) : FamilyWizardLocalStore {
    var scaffoldFailure: Throwable? = null

    override suspend fun ensureScaffold() {
        events += "scaffold"
        scaffoldFailure?.let { throw it }
    }

    override suspend fun cacheDisplayName(displayName: String) {
        events += "cache:$displayName"
    }
}

private class RecordingCreateSyncPort(
    private val events: MutableList<String>,
) : SyncPort by NoOpSyncPort() {
    var createCalls = 0
    var lastOwnerRootPassword: String? = null
    var lastOwnerTakeover = false
    var memberRequestCalls = 0
    var syncWhenAvailableCalls = 0
    var lastRequestSyncTrigger: SyncTrigger? = null
    val pendingMemberLogin = PendingMemberLogin(
        requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        displayName = "爸爸",
        deviceName = "Pixel",
        expiresAtEpochSeconds = 1_753_504_800,
    )

    override suspend fun createFamily(
        displayName: String,
        deviceName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        events += "create"
        createCalls += 1
        return Result.success(
            CreateFamilyResult(
                session = SyncSession(
                    familyId = "family-owner",
                    accessToken = "owner-token",
                    role = FamilyRole.Owner,
                    membershipId = "owner-membership",
                ),
                reclaimed = true,
            ),
        )
    }

    override suspend fun ownerLogin(
        deviceName: String,
        rootPassword: String,
        takeover: Boolean,
        candidateBaseUrl: String?,
    ): Result<OwnerLoginResult> {
        events += "owner-login"
        lastOwnerRootPassword = rootPassword
        lastOwnerTakeover = takeover
        return Result.success(
            OwnerLoginResult(
                session = SyncSession(
                    familyId = "family-owner",
                    accessToken = "owner-token",
                    role = FamilyRole.Owner,
                    membershipId = "owner-membership",
                ),
                dataRecovery = InitialFamilyDataRecovery.Complete,
            ),
        )
    }

    override fun requestSync(trigger: SyncTrigger) {
        events += "request-sync"
        lastRequestSyncTrigger = trigger
    }

    override suspend fun syncWhenAvailable(trigger: SyncTrigger): Result<Unit> {
        events += "sync-when-available"
        syncWhenAvailableCalls += 1
        awaitCancellation()
    }

    override suspend fun requestMemberLogin(
        displayName: String,
        deviceName: String,
    ): Result<PendingMemberLogin> {
        events += "member-request"
        memberRequestCalls += 1
        return Result.success(
            pendingMemberLogin.copy(displayName = displayName, deviceName = deviceName),
        )
    }
}

private fun configuredEndpoint() = FamilyEndpointConfig(
    host = "nas.home",
)
