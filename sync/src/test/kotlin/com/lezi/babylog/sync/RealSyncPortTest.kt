package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.SyncStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RealSyncPortTest {
    @Test
    fun unjoinedSyncIsDisabledNoOp() = runTest {
        val rig = SyncRig(session = SyncSession())

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.outbox.all()).isEmpty()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Disabled)
    }

    @Test
    fun nonWifiStillSnapshotsBabyAndRecordIntoFamilyOutbox() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"), wifi = false)
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pushes).isEmpty()
        assertThat(rig.outbox.peek("family-a", 100).map(OutboxEntity::entityType))
            .containsExactly("baby", "record")
        assertThat(rig.outbox.peek("family-a", 100).single { it.entityType == "record" }.payloadJson)
            .contains("\"baby_client_uuid\":\"baby-local\"")
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.BlockedOfflineHome)
    }

    @Test
    fun successfulPushUsesPortableWireAcksOnlyCurrentFamily() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(localRecord(babyId))
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-b",
                entityType = "record",
                clientUuid = "other-family-record",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)
        assertThat(result.exceptionOrNull()).isNull()

        val pushed = rig.backend.pushes.single()
        assertThat(pushed.session.familyId).isEqualTo("family-a")
        assertThat(pushed.entities.map(SyncEntity::type))
            .containsExactly("baby", "record")
            .inOrder()
        val recordPayload = Json.parseToJsonElement(
            pushed.entities.single { it.type == "record" }.payloadJson,
        ).jsonObject
        assertThat(recordPayload["baby_client_uuid"].toString()).isEqualTo("\"baby-local\"")
        assertThat(recordPayload["baby_id"]).isNull()
        assertThat(recordPayload["payload_json"]).isInstanceOf(
            kotlinx.serialization.json.JsonObject::class.java,
        )
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
        assertThat(rig.outbox.peek("family-b", 100).map(OutboxEntity::clientUuid))
            .containsExactly("other-family-record")
    }

    @Test
    fun oneSyncDrainsEveryOutboxBatchWithoutStarvingRowsPastLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    nickname = "宝宝-$index",
                    clientUuid = "baby-$index",
                    updatedAt = index.toLong() + 1,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushes).hasSize(2)
        assertThat(rig.backend.pushes.flatMap { it.entities }.map(SyncEntity::clientUuid))
            .containsExactlyElementsIn((0 until 205).map { "baby-$it" })
        assertThat(rig.outbox.peek("family-a", 300)).isEmpty()
    }

    @Test
    fun movingToBackgroundStopsBeforeTheNextNetworkBatch() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    clientUuid = "baby-$index",
                    updatedAt = index.toLong() + 1,
                ),
            )
        }
        rig.backend.afterPush = { rig.foreground.setForeground(false) }

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pushes).hasSize(1)
        assertThat(rig.outbox.peek("family-a", 300)).hasSize(5)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.BlockedOfflineHome)
    }

    @Test
    fun successfulSnapshotReadsOnlyDirtyLocalChanges() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a"),
        )
        repeat(205) { index ->
            rig.babies.seed(
                localBaby().copy(
                    nickname = "历史宝宝-$index",
                    clientUuid = "history-baby-$index",
                    updatedAt = 900,
                    syncDirty = false,
                ),
            )
        }
        rig.babies.seed(
            localBaby().copy(
                nickname = "刚更新的宝宝",
                clientUuid = "changed-baby",
                updatedAt = 1_100,
            ),
        )
        rig.clock.now = 2_000

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushes.flatMap { it.entities }.map(SyncEntity::clientUuid))
            .containsExactly("changed-baby")
    }

    @Test
    fun clockRollbackCannotHideADirtyLocalChange() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "already-synced",
                updatedAt = 2_000,
                syncDirty = false,
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "written-after-clock-rollback",
                updatedAt = 900,
                syncDirty = true,
            ),
        )
        rig.clock.now = 1_000

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushes.flatMap { it.entities }.map(SyncEntity::clientUuid))
            .containsExactly("written-after-clock-rollback")
    }

    @Test
    fun avatarDependencyJoinsBabyBatchPastTheNormalLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val avatarUuid = "33333333-3333-3333-3333-333333333333"
        repeat(201) { index ->
            val babyId = rig.babies.seed(
                localBaby().copy(
                    nickname = "宝宝-$index",
                    clientUuid = "baby-$index",
                    avatarPath = if (index == 0) "avatars/first.jpg" else null,
                    updatedAt = index.toLong() + 1,
                ),
            )
            if (index == 0) {
                rig.media.seed(
                    MediaAssetEntity(
                        clientUuid = avatarUuid,
                        kind = "avatar",
                        babyId = babyId,
                        localUri = "avatars/first.jpg",
                        remoteUri = avatarUuid,
                        mime = "image/jpeg",
                        byteSize = 12,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                )
            }
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val firstBatch = rig.backend.pushes.first().entities
        assertThat(firstBatch.map(SyncEntity::clientUuid)).contains(avatarUuid)
        assertThat(rig.outbox.peek("family-a", 300)).isEmpty()
    }

    @Test
    fun savingServerBeforeJoinClearsMediaUploadMarkers() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )
        val mediaUuid = "44444444-4444-4444-4444-444444444444"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "log",
                recordId = 7,
                localUri = "photos/already-uploaded.jpg",
                remoteUri = mediaUuid,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.saveServer("http://192.168.1.99:8765").isSuccess).isTrue()

        assertThat(rig.media.getByClientUuid(mediaUuid)?.remoteUri).isNull()
    }

    @Test
    fun joinedSessionCanRepointHostAndKeepsTokenWithCursorReset() = runTest {
        val initial = joinedSession("family-a").copy(pullCursor = 9, pullGeneration = "gen-a")
        val rig = SyncRig(session = initial)

        val result = rig.port.saveServer("http://192.168.1.99:8765")

        assertThat(result.isSuccess).isTrue()
        val after = rig.preferences.current()
        assertThat(after.familyToken).isEqualTo("token")
        assertThat(after.familyId).isEqualTo("family-a")
        assertThat(after.baseUrl).isEqualTo("http://192.168.1.99:8765")
        assertThat(after.pullCursor).isEqualTo(0)
        assertThat(after.pullGeneration).isEmpty()
        assertThat(after.allowedSsids).containsExactly("Home")
    }

    @Test
    fun joinedSessionCannotCreateOrJoinOverItsOwnerCredential() = runTest {
        val initial = joinedSession("family-a")
        val rig = SyncRig(session = initial)

        assertThat(rig.port.createFamily("妈妈", "bootstrap-secret").isFailure).isTrue()
        assertThat(rig.port.joinWithPayload("ANY-CODE").isFailure).isTrue()

        assertThat(rig.preferences.current()).isEqualTo(initial)
    }

    @Test
    fun defaultLocalPlaceholderIsNeverPublishedAsAnotherMembersName() = runTest {
        val ownerRig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )
        assertThat(ownerRig.port.createFamily("我（本机）", "bootstrap-secret").isSuccess).isTrue()
        assertThat(ownerRig.backend.createDisplayNames).hasSize(1)
        assertThat(ownerRig.backend.createDisplayNames.single()).isNull()

        val memberRig = SyncRig(session = SyncSession(), ssid = "Home")
        val config = HomeLanServerConfig(
            host = "192.168.1.20",
            port = 8787,
            allowedSsids = listOf("Home"),
        )
        assertThat(
            memberRig.port.joinWithPayload(
                payload = "ABCD1234",
                preferredConfig = config,
                displayName = "我（本机）",
            ).isSuccess,
        ).isTrue()
        assertThat(memberRig.backend.joinDisplayNames).hasSize(1)
        assertThat(memberRig.backend.joinDisplayNames.single()).isNull()
    }

    @Test
    fun editedSavedAddressWinsOverStaleQrPrefillWhenJoining() = runTest {
        val rig = SyncRig(session = SyncSession(), ssid = "EditedHome")
        val edited = HomeLanServerConfig(
            host = "192.168.1.99",
            port = 9443,
            scheme = "https",
            allowedSsids = listOf("EditedHome"),
        )
        assertThat(rig.port.saveHomeLanConfig(edited).isSuccess).isTrue()
        val staleQr = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "http://192.168.1.20:8787",
                code = "ABCD1234",
                ssids = listOf("OldHome"),
            ),
        )

        assertThat(rig.port.joinWithPayload(staleQr).isSuccess).isTrue()

        assertThat(rig.backend.joinBaseUrls).containsExactly("https://192.168.1.99:9443")
        assertThat(rig.preferences.current().allowedSsids).containsExactly("EditedHome")
    }

    @Test
    fun scannedUnsavedAddressWinsOverPreviouslySavedServerAndPersistsAfterSuccess() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "old.home",
                serverPort = 8765,
                allowedSsids = listOf("OldHome"),
            ),
            ssid = "EditedHome",
        )
        val edited = HomeLanServerConfig(
            host = "lezi.home",
            port = 443,
            scheme = "https",
            allowedSsids = listOf("EditedHome"),
        )
        val scannedQr = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "https://lezi.home:443",
                code = "ABCD1234",
                ssids = listOf("EditedHome"),
            ),
        )

        assertThat(
            rig.port.joinWithPayload(scannedQr, edited, displayName = "爸爸").isSuccess,
        ).isTrue()

        assertThat(rig.backend.joinBaseUrls).containsExactly("https://lezi.home:443")
        assertThat(rig.backend.joinDisplayNames).containsExactly("爸爸")
        assertThat(rig.preferences.current().baseUrl).isEqualTo("https://lezi.home:443")
        assertThat(rig.preferences.current().allowedSsids).containsExactly("EditedHome")
    }

    @Test
    fun joinCommandUsesItsRequiredEndpointWithoutSavedOrQrPriority() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "saved.home",
                serverPort = 8765,
                allowedSsids = listOf("SavedHome"),
            ),
            ssid = "EditedHome",
        )
        val qr = InvitePayloadCodec.encode(
            InvitePayload(
                baseUrl = "http://stale-qr.home:8787",
                code = "ABCD1234",
                ssids = listOf("StaleHome"),
            ),
        )
        val command = JoinFamilyCommand(
            invitation = qr,
            homeLanConfig = HomeLanServerConfig(
                host = "edited.home",
                port = 9443,
                scheme = "https",
                allowedSsids = listOf("EditedHome"),
            ),
            displayName = "爸爸",
        )

        assertThat(rig.port.joinFamily(command).isSuccess).isTrue()

        assertThat(rig.backend.joinBaseUrls).containsExactly("https://edited.home:9443")
        assertThat(rig.backend.joinDisplayNames).containsExactly("爸爸")
        assertThat(rig.preferences.current().homeLanConfig).isEqualTo(command.homeLanConfig)
    }

    @Test
    fun familyMemberListUsesTheJoinedHomeLanSession() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.nextMembers = listOf(
            FamilyMember("妈妈", FamilyRole.Owner, isSelf = true),
            FamilyMember("爸爸", FamilyRole.Member, isSelf = false),
        )

        val result = rig.port.listFamilyMembers()

        assertThat(result.getOrThrow()).containsExactlyElementsIn(rig.backend.nextMembers).inOrder()
        assertThat(rig.backend.memberCalls).isEqualTo(1)
    }

    @Test
    fun familyMemberListDoesNotReachBackendAwayFromHomeWifi() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"), wifi = false, ssid = null)

        assertThat(rig.port.listFamilyMembers().isFailure).isTrue()
        assertThat(rig.backend.memberCalls).isEqualTo(0)
    }

    @Test
    fun failedJoinDoesNotPersistEditedServerOrWifi() = runTest {
        val rig = SyncRig(session = SyncSession(), ssid = "EditedHome")
        rig.backend.joinFailure = IllegalStateException("join rejected")
        val edited = HomeLanServerConfig(
            host = "lezi.home",
            port = 443,
            scheme = "https",
            allowedSsids = listOf("EditedHome"),
        )

        assertThat(rig.port.joinWithPayload("ABCD1234", edited).isFailure).isTrue()

        assertThat(rig.backend.joinBaseUrls).containsExactly("https://lezi.home:443")
        val after = rig.preferences.current()
        assertThat(after.baseUrl).isEmpty()
        assertThat(after.serverHost).isEmpty()
        assertThat(after.allowedSsids).isEmpty()
        assertThat(after.isJoined).isFalse()
    }

    @Test
    fun createRetriesReuseThePersistedRecoveryIdUntilSessionSave() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )
        rig.backend.createFailure = IllegalStateException("response lost")

        assertThat(rig.port.createFamily("妈妈", "bootstrap-secret").isFailure).isTrue()
        rig.backend.createFailure = null
        assertThat(rig.port.createFamily("妈妈", "bootstrap-secret").isSuccess).isTrue()

        assertThat(rig.backend.createRequestIds).hasSize(2)
        assertThat(rig.backend.createRequestIds.distinct()).hasSize(1)
        assertThat(rig.preferences.current().isJoined).isTrue()
    }

    @Test
    fun createForwardsBootstrapSecretWithoutPersistingItInSession() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )

        assertThat(
            rig.port.createFamily(
                displayName = "妈妈",
                bootstrapSecret = "one-time-bootstrap-secret",
            ).isSuccess,
        ).isTrue()

        assertThat(rig.backend.createBootstrapSecrets)
            .containsExactly("one-time-bootstrap-secret")
        assertThat(rig.preferences.current().toString())
            .doesNotContain("one-time-bootstrap-secret")
    }

    @Test
    fun blankBootstrapSecretFailsBeforePolicyOrBackendIo() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )

        val failure = rig.port.createFamily("妈妈", "  ").exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("初始化口令")
        assertThat(rig.backend.createRequestIds).isEmpty()
        assertThat(rig.healthProbeCalls).isEqualTo(0)
    }

    @Test
    fun createMapsRejectedBootstrapSecretToActionableProductError() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )
        listOf(401, 403).forEach { statusCode ->
            rig.backend.createFailure = SyncHttpException(
                statusCode,
                "Bootstrap secret required or invalid",
            )

            val error = rig.port.createFamily(
                displayName = "妈妈",
                bootstrapSecret = "wrong-secret",
            ).exceptionOrNull()

            assertThat(error).isInstanceOf(BootstrapSecretRejectedException::class.java)
            assertThat(error).hasMessageThat()
                .isEqualTo("初始化口令不正确，请核对 NAS 配置")
            assertThat(error.toString()).doesNotContain("HTTP")
        }
    }

    @Test
    fun concurrentCreateAndJoinCannotOverwriteTheFirstCredential() = runTest {
        val rig = SyncRig(
            session = SyncSession(
                serverHost = "192.168.1.20",
                serverPort = 8787,
                allowedSsids = listOf("Home"),
            ),
        )
        rig.backend.createStarted = CompletableDeferred()
        rig.backend.releaseCreate = CompletableDeferred()

        val creating = async { rig.port.createFamily("妈妈", "bootstrap-secret") }
        rig.backend.createStarted!!.await()
        val joining = async { rig.port.joinWithPayload("JOIN-CODE") }
        runCurrent()

        rig.backend.releaseCreate!!.complete(Unit)

        assertThat(creating.await().isSuccess).isTrue()
        assertThat(joining.await().isFailure).isTrue()
        assertThat(rig.backend.joinCalls).isEqualTo(0)
        assertThat(rig.preferences.current().familyId).isEqualTo("family-created")
    }

    @Test
    fun revokedMemberCanForgetLocallyAndJoinAnotherFamily() = runTest {
        val initial = joinedSession("family-a").copy(role = FamilyRole.Member)
        val rig = SyncRig(session = initial)
        rig.backend.leaveFailure = SyncHttpException(401)

        assertThat(rig.port.leave("family-a").isSuccess).isTrue()

        assertThat(rig.preferences.current().isJoined).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Disabled)
    }

    @Test
    fun lostDeleteResponseCanFinishCleanupAfterServerRevokesCredential() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.deleteFailure = SyncHttpException(401)

        assertThat(rig.port.deleteFamily().isSuccess).isTrue()

        assertThat(rig.preferences.current().isJoined).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Disabled)
    }

    @Test
    fun routeNotFoundNeverErasesTheOnlyOwnerCredential() = runTest {
        val initial = joinedSession("family-a")
        val rig = SyncRig(session = initial)
        rig.backend.deleteFailure = SyncHttpException(404)

        assertThat(rig.port.deleteFamily().isFailure).isTrue()

        assertThat(rig.preferences.current()).isEqualTo(initial)
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
    }

    @Test
    fun localRecordClearFailureBeforeDomainCommitLeavesReplicaCleanupUntouched() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "record",
                clientUuid = "record-local",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        )

        val failure = rig.port.clearLocalRecords { _ ->
            error("domain transaction failed")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("domain transaction failed")
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly("record-local")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun committedRecordClearFailureCannotResurrectOutboxAndCanBeRetried() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "record",
                clientUuid = "record-local",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        )
        rig.outbox.failDeleteTypeAttempts = 2

        val failure = rig.port.clearLocalRecords { committed ->
            rig.records.deleteAll()
            committed()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalClearCommittedException::class.java)
        assertThat((failure as LocalClearCommittedException).familyServerRetained).isTrue()
        assertThat(failure.cause).hasMessageThat().contains("outbox delete failed")
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.outbox.peek("family-a", 10)).hasSize(1)

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).exceptionOrNull()).isNull()
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::type))
            .doesNotContain("record")

        assertThat(rig.port.clearLocalRecords { it() }.isSuccess).isTrue()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
    }

    @Test
    fun localRecordClearResetsPullAndRemovesOnlyRecordReplicaMedia() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 9,
                pullGeneration = "known-generation",
            ),
        )
        val babyId = rig.babies.seed(localBaby())
        rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"photos":["photos/not-yet-snapshotted.jpg"]}""",
            ),
        )
        val logUuid = "88888888-8888-8888-8888-888888888888"
        val avatarUuid = "99999999-9999-9999-9999-999999999999"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = logUuid,
                kind = "log",
                recordId = 1,
                localUri = "photos/log.jpg",
                createdAt = 1,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = 1,
                localUri = "avatars/baby.jpg",
                createdAt = 1,
            ),
        )
        listOf(
            OutboxEntity(
                familyId = "family-a",
                entityType = "record",
                clientUuid = "record-local",
                payloadJson = "{}",
                updatedAt = 1,
            ),
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = logUuid,
                payloadJson = "{}",
                updatedAt = 1,
            ),
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = avatarUuid,
                payloadJson = "{}",
                updatedAt = 1,
            ),
        ).forEach { rig.outbox.enqueue(it) }

        assertThat(
            rig.port.clearLocalRecords { committed ->
                rig.records.deleteAll()
                committed()
            }.isSuccess,
        ).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("known-generation")
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.media.getByClientUuid(logUuid)).isNull()
        assertThat(rig.media.getByClientUuid(avatarUuid)).isNotNull()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "photos/not-yet-snapshotted.jpg",
        )
        assertThat(rig.outbox.peek("family-a", 10).map(OutboxEntity::clientUuid))
            .containsExactly(avatarUuid)
    }

    @Test
    fun localRecordClearWaitsForPullThenDeletesTheAppliedRows() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteBaby(), remoteRecord()),
            cursor = 2,
        )
        rig.backend.pullStarted = CompletableDeferred()
        rig.backend.releasePull = CompletableDeferred()

        val pulling = async { rig.port.sync(SyncTrigger.PullToRefresh) }
        rig.backend.pullStarted!!.await()
        val clearing = async {
            rig.port.clearLocalRecords { committed ->
                rig.records.deleteAll()
                committed()
            }
        }
        runCurrent()
        assertThat(clearing.isCompleted).isFalse()

        rig.backend.releasePull!!.complete(Unit)

        assertThat(pulling.await().isSuccess).isTrue()
        assertThat(clearing.await().isSuccess).isTrue()
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun localRecordClearChunksLargeMediaOutboxDeletes() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(1_005) { index ->
            val uuid = "log-media-$index"
            rig.media.seed(
                MediaAssetEntity(
                    clientUuid = uuid,
                    kind = "log",
                    recordId = index.toLong() + 1,
                    localUri = "photos/$index.jpg",
                    createdAt = index.toLong(),
                ),
            )
            rig.outbox.enqueue(
                OutboxEntity(
                    familyId = "family-a",
                    entityType = "media",
                    clientUuid = uuid,
                    payloadJson = "{}",
                    updatedAt = index.toLong(),
                ),
            )
        }

        assertThat(rig.port.clearLocalRecords { it() }.isSuccess).isTrue()

        assertThat(rig.outbox.deleteEntityBatchSizes).containsExactly(400, 400, 205).inOrder()
        assertThat(rig.outbox.peek("family-a", 2_000)).isEmpty()
    }

    @Test
    fun clearAllLocalDataRemovesOutboxAllMediaAndFilesIncludingAvatars() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 11,
                pullGeneration = "known-generation",
            ),
        )
        val logUuid = "88888888-8888-8888-8888-888888888888"
        val avatarUuid = "99999999-9999-9999-9999-999999999999"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/stale.jpg",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = logUuid,
                kind = "log",
                recordId = 1,
                localUri = "photos/log.jpg",
                createdAt = 1,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/stale.jpg",
                createdAt = 1,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"photos":["photos/inline.jpg"]}""",
            ),
        )
        listOf(
            OutboxEntity(
                familyId = "family-a",
                entityType = "record",
                clientUuid = "record-local",
                payloadJson = "{}",
                updatedAt = 1,
            ),
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = avatarUuid,
                payloadJson = "{}",
                updatedAt = 1,
            ),
            OutboxEntity(
                familyId = "family-b",
                entityType = "baby",
                clientUuid = "other-family-baby",
                payloadJson = "{}",
                updatedAt = 1,
            ),
        ).forEach { rig.outbox.enqueue(it) }

        assertThat(
            rig.port.clearAllLocalData { committed ->
                rig.records.deleteAll()
                rig.babies.deleteAll()
                committed()
            }.isSuccess,
        ).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
        assertThat(rig.records.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.babies.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
        assertThat(rig.outbox.peek("family-a", 10)).isEmpty()
        assertThat(rig.outbox.peek("family-b", 10)).isEmpty()
        assertThat(rig.mediaFiles.deleted).containsExactly(
            "photos/log.jpg",
            "baby_avatars/stale.jpg",
            "photos/inline.jpg",
        )
    }

    @Test
    fun clearKeepsGenerationSoDirtyMemberRecoversBeforeWritingToRestoredServer() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Member,
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/stale.jpg",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/stale.jpg",
                remoteUri = avatarUuid,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )

        assertThat(rig.port.clearLocalRecords { it() }.isSuccess).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("old-generation")

        rig.backend.pushFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteBaby().copy(
                        clientUuid = "baby-local",
                        payloadJson = """
                            {
                              "nickname":"服务器宝宝",
                              "birthday":"2024-01-01",
                              "avatar_media_uuid":null
                            }
                        """.trimIndent(),
                        updatedAt = 50,
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
            ),
        )
        rig.backend.pullResults.add(
            PullResult(emptyList(), cursor = 1, generation = "new-generation"),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pushAttempts.first().pullGeneration)
            .isEqualTo("old-generation")
        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        val accepted = rig.backend.pushes
            .flatMap(PushedBatch::entities)
            .single { it.type == "baby" }
        assertThat(accepted.payloadJson).contains("\"avatar_media_uuid\":null")
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isNull()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun cursorAheadRequeuesCleanLocalReplicaThenPushesBeforeFullPull() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 9,
                pullGeneration = "old-generation",
            ),
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-after-backup",
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"cursor_ahead",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.nextPull = PullResult(emptyList(), cursor = 2)

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pullCursors).containsExactly(9L, 0L, 2L).inOrder()
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::clientUuid))
            .containsAtLeast("baby-local", "record-after-backup")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    }

    @Test
    fun generationChangeAtTheSameCursorStillForcesAFullResync() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 1,
                pullGeneration = "old-generation",
            ),
        )
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.nextPull = PullResult(emptyList(), cursor = 1)

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(1L, 0L, 1L).inOrder()
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::clientUuid))
            .contains("baby-local")
    }

    @Test
    fun legacyNonzeroCursorWithoutGenerationCalibratesFromZeroBeforePush() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 7,
                pullGeneration = "",
            ),
        )
        rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 2,
            generation = "first-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(0L, 2L).inOrder()
        assertThat(rig.backend.pushes.flatMap(PushedBatch::entities).map(SyncEntity::clientUuid))
            .contains("baby-local")
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("first-generation")
    }

    @Test
    fun memberFullResyncPullsOwnerAvatarAuthorityBeforeRequeueingLocalBaby() = runTest {
        val session = joinedSession("family-a").copy(
            role = FamilyRole.Member,
            pullCursor = 1,
            pullGeneration = "old-generation",
        )
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/stale.jpg",
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/stale.jpg",
                remoteUri = avatarUuid,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.rejectMemberAvatarPointers = true
        rig.backend.pushFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0,
                        "server_cursor":1
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteBaby().copy(
                        clientUuid = "baby-local",
                        payloadJson = """
                            {
                              "nickname":"服务器宝宝",
                              "birthday":"2024-01-01",
                              "avatar_media_uuid":null
                            }
                        """.trimIndent(),
                        updatedAt = 50,
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
            ),
        )
        rig.backend.pullResults.add(
            PullResult(emptyList(), cursor = 1, generation = "new-generation"),
        )

        val result = rig.port.sync(SyncTrigger.PullToRefresh)
        assertThat(result.exceptionOrNull()).isNull()

        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        val pushedBaby = rig.backend.pushes
            .flatMap(PushedBatch::entities)
            .single { it.type == "baby" }
        assertThat(pushedBaby.payloadJson).contains("\"avatar_media_uuid\":null")
        assertThat(rig.babies.getByClientUuid("baby-local")?.avatarMediaUuid).isNull()
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.preferences.current().pullGeneration).isEqualTo("new-generation")
    }

    @Test
    fun failedPagedMemberFullResyncDoesNotClearBabiesFromUnseenPages() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                role = FamilyRole.Member,
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-page-one",
                avatarMediaUuid = "avatar-page-one",
                avatarPath = "baby_avatars/page-one.jpg",
                syncDirty = false,
            ),
        )
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-page-two",
                avatarMediaUuid = "avatar-page-two",
                avatarPath = "baby_avatars/page-two.jpg",
                syncDirty = false,
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteBaby().copy(
                        clientUuid = "baby-page-one",
                        payloadJson = """
                            {
                              "nickname":"第一页宝宝",
                              "birthday":"2024-01-01",
                              "avatar_media_uuid":null
                            }
                        """.trimIndent(),
                    ),
                ),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = emptyList(),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        val unseen = requireNotNull(rig.babies.getByClientUuid("baby-page-two"))
        assertThat(unseen.avatarMediaUuid).isEqualTo("avatar-page-two")
        assertThat(unseen.avatarPath).isEqualTo("baby_avatars/page-two.jpg")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
    }

    @Test
    fun failedPagedOwnerFullResyncDoesNotPublishAPartialAuthoritativeCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 7,
                pullGeneration = "old-generation",
            ),
        )
        rig.backend.pullFailures.add(
            SyncHttpException(
                statusCode = 409,
                responseBody = """
                    {
                      "detail":{
                        "code":"generation_changed",
                        "action":"full_resync",
                        "reset_cursor":0
                      }
                    }
                """.trimIndent(),
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = emptyList(),
                cursor = 1,
                generation = "new-generation",
                hasMore = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
    }

    @Test
    fun avatarMaterializationNeverOverwritesAProfileChangedAfterSnapshot() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(avatarPath = "baby_avatars/local.jpg"),
        )
        rig.mediaFiles.afterInspect = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "并发改名",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("并发改名")
        assertThat(baby.updatedAt).isEqualTo(101)
        assertThat(baby.avatarMediaUuid).isNull()
        assertThat(baby.syncDirty).isTrue()
    }

    @Test
    fun recordMediaMaterializationSkipsAStalePhotoSnapshot() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"photos":["photos/snapshot-old.jpg"]}""",
            ),
        )
        val mediaUuid = "32323232-3232-3232-3232-323232323232"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "photos/user-new.jpg",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterInspect = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    payloadJson = """{"photos":["photos/user-new.jpg"]}""",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).contains("photos/user-new.jpg")
        assertThat(record.updatedAt).isEqualTo(121)
        assertThat(record.syncDirty).isTrue()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        assertThat(rig.media.listAllIncludingDeleted().map(MediaAssetEntity::localUri))
            .containsExactly("photos/user-new.jpg")
    }

    @Test
    fun downloadedPhotoRefreshPreservesAConcurrentRecordEdit() = runTest {
        val session = joinedSession("family-a")
        val rig = SyncRig(session = session)
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                payloadJson = """{"amount_ml":120}""",
                syncDirty = false,
            ),
        )
        val mediaUuid = "33333333-3333-3333-3333-333333333333"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "并发补充说明",
                    payloadJson = """
                        {"amount_ml":120,"photos":["photos/user-new.jpg"]}
                    """.trimIndent(),
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("并发补充说明")
        assertThat(record.updatedAt).isEqualTo(121)
        assertThat(record.syncDirty).isTrue()
        assertThat(record.payloadJson).contains("photos/user-new.jpg")
        assertThat(record.payloadJson).doesNotContain("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun pullWindowPhotoEditStaysAuthoritativeWhenDownloadStartsLater() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "34343434-3434-3434-3434-343434343434"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.beforePullReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    payloadJson = """
                        {"amount_ml":120,"photos":["photos/during-pull.jpg"]}
                    """.trimIndent(),
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).contains("photos/during-pull.jpg")
        assertThat(record.payloadJson).doesNotContain("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun photoEditBetweenTwoDownloadsPreventsTheSecondDerivedRefresh() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuids = listOf(
            "35353535-3535-3535-3535-353535353535",
            "36363636-3636-3636-3636-363636363636",
        )
        mediaUuids.forEach { mediaUuid ->
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = mediaUuid,
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
        }
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    payloadJson = """
                        {"amount_ml":120,"photos":["photos/between-downloads.jpg"]}
                    """.trimIndent(),
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.payloadJson).contains("photos/between-downloads.jpg")
        mediaUuids.forEach { mediaUuid ->
            assertThat(record.payloadJson).doesNotContain("downloaded/$mediaUuid")
            assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
                .isEqualTo("downloaded/$mediaUuid")
        }
    }

    @Test
    fun downloadedAvatarRefreshPreservesAProfileEditDuringFileSave() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "44444444-4444-4444-4444-444444444444"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    avatarPath = "baby_avatars/user-new.jpg",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/user-new.jpg")
        assertThat(baby.updatedAt).isEqualTo(101)
        assertThat(baby.syncDirty).isTrue()
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri)
            .isEqualTo("downloaded/$mediaUuid")
    }

    @Test
    fun noteOnlyEditAcceptsDownloadedPhotoAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(
            localRecord(babyId).copy(syncDirty = false),
        )
        val mediaUuid = "45454545-4545-4545-4545-454545454545"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("record", "record-local")
        rig.backend.remember("media", mediaUuid)
        rig.backend.beforeGetMediaReturn = {
            val current = requireNotNull(rig.records.getIncludingDeleted(recordId))
            rig.records.update(
                current.copy(
                    note = "只改备注",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val record = requireNotNull(rig.records.getIncludingDeleted(recordId))
        assertThat(record.note).isEqualTo("只改备注")
        assertThat(record.payloadJson).contains("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        val pushedMedia = rig.backend.pushes
            .flatMap(PushedBatch::entities)
            .filter { it.clientUuid == mediaUuid }
        assertThat(pushedMedia).isNotEmpty()
        assertThat(pushedMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun nicknameOnlyEditAcceptsDownloadedAvatarAndNeverTombstonesItNextSync() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val mediaUuid = "46464646-4646-4646-4646-464646464646"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = mediaUuid,
                avatarPath = null,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = mediaUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("baby", "baby-local")
        rig.backend.remember("media", mediaUuid)
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.babies.getIncludingDeleted(babyId))
            rig.babies.update(
                current.copy(
                    nickname = "只改昵称",
                    updatedAt = current.updatedAt + 1,
                    syncDirty = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 1,
            generation = "current-generation",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val baby = requireNotNull(rig.babies.getIncludingDeleted(babyId))
        assertThat(baby.nickname).isEqualTo("只改昵称")
        assertThat(baby.avatarPath).isEqualTo("downloaded/$mediaUuid")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.deletedAt).isNull()
        val pushedMedia = rig.backend.pushes
            .flatMap(PushedBatch::entities)
            .filter { it.clientUuid == mediaUuid }
        assertThat(pushedMedia).isNotEmpty()
        assertThat(pushedMedia.all { it.deletedAt == null }).isTrue()
    }

    @Test
    fun equalUpdatedAtKeepsLocalOnPullMatchingServerLww() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                nickname = "本地先到",
                updatedAt = 200,
                syncDirty = false,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-remote",
                payloadJson = """{"amount_ml":120}""",
                updatedAt = 210,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端同戳",
                          "birthday":"2024-01-01",
                          "sort_order":0
                        }
                    """.trimIndent(),
                    updatedAt = 200,
                ),
                remoteRecord().copy(
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_device_id":"device-b",
                          "type":"formula",
                          "timestamp":210,
                          "payload_json":{"amount_ml":90},
                          "schema_version":1
                        }
                    """.trimIndent(),
                    updatedAt = 210,
                ),
            ),
            cursor = 9,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.babies.getByClientUuid("baby-remote")?.nickname).isEqualTo("本地先到")
        assertThat(rig.records.getByClientUuid("record-remote")?.payloadJson)
            .isEqualTo("""{"amount_ml":120}""")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }

    @Test
    fun pullAdvancesCursorOnlyAfterAllReferencesApply() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "current-generation",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteRecord()),
            cursor = 8,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
        assertThat(rig.records.getByClientUuid("record-remote")).isNull()

        rig.backend.nextPull = PullResult(
            entities = listOf(remoteBaby(), remoteRecord()),
            cursor = 8,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(8)
        assertThat(rig.babies.getByClientUuid("baby-remote")?.nickname).isEqualTo("远端宝宝")
        val applied = rig.records.getByClientUuid("record-remote")
        assertThat(applied?.babyId).isEqualTo(rig.babies.getByClientUuid("baby-remote")?.id)
        assertThat(applied?.payloadJson).isEqualTo("""{"amount_ml":90}""")
        assertThat(applied?.createdByDeviceId).isEqualTo("device-b")
        assertThat(rig.transactions.runCount).isEqualTo(2)
    }

    @Test
    fun pullDrainsEveryPageAndPersistsEachAppliedPageCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                generation = "current-generation",
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteRecord()),
                cursor = 2,
                generation = "current-generation",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.backend.pullCursors).containsExactly(0L, 1L).inOrder()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.records.getByClientUuid("record-remote")).isNotNull()
    }

    @Test
    fun pullFailsClosedWhenAFullPageOmitsTheContinuationFlag() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.nextPull = PullResult(
            entities = List(200) { index ->
                remoteBaby().copy(clientUuid = "baby-page-limit-$index")
            },
            cursor = 200,
            hasMore = null,
        )

        val failure = rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("缺少 has_more")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.babies.getByClientUuid("baby-page-limit-0")).isNull()
    }

    @Test
    fun pullFailsInsteadOfRequestingMoreThanThePageLimit() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        repeat(500) { index ->
            rig.backend.pullResults.add(
                PullResult(
                    entities = emptyList(),
                    cursor = index.toLong() + 1,
                    hasMore = true,
                ),
            )
        }
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 501,
            hasMore = false,
        )

        val failure = rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("500 页上限")
        assertThat(rig.backend.pullCount).isEqualTo(500)
    }

    @Test
    fun laterPageFailureRetainsOnlyTheLastFullyAppliedPageCursor() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteBaby()),
                cursor = 1,
                hasMore = true,
            ),
        )
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteRecord().copy(
                        payloadJson = """
                            {
                              "baby_client_uuid":"missing-baby",
                              "type":"formula",
                              "timestamp":100,
                              "payload_json":{"amount_ml":90}
                            }
                        """.trimIndent(),
                    ),
                ),
                cursor = 2,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.babies.getByClientUuid("baby-remote")).isNotNull()
        assertThat(rig.records.getByClientUuid("record-remote")).isNull()
    }

    @Test
    fun pagedPullRejectsAContinuationThatDoesNotAdvanceCursor() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 5,
                pullGeneration = "current-generation",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = emptyList(),
            cursor = 5,
            hasMore = true,
        )

        val failure = rig.port.sync(SyncTrigger.PullToRefresh).exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("cursor 未推进")
        assertThat(rig.backend.pullCursors).containsExactly(5L)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun pulledExplicitNullsClearNullableBabyFacts() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                sex = "female",
                birthWeightGrams = 3_200,
                dueDateEpochDay = 20_030,
                sortOrder = 7,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端宝宝",
                          "sex":null,
                          "birthday":"2024-01-01",
                          "birth_weight_grams":null,
                          "due_date":null,
                          "sort_order":99,
                          "avatar_media_uuid":null
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                ),
            ),
            cursor = 1,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = rig.babies.getByClientUuid("baby-remote")
        assertThat(baby?.sex).isNull()
        assertThat(baby?.birthWeightGrams).isNull()
        assertThat(baby?.dueDateEpochDay).isNull()
        assertThat(baby?.sortOrder).isEqualTo(7)
    }

    @Test
    fun babyAvatarPointerWinsOverANewerUnreferencedAvatarRow() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val selectedUuid = "55555555-5555-5555-5555-555555555555"
        val newerUuid = "66666666-6666-6666-6666-666666666666"
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "baby-remote",
                avatarMediaUuid = newerUuid,
                avatarPath = "avatars/newer.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = selectedUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/selected.jpg",
                remoteUri = selectedUuid,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = newerUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "avatars/newer.jpg",
                remoteUri = newerUuid,
                createdAt = 200,
                updatedAt = 200,
                syncDirty = false,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteBaby().copy(
                    payloadJson = """
                        {
                          "nickname":"远端宝宝",
                          "birthday":"2024-01-01",
                          "sort_order":0,
                          "avatar_media_uuid":"$selectedUuid"
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                ),
            ),
            cursor = 1,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val baby = rig.babies.getByClientUuid("baby-remote")
        assertThat(baby?.avatarMediaUuid).isEqualTo(selectedUuid)
        assertThat(baby?.avatarPath).isEqualTo("avatars/selected.jpg")
    }

    @Test
    fun memberNeverPushesLocalAvatarMetadataOrBytes() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
        )
        val babyId = rig.babies.seed(
            localBaby().copy(avatarPath = "baby_avatars/member-local.jpg"),
        )
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/member-local.jpg",
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        // A stale row from an older app version must not escape either.
        rig.outbox.enqueue(
            OutboxEntity(
                familyId = "family-a",
                entityType = "media",
                clientUuid = avatarUuid,
                payloadJson = """{"kind":"avatar","baby_client_uuid":"baby-local"}""",
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val pushed = rig.backend.pushes.single().entities
        assertThat(pushed.map(SyncEntity::type)).containsExactly("baby")
        assertThat(pushed.single().payloadJson).contains("\"avatar_media_uuid\":null")
        assertThat(rig.backend.mediaUploads).isEmpty()
        assertThat(rig.outbox.peek("family-a", 100)).isEmpty()
    }

    @Test
    fun memberRejoiningSameFamilyKeepsCanonicalAvatarReceipt() = runTest {
        val session = joinedSession("family-a").copy(role = FamilyRole.Member)
        val rig = SyncRig(session = session)
        val avatarUuid = "11111111-1111-1111-1111-111111111111"
        val babyId = rig.babies.seed(
            localBaby().copy(
                avatarMediaUuid = avatarUuid,
                avatarPath = "baby_avatars/remote.jpg",
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                clientUuid = avatarUuid,
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/remote.jpg",
                remoteUri = avatarUuid,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.remember("media", avatarUuid)

        assertThat(rig.port.leave("family-a").isSuccess).isTrue()
        rig.preferences.saveSession(session.copy(familyToken = "replacement-token"))

        val result = rig.port.sync(SyncTrigger.LocalWrite)
        assertThat(result.exceptionOrNull()).isNull()

        val pushedBaby = rig.backend.pushes
            .flatMap(PushedBatch::entities)
            .single { it.type == "baby" }
        assertThat(pushedBaby.payloadJson)
            .contains("\"avatar_media_uuid\":\"$avatarUuid\"")
        assertThat(rig.backend.mediaUploads).isEmpty()
    }

    @Test
    fun logMediaUsesRecordAsSingleBabyAssociationAfterProfileMerge() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val sourceBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-source", nickname = "来源宝宝"),
        )
        val targetBabyId = rig.babies.seed(
            localBaby().copy(clientUuid = "baby-target", nickname = "目标宝宝"),
        )
        val recordId = rig.records.seed(
            localRecord(targetBabyId).copy(
                payloadJson = """{"photos":["photos/merged.jpg"]}""",
            ),
        )
        val mediaUuid = "22222222-2222-2222-2222-222222222222"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                babyId = sourceBabyId,
                localUri = "photos/merged.jpg",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val mediaPayload = rig.backend.pushes
            .flatMap { it.entities }
            .single { it.clientUuid == mediaUuid }
            .payloadJson
        assertThat(mediaPayload).contains("\"record_client_uuid\":\"record-local\"")
        assertThat(mediaPayload).contains("\"baby_client_uuid\":null")
        assertThat(mediaPayload).doesNotContain("baby-source")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.babyId).isNull()
    }

    private fun localBaby() = BabyEntity(
        familyId = 1,
        nickname = "本地宝宝",
        birthdayEpochDay = 20_000,
        themeColorArgb = 0,
        clientUuid = "baby-local",
        updatedAt = 100,
    )

    private fun localRecord(babyId: Long) = RecordEntity(
        clientUuid = "record-local",
        babyId = babyId,
        type = "formula",
        timestamp = 120,
        createdByUserId = 1,
        payloadJson = """{"amount_ml":120}""",
        updatedAt = 120,
    )

    private fun remoteBaby() = SyncEntity(
        type = "baby",
        clientUuid = "baby-remote",
        payloadJson = """
            {
              "nickname":"远端宝宝",
              "birthday":"2024-01-01",
              "sort_order":0
            }
        """.trimIndent(),
        updatedAt = 200,
    )

    private fun remoteRecord() = SyncEntity(
        type = "record",
        clientUuid = "record-remote",
        payloadJson = """
            {
              "baby_client_uuid":"baby-remote",
              "created_by_device_id":"device-b",
              "type":"formula",
              "timestamp":210,
              "payload_json":{"amount_ml":90},
              "schema_version":1
            }
        """.trimIndent(),
        updatedAt = 210,
    )

    @Test
    fun mediaGet404DoesNotBlockPullCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "55555555-5555-5555-5555-555555555555"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.getMediaFailure = SyncHttpException(404, "not found")
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 42,
            generation = "gen-after-missing-media",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(42)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("gen-after-missing-media")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun mediaGetAuthFailureFailsSyncWithoutAdvancingCursorOrMarkingSuccess() = runTest {
        listOf(401, 403).forEach { statusCode ->
            val rig = SyncRig(session = joinedSession("family-a"))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
            val mediaUuid = "auth-media-$statusCode"
            rig.media.seed(
                MediaAssetEntity(
                    recordId = recordId,
                    clientUuid = mediaUuid,
                    kind = "log",
                    localUri = "",
                    remoteUri = mediaUuid,
                    mime = "image/jpeg",
                    byteSize = 12,
                    createdAt = 100,
                    updatedAt = 100,
                    syncDirty = false,
                ),
            )
            rig.backend.getMediaFailure = SyncHttpException(statusCode, "auth failed")
            rig.backend.nextPull = PullResult(
                emptyList(),
                cursor = 42,
                generation = "gen-after-auth-failure",
            )

            val result = rig.port.sync(SyncTrigger.PullToRefresh)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(SyncHttpException::class.java)
            assertThat((result.exceptionOrNull() as SyncHttpException).statusCode)
                .isEqualTo(statusCode)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
            assertThat(rig.preferences.current().pullGeneration).isEmpty()
            assertThat(rig.preferences.current().lastSuccessAt).isNull()
        }
    }

    @Test
    fun invalidMediaBytesDoNotBlockPullCursorAdvance() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordId = rig.records.seed(localRecord(babyId).copy(syncDirty = false))
        val mediaUuid = "56565656-5656-5656-5656-565656565656"
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = "",
                remoteUri = mediaUuid,
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.backend.mediaBytes = byteArrayOf()
        rig.backend.nextPull = PullResult(
            emptyList(),
            cursor = 43,
            generation = "gen-after-invalid-media",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(43)
        assertThat(rig.preferences.current().pullGeneration)
            .isEqualTo("gen-after-invalid-media")
        assertThat(rig.media.getByClientUuid(mediaUuid)?.localUri).isEmpty()
        assertThat(rig.media.listMissingLocalBytes().map(MediaAssetEntity::clientUuid))
            .containsExactly(mediaUuid)
    }

    @Test
    fun pullWithMultipleOpenSleepsKeepsOnlyLatestOpen() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.babies.seed(localBaby().copy(syncDirty = false, clientUuid = "baby-remote"))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "sleep-old",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_device_id":"device-b",
                          "type":"sleep",
                          "timestamp":1000,
                          "payload_json":{},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 1000,
                ),
                SyncEntity(
                    type = "record",
                    clientUuid = "sleep-new",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-remote",
                          "created_by_device_id":"device-b",
                          "type":"sleep",
                          "timestamp":2000,
                          "payload_json":{},
                          "schema_version":2
                        }
                    """.trimIndent(),
                    updatedAt = 2000,
                ),
            ),
            cursor = 7,
            generation = "gen-sleep",
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

        val babyId = requireNotNull(rig.babies.getByClientUuid("baby-remote")).id
        val opens = rig.records.listOpenSleeps(babyId)
        assertThat(opens).hasSize(1)
        assertThat(opens.single().clientUuid).isEqualTo("sleep-new")
        assertThat(opens.single().endTimestamp).isNull()

        val old = requireNotNull(rig.records.getByClientUuid("sleep-old"))
        assertThat(old.endTimestamp).isEqualTo(2000L)
        assertThat(old.payloadJson).contains("\"anomaly_flag\":true")
        assertThat(old.syncDirty).isTrue()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
    }
}

private data class PushedBatch(
    val session: SyncSession,
    val entities: List<SyncEntity>,
)

private class RecordingSyncBackend : SyncBackend {
    val pushes = mutableListOf<PushedBatch>()
    val pushAttempts = mutableListOf<SyncSession>()
    val mediaUploads = mutableListOf<String>()
    var pullCount = 0
    var nextPull = PullResult(emptyList(), 0)
    val pullResults = ArrayDeque<PullResult>()
    val pullFailures = ArrayDeque<Throwable>()
    val pushFailures = ArrayDeque<Throwable>()
    val pullCursors = mutableListOf<Long>()
    var afterPush: (() -> Unit)? = null
    var pullStarted: CompletableDeferred<Unit>? = null
    var releasePull: CompletableDeferred<Unit>? = null
    var createStarted: CompletableDeferred<Unit>? = null
    var releaseCreate: CompletableDeferred<Unit>? = null
    var leaveFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    var createFailure: Throwable? = null
    var joinFailure: Throwable? = null
    var membersFailure: Throwable? = null
    var nextMembers = listOf(
        FamilyMember("我（本机）", FamilyRole.Owner, isSelf = true),
    )
    var rejectMemberAvatarPointers = false
    var beforeGetMediaReturn: (suspend () -> Unit)? = null
    var beforePullReturn: (suspend () -> Unit)? = null
    var getMediaFailure: Throwable? = null
    var mediaBytes: ByteArray = byteArrayOf(1)
    val createRequestIds = mutableListOf<String>()
    val createDisplayNames = mutableListOf<String?>()
    val createBootstrapSecrets = mutableListOf<String?>()
    var joinCalls = 0
    val joinBaseUrls = mutableListOf<String>()
    val joinDisplayNames = mutableListOf<String?>()
    var memberCalls = 0
    private val knownEntities = mutableSetOf<Pair<String, String>>()

    fun remember(type: String, clientUuid: String) {
        knownEntities += type to clientUuid
    }

    override suspend fun create(
        baseUrl: String,
        deviceId: String,
        displayName: String?,
        createRequestId: String,
        bootstrapSecret: String?,
    ): JoinResult {
        createRequestIds += createRequestId
        createDisplayNames += displayName
        createBootstrapSecrets += bootstrapSecret
        createStarted?.complete(Unit)
        releaseCreate?.await()
        createFailure?.let { throw it }
        return JoinResult(
            familyId = "family-created",
            token = "owner-token",
            role = FamilyRole.Owner,
        )
    }

    override suspend fun push(session: SyncSession, entities: List<SyncEntity>): Int {
        pushAttempts += session
        pushFailures.removeFirstOrNull()?.let { throw it }
        val available = knownEntities + entities.map { it.type to it.clientUuid }
        entities.forEach { entity ->
            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
            when (entity.type) {
                "baby" -> payload["avatar_media_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeUnless { it == "null" }
                    ?.let {
                        if (rejectMemberAvatarPointers && session.role == FamilyRole.Member) {
                            throw SyncHttpException(403)
                        }
                        require("media" to it in available)
                    }
                "record" -> payload["baby_client_uuid"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { require("baby" to it in available) }
                "media" -> when (payload["kind"]?.jsonPrimitive?.contentOrNull) {
                    "avatar" -> payload["baby_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("baby" to it in available) }
                    "log" -> payload["record_client_uuid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { require("record" to it in available) }
                }
            }
        }
        pushes += PushedBatch(session, entities)
        knownEntities += entities.map { it.type to it.clientUuid }
        afterPush?.invoke()
        return entities.size
    }

    override suspend fun pull(session: SyncSession): PullResult {
        pullCount++
        pullCursors += session.pullCursor
        pullStarted?.complete(Unit)
        releasePull?.await()
        pullFailures.removeFirstOrNull()?.let { throw it }
        beforePullReturn?.also { beforePullReturn = null }?.invoke()
        return pullResults.removeFirstOrNull() ?: nextPull
    }

    override suspend fun invite(session: SyncSession): Invite = error("not used")
    override suspend fun join(
        baseUrl: String,
        code: String,
        deviceId: String,
        displayName: String?,
    ): JoinResult {
        joinCalls++
        joinBaseUrls += baseUrl
        joinDisplayNames += displayName
        joinFailure?.let { throw it }
        return JoinResult(
            familyId = "family-joined",
            token = "member-token",
            role = FamilyRole.Member,
        )
    }

    override suspend fun members(session: SyncSession): List<FamilyMember> {
        memberCalls++
        membersFailure?.let { throw it }
        return nextMembers
    }

    override suspend fun leave(session: SyncSession) {
        leaveFailure?.let { throw it }
    }

    override suspend fun deleteFamily(session: SyncSession) {
        deleteFailure?.let { throw it }
    }

    override suspend fun putMedia(
        session: SyncSession,
        clientUuid: String,
        bytes: ByteArray,
        mime: String?,
    ) {
        mediaUploads += clientUuid
    }

    override suspend fun getMedia(session: SyncSession, clientUuid: String): ByteArray {
        beforeGetMediaReturn?.also { beforeGetMediaReturn = null }?.invoke()
        getMediaFailure?.let { throw it }
        return mediaBytes
    }
}

private class MemorySyncPreferences(initial: SyncSession) : SyncPreferences {
    private val state = MutableStateFlow(initial)
    private var createRequestId: String? = null
    override val session: Flow<SyncSession> = state

    fun current(): SyncSession = state.value

    override suspend fun saveServer(baseUrl: String) {
        val parsed = HomeLanServerConfig.fromBaseUrl(baseUrl).withNormalized()
        saveHomeLanConfig(parsed.copy(allowedSsids = state.value.allowedSsids))
    }

    override suspend fun saveHomeLanConfig(
        config: HomeLanServerConfig,
        clearSessionIfServerChanged: Boolean,
    ) {
        val n = config.withNormalized()
        val prev = state.value
        var next = prev.copy(
            serverHost = n.host,
            serverPort = n.port,
            allowedSsids = n.allowedSsids,
            serverScheme = n.scheme,
        )
        if (prev.isJoined && prev.baseUrl != n.baseUrl && n.baseUrl.isNotBlank()) {
            next = next.copy(pullCursor = 0, pullGeneration = "")
        }
        if (clearSessionIfServerChanged && !prev.isJoined && prev.baseUrl != n.baseUrl) {
            next = next.copy(
                familyId = "",
                familyToken = "",
                role = FamilyRole.None,
                pullCursor = 0,
                pullGeneration = "",
                lastSuccessAt = null,
            )
        }
        state.value = next
    }

    override suspend fun saveSession(session: SyncSession) {
        state.value = session
    }

    override suspend fun updateCursor(cursor: Long, generation: String) {
        state.value = state.value.copy(
            pullCursor = cursor,
            pullGeneration = generation,
        )
    }

    override suspend fun markSuccess(atMillis: Long) {
        state.value = state.value.copy(lastSuccessAt = atMillis)
    }

    override suspend fun ensureDeviceId(): String {
        if (state.value.deviceId.isBlank()) {
            state.value = state.value.copy(deviceId = "test-device")
        }
        return state.value.deviceId
    }

    override suspend fun ensureCreateRequestId(): String =
        createRequestId ?: "77777777-7777-7777-7777-777777777777".also {
            createRequestId = it
        }

    override suspend fun clearCreateRequestId() {
        createRequestId = null
    }

    override suspend fun clearFamilySession() {
        state.value = state.value.copy(
            familyId = "",
            familyToken = "",
            role = FamilyRole.None,
            pullCursor = 0,
            pullGeneration = "",
            lastSuccessAt = null,
        )
    }

    override suspend fun clearAllLocalSyncConfig() {
        state.value = SyncSession()
    }
}

private class MutablePolicyClock(var now: Long = 1_000) : PolicyClock {
    override fun nowMillis(): Long = now
}

private class TestForegroundState(
    private var foreground: Boolean = true,
) : ForegroundState {
    override fun isForeground(): Boolean = foreground
    override fun setForeground(value: Boolean) {
        foreground = value
    }
}

private class TestMediaFileStore : SyncMediaFileStore {
    val deleted = mutableListOf<String>()
    var afterInspect: (suspend () -> Unit)? = null
    var afterSaveDownloaded: (suspend () -> Unit)? = null

    override suspend fun inspect(localUri: String): LocalMediaInfo {
        afterInspect?.also { afterInspect = null }?.invoke()
        return LocalMediaInfo(byteSize = 12, mime = "image/jpeg", width = 10, height = 10)
    }

    override suspend fun prepareUpload(localUri: String) =
        PreparedMedia(byteArrayOf(1), "image/jpeg")

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String {
        require(bytes.isNotEmpty()) { "downloaded media must not be empty" }
        afterSaveDownloaded?.also { afterSaveDownloaded = null }?.invoke()
        return "downloaded/$clientUuid"
    }

    override suspend fun delete(localUri: String) {
        deleted += localUri
    }
}

private class SyncRig(
    session: SyncSession,
    wifi: Boolean = true,
    ssid: String? = "Home",
) {
    val backend = RecordingSyncBackend()
    val preferences = MemorySyncPreferences(session)
    val outbox = MemoryOutboxDao()
    val records = MemoryRecordDao()
    val babies = MemoryBabyDao()
    val media = MemoryMediaDao()
    val mediaFiles = TestMediaFileStore()
    val transactions = RecordingTransactionRunner()
    val families = MemoryFamilyDao().apply {
        seed(FamilyEntity(id = 1, ownerUserId = 1, createdAt = 0))
    }
    val clock = MutablePolicyClock()
    val foreground = TestForegroundState()
    var healthProbeCalls = 0
    private val networkState = object : NetworkState {
        override fun isWifiConnected(): Boolean = wifi
        override fun currentWifiSsid(): String? = ssid
    }
    private val policy = HomeNetworkPolicy(
        networkState = networkState,
        healthProbe = HealthProbe {
            healthProbeCalls++
            true
        },
        clock = clock,
    )
    val port = RealSyncPort(
        backend = backend,
        preferences = preferences,
        policy = policy,
        networkState = networkState,
        outboxDao = outbox,
        recordDao = records,
        babyDao = babies,
        mediaDao = media,
        familyDao = families,
        clock = clock,
        foregroundState = foreground,
        mediaFiles = mediaFiles,
        transactionRunner = transactions,
    )
}

private class RecordingTransactionRunner : DatabaseTransactionRunner {
    var runCount = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        return block()
    }
}

private fun joinedSession(familyId: String) = SyncSession(
    familyId = familyId,
    familyToken = "token",
    deviceId = "device-a",
    role = FamilyRole.Owner,
    serverHost = "192.168.1.20",
    serverPort = 8787,
    allowedSsids = listOf("Home"),
)

private class MemoryOutboxDao : OutboxDao {
    private val rows = mutableListOf<OutboxEntity>()
    private val ids = AtomicLong(1)
    val deleteEntityBatchSizes = mutableListOf<Int>()
    var failDeleteTypeAttempts = 0

    fun all(): List<OutboxEntity> = rows.toList()

    override suspend fun enqueue(row: OutboxEntity): Long {
        rows.removeAll {
            it.familyId == row.familyId &&
                it.entityType == row.entityType &&
                it.clientUuid == row.clientUuid
        }
        val id = row.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows += row.copy(id = id)
        return id
    }

    override suspend fun peek(familyId: String, limit: Int): List<OutboxEntity> =
        rows.filter { it.familyId == familyId }.sortedBy(OutboxEntity::id).take(limit)

    override suspend fun find(
        familyId: String,
        entityType: String,
        clientUuid: String,
    ): OutboxEntity? = rows.find {
        it.familyId == familyId &&
            it.entityType == entityType &&
            it.clientUuid == clientUuid
    }

    override suspend fun deleteIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun deleteFamily(familyId: String) {
        rows.removeAll { it.familyId == familyId }
    }

    override suspend fun deleteType(familyId: String, entityType: String) {
        if (failDeleteTypeAttempts > 0) {
            failDeleteTypeAttempts--
            error("outbox delete failed")
        }
        rows.removeAll { it.familyId == familyId && it.entityType == entityType }
    }

    override suspend fun deleteEntities(
        familyId: String,
        entityType: String,
        clientUuids: List<String>,
    ) {
        deleteEntityBatchSizes += clientUuids.size
        require(clientUuids.size <= 400)
        rows.removeAll {
            it.familyId == familyId &&
                it.entityType == entityType &&
                it.clientUuid in clientUuids
        }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

private class MemoryBabyDao : BabyDao {
    private val rows = MutableStateFlow<List<BabyEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: BabyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<BabyEntity>> =
        rows.map { values -> values.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<BabyEntity> = rows.value.filter { it.deletedAt == null }
    override suspend fun get(id: Long): BabyEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): BabyEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): BabyEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<BabyEntity> = rows.value

    override suspend fun listPendingSync(): List<BabyEntity> =
        rows.value.filter(BabyEntity::syncDirty).sortedBy(BabyEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun countByNickname(nickname: String, excludeId: Long): Int =
        rows.value.count {
            it.deletedAt == null &&
                it.nickname.trim() == nickname.trim() &&
                (excludeId < 0 || it.id != excludeId)
        }

    override suspend fun countActive(): Int = rows.value.count { it.deletedAt == null }

    override suspend fun upsert(baby: BabyEntity): Long = seed(baby)

    override suspend fun update(baby: BabyEntity) {
        rows.value = rows.value.map { if (it.id == baby.id) baby else it }
    }

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    avatarMediaUuid = avatarMediaUuid,
                    avatarPath = avatarPath,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (
                it.id == id &&
                it.updatedAt == expectedUpdatedAt &&
                it.avatarPath == expectedAvatarPath
            ) {
                changed = 1
                it.copy(avatarMediaUuid = avatarMediaUuid, syncDirty = true)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (it.id == id && it.avatarMediaUuid == expectedAvatarMediaUuid) {
                changed = 1
                it.copy(avatarPath = avatarPath)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

private class MemoryRecordDao : RecordDao {
    private val rows = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val ids = AtomicLong(1)

    fun seed(entity: RecordEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = rows.map {
        it.filter { record ->
            record.babyId == babyId &&
                record.deletedAt == null &&
                record.timestamp in startInclusive until endExclusive
        }.sortedByDescending(RecordEntity::timestamp)
    }

    override fun observeDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> = observeRange(babyId, startInclusive, endExclusive)

    override suspend fun listDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun get(id: Long): RecordEntity? =
        rows.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): RecordEntity? =
        rows.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        rows.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<RecordEntity> = rows.value

    override suspend fun listPendingSync(): List<RecordEntity> =
        rows.value.filter(RecordEntity::syncDirty).sortedBy(RecordEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        rows.value = rows.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        listOpenSleeps(babyId).firstOrNull()

    override suspend fun listOpenSleeps(babyId: Long): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId &&
                it.type == "sleep" &&
                it.deletedAt == null &&
                it.endTimestamp == null
        }.sortedWith(
            compareByDescending<RecordEntity> { it.timestamp }.thenByDescending { it.id },
        )

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        rows.map {
            it.filter { record ->
                record.babyId == babyId &&
                    record.type == "sleep" &&
                    record.deletedAt == null &&
                    record.endTimestamp == null
            }.maxWithOrNull(
                compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
            )
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        rows.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedByDescending(RecordEntity::timestamp)

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            (
                it.note.orEmpty().contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.payloadJson.contains(escapedPattern.trim('%'), ignoreCase = true) ||
                    it.type in matchingTypeKeys
                )
    }.sortedByDescending(RecordEntity::timestamp)

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> = rows.value.filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.timestamp in startInclusive until endExclusive
    }.sortedBy(RecordEntity::timestamp)

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        rows.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.type == type
        }.sortedBy(RecordEntity::timestamp)

    override suspend fun upsert(record: RecordEntity): Long = seed(record)

    override suspend fun update(record: RecordEntity) {
        rows.value = rows.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun updatePayloadReplica(
        id: Long,
        expectedPayloadJson: String,
        payloadJson: String,
    ): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (it.id == id && it.payloadJson == expectedPayloadJson) {
                changed = 1
                it.copy(payloadJson = payloadJson)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) {
                it.copy(updatedAt = deletedAt, deletedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

private class MemoryMediaDao : MediaAssetDao {
    private val rows = mutableListOf<MediaAssetEntity>()
    private val ids = AtomicLong(1)

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long = seed(asset)

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        rows.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy(MediaAssetEntity::id)

    override suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity? =
        rows.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .maxWithOrNull(compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id })

    override suspend fun listAllIncludingDeleted(): List<MediaAssetEntity> =
        rows.sortedBy(MediaAssetEntity::id)

    override suspend fun listPendingSync(): List<MediaAssetEntity> =
        rows.filter(MediaAssetEntity::syncDirty).sortedBy(MediaAssetEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        rows.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun listMissingLocalBytes(): List<MediaAssetEntity> =
        rows.filter {
            it.deletedAt == null && it.remoteUri != null && it.localUri.isEmpty()
        }.sortedBy(MediaAssetEntity::id)

    override suspend fun getByClientUuid(uuid: String): MediaAssetEntity? =
        rows.find { it.clientUuid == uuid }

    override suspend fun update(asset: MediaAssetEntity) {
        rows.replaceAll { if (it.id == asset.id) asset else it }
    }

    override suspend fun clearRemoteUris() {
        rows.replaceAll { it.copy(remoteUri = null, syncDirty = true) }
    }

    override suspend fun deleteLogMedia() {
        rows.removeAll { it.kind == "log" }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        rows.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

private class MemoryFamilyDao : FamilyDao {
    private val rows = mutableListOf<FamilyEntity>()
    private val ids = AtomicLong(1)

    fun seed(entity: FamilyEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: ids.getAndIncrement()
        rows.removeAll { it.id == id }
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun get(id: Long): FamilyEntity? = rows.find { it.id == id }
    override suspend fun listAll(): List<FamilyEntity> = rows.toList()
    override suspend fun insert(family: FamilyEntity): Long = seed(family)
    override suspend fun deleteAll() {
        rows.clear()
    }
}
