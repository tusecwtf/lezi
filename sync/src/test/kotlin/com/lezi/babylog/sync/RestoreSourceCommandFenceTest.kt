package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.*
import com.lezi.babylog.sync.session.*
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class RestoreSourceCommandFenceTest {
    @Test(timeout = 30_000)
    fun startedCommandFinishesCanonicalSettlementBeforeRestoreCapture() = runBlocking {
        val directory = Files.createTempDirectory("restore-command-fence").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val transport = RecordingSyncBackend()
            val backend = object : SyncBackend by transport {
                override suspend fun resolveSourceRelationGroup(session: SyncSession, request: SourceRelationResolveGroupRequest) =
                    SourceRelationResult("accepted", RELATION, B, listOf(A), true)
                override suspend fun readCurrentSourceRelations(session: SyncSession, request: CurrentSourceRelationsRequest) =
                    CurrentSourceRelationsSnapshot(session.familyId, session.pullGeneration, session.pullCursor,
                        request.recordClientUuids, listOf(CurrentSourceRelationRecord(A, "live", RELATION),
                            CurrentSourceRelationRecord(B, "live", RELATION)),
                        listOf(CurrentSourceRelationGroup(RELATION, B, listOf(A))))
            }
            val rig = seeded(directory, backend)
            rig.sourceRelations.beforeCanonicalRelationWrite = {
                entered.complete(Unit)
                release.await()
            }
            val command = async { rig.port.resolveSourceRelationGroup(request()) }
            withTimeout(5_000) { entered.await() }
            val restore = async { rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root") }
            yield()
            assertThat(transport.disasterRestoreStartRequestIds).isEmpty()
            release.complete(Unit)
            assertThat(command.await().status).isEqualTo("accepted")
            restore.await().getOrThrow()
            assertThat(transport.disasterRestoreManifestRelations.single().single().displayClientUuid).isEqualTo(B)
            assertThat(rig.sourceRelations.listMembers(RELATION).map { it.recordClientUuid to it.role })
                .containsExactly(A to "source", B to "display")
            Unit
        } finally { release.complete(Unit); directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun preparedRestoreRejectsNewSourceCommandBeforeNetworkOrJournal() = runBlocking {
        val directory = Files.createTempDirectory("restore-command-block").toFile()
        try {
            var commands = 0
            val transport = RecordingSyncBackend()
            val backend = object : SyncBackend by transport {
                override suspend fun resolveSourceRelationGroup(session: SyncSession, request: SourceRelationResolveGroupRequest): SourceRelationResult {
                    commands += 1
                    return SourceRelationResult("accepted", RELATION, B, listOf(A), true)
                }
            }
            val rig = seeded(directory, backend)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val result = runCatching { rig.port.resolveSourceRelationGroup(request()) }
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("恢复正在进行")
            assertThat(commands).isEqualTo(0)
            assertThat(rig.conflictDetails.getTransportJournal("source-relation-command-v1")).isNull()
            assertThat(rig.sourceRelations.listAllMembers()).isEmpty()
            Unit
        } finally { directory.deleteRecursively() }
    }

    private suspend fun seeded(directory: java.io.File, backend: SyncBackend): SyncRig {
        val rig = SyncRig(joinedSession("family-a"), syncBackend = backend, appUpdateCacheDir = directory,
            setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
        rig.awaitStartupRecovery()
        val baby = rig.babies.seed(localBaby().copy(clientUuid = BABY))
        rig.records.seed(localRecord(baby).copy(clientUuid = A, baseVersion = "v-a"))
        rig.records.seed(localRecord(baby).copy(clientUuid = B, baseVersion = "v-b"))
        return rig
    }
    private fun request() = SourceRelationResolveGroupRequest("operation-1", listOf(A, B), B, mapOf(A to "v-a", B to "v-b"))
    companion object {
        private val ENDPOINT = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        private const val BABY = "11111111-1111-4111-8111-111111111111"
        private const val A = "22222222-2222-4222-8222-222222222222"
        private const val B = "33333333-3333-4333-8333-333333333333"
        private const val RELATION = "44444444-4444-4444-8444-444444444444"
    }
}
