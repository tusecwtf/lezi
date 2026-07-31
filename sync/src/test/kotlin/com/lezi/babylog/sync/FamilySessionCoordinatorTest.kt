package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySessionCoordinatorTest {
    @Test
    fun serverChangeInvalidatesOldReceiptsAndCredentialsBeforePublishingTheNewEndpoint() = runTest {
        val previous = joinedFamilySession().copy(
            pullCursor = 9,
            pullGeneration = "generation-a",
        )
        val preferences = MemorySyncPreferences(previous)
        val events = mutableListOf<String>()
        val replica = RecordingFamilySessionReplica(
            onReset = { session ->
                assertThat(session).isEqualTo(previous)
                assertThat(preferences.current()).isEqualTo(previous)
                events += "receipts-reset"
            },
        )
        val coordinator = FamilySessionCoordinator(
            backend = RecordingSyncBackend(),
            preferences = preferences,
            outboxDao = MemoryOutboxDao(),
            replica = replica,
            barrier = Mutex(),
            requireRemoteAllowed = {},
            onSessionChanged = {
                events += "session-published"
            },
            onSessionObserved = {},
            requestSync = {},
            recoverReclaimedSession = { InitialFamilyDataRecovery.NotRequired },
        )

        val result = coordinator.execute(
            FamilySessionCommand.SaveServer("https://192.168.1.99:8765"),
        )

        assertThat(result.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events).containsExactly("receipts-reset", "session-published").inOrder()
        assertThat(preferences.current()).isEqualTo(
            SyncSession(
                deviceId = previous.deviceId,
                serverHost = "192.168.1.99",
                serverPort = 8765,
            ),
        )
    }

    @Test
    fun failedReceiptResetKeepsThePreviousEndpointAndSession() = runTest {
        val previous = joinedFamilySession().copy(
            pullCursor = 9,
            pullGeneration = "generation-a",
        )
        val preferences = MemorySyncPreferences(previous)
        var published = false
        val coordinator = coordinator(
            preferences = preferences,
            replica = RecordingFamilySessionReplica(
                onReset = { throw IllegalStateException("receipt reset interrupted") },
            ),
            onSessionChanged = { published = true },
        )

        val failure = coordinator.execute(
            FamilySessionCommand.SaveServer("https://192.168.1.99:8765"),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("receipt reset interrupted")
        assertThat(preferences.current()).isEqualTo(previous)
        assertThat(published).isFalse()
    }

    @Test
    fun structuredEndpointUpdateResetsReceiptsWheneverTheOriginChanges() =
        runTest {
            val preferences = MemorySyncPreferences(joinedFamilySession())
            val resetSessions = mutableListOf<SyncSession>()
            val coordinator = coordinator(
                preferences = preferences,
                replica = RecordingFamilySessionReplica(
                    onReset = { resetSessions += it },
                ),
            )

            assertThat(
                coordinator.execute(
                    FamilySessionCommand.SaveEndpointConfig(
                        FamilyEndpointConfig(
                            host = "192.168.1.20",
                            port = 9443,
                            scheme = "https",
                        ),
                    ),
                ).isSuccess,
            ).isTrue()
            assertThat(preferences.current().isJoined).isFalse()
            assertThat(resetSessions).hasSize(1)

            assertThat(
                coordinator.execute(
                    FamilySessionCommand.SaveEndpointConfig(
                        FamilyEndpointConfig(
                            host = "new.home",
                            port = 8765,
                            scheme = "https",
                        ),
                    ),
                ).isSuccess,
            ).isTrue()

            assertThat(resetSessions).hasSize(2)
            assertThat(preferences.current().endpointConfig).isEqualTo(
                FamilyEndpointConfig(
                    host = "new.home",
                    port = 8765,
                    scheme = "https",
                ),
            )
        }

    @Test
    fun createFamilyOwnsWireNormalizationInitialApplySessionPublishAndSyncRequest() = runTest {
        val previous = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            nextCreateFamilyName = "乐乐一家"
            nextCreateEntities = listOf(
                SyncEntity(
                    type = "baby",
                    clientUuid = "baby-a",
                    payloadJson = """{"nickname":"乐乐"}""",
                    updatedAt = 10,
                ),
            )
        }
        val events = mutableListOf<String>()
        val replica = RecordingFamilySessionReplica(
            onReset = {
                assertThat(preferences.current()).isEqualTo(
                    previous,
                )
                events += "receipts-reset"
            },
            onApply = { session, entities ->
                assertThat(preferences.current()).isEqualTo(
                    previous,
                )
                assertThat(session.familyId).isEqualTo("family-created")
                assertThat(entities.map(SyncEntity::clientUuid)).containsExactly("baby-a")
                events += "initial-applied"
            },
        )
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            replica = replica,
            requireRemoteAllowed = { config ->
                assertThat(config).isEqualTo(previous.endpointConfig)
                events += "gate"
            },
            onSessionChanged = { session ->
                assertThat(preferences.current()).isEqualTo(session)
                events += "session-published"
            },
            recoverReclaimedSession = { session ->
                assertThat(preferences.current()).isEqualTo(session)
                events += "initial-pull"
                InitialFamilyDataRecovery.Complete
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "  妈妈  ",
                bootstrapSecret = "one-time-secret",
                familyName = "  乐乐一家  ",
            ),
        ).getOrThrow()

        assertThat(outcome).isInstanceOf(FamilySessionOutcome.Joined::class.java)
        val joinedOutcome = outcome as FamilySessionOutcome.Joined
        assertThat(joinedOutcome.reclaimed).isFalse()
        val joined = joinedOutcome.session
        assertThat(joined.familyId).isEqualTo("family-created")
        assertThat(joined.accessToken).isEqualTo("owner-token")
        assertThat(joined.membershipId).isEqualTo("membership-created")
        assertThat(joined.familyName).isEqualTo("乐乐一家")
        assertThat(joined.pullCursor).isEqualTo(0)
        assertThat(backend.createDisplayNames).containsExactly("妈妈")
        assertThat(backend.createFamilyNames).containsExactly("乐乐一家")
        assertThat(backend.createBootstrapSecrets).containsExactly("one-time-secret")
        assertThat(events)
            .containsExactly(
                "gate",
                "receipts-reset",
                "initial-applied",
                "session-published",
                "initial-pull",
            )
            .inOrder()
    }

    @Test
    fun createFamilyReclaimPublishesOwnerSessionAtCursorZeroForPortRecovery() = runTest {
        val previous = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
        )
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            nextCreateReclaimed = true
            nextCreateFamilyName = "乐乐一家"
        }
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            requireRemoteAllowed = { events += "gate" },
            onSessionChanged = { events += "session-published" },
            requestSync = { error("reclaim sync is owned by the public SyncPort flow") },
            recoverReclaimedSession = { session ->
                assertThat(session).isEqualTo(preferences.current())
                events += "recovery"
                InitialFamilyDataRecovery.Complete
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "爸爸",
                bootstrapSecret = "deploy-secret",
                familyName = "乐乐一家",
            ),
        ).getOrThrow()

        val joined = outcome as FamilySessionOutcome.Joined
        assertThat(joined.reclaimed).isTrue()
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.Complete)
        assertThat(joined.session.familyId).isEqualTo("family-created")
        assertThat(joined.session.accessToken).isEqualTo("owner-token-reclaimed")
        assertThat(joined.session.membershipId).isEqualTo("membership-created")
        assertThat(joined.session.role).isEqualTo(FamilyRole.Owner)
        assertThat(joined.session.pullCursor).isEqualTo(0)
        assertThat(joined.session.pullGeneration).isEqualTo("current-generation")
        assertThat(joined.session.familyName).isEqualTo("乐乐一家")
        assertThat(events)
            .containsExactly("gate", "session-published", "recovery")
            .inOrder()
    }

    @Test
    fun ownerLoginPersistsCanonicalSessionBeforeRecoveryAndCarriesTakeoverMode() = runTest {
        listOf(false, true).forEach { takeover ->
            val previous = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            )
            val preferences = MemorySyncPreferences(previous)
            val backend = RecordingSyncBackend()
            val events = mutableListOf<String>()
            val coordinator = coordinator(
                preferences = preferences,
                backend = backend,
                requireRemoteAllowed = { events += "gate" },
                onSessionChanged = { events += "session-published" },
                recoverReclaimedSession = { session ->
                    assertThat(preferences.current()).isEqualTo(session)
                    events += "initial-pull"
                    InitialFamilyDataRecovery.RetryRequired
                },
            )

            val outcome = coordinator.execute(
                FamilySessionCommand.OwnerLogin(
                    deviceName = "  Pixel 9  ",
                    rootPassword = "root-password-secret",
                    takeover = takeover,
                ),
            ).getOrThrow() as FamilySessionOutcome.Joined

            assertThat(backend.ownerLoginRequestIds)
                .containsExactly("88888888-8888-8888-8888-888888888888")
            assertThat(backend.ownerLoginDeviceNames).containsExactly("Pixel 9")
            assertThat(backend.ownerLoginRootPasswords).containsExactly("root-password-secret")
            assertThat(backend.ownerLoginTakeovers).containsExactly(takeover)
            assertThat(outcome.session.role).isEqualTo(FamilyRole.Owner)
            assertThat(outcome.session.familyId).isEqualTo("family-owner-login")
            assertThat(outcome.session.pullCursor).isEqualTo(0)
            assertThat(outcome.dataRecovery).isEqualTo(InitialFamilyDataRecovery.RetryRequired)
            assertThat(events)
                .containsExactly("gate", "session-published", "initial-pull")
                .inOrder()
        }
    }

    @Test
    fun ownerLoginWrongRootMapsToProductErrorWithoutPublishingSession() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val previous = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            )
            val backend = RecordingSyncBackend().apply {
                ownerLoginFailure = SyncHttpException(statusCode)
            }
            val preferences = MemorySyncPreferences(previous)
            val coordinator = coordinator(preferences = preferences, backend = backend)

            val failure = coordinator.execute(
                FamilySessionCommand.OwnerLogin(
                    deviceName = "Pixel 9",
                    rootPassword = "wrong-root",
                    takeover = false,
                ),
            ).exceptionOrNull()

            assertThat(failure).isInstanceOf(OwnerRootPasswordRejectedException::class.java)
            assertThat(preferences.current()).isEqualTo(previous)
            assertThat(backend.ownerLoginRequestIds).hasSize(1)
        }
    }


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

        assertThat(outcome).isEqualTo(FamilySessionOutcome.MembersListed(members))
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
        val outbox = MemoryOutboxDao()
        outbox.enqueue(
            OutboxEntity(
                familyId = previous.familyId,
                entityType = "record",
                clientUuid = "record-a",
                payloadJson = "{}",
                updatedAt = 10,
            ),
        )
        backend.onLeave = {
            assertThat(preferences.current()).isEqualTo(previous)
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            outbox = outbox,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.Leave,
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(preferences.current()).isEqualTo(previous)
        assertThat(outbox.all()).hasSize(1)
    }

    @Test
    fun ownerDeleteOnlyConfirmsRemoteBeforeOuterCrashSafeClear() = runTest {
        val previous = joinedFamilySession(role = FamilyRole.Owner)
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend()
        val outbox = MemoryOutboxDao()
        outbox.enqueue(
            OutboxEntity(
                familyId = previous.familyId,
                entityType = "record",
                clientUuid = "record-a",
                payloadJson = "{}",
                updatedAt = 10,
            ),
        )
        val events = mutableListOf<String>()
        backend.onDeleteFamily = {
            assertThat(preferences.current()).isEqualTo(previous)
            events += "remote-deleted"
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            outbox = outbox,
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.DeleteFamily("  乐乐一家  ", "root-password-secret"),
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events).containsExactly("remote-deleted")
        assertThat(preferences.current()).isEqualTo(previous)
        assertThat(outbox.all()).hasSize(1)
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
    fun blankRootPasswordFailsBeforeCallingTheBackend() = runTest {
        val backend = RecordingSyncBackend()
        var gateCalls = 0
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                SyncSession(
                    serverHost = "192.168.1.20",
                    serverPort = 8787,
                ),
            ),
            backend = backend,
            requireRemoteAllowed = { gateCalls += 1 },
        )

        val failure = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "妈妈",
                bootstrapSecret = "  ",
                familyName = "乐乐一家",
            ),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("根密码")
        assertThat(gateCalls).isEqualTo(1)
        assertThat(backend.createBootstrapSecrets).isEmpty()
        assertThat(backend.createRequestIds).isEmpty()
    }

    @Test
    fun rejectedBootstrapSecretMapsToTheProductErrorWithoutPublishingSession() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val previous = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            )
            val backend = RecordingSyncBackend().apply {
                createFailure = SyncHttpException(statusCode)
            }
            val preferences = MemorySyncPreferences(previous)
            val coordinator = coordinator(preferences = preferences, backend = backend)

            val failure = coordinator.execute(
                FamilySessionCommand.CreateFamily(
                    displayName = "妈妈",
                    bootstrapSecret = "wrong",
                    familyName = "乐乐一家",
                ),
            ).exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(BootstrapSecretRejectedException::class.java)
            assertThat(failure).hasMessageThat()
                .isEqualTo("初始化口令不正确，请核对 NAS 配置")
            assertThat(preferences.current()).isEqualTo(
                previous,
            )
            assertThat(backend.createRequestIds).hasSize(1)
        }
    }

    @Test
    fun committedCreateDoesNotExposeSeparateRequestIdCleanupFailure() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            ),
        ).apply {
            clearCreateRequestIdFailure = IllegalStateException("preferences unavailable")
        }
        val coordinator = coordinator(
            preferences = preferences,
            recoverReclaimedSession = { InitialFamilyDataRecovery.Complete },
        )

        val result = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "妈妈",
                bootstrapSecret = "bootstrap-secret",
                familyName = "乐乐一家",
            ),
        )

        assertThat(result.getOrThrow()).isInstanceOf(FamilySessionOutcome.Joined::class.java)
        assertThat(preferences.current().isJoined).isTrue()
        assertThat(preferences.clearCreateRequestIdCalls).isEqualTo(0)
    }

    @Test
    fun committedCreateStillReturnsJoinedWhenSyncSchedulingFails() = runTest {
        val preferences = MemorySyncPreferences(
            SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
            ),
        )
        val coordinator = coordinator(
            preferences = preferences,
            recoverReclaimedSession = { InitialFamilyDataRecovery.RetryRequired },
        )

        val result = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "妈妈",
                bootstrapSecret = "bootstrap-secret",
                familyName = "乐乐一家",
            ),
        )

        assertThat(result.getOrThrow()).isInstanceOf(FamilySessionOutcome.Joined::class.java)
        assertThat(preferences.current().isJoined).isTrue()
    }


    @Test
    fun homeLanGateFailurePreventsAuthenticatedBackendIo() = runTest {
        val session = joinedFamilySession()
        val preferences = MemorySyncPreferences(session)
        val backend = RecordingSyncBackend()
        val observed = mutableListOf<SyncSession>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            requireRemoteAllowed = { error("home gate blocked") },
            onSessionObserved = { observed += it },
        )

        val failure = coordinator.execute(
            FamilySessionCommand.ListMembers,
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("home gate blocked")
        assertThat(backend.memberCalls).isEqualTo(0)
        assertThat(preferences.current()).isEqualTo(session)
        assertThat(observed).containsExactly(session)
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

    @Test
    fun memberRequestIsDurableAndPendingChecksNeverClaimOrPublishASession() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Pending
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val requested = coordinator.execute(
            FamilySessionCommand.RequestMemberLogin("  爸爸  ", "  Pixel 9  "),
        ).getOrThrow() as FamilySessionOutcome.MemberLoginRequested
        val checked = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(backend.memberLoginRequests).containsExactly(
            Triple("https://family.home:8765", "爸爸", "Pixel 9"),
        )
        assertThat(requested.request).isEqualTo(preferences.pendingMemberLogin.first())
        assertThat(preferences.pendingMemberSecret())
            .isEqualTo(backend.nextMemberLoginReceipt.pendingSecret)
        assertThat(checked.result).isEqualTo(MemberLoginCheckResult.Waiting(requested.request))
        assertThat(backend.memberLoginClaimCalls).isEqualTo(0)
        assertThat(preferences.current().isJoined).isFalse()
    }

    @Test
    fun approvedMemberClaimPersistsSessionBeforeReplicaRecoveryAndNeverClaimsTwice() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Approved
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        var resetSawDurableSession = false
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            replica = RecordingFamilySessionReplica(
                onReset = {
                    resetSawDurableSession = preferences.current().isJoined
                },
            ),
            recoverReclaimedSession = {
                assertThat(preferences.current()).isEqualTo(it)
                error("first pull offline")
            },
        )

        val checked = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked
        val joined = checked.result as MemberLoginCheckResult.Joined

        assertThat(resetSawDurableSession).isTrue()
        assertThat(joined.session).isEqualTo(preferences.current())
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.RetryRequired)
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.pendingMemberSecret()).isEmpty()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(1)

        assertThat(coordinator.execute(FamilySessionCommand.CheckMemberLogin).isFailure).isTrue()
        assertThat(backend.memberLoginClaimCalls).isEqualTo(1)
    }

    @Test
    fun rejectedMemberRequestClearsCapabilityWithoutCreatingIdentity() = runTest {
        val initial = SyncSession(
            serverHost = "family.home",
            serverPort = 8765,
            serverScheme = "https",
        )
        val preferences = MemorySyncPreferences(initial)
        val backend = RecordingSyncBackend().apply {
            memberLoginStatuses += MemberLoginStatus.Rejected
        }
        preferences.savePendingMemberLogin(
            backend.nextMemberLoginReceipt,
            displayName = "爸爸",
            deviceName = "Pixel 9",
        )
        val coordinator = coordinator(preferences = preferences, backend = backend)

        val outcome = coordinator.execute(FamilySessionCommand.CheckMemberLogin)
            .getOrThrow() as FamilySessionOutcome.MemberLoginChecked

        assertThat(outcome.result)
            .isEqualTo(MemberLoginCheckResult.Terminal(MemberLoginStatus.Rejected))
        assertThat(preferences.pendingMemberLogin.first()).isNull()
        assertThat(preferences.current()).isEqualTo(initial)
        assertThat(backend.memberLoginClaimCalls).isEqualTo(0)
    }

    @Test
    fun onlyOwnerCanListApproveAndRejectPendingMemberRequests() = runTest {
        val requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val pending = PendingMemberLoginRequest(
            requestId = requestId,
            displayName = "爸爸",
            deviceName = "Pixel 9",
            createdAtEpochSeconds = 100,
            expiresAtEpochSeconds = 200,
        )
        val ownerBackend = RecordingSyncBackend().apply {
            nextPendingMemberLogins = listOf(pending)
        }
        val owner = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Owner)),
            backend = ownerBackend,
        )
        val memberBackend = RecordingSyncBackend()
        val member = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession(FamilyRole.Member)),
            backend = memberBackend,
        )

        val listed = owner.execute(FamilySessionCommand.ListPendingMemberLogins)
            .getOrThrow() as FamilySessionOutcome.PendingMemberLoginsListed
        assertThat(listed.requests).containsExactly(pending)
        assertThat(
            owner.execute(FamilySessionCommand.ApproveNewMemberLogin(requestId)).isSuccess,
        ).isTrue()
        assertThat(
            owner.execute(
                FamilySessionCommand.BindExistingMemberLogin(requestId, "membership-existing"),
            ).isSuccess,
        ).isTrue()
        assertThat(
            owner.execute(FamilySessionCommand.RejectMemberLogin(requestId)).isSuccess,
        ).isTrue()
        assertThat(ownerBackend.approvedMemberLoginRequestIds).containsExactly(requestId)
        assertThat(ownerBackend.boundMemberLoginRequests).containsExactly(
            requestId to "membership-existing",
        )
        assertThat(ownerBackend.rejectedMemberLoginRequestIds).containsExactly(requestId)

        assertThat(member.execute(FamilySessionCommand.ListPendingMemberLogins).isFailure).isTrue()
        assertThat(
            member.execute(FamilySessionCommand.ApproveNewMemberLogin(requestId)).isFailure,
        ).isTrue()
        assertThat(
            member.execute(
                FamilySessionCommand.BindExistingMemberLogin(requestId, "membership-existing"),
            ).isFailure,
        ).isTrue()
        assertThat(memberBackend.approvedMemberLoginRequestIds).isEmpty()
        assertThat(memberBackend.boundMemberLoginRequests).isEmpty()
    }

    @Test
    fun onlyOwnerCanCreateTargetBoundMemberLoginGrant() = runTest {
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com")
        val ownerPreferences = MemorySyncPreferences(
            joinedFamilySession(FamilyRole.Owner).copy(
                serverHost = "family.example.com",
                serverPort = 443,
                serverScheme = "https",
            ),
        ).apply { rememberEndpoint(endpoint) }
        val ownerBackend = RecordingSyncBackend()
        val owner = coordinator(ownerPreferences, ownerBackend)

        val outcome = owner.execute(
            FamilySessionCommand.CreateMemberLoginGrant("membership-member"),
        ).getOrThrow()

        assertThat(outcome).isEqualTo(
            FamilySessionOutcome.MemberLoginGrantCreated(ownerBackend.nextMemberLoginGrant),
        )
        assertThat(ownerBackend.memberLoginGrantTargets)
            .containsExactly(Triple("token-a", endpoint, "membership-member"))

        val memberBackend = RecordingSyncBackend()
        val member = coordinator(
            MemorySyncPreferences(joinedFamilySession(FamilyRole.Member)),
            memberBackend,
        )
        assertThat(
            member.execute(
                FamilySessionCommand.CreateMemberLoginGrant("membership-member"),
            ).isFailure,
        ).isTrue()
        assertThat(memberBackend.memberLoginGrantTargets).isEmpty()
    }

    @Test
    fun qrGrantClaimRequiresExactPersistedTrustAndPersistsSessionBeforeRecovery() = runTest {
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.example.com:9443",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
        val payload = MemberLoginQrPayload(
            endpoint = endpoint,
            grant = "grant-0000000000000000000000000000000000000",
            familyName = "乐乐一家",
            memberDisplayName = "妈妈",
            expiresAtEpochSeconds = 1_753_419_000,
        )
        val preferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(endpoint)
        }
        val backend = RecordingSyncBackend()
        var recoverySawDurableSession = false
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            recoverReclaimedSession = {
                recoverySawDurableSession = preferences.current().isJoined
                error("first pull offline")
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, "  Pixel Tablet  "),
        ).getOrThrow() as FamilySessionOutcome.Joined

        assertThat(recoverySawDurableSession).isTrue()
        assertThat(outcome.session).isEqualTo(preferences.current())
        assertThat(outcome.dataRecovery).isEqualTo(InitialFamilyDataRecovery.RetryRequired)
        assertThat(backend.memberLoginGrantClaims).containsExactly(
            Triple(endpoint, payload.grant, "Pixel Tablet"),
        )
        assertThat(preferences.current().baseUrl).isEqualTo(endpoint.origin)

        val mismatchBackend = RecordingSyncBackend()
        val mismatchPreferences = MemorySyncPreferences(SyncSession()).apply {
            rememberEndpoint(TrustedEndpointProfile.systemPki("https://other.example.com"))
        }
        val mismatch = coordinator(mismatchPreferences, mismatchBackend).execute(
            FamilySessionCommand.ClaimMemberLoginGrant(payload, "Pixel Tablet"),
        )
        assertThat(mismatch.isFailure).isTrue()
        assertThat(mismatchBackend.memberLoginGrantClaims).isEmpty()
    }

    @Test
    fun cancellationEscapesWithoutBeingTranslatedIntoACommandFailure() = runTest {
        val cancellation = CancellationException("stop family command")
        val backend = RecordingSyncBackend().apply {
            renameFamilyFailure = cancellation
        }
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(joinedFamilySession()),
            backend = backend,
        )

        val thrown = runCatching {
            coordinator.execute(FamilySessionCommand.RenameFamily("新名字"))
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(cancellation)
    }
}

