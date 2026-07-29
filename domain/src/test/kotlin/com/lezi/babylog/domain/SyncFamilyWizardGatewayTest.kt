package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.CreateFamilyResult
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.HomeLanServerConfig
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncFamilyWizardGatewayTest {
    @Test
    fun createPreparesLocalScaffoldBeforeRemoteReclaim() = runTest {
        val events = mutableListOf<String>()
        val local = RecordingFamilyWizardLocalStore(events)
        val sync = RecordingCreateSyncPort(events)
        val gateway = SyncFamilyWizardGateway(local, sync, unusedJoinFamily())

        val result = gateway.createFamily(
            config = configuredHomeLan(),
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
        val gateway = SyncFamilyWizardGateway(local, sync, unusedJoinFamily())

        val result = gateway.createFamily(
            config = configuredHomeLan(),
            displayName = "妈妈",
            bootstrapSecret = "bootstrap-secret",
            familyName = null,
        )

        assertThat(result.isFailure).isTrue()
        assertThat(events).containsExactly("scaffold")
        assertThat(sync.createCalls).isEqualTo(0)
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

    override suspend fun createFamily(
        displayName: String,
        bootstrapSecret: String,
        familyName: String?,
    ): Result<CreateFamilyResult> {
        events += "create"
        createCalls += 1
        return Result.success(
            CreateFamilyResult(
                session = SyncSession(
                    familyId = "family-owner",
                    familyToken = "owner-token",
                    role = FamilyRole.Owner,
                    membershipId = "owner-membership",
                ),
                reclaimed = true,
            ),
        )
    }
}

private fun unusedJoinFamily(): JoinFamilyUseCase = object : JoinFamilyUseCase {
    override suspend fun execute(request: JoinFamilyRequest): JoinFamilyResult =
        error("join must not be called")
}

private fun configuredHomeLan() = HomeLanServerConfig(
    host = "nas.home",
    allowedSsids = listOf("Home"),
)
