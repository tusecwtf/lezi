package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.availability.FamilyServerAvailability
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [SyncPort]/[RealSyncPort] LocalWrite no-pull under causal wire —
 * operation order, cursor independence, background/lease gates, and later full pull.
 * Does not assert Channel/Job/mutex private coordinator structure.
 */
class RealSyncPortLocalWriteNoPullTest {

    @Test
    fun localWriteReplaysFrozenSleepThenWakeAtFacadeForLiveAndTombstone() = runTest {
        for (deleted in listOf(false, true)) {
            val session = joinedSession("family-a").copy(pullCursor = 19)
            val rig = SyncRig(
                session = session,
            )
            rig.backend.enableCausal = true
            val babyId = rig.babies.seed(
                localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"),
            )
            val sleepUuid = "sleep-facade-wake-$deleted"
            rig.records.seed(
                RecordEntity(
                    clientUuid = sleepUuid,
                    babyId = babyId,
                    type = "sleep",
                    timestamp = 100,
                    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = true,
                    baseVersion = "v-sleep",
                ),
            )
            val wakeUuid = "wake-facade-$deleted"
            rig.wakeObservations.seed(
                WakeObservationEntity(
                    clientUuid = wakeUuid,
                    sleepRecordClientUuid = sleepUuid,
                    wakeTimestamp = 200,
                    observerMembershipId = "local-observer-must-not-publish",
                    note = "observed",
                    withdrawn = false,
                    updatedAt = 200,
                    deletedAt = if (deleted) 199 else null,
                    syncDirty = true,
                    baseVersion = "v-wake",
                ),
            )
            var firstFrozenBatch: List<com.lezi.babylog.sync.backend.CausalMutationUnit>? = null
            rig.backend.onCausalCommit = { units ->
                firstFrozenBatch = units
                error("lost Wake commit response")
            }

            val lost = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(lost.isFailure).isTrue()
            val frozen = requireNotNull(firstFrozenBatch)
            assertThat(frozen.map { it.entityType })
                .containsExactly("record", "wake_observation")
                .inOrder()
            assertThat(frozen.last().deleted).isEqualTo(deleted)
            assertThat(frozen.last().rootJson).doesNotContain("observer_membership_id")
            assertThat(rig.conflictDetails.getFrozenMutation("record", sleepUuid)).isNotNull()
            assertThat(
                rig.conflictDetails.getFrozenMutation("wake_observation", wakeUuid),
            ).isNotNull()
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(19)

            rig.backend.onCausalCommit = null
            val replayed = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(replayed.isSuccess).isTrue()
            assertThat(rig.backend.causalCommittedUnits.last()).isEqualTo(frozen)
            assertThat(rig.backend.pullCount).isEqualTo(0)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(19)
            with(requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))) {
                assertThat(syncDirty).isFalse()
                assertThat(mutationId).isNull()
                assertThat(baseVersion).isNotNull()
                assertThat(observerMembershipId).isEqualTo(session.membershipId)
                assertThat(sleepRecordClientUuid).isEqualTo(sleepUuid)
                assertThat(deletedAt != null).isEqualTo(deleted)
            }
            assertThat(rig.conflictDetails.getFrozenMutation("wake_observation", wakeUuid))
                .isNull()
        }
    }

    @Test
    fun foregroundPullRejectsWakeBeforeItsSleepWithoutWritingOrAdvancingCursor() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 17)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"),
        )
        val sleepUuid = "sleep-public-wake-order"
        rig.records.seed(
            RecordEntity(
                clientUuid = sleepUuid,
                babyId = babyId,
                type = "sleep",
                timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-sleep",
            ),
        )
        val wakeUuid = "wake-public-order"
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = wakeUuid,
                sleepRecordClientUuid = sleepUuid,
                wakeTimestamp = 200,
                observerMembershipId = "membership-a",
                note = "existing fact",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = false,
                baseVersion = "v-wake-old",
            ),
        )
        val before = requireNotNull(rig.wakeObservations.getByClientUuid(wakeUuid))
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "wake_observation",
                    clientUuid = wakeUuid,
                    payloadJson = """
                        {
                          "sleep_record_client_uuid":"$sleepUuid",
                          "wake_timestamp":99,
                          "note":"invalid early wake",
                          "withdrawn":false,
                          "observer_membership_id":"server-observer"
                        }
                    """.trimIndent(),
                    updatedAt = 300,
                    rev = 2,
                    versionId = "v-wake-invalid",
                ),
            ),
            cursor = 18,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.Foreground)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.wakeObservations.getByClientUuid(wakeUuid)).isEqualTo(before)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(17)
    }

    @Test
    fun localWriteLeavesEveryWakeMediaFieldUntouchedWhenBytesAreUnavailable() = runTest {
        for (bytesCase in listOf("missing", "read-failure")) {
            val session = joinedSession("family-a").copy(pullCursor = 16)
            val rig = SyncRig(
                session = session,
            )
            rig.backend.enableCausal = true
            val babyId = rig.babies.seed(
                localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-baby"),
            )
            val sleepUuid = "sleep-wake-media-$bytesCase"
            rig.records.seed(
                RecordEntity(
                    clientUuid = sleepUuid,
                    babyId = babyId,
                    type = "sleep",
                    timestamp = 100,
                    payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                    schemaVersion = 2,
                    updatedAt = 100,
                    syncDirty = false,
                    baseVersion = "v-sleep",
                ),
            )
            val wakeId = rig.wakeObservations.seed(
                WakeObservationEntity(
                    clientUuid = "wake-media-$bytesCase",
                    sleepRecordClientUuid = sleepUuid,
                    wakeTimestamp = 200,
                    observerMembershipId = "membership-a",
                    note = "observed",
                    withdrawn = false,
                    updatedAt = 200,
                    syncDirty = true,
                    baseVersion = "v-wake",
                ),
            )
            val mediaUuid = if (bytesCase == "missing") {
                "00000000-0000-4000-8000-000000001311"
            } else {
                "00000000-0000-4000-8000-000000001312"
            }
            val localUri = "/private/$bytesCase-wake.jpg"
            rig.media.seed(
                MediaAssetEntity(
                    wakeObservationId = wakeId,
                    clientUuid = mediaUuid,
                    kind = "wake",
                    localUri = localUri,
                    remoteUri = "remote/$mediaUuid",
                    mime = "image/jpeg",
                    width = 640,
                    height = 480,
                    byteSize = 1234,
                    createdAt = 150,
                    updatedAt = 201,
                    syncDirty = true,
                    baseVersion = "v-media",
                    mutationId = "media-mutation",
                    openConflictId = "media-conflict",
                    localBranchVersionId = "media-branch",
                ),
            )
            if (bytesCase == "missing") {
                rig.mediaFiles.missing += localUri
            } else {
                rig.mediaFiles.afterInspect = {
                    error("legacy technical repair must not inspect Wake media")
                }
            }
            val before = requireNotNull(rig.media.getByClientUuid(mediaUuid))

            val result = rig.port.sync(SyncTrigger.LocalWrite)

            assertThat(result.isSuccess).isTrue()
            assertThat(rig.media.getByClientUuid(mediaUuid)).isEqualTo(before)
            assertThat(rig.backend.causalCommittedUnits).isEmpty()
            assertThat(
                rig.conflictDetails.getFrozenMutation("wake_observation", "wake-media-$bytesCase"),
            ).isNull()
            assertThat(rig.wakeObservations.getByClientUuid("wake-media-$bytesCase")?.syncDirty)
                .isTrue()
        }
    }

    @Test
    fun localWriteCommitsProvidersAndFulfilledRecordBeforeCarePlanAtFacadeSeam() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 14)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000410",
                syncDirty = true,
                familyAuthority = true,
                baseVersion = "v-baby-provider",
                updatedAt = 100,
            ),
        )
        val customItemId = rig.customItems.seed(
            CustomItemEntity(
                clientUuid = "00000000-0000-4000-8000-000000000411",
                familyId = 1,
                name = "抚触",
                iconSlot = 2,
                sortOrder = 7,
                updatedAt = 101,
                syncDirty = true,
                baseVersion = "v-custom-provider",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "00000000-0000-4000-8000-000000000412",
                type = "custom",
                payloadJson =
                    """{"title":"抚触","detail":"十分钟","custom_item_id":$customItemId,"icon_slot":2}""",
                schemaVersion = 2,
                updatedAt = 102,
                syncDirty = true,
                baseVersion = "v-record-provider",
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "plan-facade-consumer",
                type = "custom",
                customItemId = customItemId,
                payloadJson =
                    """{"title":"抚触","detail":"十分钟","custom_item_id":$customItemId,"icon_slot":2}""",
                status = "completed",
                fulfilledRecordClientUuid = "00000000-0000-4000-8000-000000000412",
                fulfilledAt = 103,
                updatedAt = 103,
                syncDirty = true,
                baseVersion = "v-plan-provider",
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(14)
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType })
            .containsExactly("baby", "custom_item", "record", "care_plan")
            .inOrder()
        assertThat(rig.customItems.get("00000000-0000-4000-8000-000000000411")?.sortOrder)
            .isEqualTo(7)
        assertThat(
            rig.records.getByClientUuid("00000000-0000-4000-8000-000000000412")?.babyId,
        )
            .isEqualTo(babyId)
        assertThat(rig.carePlans.getByClientUuid("plan-facade-consumer")?.syncDirty).isFalse()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun localWriteDefersCrossBabyCarePlanReferenceAtFacadeWithoutPullOrSnapshot() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 15)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = true
        val planBabyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000420",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-plan-baby",
            ),
        )
        val otherBabyId = rig.babies.seed(
            localBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000421",
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-other-baby",
            ),
        )
        val recordUuid = "00000000-0000-4000-8000-000000000422"
        rig.records.seed(
            localRecord(otherBabyId).copy(
                clientUuid = recordUuid,
                syncDirty = false,
                baseVersion = "v-other-record",
            ),
        )
        rig.carePlans.seed(
            localCarePlan(planBabyId).copy(
                clientUuid = "plan-facade-cross-baby",
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 130,
                syncDirty = true,
                baseVersion = "v-plan",
            ),
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(15)
        assertThat(rig.carePlans.getByClientUuid("plan-facade-cross-baby")?.syncDirty).isTrue()
        assertThat(
            rig.conflictDetails.getFrozenMutation("care_plan", "plan-facade-cross-baby"),
        ).isNull()
    }

    @Test
    fun localWriteNoMediaRecordCommitsWithoutReconcilePullOrCursorAdvance() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 12)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-facade-fast",
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-peer-should-wait",
                    updatedAt = 900,
                    versionId = "v-peer",
                ),
            ),
            cursor = 99,
            generation = session.pullGeneration,
            hasMore = false,
        )

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.syncOrder.filter { it.startsWith("pull:") }).isEmpty()
        assertThat(rig.backend.syncOrder.filter { it.startsWith("causal_") })
            .containsExactly("causal_commit:1")
        assertThat(rig.backend.causalCommittedUnits).hasSize(1)
        assertThat(rig.backend.causalCommittedUnits.single().single().media).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(12)
        assertThat(rig.records.getByClientUuid("record-peer-should-wait")).isNull()
        val settled = requireNotNull(rig.records.getByClientUuid("record-facade-fast"))
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(settled.baseVersion).isNotNull()
        assertThat(
            rig.conflictDetails.getFrozenMutation("record", "record-facade-fast"),
        ).isNull()
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Idle)
    }

    @Test
    fun localWriteNoPullThenForegroundPullReceivesUnrelatedPeer() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 0)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-mine-facade",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 50,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)

        rig.backend.nextPull = PullResult(
            entities = listOf(
                remoteRecord().copy(
                    clientUuid = "record-peer-facade",
                    versionId = "v-peer-2",
                    payloadJson = """
                        {
                          "baby_client_uuid":"baby-local",
                          "created_by_membership_id":"membership-b",
                          "type":"formula",
                          "custom_item_client_uuid":null,
                          "timestamp":210,
                          "end_timestamp":null,
                          "note":null,
                          "payload_json":{"amount_ml":90},
                          "schema_version":2
                        }
                    """.trimIndent(),
                ),
            ),
            cursor = 3,
            generation = session.pullGeneration,
            hasMore = false,
        )
        val full = rig.port.sync(SyncTrigger.Foreground)
        assertThat(full.exceptionOrNull()).isNull()
        assertThat(full.isSuccess).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(3)
        val peer = requireNotNull(rig.records.getByClientUuid("record-peer-facade"))
        assertThat(peer.syncDirty).isFalse()
        assertThat(peer.baseVersion).isEqualTo("v-peer-2")
    }

    @Test
    fun backgroundLocalWriteDoesNotCallCausalSettleAndKeepsDirty() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 6)
        val rig = SyncRig(session = session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-background",
                schemaVersion = 2,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.foreground.setForeground(false)

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(6)
        assertThat(requireNotNull(rig.records.getByClientUuid("record-background")).syncDirty)
            .isTrue()
    }

    @Test
    fun handshakeFailureStopsSyncWhenAvailableWithoutAnonymousProbeOrMutation() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 9)
        val rig = SyncRig(
            session = session,
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Configured)
            },
        )
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = true, baseVersion = "v-b"),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-lease",
                schemaVersion = 2,
                syncDirty = true,
                baseVersion = "v-r0",
            ),
        )
        rig.backend.handshakeFailure = java.io.IOException("unreachable")

        val result = rig.port.syncWhenAvailable(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.backend.handshakeCalls).isEqualTo(1)
        assertThat(rig.backend.anonymousHealthCalls).isEqualTo(0)
        assertThat(rig.backend.anonymousReadyCalls).isEqualTo(0)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(9)
        assertThat(requireNotNull(rig.records.getByClientUuid("record-lease")).syncDirty).isTrue()
    }

    @Test
    fun localWriteWithoutCausalCapabilityStillPullsOnFacade() = runTest {
        val session = joinedSession("family-a").copy(pullCursor = 2)
        val rig = SyncRig(
            session = session,
        )
        rig.backend.enableCausal = false
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = true))
        rig.records.seed(localRecord(babyId).copy(syncDirty = true))

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("因果同步协议")
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.syncOrder.take(2)).containsExactly("handshake", "pull:2").inOrder()
    }
}
