package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.sync.session.*
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RestoreSourceRelationFlowTest {
    @Test(timeout = 30_000)
    fun selectedDisplaySurvivesPublicCaptureAndAuthorityActivation() = runBlocking {
        val directory = Files.createTempDirectory("restore-relation-public").toFile()
        try {
            val rig = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val wire = rig.backend.disasterRestoreManifestRelations.single().single()
            assertThat(wire.displayClientUuid).isEqualTo(B)
            assertThat(wire.sourceClientUuids).containsExactly(A)
            rig.foreground.setForeground(false)
            rig.port.commitDisasterRecovery("root").getOrThrow()
            assertThat(rig.sourceRelations.listAllMembers().map { it.recordClientUuid to it.role })
                .containsExactly(A to "source", B to "display")
            assertThat(rig.sourceRelations.get(RELATION)?.displayClientUuid).isEqualTo(B)
            assertThat(rig.records.getByClientUuid(A)?.deletedAt).isNull()
            assertThat(rig.records.getByClientUuid(B)?.deletedAt).isNull()
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun switchedRelationsResumeLocalPublicationWithoutRecheckingOldProjectionOrRemotePassword() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("restore-relation-switched-retry").toFile()
        try {
            val rig = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            rig.foreground.setForeground(false)
            rig.preferences.failCompleteRestoreSessionAttempts = 1
            assertThat(rig.port.commitDisasterRecovery("root").isFailure).isTrue()
            assertThat(rig.sourceRelations.get(RELATION)?.displayClientUuid).isEqualTo(B)
            rig.backend.disasterRestoreStatusFailure = java.io.IOException("must not query after switch")
            rig.backend.disasterRestoreCommitFailure = java.io.IOException("must not repost")
            assertThat(rig.port.resumeDisasterRecovery().getOrThrow().localActivationReady).isTrue()
            rig.port.commitDisasterRecovery("").getOrThrow()
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).hasSize(1)
            assertThat(rig.sourceRelations.listAllMembers().map { it.recordClientUuid to it.role })
                .containsExactly(A to "source", B to "display")
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 30_000)
    fun changedSelectionStopsBeforeRemoteActivationAndPreservesBothIntents() = runBlocking {
        val directory = Files.createTempDirectory("restore-relation-changed").toFile()
        try {
            val rig = seeded(directory)
            rig.port.startDisasterRecovery(ENDPOINT, "Owner", "Phone", "root").getOrThrow()
            val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
            select(rig, A, B)
            val outcome = rig.port.commitDisasterRecovery("root")
            assertThat(outcome.exceptionOrNull()).hasMessageThat().contains("来源关系在恢复快照之后发生变化")
            assertThat(rig.backend.disasterRestoreCommitRootPasswords).isEmpty()
            assertThat(rig.sourceRelations.get(RELATION)?.displayClientUuid).isEqualTo(A)
            assertThat(rig.backend.disasterRestoreManifestRelations.single().single().displayClientUuid).isEqualTo(B)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isEqualTo(checkpoint)
            Unit
        } finally { directory.deleteRecursively() }
    }

    private suspend fun seeded(directory: java.io.File): SyncRig {
        val rig = SyncRig(joinedSession("family-a"), appUpdateCacheDir = directory,
            setupProbe = SetupProbe { _, trusted -> SetupProbeResult.Ready(requireNotNull(trusted),
                SetupFamilyState.Empty, setOf("nursing_plan_intent_v1", "restore_authority_v1")) })
        rig.awaitStartupRecovery()
        val baby = rig.babies.seed(localBaby().copy(clientUuid = BABY))
        rig.records.seed(localRecord(baby).copy(clientUuid = A))
        rig.records.seed(localRecord(baby).copy(clientUuid = B, timestamp = 10_000_000L))
        select(rig, B, A)
        return rig
    }

    private suspend fun select(rig: SyncRig, display: String, source: String) {
        rig.sourceRelations.applyOwnerGroupResolution(SourceRelationEntity(RELATION, display, true,
            SourceRelationReason.OWNER_GROUP_RESOLVE, "choice-$display", "member-old", 123), listOf(
            SourceRelationMemberEntity(RELATION, display, "display"),
            SourceRelationMemberEntity(RELATION, source, "source")))
    }

    companion object {
        private val ENDPOINT = TrustedEndpointProfile.systemPki("https://replacement.example.test")
        private const val BABY = "11111111-1111-4111-8111-111111111111"
        private const val A = "22222222-2222-4222-8222-222222222222"
        private const val B = "33333333-3333-4333-8333-333333333333"
        private const val RELATION = "44444444-4444-4444-8444-444444444444"
    }
}
