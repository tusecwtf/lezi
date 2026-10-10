package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.model.RootPublicationState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole

// Split from RealSyncPortTest kitchen sink by contract cluster (ticket 05).
class RealSyncPortCarePlanFulfillTest {
    @Test
    fun atomicCarePlanPullAppliesPlanAndPhotosThenInvokesProjectionHook() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Warm policy.
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-1",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 500,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("remote-plan-media-1"),
                    mediaIdentity = com.lezi.babylog.sync.backend.PullMediaIdentity(
                        testMediaUuid("remote-plan-media-1"), "plan",
                        com.lezi.babylog.core.common.MediaContentDigest.ofBytes(byteArrayOf(1, 2, 3)), 3,
                    ),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":null,"care_plan_client_uuid":"remote-plan-1","baby_client_uuid":"$babyUuid","mime":"image/jpeg","width":null,"height":null,"byte_size":3}""",
                    updatedAt = 500,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.mediaBytes = byteArrayOf(1, 2, 3)
        rig.port.sync(SyncTrigger.Foreground).getOrThrow()
        val plan = rig.carePlans.getByClientUuid("remote-plan-1")
        assertThat(plan).isNotNull()
        assertThat(plan!!.syncDirty).isFalse()
        assertThat(plan.status).isEqualTo("pending")
        val media = rig.media.listForCarePlan(plan.id).single()
        assertThat(requireNotNull(rig.mediaFiles.readableFile(media.localUri)).readBytes())
            .isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(media.recordId).isNull()
        assertThat(media.babyId).isNull()
        assertThat(applied).containsExactly("remote-plan-1")
    }

    @Test
    fun remoteCarePlanProjectionRevisionInvalidatesCalendarReadiness() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-revision",
                type = "formula",
                scheduledAt = 1_000,
                scheduledZoneId = "UTC",
                note = "旧备注",
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = "provider-event-1",
                systemCalendarReminderReady = true,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-revision",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"sleep","custom_item_client_uuid":null,"scheduled_at":2000,"scheduled_zone_id":"Asia/Shanghai","note":"新备注","status":"pending","payload_json":{"is_nap":false,"anomaly_flag":false},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-revision")!!
        assertThat(plan.type).isEqualTo("sleep")
        assertThat(plan.scheduledAt).isEqualTo(2_000)
        assertThat(plan.scheduledZoneId).isEqualTo("Asia/Shanghai")
        assertThat(plan.note).isEqualTo("新备注")
        assertThat(plan.systemCalendarEventId).isEqualTo("provider-event-1")
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isTrue()
        assertThat(applied).containsExactly("remote-plan-revision")
    }

    @Test
    fun remoteCarePlanRevisionWithoutProjectionEvidenceDoesNotClaimCleanupPending() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-never-projected",
                scheduledAt = 1_000,
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = null,
                systemCalendarReminderReady = false,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-never-projected",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":2000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-never-projected")!!
        assertThat(plan.scheduledAt).isEqualTo(2_000)
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isFalse()
        assertThat(applied).containsExactly("remote-plan-never-projected")
    }

    @Test
    fun remoteCarePlanTerminalRevisionMarksCalendarCleanupPending() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-plan-terminal",
                updatedAt = 100,
                syncDirty = false,
                systemCalendarEventId = "provider-event-terminal",
                systemCalendarReminderReady = true,
                systemCalendarProjectionPending = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-terminal",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"skipped","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 200,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        val plan = rig.carePlans.getByClientUuid("remote-plan-terminal")!!
        assertThat(plan.status).isEqualTo("skipped")
        assertThat(plan.systemCalendarEventId).isEqualTo("provider-event-terminal")
        assertThat(plan.systemCalendarReminderReady).isFalse()
        assertThat(plan.systemCalendarProjectionPending).isTrue()
        assertThat(applied).containsExactly("remote-plan-terminal")
    }

    @Test
    fun atomicCarePlanDownloadFailureKeepsPlanInvisibleAndCursorUnmoved() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a").copy(
                pullCursor = 10,
                pullGeneration = "g0",
            ),
            carePlanApplied = { uuids -> applied += uuids },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.preferences.saveSession(
            rig.preferences.current().copy(pullCursor = 10, pullGeneration = "g0"),
        )
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-plan-dl-fail",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"pee","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"UTC","note":null,"status":"pending","payload_json":{"pee_amount":2},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}""",
                    updatedAt = 600,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "media",
                    clientUuid = testMediaUuid("remote-plan-media-fail"),
                    payloadJson =
                        """{"kind":"log","record_client_uuid":null,"care_plan_client_uuid":"remote-plan-dl-fail","baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
                    updatedAt = 600,
                    deletedAt = null,
                ),
            ),
            cursor = 20,
            generation = "current-generation",
            hasMore = false,
        )
        rig.backend.getMediaFailure = IllegalStateException("download aborted")
        rig.port.sync(SyncTrigger.Foreground)
        assertThat(rig.port.lastFailureKind().first())
            .isNotEqualTo(com.lezi.babylog.core.common.failure.FailureKind.InvalidInput)
        assertThat(rig.carePlans.getByClientUuid("remote-plan-dl-fail")).isNull()
        assertThat(applied).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(10)
    }

    @Test
    fun localCarePlanPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy() {
        assertThat(
            localCarePlanPublishLabel(
                syncDirty = true,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("仅本机 · 等待家庭同步")
        assertThat(
            localCarePlanPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isEqualTo("其他成员暂不可见、不会提醒，护理计划发布成功后才会出现。")
        assertThat(
            localCarePlanPublishDetail(
                lastSyncFailed = false,
                publicationState = RootPublicationState.PREVIOUS_VERSION_PUBLISHED,
            ),
        ).contains("上一完整版本")
        assertThat(
            localCarePlanPublishLabel(
                syncDirty = false,
                familyJoined = true,
                lastSyncFailed = false,
                publicationState = RootPublicationState.NEVER_PUBLISHED,
            ),
        ).isNull()
    }

    @Test
    fun fulfillUnitRetryUsesStableCandidateAndLostCommitDoesNotDuplicate() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "retry-fulfill-record"
        val planUuid = "retry-fulfill-plan"
        val candUuid = "retry-fulfill-cand"
        val recordId = rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 700,
                syncDirty = true,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = testMediaUuid("retry-fulfill-media"),
                kind = "log",
                localUri = "photos/retry-fulfill.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 700,
                updatedAt = 700,
                syncDirty = true,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 700,
                updatedAt = 701,
                syncDirty = true,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 120,
                confirmedAt = 700,
                updatedAt = 702,
                syncDirty = true,
            ),
        )
        // Mutable roots commit first; fail the immutable candidate bundle once.
        rig.backend.failCommitRootTypeOnce =
            "fulfillment_candidate" to IllegalStateException("candidate commit lost")
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isFalse()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.entityType to it.clientUuid })
            .contains("record" to recordUuid)
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)?.syncDirty).isTrue()

        // Re-dirty only candidate if records already marked synced; re-seed dirty candidate.
        val cand = rig.fulfillmentCandidates.getByClientUuid(candUuid)!!
        rig.fulfillmentCandidates.seed(cand.copy(syncDirty = true))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val candidateDrafts = rig.backend.stagedBundles
            .filter { it.root.type == "fulfillment_candidate" && it.root.clientUuid == candUuid }
        val candPushes = candidateDrafts
            .map(AtomicBundleDraft::root)
            .filter { it.type == "fulfillment_candidate" && it.clientUuid == candUuid }
        assertThat(candPushes).isNotEmpty()
        assertThat(candPushes.map { it.clientUuid }.distinct()).containsExactly(candUuid)
        assertThat(candidateDrafts.map(AtomicBundleDraft::bundleId).distinct()).hasSize(1)
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)?.syncDirty).isFalse()
        assertThat(rig.backend.syncOrder.filter { "reconcile" in it }).isEmpty()
    }

    @Test
    fun fulfillReceiveFullSetAppliesAndProjectsCompletedPlanCancellation() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { applied += it },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Pending plan already local (open) so completed package replaces it.
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "remote-fulfill-plan",
                status = "pending",
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "remote-fulfill-record",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"member-b","type":"formula","custom_item_client_uuid":null,"timestamp":200,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                    updatedAt = 800,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "remote-fulfill-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"remote-fulfill-record","fulfilled_at":800,"source_record_client_uuid":null}""",
                    updatedAt = 801,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "remote-fulfill-cand",
                    payloadJson =
                        """{"care_plan_client_uuid":"remote-fulfill-plan","record_client_uuid":"remote-fulfill-record","actual_timestamp":200,"submitter_membership_id":"member-b","submitter_role":"member","confirmed_at":800}""",
                    updatedAt = 802,
                    deletedAt = null,
                ),
            ),
            cursor = 99,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(
            rig.records.getByClientUuid("remote-fulfill-record")?.familyPublishedUpdatedAt,
        ).isEqualTo(800)
        assertThat(rig.carePlans.getByClientUuid("remote-fulfill-plan")?.status)
            .isEqualTo("completed")
        assertThat(
            rig.carePlans.getByClientUuid("remote-fulfill-plan")?.familyPublishedUpdatedAt,
        ).isEqualTo(801)
        assertThat(rig.carePlans.getByClientUuid("remote-fulfill-plan")?.fulfilledRecordClientUuid)
            .isEqualTo("remote-fulfill-record")
        val cand = rig.fulfillmentCandidates.getByClientUuid("remote-fulfill-cand")!!
        assertThat(cand.submitterMembershipId).isEqualTo("member-b")
        assertThat(cand.submitterRole).isEqualTo("member")
        assertThat(cand.confirmedAt).isEqualTo(800)
        assertThat(cand.syncDirty).isFalse()
        assertThat(applied).contains("remote-fulfill-plan")
        assertThat(rig.preferences.current().pullCursor).isEqualTo(99)
    }

    @Test
    fun multiCandidateReceiveConvergesIndependentOfArrivalOrderAndPlanLww() = runTest {
        suspend fun runOrder(order: List<String>) {
            val rig = SyncRig(session = joinedSession("family-conflict"))
                val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
            val planUuid = "conflict-plan"
            // Local member already fulfilled; plan LWW wrongly points at local record.
            rig.records.seed(
                localRecord(babyId).copy(
                    clientUuid = "rec-member",
                    updatedAt = 500,
                    syncDirty = false,
                ),
            )
            rig.carePlans.seed(
                localCarePlan(babyId).copy(
                    clientUuid = planUuid,
                    status = "completed",
                    fulfilledRecordClientUuid = "rec-member",
                    fulfilledAt = 500,
                    updatedAt = 9_000,
                    syncDirty = false,
                ),
            )
            rig.fulfillmentCandidates.seed(
                FulfillmentCandidateEntity(
                    clientUuid = "cand-member",
                    carePlanClientUuid = planUuid,
                    recordClientUuid = "rec-member",
                    confirmedAt = 500,
                    submitterMembershipId = "m-member",
                    submitterRole = "member",
                    adoptionStatus = com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED,
                    updatedAt = 500,
                    syncDirty = false,
                ),
            )
            assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

            val ownerRecord = SyncEntity(
                type = "record",
                clientUuid = "rec-owner",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-owner","type":"formula","custom_item_client_uuid":null,"timestamp":200,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 800,
                deletedAt = null,
            )
            val ownerCandidate = SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "cand-owner",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-owner","actual_timestamp":200,"submitter_membership_id":"m-owner","submitter_role":"owner","confirmed_at":900}""",
                updatedAt = 900,
                deletedAt = null,
            )
            // Stale plan LWW with higher updatedAt still pointing at member record —
            // resolution must re-link after candidates are complete.
            val stalePlan = SyncEntity(
                type = "care_plan",
                clientUuid = planUuid,
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"rec-member","fulfilled_at":500,"source_record_client_uuid":null}""",
                updatedAt = 10_000,
                deletedAt = null,
            )
            val byKey = mapOf(
                "record" to ownerRecord,
                "candidate" to ownerCandidate,
                "plan" to stalePlan,
            )
            rig.backend.nextPull = PullResult(
                entities = order.map { byKey.getValue(it) },
                cursor = 120,
                generation = "current-generation",
                hasMore = false,
            )
            assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
            assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
                .isEqualTo("rec-owner")
            assertThat(rig.fulfillmentCandidates.getByClientUuid("cand-owner")?.adoptionStatus)
                .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
            assertThat(rig.fulfillmentCandidates.getByClientUuid("cand-member")?.adoptionStatus)
                .isEqualTo(
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                )
            // Loser record retained (not soft-deleted).
            assertThat(rig.records.getByClientUuid("rec-member")?.deletedAt).isNull()
            assertThat(rig.records.getByClientUuid("rec-owner")).isNotNull()
        }
        runOrder(listOf("record", "plan", "candidate"))
        runOrder(listOf("record", "candidate", "plan"))
        runOrder(listOf("plan", "record", "candidate"))
    }

    @Test
    fun multiCandidateUuidTieBreakAndIdempotentReplay() = runTest {
        val rig = SyncRig(session = joinedSession("family-uuid"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        val planUuid = "uuid-plan"
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "pending",
                updatedAt = 10,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val entities = listOf(
            SyncEntity(
                type = "record",
                clientUuid = "rec-z",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-z","type":"formula","custom_item_client_uuid":null,"timestamp":1,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 20,
            ),
            SyncEntity(
                type = "record",
                clientUuid = "rec-a",
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","created_by_membership_id":"m-a","type":"formula","custom_item_client_uuid":null,"timestamp":2,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},"schema_version":2}""",
                updatedAt = 21,
            ),
            SyncEntity(
                type = "care_plan",
                clientUuid = planUuid,
                payloadJson =
                    """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"m","fulfilled_record_client_uuid":"rec-z","fulfilled_at":50,"source_record_client_uuid":null}""",
                updatedAt = 30,
            ),
            SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "uuid-zzz",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-z","actual_timestamp":1,"submitter_membership_id":"m-z","submitter_role":"member","confirmed_at":50}""",
                updatedAt = 40,
            ),
            SyncEntity(
                type = "fulfillment_candidate",
                clientUuid = "uuid-aaa",
                payloadJson =
                    """{"care_plan_client_uuid":"$planUuid","record_client_uuid":"rec-a","actual_timestamp":2,"submitter_membership_id":"m-a","submitter_role":"member","confirmed_at":50}""",
                updatedAt = 41,
            ),
        )
        rig.backend.nextPull = PullResult(
            entities = entities,
            cursor = 200,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
            .isEqualTo("rec-a")
        // Idempotent full-page replay with same entities (cursor advance already done).
        rig.backend.nextPull = PullResult(
            entities = entities,
            cursor = 200,
            generation = "current-generation",
            hasMore = false,
        )
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()
        assertThat(rig.carePlans.getByClientUuid(planUuid)?.fulfilledRecordClientUuid)
            .isEqualTo("rec-a")
        assertThat(rig.fulfillmentCandidates.getByClientUuid("uuid-aaa")?.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
        assertThat(rig.fulfillmentCandidates.getByClientUuid("uuid-zzz")?.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
    }

    @Test
    fun fulfillReceiveCompletedPlanWithoutRecordKeepsInvisibleAndCursorUnmoved() = runTest {
        val applied = mutableListOf<String>()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            carePlanApplied = { applied += it },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val babyUuid = rig.babies.getIncludingDeleted(babyId)!!.clientUuid
        // Existing open plan revision stays visible until full set arrives.
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "gated-plan",
                status = "pending",
                updatedAt = 50,
                syncDirty = false,
            ),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val cursorBefore = rig.preferences.current().pullCursor
        rig.backend.nextPull = PullResult(
            entities = listOf(
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "gated-plan",
                    payloadJson =
                        """{"baby_client_uuid":"$babyUuid","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":null,"status":"completed","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":"member-a","fulfilled_record_client_uuid":"missing-record","fulfilled_at":900,"source_record_client_uuid":null}""",
                    updatedAt = 900,
                    deletedAt = null,
                ),
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "gated-cand",
                    payloadJson =
                        """{"care_plan_client_uuid":"gated-plan","record_client_uuid":"missing-record","actual_timestamp":null,"submitter_membership_id":"member-a","submitter_role":"member","confirmed_at":900}""",
                    updatedAt = 901,
                    deletedAt = null,
                ),
            ),
            cursor = 55,
            generation = "current-generation",
            hasMore = false,
        )
        rig.port.sync(SyncTrigger.PullToRefresh)
        // Incomplete completed-plan group stays gated: no projection, no candidate,
        // cursor held. Cycle outcome is the named deferral, not a generation fallback.
        assertThat(rig.carePlans.getByClientUuid("gated-plan")?.status).isEqualTo("pending")
        assertThat(rig.carePlans.getByClientUuid("gated-plan")?.updatedAt).isEqualTo(50)
        assertThat(rig.fulfillmentCandidates.getByClientUuid("gated-cand")).isNull()
        assertThat(applied).isEmpty()
        assertThat(rig.preferences.current().pullCursor).isEqualTo(cursorBefore)
    }

    @Test
    fun unpublishableCandidate409WritesAbandonedReceiptAndClearsPendingWithoutCard() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "never-accepted-record"
        val planUuid = "never-accepted-plan"
        val candUuid = "never-accepted-cand"
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 400,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 400,
                updatedAt = 401,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 120,
                confirmedAt = 400,
                updatedAt = 402,
                syncDirty = true,
            ),
        )
        rig.backend.failCommitRootTypeOnce =
            "fulfillment_candidate" to SyncHttpException(409, """{"detail":"UnresolvedReference"}""")

        assertThat(rig.port.sync(SyncTrigger.Foreground).isSuccess).isTrue()

        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
        assertThat(rig.port.unacceptedFact().first()).isNull()
        val local = rig.fulfillmentCandidates.getByClientUuid(candUuid)
        assertThat(local).isNotNull()
        val receipt = rig.conflictDetails.getTerminalReceipt("fulfillment_candidate", candUuid)
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.abandoned).isTrue()
        assertThat(receipt.contentEpoch).isEqualTo(402)
        assertThat(rig.backend.committedBundles).isEmpty()
        val stagedAfterAbandon = rig.backend.stagedBundles.count {
            it.root.clientUuid == candUuid
        }

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        assertThat(
            rig.backend.stagedBundles.count { it.root.clientUuid == candUuid },
        ).isEqualTo(stagedAfterAbandon)
        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
    }

    @Test
    fun unpublishableCandidate409DoesNotBlockHealthySiblingInSameBatch() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "batch-bad-record",
                updatedAt = 500,
                syncDirty = false,
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "batch-good-record",
                updatedAt = 510,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "batch-bad-plan",
                status = "completed",
                fulfilledRecordClientUuid = "batch-bad-record",
                fulfilledAt = 500,
                updatedAt = 501,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = "batch-good-plan",
                status = "completed",
                fulfilledRecordClientUuid = "batch-good-record",
                fulfilledAt = 510,
                updatedAt = 511,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = "batch-bad-cand",
                carePlanClientUuid = "batch-bad-plan",
                recordClientUuid = "batch-bad-record",
                actualTimestamp = 120,
                confirmedAt = 500,
                updatedAt = 502,
                syncDirty = true,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = "batch-good-cand",
                carePlanClientUuid = "batch-good-plan",
                recordClientUuid = "batch-good-record",
                actualTimestamp = 130,
                confirmedAt = 510,
                updatedAt = 512,
                syncDirty = true,
            ),
        )
        rig.backend.failCommitRootTypeOnce =
            "fulfillment_candidate" to SyncHttpException(409)

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        val goodDraft = rig.backend.stagedBundles.single {
            it.root.type == "fulfillment_candidate" && it.root.clientUuid == "batch-good-cand"
        }
        val badDraft = rig.backend.stagedBundles.single {
            it.root.type == "fulfillment_candidate" && it.root.clientUuid == "batch-bad-cand"
        }
        assertThat(rig.backend.committedBundles).containsExactly(goodDraft.bundleId)
        assertThat(rig.backend.committedBundles).doesNotContain(badDraft.bundleId)
        assertThat(rig.fulfillmentCandidates.getByClientUuid("batch-good-cand")?.syncDirty)
            .isFalse()
        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
        assertThat(rig.port.unacceptedFact().first()).isNull()
        val badReceipt = rig.conflictDetails.getTerminalReceipt(
            "fulfillment_candidate",
            "batch-bad-cand",
        )
        assertThat(badReceipt).isNotNull()
        assertThat(badReceipt!!.abandoned).isTrue()
    }

    @Test
    fun memberNonAuthorityBabyCandidateIsSilentlyAbandoned() = runTest {
        val rig = SyncRig(
            session = joinedSession("family-a").copy(role = FamilyRole.Member),
        )
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(
            localBaby().copy(syncDirty = false, familyAuthority = false),
        )
        val recordUuid = "member-local-record"
        val planUuid = "member-local-plan"
        val candUuid = "member-local-cand"
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 600,
                syncDirty = false,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 600,
                updatedAt = 601,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 120,
                confirmedAt = 600,
                updatedAt = 602,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()

        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
        assertThat(rig.port.unacceptedFact().first()).isNull()
        assertThat(
            rig.backend.stagedBundles.filter { it.root.type == "fulfillment_candidate" },
        ).isEmpty()
        assertThat(rig.backend.committedBundles).isEmpty()
        val local = rig.fulfillmentCandidates.getByClientUuid(candUuid)
        assertThat(local).isNotNull()
        assertThat(local!!.syncDirty).isTrue()
        val receipt = rig.conflictDetails.getTerminalReceipt("fulfillment_candidate", candUuid)
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.abandoned).isTrue()
        assertThat(receipt.contentEpoch).isEqualTo(602)
    }

    @Test
    fun abandoningParentRecordSilentlyAbandonsDirtyFulfillmentCandidate() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        assertThat(rig.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val recordUuid = "abandoned-parent-record"
        val planUuid = "abandoned-parent-plan"
        val candUuid = "abandoned-parent-cand"
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = recordUuid,
                updatedAt = 700,
                syncDirty = true,
            ),
        )
        rig.carePlans.seed(
            localCarePlan(babyId).copy(
                clientUuid = planUuid,
                status = "completed",
                fulfilledRecordClientUuid = recordUuid,
                fulfilledAt = 700,
                updatedAt = 701,
                syncDirty = false,
            ),
        )
        rig.fulfillmentCandidates.seed(
            FulfillmentCandidateEntity(
                clientUuid = candUuid,
                carePlanClientUuid = planUuid,
                recordClientUuid = recordUuid,
                actualTimestamp = 120,
                confirmedAt = 700,
                updatedAt = 702,
                syncDirty = true,
            ),
        )

        assertThat(rig.port.abandonRejectedMutation("record", recordUuid).isSuccess).isTrue()

        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(0)
        assertThat(rig.port.unacceptedFact().first()).isNull()
        assertThat(rig.fulfillmentCandidates.getByClientUuid(candUuid)).isNotNull()
        val receipt = rig.conflictDetails.getTerminalReceipt("fulfillment_candidate", candUuid)
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.abandoned).isTrue()
        assertThat(receipt.contentEpoch).isEqualTo(702)
    }

}
