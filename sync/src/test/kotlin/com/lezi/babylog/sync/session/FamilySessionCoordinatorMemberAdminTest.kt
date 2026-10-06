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

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySessionCoordinatorMemberAdminTest {
    @Test
    fun ownerCanRemoveAnotherMember() = runTest {
        val session = joinedFamilySession(role = FamilyRole.Owner).copy(
            membershipId = "owner-m",
        )
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(session),
            backend = backend,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.RemoveMember("member-m"),
        ).getOrThrow()

        assertThat(outcome).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(backend.removedMembershipIds).containsExactly("member-m")
    }

    @Test
    fun ownerCannotRemoveSelf() = runTest {
        val session = joinedFamilySession(role = FamilyRole.Owner).copy(
            membershipId = "owner-m",
        )
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(session),
            backend = backend,
        )

        val failure = coordinator.execute(
            FamilySessionCommand.RemoveMember("owner-m"),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("自己")
        assertThat(backend.removedMembershipIds).isEmpty()
    }

    @Test
    fun memberCannotRemoveOthers() = runTest {
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                joinedFamilySession(role = FamilyRole.Member).copy(membershipId = "member-m"),
            ),
            backend = backend,
        )

        val failure = coordinator.execute(
            FamilySessionCommand.RemoveMember("other-m"),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("管理员")
        assertThat(backend.removedMembershipIds).isEmpty()
    }

    @Test
    fun membersValidatesCurrentSelfMembershipThroughTheReplicaSeam() = runTest {
        val previous = joinedFamilySession().copy(membershipId = "membership-self")
        val preferences = MemorySyncPreferences(previous)
        val members = listOf(
            FamilyMember(
                displayName = "妈妈",
                role = FamilyRole.Owner,
                isSelf = true,
                membershipId = "membership-self",
            ),
            FamilyMember(
                displayName = "爸爸",
                role = FamilyRole.Member,
                isSelf = false,
                membershipId = "membership-peer",
            ),
        )
        val backend = RecordingSyncBackend().apply {
            nextMembers = members
        }
        val observed = mutableListOf<SyncSession>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            replica = RecordingFamilySessionReplica(
                onPersistMembership = { session, projected ->
                    assertThat(session).isEqualTo(previous)
                    assertThat(projected).containsExactlyElementsIn(members).inOrder()
                    session
                },
            ),
            onSessionObserved = { observed += it },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.ListMembers,
        ).getOrThrow()

        assertThat(outcome).isEqualTo(
            FamilySessionOutcome.MembersListed("directory-test", members),
        )
        assertThat(preferences.current().membershipId).isEqualTo("membership-self")
        assertThat(observed)
            .containsExactly(previous, previous)
            .inOrder()
        assertThat(backend.memberCalls).isEqualTo(1)
    }

    @Test
    fun ownerRenamePersistsTheNormalizedFamilyNameAfterBackendSuccess() = runTest {
        val previous = joinedFamilySession().copy(familyName = "旧家庭名")
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend()
        val observed = mutableListOf<SyncSession>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            onSessionChanged = { error("rename is not a session transition") },
            onSessionObserved = { observed += it },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.RenameFamily("  新家庭名  "),
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(backend.renamedFamilyNames).containsExactly("新家庭名")
        assertThat(preferences.current().familyName).isEqualTo("新家庭名")
        assertThat(observed)
            .containsExactly(previous, preferences.current())
            .inOrder()
    }

    @Test
    fun ownerRenameRejectsBlankNameBeforeBackendSoDeleteConfirmationStaysReachable() = runTest {
        val previous = joinedFamilySession().copy(familyName = "旧家庭名")
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val result = coordinator.execute(FamilySessionCommand.RenameFamily("  "))

        assertThat(result.isFailure).isTrue()
        assertThat(backend.renamedFamilyNames).isEmpty()
        assertThat(preferences.current()).isEqualTo(previous)
    }

    @Test
    fun memberCanUpdateOnlyTheAuthenticatedMembershipDisplayName() = runTest {
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                joinedFamilySession(role = FamilyRole.Member),
            ),
            backend = backend,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.UpdateMyDisplayName("  干爹  "),
        )

        assertThat(outcome.getOrThrow()).isEqualTo(
            FamilySessionOutcome.DisplayNameUpdateCompleted(
                DisplayNameUpdateResult.Updated("干爹"),
            ),
        )
        assertThat(backend.updatedDisplayNames).containsExactly("干爹")
    }

    @Test
    fun memberLeaveOnlyConfirmsRemoteDeleteBeforeOuterCrashSafeCleanup() = runTest {
        val previous = joinedFamilySession(role = FamilyRole.Member)
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend()
        backend.onLeave = {
            assertThat(preferences.current()).isEqualTo(previous)
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.Leave,
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(preferences.current()).isEqualTo(previous)
    }

    @Test
    fun ownerDeleteOnlyConfirmsRemoteBeforeOuterCrashSafeClear() = runTest {
        val previous = joinedFamilySession(role = FamilyRole.Owner)
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend()
        val events = mutableListOf<String>()
        backend.onDeleteFamily = {
            assertThat(preferences.current()).isEqualTo(previous)
            events += "remote-deleted"
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.DeleteFamily("  乐乐一家  ", "root-password-secret"),
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events).containsExactly("remote-deleted")
        assertThat(preferences.current()).isEqualTo(previous)
        assertThat(backend.deletedFamilyConfirmations)
            .containsExactly("乐乐一家" to "root-password-secret")
    }

    @Test
    fun memberCannotDeleteTheFamily() = runTest {
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                joinedFamilySession(role = FamilyRole.Member),
            ),
            backend = backend,
        )

        val failure = coordinator.execute(
            FamilySessionCommand.DeleteFamily("乐乐一家", "root-password-secret"),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("管理员")
        assertThat(backend.deleteFamilyCalls).isEqualTo(0)
    }

    @Test
    fun ownerFamilyDeleteRequiresExactNameAndNonBlankRootBeforeBackend() = runTest {
        val backend = RecordingSyncBackend()
        val preferences = MemorySyncPreferences(joinedFamilySession())
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val wrongName = coordinator.execute(
            FamilySessionCommand.DeleteFamily("乐乐二家", "root-password-secret"),
        ).exceptionOrNull()
        val blankRoot = coordinator.execute(
            FamilySessionCommand.DeleteFamily("乐乐一家", "   "),
        ).exceptionOrNull()

        assertThat(wrongName).hasMessageThat().contains("家庭名")
        assertThat(blankRoot).hasMessageThat().contains("根密码")
        assertThat(backend.deleteFamilyCalls).isEqualTo(0)
        assertThat(preferences.current()).isEqualTo(joinedFamilySession())
    }

    @Test
    fun generic401CannotPretendMemberWasHardDeleted() = runTest {
        val preferences = MemorySyncPreferences(
            joinedFamilySession(role = FamilyRole.Member),
        )
        val backend = RecordingSyncBackend().apply {
            leaveFailure = SyncHttpException(401)
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)

        assertThat(coordinator.execute(FamilySessionCommand.Leave).isFailure).isTrue()
        assertThat(preferences.current().isJoined).isTrue()
    }

    @Test
    fun routeNotFoundNeverErasesTheOnlyOwnerCredential() = runTest {
        val previous = joinedFamilySession(role = FamilyRole.Owner)
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            deleteFailure = SyncHttpException(404)
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)

        assertThat(
            coordinator.execute(
                FamilySessionCommand.DeleteFamily("乐乐一家", "root-password-secret"),
            ).isFailure,
        ).isTrue()
        assertThat(preferences.current()).isEqualTo(previous)
    }

    @Test
    fun ownerCannotLeaveAndMemberCannotRenameTheSharedFamily() = runTest {
        val ownerBackend = RecordingSyncBackend()
        val owner = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = ownerBackend,
        )
        val memberBackend = RecordingSyncBackend()
        val member = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Member)),
            backend = memberBackend,
        )

        assertThat(
            owner.execute(FamilySessionCommand.Leave).exceptionOrNull(),
        ).hasMessageThat().contains("管理员")
        assertThat(
            member.execute(FamilySessionCommand.RenameFamily("新名字")).exceptionOrNull(),
        ).hasMessageThat().contains("管理员")
        assertThat(ownerBackend.leaveFailure).isNull()
        assertThat(memberBackend.renamedFamilyNames).isEmpty()
    }

    @Test
    fun ownerRevokesAnyDeviceWhileBothRolesCanLogoutOnlyTheirCurrentDevice() = runTest {
        val ownerPreferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner))
        val ownerBackend = RecordingSyncBackend()
        val owner = coordinator(ownerPreferences, ownerBackend)
        val memberPreferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Member))
        val memberBackend = RecordingSyncBackend()
        val member = coordinator(memberPreferences, memberBackend)

        assertThat(
            owner.execute(FamilySessionCommand.RevokeFamilyDevice("device-remote")).isSuccess,
        ).isTrue()
        assertThat(
            member.execute(FamilySessionCommand.RevokeFamilyDevice("device-remote")).isFailure,
        ).isTrue()
        assertThat(ownerBackend.revokedDeviceIds).containsExactly("device-remote")
        assertThat(memberBackend.revokedDeviceIds).isEmpty()

        assertThat(owner.execute(FamilySessionCommand.LogoutCurrentDevice).isSuccess).isTrue()
        assertThat(member.execute(FamilySessionCommand.LogoutCurrentDevice).isSuccess).isTrue()
        assertThat(ownerBackend.deviceLogoutCalls).isEqualTo(1)
        assertThat(memberBackend.deviceLogoutCalls).isEqualTo(1)
        assertThat(ownerPreferences.current().isJoined).isTrue()
        assertThat(memberPreferences.current().isJoined).isTrue()
    }
}
