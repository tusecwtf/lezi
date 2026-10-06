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
class FamilySessionCoordinatorEndpointCreateLoginTest {
    @Test
    fun retainedFamilyRefusesEndpointReplacementWithoutDirtyingReceipts() = runTest {
        val previous = joinedFamilySession().copy(
            pullCursor = 9,
            pullGeneration = "generation-a",
        )
        val preferences = MemorySyncPreferences(previous)
        var published = false
        val replica = RecordingFamilySessionReplica(
            onReset = { error("retained family must not reset receipts") },
        )
        val coordinator = FamilySessionCoordinator(
            backend = RecordingSyncBackend(),
            preferences = preferences,
            replica = replica,
            barrier = Mutex(),
            requireRemoteAllowed = {},
            onSessionChanged = { published = true },
            onSessionObserved = {},
            requestSync = {},
        )

        val failure = coordinator.execute(
            FamilySessionCommand.SaveEndpointConfig(
                FamilyEndpointConfig.fromBaseUrl("https://192.168.1.99:8765"),
            ),
        ).exceptionOrNull()

        assertThat(failure).isInstanceOf(com.lezi.babylog.sync.DifferentFamilyServerException::class.java)
        assertThat(preferences.current()).isEqualTo(previous)
        assertThat(published).isFalse()
        assertThat(replica.resetCalls).isEmpty()
    }

    @Test
    fun blankFamilyCanChangeEndpointWithoutResettingReceipts() = runTest {
        val previous = SyncSession(serverHost = "192.168.1.20", serverPort = 8787)
        val preferences = MemorySyncPreferences(previous)
        val replica = RecordingFamilySessionReplica(
            onReset = { error("first endpoint must not reset receipts") },
        )
        val coordinator = coordinator(preferences = preferences, replica = replica)

        assertThat(
            coordinator.execute(
                FamilySessionCommand.SaveEndpointConfig(
                    FamilyEndpointConfig.fromBaseUrl("https://new.home:8765"),
                ),
            ).isSuccess,
        ).isTrue()

        assertThat(preferences.current().endpointConfig).isEqualTo(
            FamilyEndpointConfig(host = "new.home", port = 8765, scheme = "https"),
        )
        assertThat(preferences.current().familyId).isEmpty()
        assertThat(replica.resetCalls).isEmpty()
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
            requestSync = { events += "sync-scheduled:$it" },
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
        assertThat(joinedOutcome.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
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
                "sync-scheduled:Foreground",
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
            requestSync = { events += "sync-scheduled:$it" },
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
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
        assertThat(joined.session.familyId).isEqualTo("family-created")
        assertThat(joined.session.accessToken).isEqualTo("owner-token-reclaimed")
        assertThat(joined.session.membershipId).isEqualTo("membership-created")
        assertThat(joined.session.role).isEqualTo(FamilyRole.Owner)
        assertThat(joined.session.pullCursor).isEqualTo(0)
        assertThat(joined.session.pullGeneration).isEqualTo("current-generation")
        assertThat(joined.session.familyName).isEqualTo("乐乐一家")
        assertThat(events)
            .containsExactly("gate", "session-published", "sync-scheduled:Foreground")
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
                requestSync = { events += "sync-scheduled:$it" },
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
            assertThat(outcome.dataRecovery).isEqualTo(InitialFamilyDataRecovery.NotRequired)
            assertThat(events)
                .containsExactly("gate", "session-published", "sync-scheduled:Foreground")
                .inOrder()
        }
    }

    @Test
    fun ownerReauthSkipsReceiptResetOnlyWhenReplicaIdentityIsUnchanged() = runTest {
        listOf(
            Triple("family-a", "membership-owner" to FamilyRole.Owner, null),
            Triple("family-a", "membership-prior" to FamilyRole.Owner, false),
            Triple("family-a", "membership-owner" to FamilyRole.Member, false),
            Triple("family-b", "membership-owner" to FamilyRole.Owner, null),
        ).forEach { (joinedFamilyId, previousIdentity, expectedBoundaryCrossing) ->
            val previous = joinedFamilySession().copy(
                accessToken = "",
                refreshToken = "",
                reauthRequired = true,
                membershipId = previousIdentity.first,
                role = previousIdentity.second,
            )
            val preferences = MemorySyncPreferences(previous)
            val backend = RecordingSyncBackend().apply {
                nextOwnerLoginFamilyId = joinedFamilyId
            }
            val replica = RecordingFamilySessionReplica()

            val failure = coordinator(
                preferences = preferences,
                backend = backend,
                replica = replica,
            ).execute(
                FamilySessionCommand.OwnerLogin(
                    deviceName = "Pixel 9",
                    rootPassword = "root-password-secret",
                    takeover = false,
                ),
            ).exceptionOrNull()

            if (joinedFamilyId != previous.familyId) {
                assertThat(failure)
                    .isInstanceOf(com.lezi.babylog.sync.DifferentFamilyServerException::class.java)
                assertThat(preferences.current()).isEqualTo(previous)
                assertThat(replica.resetCalls).isEmpty()
                assertThat(backend.deviceLogoutCalls).isEqualTo(1)
                return@forEach
            }
            assertThat(failure).isNull()

            if (expectedBoundaryCrossing == null) {
                assertThat(replica.resetCalls).isEmpty()
            } else {
                assertThat(replica.resetCalls).containsExactly(
                    ReceiptResetCall(
                        previous = previous,
                        invalidateCurrentReceipts = false,
                        crossingFamilyBoundary = expectedBoundaryCrossing,
                    ),
                )
            }
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
    fun ownerLoginModeConflictRetiresTheStickyRequestIdWithRecoverableCopy() = runTest {
        val previous = SyncSession(serverHost = "192.168.1.20", serverPort = 8787)
        val backend = RecordingSyncBackend().apply {
            ownerLoginFailure = SyncHttpException(409, "login request conflict")
        }
        val preferences = MemorySyncPreferences(previous)

        val failure = coordinator(preferences = preferences, backend = backend).execute(
            FamilySessionCommand.OwnerLogin(
                deviceName = "Pixel 9",
                rootPassword = "root-password-secret",
                takeover = true,
            ),
        ).exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("登录方式或设备称呼已变化，请重试")
        assertThat(preferences.clearOwnerLoginRequestIdCalls).isEqualTo(1)
        assertThat(preferences.current()).isEqualTo(previous)
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
        val coordinator = coordinator(preferences = preferences)

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
            requestSync = { error("sync scheduler unavailable") },
        )

        val result = coordinator.execute(
            FamilySessionCommand.CreateFamily(
                displayName = "妈妈",
                bootstrapSecret = "bootstrap-secret",
                familyName = "乐乐一家",
            ),
        )

        val joined = result.getOrThrow() as FamilySessionOutcome.Joined
        assertThat(joined.dataRecovery).isEqualTo(InitialFamilyDataRecovery.RetryRequired())
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
}