private fun coordinator(
    preferences: MemorySyncPreferences,
    backend: RecordingSyncBackend = RecordingSyncBackend(),
    outbox: MemoryOutboxDao = MemoryOutboxDao(),
    replica: RecordingFamilySessionReplica = RecordingFamilySessionReplica(),
    requireRemoteAllowed: suspend (FamilyEndpointConfig) -> Unit = {},
    onSessionChanged: (SyncSession) -> Unit = {},
    onSessionObserved: (SyncSession) -> Unit = {},
    requestSync: (SyncTrigger) -> Unit = {},
    recoverReclaimedSession: suspend (SyncSession) -> InitialFamilyDataRecovery = {
        InitialFamilyDataRecovery.NotRequired
    },
): FamilySessionCoordinator = FamilySessionCoordinator(
    backend = backend,
    preferences = preferences,
    outboxDao = outbox,
    replica = replica,
    barrier = Mutex(),
    requireRemoteAllowed = requireRemoteAllowed,
    onSessionChanged = onSessionChanged,
    onSessionObserved = onSessionObserved,
    requestSync = requestSync,
    recoverReclaimedSession = recoverReclaimedSession,
)

private class RecordingFamilySessionReplica(
    private val onReset: suspend (SyncSession) -> Unit = {},
    private val onApply: suspend (SyncSession, List<SyncEntity>) -> Unit = { _, _ -> },
    private val onPersistMembership:
        suspend (SyncSession, List<FamilyMember>) -> SyncSession = { session, _ -> session },
) : FamilySessionReplica {
    override suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean,
        crossingFamilyBoundary: Boolean,
    ) {
        onReset(previous)
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    ) {
        onApply(session, entities)
    }

    override suspend fun convergeAuthenticatedSelfMembership(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession = onPersistMembership(session, members)
}

private fun joinedFamilySession(
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
