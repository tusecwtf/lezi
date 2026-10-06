package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.PullDiagnosticReceipt
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] for ticket 01 sleep/wake one root.
 *
 * Expected values come from [docs/spec/contracts/causal-sync-wire.md] §4.2 / §4.5 / §4.6
 * and `config/conflict-v2-golden.json` `dataflow_01_sleep_wake`.
 */
class ReplicaSyncEngineSleepWakeDataflowTest {

    @Test
    fun pullAttachesWakePhotosToTheWakeRootAndAdvancesTheCursor() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.mediaBytes = KNOWN_BYTES
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteCausalSleep(SLEEP_UUID),
                remoteWakeObservation(WAKE_UUID, SLEEP_UUID),
                remoteWakeMedia(MEDIA_UUID, WAKE_UUID),
                remoteWakeMedia(MEDIA_UUID_2, WAKE_UUID),
                remoteWakeMedia(MEDIA_UUID_3, WAKE_UUID),
            ).let { page ->
                page.map { entity ->
                    if (entity.clientUuid == WAKE_UUID) {
                        entity.copy(
                            media = listOf(
                                causalWakeMedia(MEDIA_UUID),
                                causalWakeMedia(MEDIA_UUID_2),
                                causalWakeMedia(MEDIA_UUID_3),
                            ),
                        )
                    } else {
                        entity
                    }
                }
            },
            cursor = 4,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val wake = requireNotNull(rig.wakeObservations.getByClientUuid(WAKE_UUID))
        val photos = listOf(MEDIA_UUID, MEDIA_UUID_2, MEDIA_UUID_3).map { uuid ->
            requireNotNull(rig.media.getByClientUuid(uuid))
        }
        assertThat(wake.sleepRecordClientUuid).isEqualTo(SLEEP_UUID)
        assertThat(wake.wakeTimestamp).isEqualTo(1_500)
        photos.forEach { photo ->
            assertThat(photo.kind).isEqualTo("wake")
            assertThat(photo.wakeObservationId).isEqualTo(wake.id)
            assertThat(photo.recordId).isNull()
            assertThat(photo.carePlanId).isNull()
            assertThat(photo.sha256).isEqualTo(KNOWN_BYTES_SHA256)
        }
        assertThat(rig.backend.mediaGets).containsExactly(MEDIA_UUID)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(4)
    }

    @Test
    fun pullConvergesAZeroPhotoWakeAndAdvancesTheCursor() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, SLEEP_UUID)),
            cursor = 5,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val wake = requireNotNull(rig.wakeObservations.getByClientUuid(WAKE_UUID))
        assertThat(wake.sleepRecordClientUuid).isEqualTo(SLEEP_UUID)
        assertThat(rig.media.listActiveForWakeObservation(wake.id)).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(5)
    }

    @Test
    fun pullAppliesWakeWhenParentSleepExistsWithoutComparingTimestamps() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteWakeObservation(WAKE_UUID, SLEEP_UUID).copy(
                    payloadJson = """
                        {
                          "sleep_record_client_uuid":"$SLEEP_UUID",
                          "wake_timestamp":99,
                          "note":null,
                          "withdrawn":false,
                          "observer_membership_id":"membership-b"
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 6,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val wake = requireNotNull(rig.wakeObservations.getByClientUuid(WAKE_UUID))
        assertThat(wake.sleepRecordClientUuid).isEqualTo(SLEEP_UUID)
        assertThat(wake.wakeTimestamp).isEqualTo(99)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
    }

    @Test
    fun pullRetriesDanglingWakeThenSkipsPastAttemptCeiling() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        val stalledPage = PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )
        // Attempts 1..3 (PRD §1.4: MAX_PULL_STALL_ATTEMPTS=3 re-deliveries):
        // the checkpoint holds before the wake so the family re-delivers it —
        // the sleep may still arrive.
        repeat(3) {
            rig.backend.nextPull = stalledPage
            rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        }

        // Attempt 4 exceeds the ceiling: convergence beats an eternally
        // dangling reference. The wake is skipped for good and only the
        // durable diagnostic receipt remains.
        rig.backend.nextPull = stalledPage
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
        val diagnostic = requireNotNull(rig.conflictDetails.listPullDiagnosticJournals().single())
        assertThat(diagnostic.payloadJson).contains(WAKE_UUID)
        assertThat(diagnostic.payloadJson).contains("reference_unready")
        val receipt = requireNotNull(rig.conflictDetails.listPullDiagnostics().single())
        assertThat(receipt.reasonGate).isEqualTo(DeferredGate.SleepTypeMismatch.name)
        assertThat(receipt.missingEntityType).isEqualTo("record")
        assertThat(receipt.missingClientUuid).isEqualTo("missing-sleep")
    }

    @Test
    fun pullWipeClearsStaleDiagnosticsWhileCurrentHoleSurvives() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        // A stale receipt left by an earlier cycle's pull.
        rig.conflictDetails.putPullDiagnostic(
            PullDiagnosticReceipt(
                entityType = "record",
                clientUuid = "stale-record",
                code = "reference_unready",
                recordedAt = 1L,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        // Pre-pull wipe removed the stale entry; this attempt's own hole stays.
        val diagnostics = rig.conflictDetails.listPullDiagnosticJournals()
        assertThat(diagnostics).hasSize(1)
        assertThat(diagnostics.single().payloadJson).contains(WAKE_UUID)
        assertThat(diagnostics.single().payloadJson).doesNotContain("stale-record")
    }

    @Test
    fun applyInitialEntitiesJournalsUnresolvedInsteadOfFailingJoin() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)

        rig.engine.applyInitialEntities(
            session = rig.preferences.current(),
            entities = listOf(remoteWakeObservation(WAKE_UUID, "missing-sleep")),
            resetReceipt = null,
        )

        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        val diagnostic = requireNotNull(rig.conflictDetails.listPullDiagnosticJournals().single())
        assertThat(diagnostic.payloadJson).contains(WAKE_UUID)
    }

    @Test
    fun pullAppliesDeferredWakeWhenLaterPageDeliversItsSleep() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.pullResults.addAll(
            listOf(
                PullResult(
                    entities = listOf(remoteWakeObservation(WAKE_UUID, "later-sleep")),
                    cursor = 5,
                    generation = session.pullGeneration,
                    hasMore = true,
                ),
                PullResult(
                    entities = listOf(remoteCausalSleep("later-sleep")),
                    cursor = 6,
                    generation = session.pullGeneration,
                    hasMore = false,
                ),
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        // The deferred second chance applies the wake once its sleep landed.
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
        assertThat(rig.conflictDetails.listPullDiagnosticJournals()).isEmpty()
    }

    @Test
    fun laterPageFailureDoesNotStrandUnresolvedBehindPublishedCursor() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(remoteWakeObservation(WAKE_UUID, "later-sleep")),
                cursor = 5,
                generation = session.pullGeneration,
                hasMore = true,
            ),
        )
        rig.backend.pullFailures.add(IllegalStateException("transport died mid-round"))

        // Page 1 held publication for its unresolved wake; page 2 failed before
        // the post-loop ledger/hold ran. The checkpoint must still be at 2 so
        // the next cycle re-delivers the wake instead of stranding it.
        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()

        rig.backend.pullResults.add(
            PullResult(
                entities = listOf(
                    remoteCausalSleep("later-sleep"),
                    remoteWakeObservation(WAKE_UUID, "later-sleep"),
                ),
                cursor = 6,
                generation = session.pullGeneration,
                hasMore = false,
            ),
        )
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNotNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
    }

    @Test
    fun downloadWithoutContentIdentityDoesNotMarkWakeMediaVerified() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 3)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.mediaBytes = KNOWN_BYTES
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteWakeObservation(WAKE_UUID, SLEEP_UUID),
                remoteWakeMedia(MEDIA_UUID, WAKE_UUID),
            ),
            cursor = 6,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("内容身份")
        assertThat(rig.media.getByClientUuid(MEDIA_UUID)).isNull()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(3)
    }

    @Test
    fun pullKeepsLocalSleepProjectionEndAndDoesNotWriteFamilyEndTimestamp() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = seedSleep(
            rig,
            SLEEP_UUID,
            endTimestamp = 200,
            updatedAt = 100,
            baseVersion = "v-local",
        )
        assertThat(babyId).isGreaterThan(0)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteCausalSleep(SLEEP_UUID).copy(
                    updatedAt = 300,
                    versionId = "v-family",
                ),
            ),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val sleep = requireNotNull(rig.records.getByClientUuid(SLEEP_UUID))
        assertThat(sleep.endTimestamp).isEqualTo(200)
        assertThat(sleep.effectiveWakeObservationClientUuid).isNull()
        assertThat(sleep.baseVersion).isEqualTo("v-family")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(7)
    }

    @Test
    fun localWriteFreezesSleepAsCausalShapeOnly() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = SLEEP_UUID,
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                endTimestamp = 200,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 50,
                syncDirty = true,
                effectiveWakeObservationClientUuid = null,
            ),
        )
        var frozenRoot: String? = null
        rig.backend.onCausalCommit = { units ->
            frozenRoot = units.single().rootJson
        }

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val root = Json.parseToJsonElement(requireNotNull(frozenRoot)).jsonObject
        assertThat(root.keys).containsExactly(
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "effective_wake_observation_client_uuid",
        )
        assertThat(root).doesNotContainKey("end_timestamp")
    }

    @Test
    fun pullRejectsSleepThatStillCarriesEndTimestamp() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 1)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteCausalSleep(SLEEP_UUID).copy(
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"sleep",
                          "custom_item_client_uuid":null,
                          "timestamp":1000,
                          "end_timestamp":1500,
                          "note":null,
                          "payload_json":{"is_nap":false,"anomaly_flag":false},
                          "schema_version":2,
                          "effective_wake_observation_client_uuid":null
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 8,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val failure = runCatching {
            rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("end_timestamp")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    }

    @Test
    fun pullTombstoneRemovesWakePhotoFromTheWakeRoot() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 2)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, SLEEP_UUID)
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = WAKE_UUID,
                sleepRecordClientUuid = SLEEP_UUID,
                wakeTimestamp = 1_500,
                observerMembershipId = "membership-b",
                withdrawn = false,
                updatedAt = 300,
                syncDirty = false,
                baseVersion = "v-wake",
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = MEDIA_UUID,
                kind = "wake",
                localUri = "downloaded/$MEDIA_UUID",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 300,
                updatedAt = 300,
                syncDirty = false,
                sha256 = KNOWN_BYTES_SHA256,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteWakeObservation(WAKE_UUID, SLEEP_UUID).copy(
                    media = emptyList(),
                    updatedAt = 400,
                    versionId = "v-wake-2",
                ),
                remoteWakeMedia(MEDIA_UUID, WAKE_UUID).copy(
                    deletedAt = 400,
                    updatedAt = 400,
                ),
            ),
            cursor = 9,
            generation = session.pullGeneration,
            hasMore = false,
        )

        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)

        val stored = requireNotNull(rig.media.getByClientUuid(MEDIA_UUID))
        assertThat(stored.deletedAt).isEqualTo(400)
        assertThat(rig.media.listActiveForWakeObservation(wakeId)).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }

    @Test
    fun localWritePublishesWakeWithPhotosAsCommitFirstMedia() = runTest {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 9)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        seedSleep(rig, "sleep-$WAKE_UUID", updatedAt = 100, baseVersion = "v-sleep")
        val wakeId = rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = WAKE_UUID,
                sleepRecordClientUuid = "sleep-$WAKE_UUID",
                wakeTimestamp = 1_500,
                observerMembershipId = "membership-a",
                note = "up",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = true,
                baseVersion = "v-wake-base",
            ),
        )
        val localUri = "/private/wake-commit.jpg"
        rig.mediaFiles.preparedUploadBytes[localUri] = KNOWN_BYTES
        rig.media.seed(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = MEDIA_UUID,
                kind = "wake",
                localUri = localUri,
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 200,
                updatedAt = 200,
                syncDirty = true,
            ),
        )

        rig.engine.synchronize(session, SyncTrigger.LocalWrite)

        val committed = rig.backend.causalCommittedUnits.single().single()
        assertThat(committed.entityType).isEqualTo("wake_observation")
        assertThat(committed.media).hasSize(1)
        assertThat(committed.media.single().role).isEqualTo("wake")
        assertThat(committed.media.single().sha256).isEqualTo(KNOWN_BYTES_SHA256)
        assertThat(requireNotNull(rig.wakeObservations.getByClientUuid(WAKE_UUID)).syncDirty)
            .isFalse()
        assertThat(requireNotNull(rig.media.getByClientUuid(MEDIA_UUID)).syncDirty).isFalse()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
    }

    private fun seedSleep(
        rig: ReplicaEngineRig,
        sleepUuid: String,
        endTimestamp: Long? = null,
        updatedAt: Long = 200,
        baseVersion: String? = "v-sleep",
    ): Long {
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "baby-local",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 1_000,
                endTimestamp = endTimestamp,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = updatedAt,
                syncDirty = false,
                familyPublishedUpdatedAt = updatedAt,
                baseVersion = baseVersion,
            ),
        )
        return babyId
    }

    private companion object {
        const val SLEEP_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01"
        const val WAKE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        const val MEDIA_UUID = "11111111-1111-4111-8111-111111111101"
        const val MEDIA_UUID_2 = "11111111-1111-4111-8111-111111111102"
        const val MEDIA_UUID_3 = "11111111-1111-4111-8111-111111111103"
        val KNOWN_BYTES = byteArrayOf(1, 2, 3, 4)
        const val KNOWN_BYTES_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"

        fun remoteCausalSleep(clientUuid: String) = SyncEntity(
            type = "record",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "baby_client_uuid":"baby-local",
                  "created_by_membership_id":"membership-b",
                  "type":"sleep",
                  "custom_item_client_uuid":null,
                  "timestamp":1000,
                  "note":null,
                  "payload_json":{"is_nap":false,"anomaly_flag":false},
                  "schema_version":2,
                  "effective_wake_observation_client_uuid":null
                }
            """.trimIndent(),
            updatedAt = 210,
        )

        fun remoteWakeObservation(clientUuid: String, sleepUuid: String) = SyncEntity(
            type = "wake_observation",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "sleep_record_client_uuid":"$sleepUuid",
                  "wake_timestamp":1500,
                  "note":null,
                  "withdrawn":false,
                  "observer_membership_id":"membership-b"
                }
            """.trimIndent(),
            updatedAt = 300,
        )

        fun remoteWakeMedia(clientUuid: String, wakeUuid: String) = SyncEntity(
            type = "media",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "kind":"wake",
                  "record_client_uuid":"$wakeUuid",
                  "care_plan_client_uuid":null,
                  "baby_client_uuid":null,
                  "mime":"image/jpeg",
                  "width":null,
                  "height":null,
                  "byte_size":4
                }
            """.trimIndent(),
            updatedAt = 300,
        )

        fun causalWakeMedia(mediaUuid: String) = CausalMediaItem(
            mediaUuid = mediaUuid,
            role = "wake",
            sha256 = KNOWN_BYTES_SHA256,
            byteSize = 4,
            mime = "image/jpeg",
        )
    }
}
