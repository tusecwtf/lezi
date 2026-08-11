package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.sourceCausalHandshake
import com.lezi.babylog.sync.backend.REQUIRED_CAUSAL_WIRE_CAPABILITIES
import com.lezi.babylog.sync.backend.SyncHandshakePrincipal
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Public seam: one [ReplicaSyncEngine.synchronize] foreground cycle. */
class ReplicaSyncEngineHandshakeTest {

    @Test
    fun unchangedDirectoryUsesOneHandshakeWithoutDownloadingMembers() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session).also {
            it.backend.enableCausal = true
            it.backend.nextHandshake = handshake(session, directoryGeneration = "directory-a")
            it.preferences.seedFamilyMemberDirectory(
                generation = "directory-a",
                members = listOf(selfMember(session)),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.memberCalls).isEqualTo(0)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(0)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(0)
        assertThat(rig.backend.syncOrder.first()).isEqualTo("handshake")
    }

    @Test
    fun changedDirectoryRefreshesOnceAndPersistsMatchingGeneration() = runTest {
        val session = joinedReplicaSession()
        val peer = FamilyMember(
            displayName = "爸爸",
            role = FamilyRole.Member,
            isSelf = false,
            membershipId = "membership-peer",
        )
        val rig = ReplicaEngineRig(session).also {
            it.backend.enableCausal = true
            it.backend.nextHandshake = handshake(session, directoryGeneration = "directory-b")
            it.backend.nextDirectoryGeneration = "directory-b"
            it.backend.nextMembers = listOf(selfMember(session), peer)
            it.preferences.seedFamilyMemberDirectory(
                generation = "directory-a",
                members = listOf(selfMember(session)),
            )
        }

        rig.engine.synchronize(session, SyncTrigger.Foreground)

        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.memberCalls).isEqualTo(1)
        assertThat(rig.preferences.familyMemberDirectoryGeneration.value)
            .isEqualTo("directory-b")
        assertThat(rig.preferences.familyMemberDirectory.first())
            .containsExactly(selfMember(session), peer)
            .inOrder()
    }

    @Test
    fun terminalHandshakeFailuresStopBeforePullOrMutation() = runTest {
        val session = joinedReplicaSession()
        for (code in listOf("unauthenticated", "not_ready", "capability_mismatch")) {
            val rig = ReplicaEngineRig(session).also {
                it.backend.enableCausal = true
                it.backend.handshakeFailure = SyncHandshakeRejectedException(code)
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.Foreground)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncHandshakeRejectedException::class.java)
            assertThat((failure as SyncHandshakeRejectedException).code).isEqualTo(code)
            assertThat(rig.backend.handshakeCalls).isEqualTo(1)
            assertThat(rig.backend.memberCalls).isEqualTo(0)
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(rig.backend.causalReconciledUnits).isEmpty()
            assertThat(rig.backend.reconciledUnits).isEmpty()
        }
    }

    @Test
    fun principalMismatchStopsBeforeDirectoryOrReplicaWork() = runTest {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session).also {
            it.backend.enableCausal = true
            it.backend.nextHandshake = handshake(session, directoryGeneration = "directory-a")
                .copy(
                    principal = SyncHandshakePrincipal(
                        membershipId = "membership-other",
                        deviceId = session.deviceId,
                        role = session.role,
                    ),
                )
        }

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.Foreground)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHandshakeRejectedException::class.java)
        assertThat(rig.backend.memberCalls).isEqualTo(0)
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun extraOrPrematureV2CapabilityStopsBeforeDirectoryOrReplicaWork() = runTest {
        val session = joinedReplicaSession()
        for (extra in listOf("future_extra", "causal_sync_v2")) {
            val rig = ReplicaEngineRig(session).also {
                it.backend.enableCausal = true
                it.backend.nextHandshake = handshake(session, directoryGeneration = "directory-a")
                    .copy(capabilities = REQUIRED_CAUSAL_WIRE_CAPABILITIES + extra)
            }

            val failure = runCatching {
                rig.engine.synchronize(session, SyncTrigger.Foreground)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncHandshakeRejectedException::class.java)
            assertThat((failure as SyncHandshakeRejectedException).code)
                .isEqualTo("capability_mismatch")
            assertThat(rig.backend.memberCalls).isEqualTo(0)
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
        }
    }

    private fun handshake(
        session: com.lezi.babylog.sync.session.SyncSession,
        directoryGeneration: String,
    ) = sourceCausalHandshake(
        principal = SyncHandshakePrincipal(
            membershipId = session.membershipId,
            deviceId = session.deviceId,
            role = session.role,
        ),
        directoryGeneration = directoryGeneration,
    )

    private fun selfMember(session: com.lezi.babylog.sync.session.SyncSession) = FamilyMember(
        displayName = "管理员",
        role = session.role,
        isSelf = true,
        membershipId = session.membershipId,
    )
}
