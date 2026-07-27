package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilySessionCoordinatorTest {
    @Test
    fun serverChangeInvalidatesOldReceiptsBeforePublishingTheNewEndpoint() = runTest {
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
        )

        val result = coordinator.execute(
            FamilySessionCommand.SaveServer("http://192.168.1.99:8765"),
        )

        assertThat(result.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events).containsExactly("receipts-reset", "session-published").inOrder()
        assertThat(preferences.current()).isEqualTo(
            previous.copy(
                pullCursor = 0,
                pullGeneration = "",
                serverHost = "192.168.1.99",
                serverPort = 8765,
            ),
        )
    }

    @Test
    fun structuredEndpointUpdatePreservesSsidsOnSameHostAndResetsReceiptsOnHostChange() =
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
                    FamilySessionCommand.SaveHomeLanConfig(
                        HomeLanServerConfig(
                            host = "192.168.1.20",
                            port = 9443,
                            scheme = "https",
                        ),
                    ),
                ).isSuccess,
            ).isTrue()
            assertThat(preferences.current().allowedSsids).containsExactly("Home")
            assertThat(resetSessions).hasSize(1)

            assertThat(
                coordinator.execute(
                    FamilySessionCommand.SaveHomeLanConfig(
                        HomeLanServerConfig(
                            host = "new.home",
                            port = 8765,
                            scheme = "http",
                            allowedSsids = listOf("NewHome"),
                        ),
                    ),
                ).isSuccess,
            ).isTrue()

            assertThat(resetSessions).hasSize(2)
            assertThat(preferences.current().homeLanConfig).isEqualTo(
                HomeLanServerConfig(
                    host = "new.home",
                    port = 8765,
                    scheme = "http",
                    allowedSsids = listOf("NewHome"),
                ),
            )
        }

    @Test
    fun createFamilyOwnsWireNormalizationInitialApplySessionPublishAndSyncRequest() = runTest {
        val previous = SyncSession(
            serverHost = "192.168.1.20",
            serverPort = 8787,
            allowedSsids = listOf("Home"),
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
                    previous.copy(deviceId = "test-device"),
                )
                events += "receipts-reset"
            },
            onApply = { session, entities ->
                assertThat(preferences.current()).isEqualTo(
                    previous.copy(deviceId = "test-device"),
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
                assertThat(config).isEqualTo(previous.homeLanConfig)
                events += "gate"
            },
            onSessionChanged = { session ->
                assertThat(preferences.current()).isEqualTo(session)
                events += "session-published"
            },
            requestSync = { trigger ->
                assertThat(trigger).isEqualTo(SyncTrigger.LocalWrite)
                events += "sync-requested"
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
        val joined = (outcome as FamilySessionOutcome.Joined).session
        assertThat(joined.familyId).isEqualTo("family-created")
        assertThat(joined.familyToken).isEqualTo("owner-token")
        assertThat(joined.membershipId).isEqualTo("membership-created")
        assertThat(joined.familyName).isEqualTo("乐乐一家")
        assertThat(backend.createDisplayNames).containsExactly("妈妈")
        assertThat(backend.createFamilyNames).containsExactly("乐乐一家")
        assertThat(backend.createBootstrapSecrets).containsExactly("one-time-secret")
        assertThat(events)
            .containsExactly(
                "gate",
                "receipts-reset",
                "initial-applied",
                "session-published",
                "sync-requested",
            )
            .inOrder()
    }

    @Test
    fun joinUsesTheConfirmedEndpointAndPublishesItOnlyAfterTheRemoteJoin() = runTest {
        val previous = SyncSession(
            serverHost = "old.home",
            serverPort = 8765,
            allowedSsids = listOf("OldHome"),
        )
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            nextJoinFamilyName = "新家庭"
            nextJoinEntities = listOf(
                SyncEntity(
                    type = "baby",
                    clientUuid = "baby-joined",
                    payloadJson = """{"nickname":"乐乐"}""",
                    updatedAt = 20,
                ),
            )
        }
        val confirmed = HomeLanServerConfig(
            host = "confirmed.home",
            port = 9443,
            scheme = "https",
            allowedSsids = listOf("ConfirmedHome"),
        )
        val invitation = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "http://stale-qr.home:8787",
                code = "ABCD1234",
                ssids = listOf("StaleHome"),
            ),
        )
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            replica = RecordingFamilySessionReplica(
                onReset = {
                    assertThat(preferences.current()).isEqualTo(
                        previous.copy(deviceId = "test-device"),
                    )
                    events += "receipts-reset"
                },
                onApply = { session, entities ->
                    assertThat(session.homeLanConfig).isEqualTo(confirmed)
                    assertThat(entities.map(SyncEntity::clientUuid))
                        .containsExactly("baby-joined")
                    events += "initial-applied"
                },
            ),
            requireRemoteAllowed = { config ->
                assertThat(config).isEqualTo(confirmed)
                assertThat(preferences.current().homeLanConfig).isEqualTo(previous.homeLanConfig)
                events += "gate"
            },
            onSessionChanged = {
                events += "session-published"
            },
            requestSync = {
                events += "unexpected-sync-request"
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.JoinFamily(
                JoinFamilyCommand(
                    invitation = invitation,
                    homeLanConfig = confirmed,
                    displayName = "  爸爸  ",
                ),
            ),
        ).getOrThrow()

        val joined = (outcome as FamilySessionOutcome.Joined).session
        assertThat(joined.familyId).isEqualTo("family-joined")
        assertThat(joined.role).isEqualTo(FamilyRole.Member)
        assertThat(joined.familyName).isEqualTo("新家庭")
        assertThat(joined.homeLanConfig).isEqualTo(confirmed)
        assertThat(backend.joinBaseUrls).containsExactly("https://confirmed.home:9443")
        assertThat(backend.joinCodes).containsExactly("ABCD1234")
        assertThat(backend.joinDisplayNames).containsExactly("爸爸")
        assertThat(events)
            .containsExactly("gate", "receipts-reset", "initial-applied", "session-published")
            .inOrder()
    }

    @Test
    fun ownerCanCreateInviteThroughTheJoinedSession() = runTest {
        val session = joinedFamilySession()
        val preferences = MemorySyncPreferences(session)
        val backend = RecordingSyncBackend().apply {
            nextInvite = Invite(code = "INVITE-A", expiresAt = 1_800)
        }
        var gatedConfig: HomeLanServerConfig? = null
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            requireRemoteAllowed = { gatedConfig = it },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.CreateInvite,
        ).getOrThrow()

        assertThat(outcome).isEqualTo(
            FamilySessionOutcome.InviteCreated(
                Invite(code = "INVITE-A", expiresAt = 1_800),
            ),
        )
        assertThat(gatedConfig).isEqualTo(session.homeLanConfig)
        assertThat(backend.inviteSessions).containsExactly(session)
    }

    @Test
    fun memberCannotCreateInvite() = runTest {
        val backend = RecordingSyncBackend()
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                joinedFamilySession(role = FamilyRole.Member),
            ),
            backend = backend,
        )

        val failure = coordinator.execute(
            FamilySessionCommand.CreateInvite,
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("管理员")
        assertThat(backend.inviteSessions).isEmpty()
    }

    @Test
    fun membersRefreshesLegacySelfMembershipThroughTheReplicaSeam() = runTest {
        val previous = joinedFamilySession().copy(membershipId = "")
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
                    session.copy(membershipId = "membership-self").also {
                        preferences.saveSession(it)
                    }
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
            .containsExactly(previous, preferences.current())
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

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(backend.updatedDisplayNames).containsExactly("干爹")
    }

    @Test
    fun memberLeaveClearsOutboxReceiptsAndSessionOnlyAfterRemoteSuccess() = runTest {
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
        val events = mutableListOf<String>()
        backend.onLeave = {
            assertThat(preferences.current()).isEqualTo(previous)
            events += "remote-left"
        }
        outbox.afterDeleteFamily = {
            assertThat(preferences.current()).isEqualTo(previous)
            events += "outbox-cleared"
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            outbox = outbox,
            replica = RecordingFamilySessionReplica(
                onReset = { session ->
                    assertThat(session).isEqualTo(previous)
                    assertThat(outbox.all()).isEmpty()
                    assertThat(preferences.current()).isEqualTo(previous)
                    events += "receipts-reset"
                },
            ),
            onSessionChanged = { session ->
                assertThat(session).isEqualTo(SyncSession())
                assertThat(preferences.current()).isEqualTo(SyncSession())
                events += "session-cleared"
            },
        )

        val outcome = coordinator.execute(
            FamilySessionCommand.Leave,
        )

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events)
            .containsExactly(
                "remote-left",
                "outbox-cleared",
                "receipts-reset",
                "session-cleared",
            )
            .inOrder()
    }

    @Test
    fun ownerDeleteClearsOutboxReceiptsAndSessionOnlyAfterRemoteSuccess() = runTest {
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
        outbox.afterDeleteFamily = {
            assertThat(preferences.current()).isEqualTo(previous)
            events += "outbox-cleared"
        }
        val coordinator = coordinator(
            preferences = preferences,
            backend = backend,
            outbox = outbox,
            replica = RecordingFamilySessionReplica(
                onReset = {
                    assertThat(outbox.all()).isEmpty()
                    assertThat(preferences.current()).isEqualTo(previous)
                    events += "receipts-reset"
                },
            ),
            onSessionChanged = {
                assertThat(preferences.current()).isEqualTo(SyncSession())
                events += "session-cleared"
            },
        )

        val outcome = coordinator.execute(FamilySessionCommand.DeleteFamily)

        assertThat(outcome.getOrThrow()).isEqualTo(FamilySessionOutcome.Completed)
        assertThat(events)
            .containsExactly(
                "remote-deleted",
                "outbox-cleared",
                "receipts-reset",
                "session-cleared",
            )
            .inOrder()
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
            FamilySessionCommand.DeleteFamily,
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("管理员")
        assertThat(backend.deleteFamilyCalls).isEqualTo(0)
    }

    @Test
    fun blankBootstrapSecretFailsBeforeGateOrBackendIo() = runTest {
        val backend = RecordingSyncBackend()
        var gateCalls = 0
        val coordinator = coordinator(
            preferences = MemorySyncPreferences(
                SyncSession(
                    serverHost = "192.168.1.20",
                    serverPort = 8787,
                    allowedSsids = listOf("Home"),
                ),
            ),
            backend = backend,
            requireRemoteAllowed = { gateCalls += 1 },
        )

        val failure = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "妈妈",
                bootstrapSecret = "  ",
                familyName = null,
            ),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("初始化口令")
        assertThat(gateCalls).isEqualTo(0)
        assertThat(backend.createRequestIds).isEmpty()
    }

    @Test
    fun rejectedBootstrapSecretMapsToTheProductErrorWithoutPublishingSession() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val previous = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
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
                    familyName = null,
                ),
            ).exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(BootstrapSecretRejectedException::class.java)
            assertThat(failure).hasMessageThat()
                .isEqualTo("初始化口令不正确，请核对 NAS 配置")
            assertThat(preferences.current()).isEqualTo(
                previous.copy(deviceId = "test-device"),
            )
            assertThat(backend.createRequestIds).hasSize(1)
        }
    }

    @Test
    fun failedJoinDoesNotPublishTheConfirmedEndpointOrSession() = runTest {
        val previous = SyncSession()
        val preferences = MemorySyncPreferences(previous)
        val backend = RecordingSyncBackend().apply {
            joinFailure = IllegalStateException("join rejected")
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)
        val confirmed = HomeLanServerConfig(
            host = "confirmed.home",
            port = 9443,
            scheme = "https",
            allowedSsids = listOf("Home"),
        )

        val failure = coordinator.execute(
            FamilySessionCommand.JoinFamily(
                JoinFamilyCommand(
                    invitation = "ABCD1234",
                    homeLanConfig = confirmed,
                    displayName = "爸爸",
                ),
            ),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("join rejected")
        assertThat(preferences.current()).isEqualTo(
            previous.copy(deviceId = "test-device"),
        )
        assertThat(backend.joinBaseUrls).containsExactly("https://confirmed.home:9443")
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
    fun sharedBarrierPreventsConcurrentCreateAndJoinFromOverwritingTheFirstCredential() =
        runTest {
            val previous = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            )
            val preferences = MemorySyncPreferences(previous)
            val backend = RecordingSyncBackend().apply {
                createStarted = CompletableDeferred()
                releaseCreate = CompletableDeferred()
            }
            val coordinator = coordinator(preferences = preferences, backend = backend)

            val creating = async {
                coordinator.execute(
                    FamilySessionCommand.CreateFamily(
                        displayName = "妈妈",
                        bootstrapSecret = "bootstrap-secret",
                        familyName = null,
                    ),
                )
            }
            backend.createStarted!!.await()
            val joining = async {
                coordinator.execute(
                    FamilySessionCommand.JoinFamily(
                        JoinFamilyCommand(
                            invitation = "JOIN-CODE",
                            homeLanConfig = previous.homeLanConfig,
                            displayName = "爸爸",
                        ),
                    ),
                )
            }
            runCurrent()
            backend.releaseCreate!!.complete(Unit)

            assertThat(creating.await().isSuccess).isTrue()
            assertThat(joining.await().isFailure).isTrue()
            assertThat(backend.joinCalls).isEqualTo(0)
            assertThat(preferences.current().familyId).isEqualTo("family-created")
        }

    @Test
    fun revokedMemberCanCompleteLocalLeaveCleanup() = runTest {
        val preferences = MemorySyncPreferences(
            joinedFamilySession(role = FamilyRole.Member),
        )
        val backend = RecordingSyncBackend().apply {
            leaveFailure = SyncHttpException(401)
        }
        val coordinator = coordinator(preferences = preferences, backend = backend)

        assertThat(
            coordinator.execute(FamilySessionCommand.Leave).isSuccess,
        ).isTrue()
        assertThat(preferences.current()).isEqualTo(SyncSession())
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
            coordinator.execute(FamilySessionCommand.DeleteFamily).isFailure,
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
    requireRemoteAllowed: suspend (HomeLanServerConfig) -> Unit = {},
    onSessionChanged: (SyncSession) -> Unit = {},
    onSessionObserved: (SyncSession) -> Unit = {},
    requestSync: (SyncTrigger) -> Unit = {},
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
    ) {
        onReset(previous)
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
    ) {
        onApply(session, entities)
    }

    override suspend fun persistAuthenticatedSelfMembershipIfMissing(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession = onPersistMembership(session, members)
}

private fun joinedFamilySession(
    role: FamilyRole = FamilyRole.Owner,
): SyncSession = SyncSession(
    familyId = "family-a",
    familyToken = "token-a",
    deviceId = "device-a",
    role = role,
    pullCursor = 0,
    pullGeneration = "generation-a",
    serverHost = "192.168.1.20",
    serverPort = 8787,
    allowedSsids = listOf("Home"),
)
