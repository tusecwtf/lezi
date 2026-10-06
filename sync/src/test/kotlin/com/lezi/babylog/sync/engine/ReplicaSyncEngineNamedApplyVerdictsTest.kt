package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.PullDiagnosticReceipt
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.causal.decodePullDiagnosticReceipt
import com.lezi.babylog.core.database.causal.encodePullDiagnosticReceipt
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] × [ReplicaEngineRig] for
 * ticket 01 named apply verdicts. Every reference gate in the pull apply
 * tree must produce a durable `reference_unready` receipt carrying
 * `reason_gate` (the [DeferredGate] name), the missing reference identity,
 * and the local state snapshot observed at the gate. The receipt codec
 * round-trips reason fields and still decodes old 4-field receipts.
 *
 * MediaBytesUnstaged and BabyLocalDirty have no engine fixture here: staging
 * covers the identical live log/wake entity set inside the same applyRemote
 * call (a failed staging aborts the page before apply), and the causal
 * stable-pull gate short-circuits dirty causal roots before applyBaby's dirty
 * checks. Both verdicts stay constructable and codec-round-tripped in
 * [everyDeferredGateSurvivesReceiptCodecRoundTrip].
 */
class ReplicaSyncEngineNamedApplyVerdictsTest {

    // --------------------------------------------------------------------
    // Receipt codec
    // --------------------------------------------------------------------

    @Test
    fun receiptCodecRoundTripsReasonFields() {
        val receipt = PullDiagnosticReceipt(
            entityType = "wake_observation",
            clientUuid = WAKE_UUID,
            code = "reference_unready",
            recordedAt = 42L,
            reasonGate = DeferredGate.SleepTypeMismatch.name,
            missingEntityType = "record",
            missingClientUuid = MISSING_SLEEP_UUID,
            localSnapshot = "sleep=absent",
        )

        assertThat(decodePullDiagnosticReceipt(encodePullDiagnosticReceipt(receipt)))
            .isEqualTo(receipt)
    }

    @Test
    fun encodedReceiptOmitsNullReasonKeys() {
        val json = encodePullDiagnosticReceipt(
            PullDiagnosticReceipt(
                entityType = "record",
                clientUuid = RECORD_UUID,
                code = "reference_unready",
                recordedAt = 7L,
            ),
        )

        assertThat(json).doesNotContain("reason_gate")
        assertThat(json).doesNotContain("missing_entity_type")
        assertThat(json).doesNotContain("missing_client_uuid")
        assertThat(json).doesNotContain("local_snapshot")
    }

    @Test
    fun oldReceiptJsonWithoutReasonStillDecodes() {
        val decoded = decodePullDiagnosticReceipt(
            """{"entity_type":"record","client_uuid":"old-record",""" +
                """"code":"reference_unready","recorded_at":7}""",
        )

        assertThat(decoded.entityType).isEqualTo("record")
        assertThat(decoded.clientUuid).isEqualTo("old-record")
        assertThat(decoded.code).isEqualTo("reference_unready")
        assertThat(decoded.recordedAt).isEqualTo(7L)
        assertThat(decoded.reasonGate).isNull()
        assertThat(decoded.missingEntityType).isNull()
        assertThat(decoded.missingClientUuid).isNull()
        assertThat(decoded.localSnapshot).isNull()
    }

    @Test
    fun everyDeferredGateSurvivesReceiptCodecRoundTrip() {
        for (gate in DeferredGate.entries) {
            val receipt = PullDiagnosticReceipt(
                entityType = "record",
                clientUuid = RECORD_UUID,
                code = "reference_unready",
                recordedAt = 1L,
                reasonGate = gate.name,
                missingEntityType = "baby",
                missingClientUuid = MISSING_BABY_UUID,
                localSnapshot = "baby=absent",
            )

            assertThat(decodePullDiagnosticReceipt(encodePullDiagnosticReceipt(receipt)))
                .isEqualTo(receipt)
        }
    }

    // --------------------------------------------------------------------
    // Record gates
    // --------------------------------------------------------------------

    @Test
    fun recordWithMissingBabyJournalsBabyMissing() = runTest {
        val rig = newRig()

        pullOnce(rig, remoteReplicaRecord(RECORD_UUID, babyClientUuid = MISSING_BABY_UUID))

        assertThat(rig.records.getByClientUuid(RECORD_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.BabyMissing,
            missingEntityType = "baby",
            missingClientUuid = MISSING_BABY_UUID,
            localSnapshot = "baby=absent",
        )
    }

    @Test
    fun customRecordWithMissingCustomItemJournalsCustomItemMissing() = runTest {
        val rig = newRig()
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false, familyAuthority = true))

        pullOnce(rig, remoteCustomRecord(RECORD_UUID, MISSING_CUSTOM_ITEM_UUID))

        assertThat(rig.records.getByClientUuid(RECORD_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.CustomItemMissing,
            missingEntityType = "custom_item",
            missingClientUuid = MISSING_CUSTOM_ITEM_UUID,
            localSnapshot = "custom_item=absent",
        )
    }

    // --------------------------------------------------------------------
    // Family row gates
    // --------------------------------------------------------------------

    @Test
    fun babyWithoutFamilyRowJournalsFamilyRowMissing() = runTest {
        val rig = newRig()
        rig.families.deleteAll()

        pullOnce(rig, remoteReplicaBaby().copy(clientUuid = FRESH_BABY_UUID))

        assertThat(rig.babies.getByClientUuid(FRESH_BABY_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.FamilyRowMissing,
            missingEntityType = "family",
            missingClientUuid = "",
            localSnapshot = "familyDao.empty=true",
        )
    }

    @Test
    fun customItemWithoutFamilyRowJournalsFamilyRowMissing() = runTest {
        val rig = newRig()
        rig.families.deleteAll()

        pullOnce(rig, remoteCustomItem(FRESH_CUSTOM_ITEM_UUID))

        assertThat(rig.customItems.getByClientUuid(FRESH_CUSTOM_ITEM_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.FamilyRowMissing,
            missingEntityType = "family",
            missingClientUuid = "",
            localSnapshot = "familyDao.empty=true",
        )
    }

    // --------------------------------------------------------------------
    // Wake gates
    // --------------------------------------------------------------------

    @Test
    fun wakeWithoutParentSleepJournalsSleepTypeMismatch() = runTest {
        val rig = newRig()

        pullOnce(rig, remoteWake(WAKE_UUID, MISSING_SLEEP_UUID))

        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.SleepTypeMismatch,
            missingEntityType = "record",
            missingClientUuid = MISSING_SLEEP_UUID,
            localSnapshot = "sleep=absent",
        )
    }

    @Test
    fun wakeUnderNonSleepParentJournalsSleepTypeMismatch() = runTest {
        val rig = newRig()
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = FORMULA_SLEEP_UUID,
                babyId = babyId,
                type = "formula",
                timestamp = 1_000,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = false,
            ),
        )

        pullOnce(rig, remoteWake(WAKE_UUID, FORMULA_SLEEP_UUID))

        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.SleepTypeMismatch,
            missingEntityType = "record",
            missingClientUuid = FORMULA_SLEEP_UUID,
            localSnapshot = "sleep.type=formula,expected=sleep",
        )
    }

    @Test
    fun retargetedWakeJournalsWakeRetarget() = runTest {
        val rig = newRig()
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = WAKE_UUID,
                sleepRecordClientUuid = SLEEP_UUID_A,
                wakeTimestamp = 1_500,
                observerMembershipId = "membership-b",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = false,
            ),
        )

        pullOnce(rig, remoteWake(WAKE_UUID, SLEEP_UUID_B))

        assertThat(rig.wakeObservations.getByClientUuid(WAKE_UUID)?.sleepRecordClientUuid)
            .isEqualTo(SLEEP_UUID_A)
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.WakeRetarget,
            missingEntityType = "record",
            missingClientUuid = SLEEP_UUID_B,
            localSnapshot = "existing.sleep=$SLEEP_UUID_A incoming=$SLEEP_UUID_B",
        )
    }

    // --------------------------------------------------------------------
    // Care plan gates
    // --------------------------------------------------------------------

    @Test
    fun planWithMissingBabyJournalsPlanBabyMissing() = runTest {
        val rig = newRig()

        pullOnce(rig, remotePlan(PLAN_UUID, babyClientUuid = MISSING_BABY_UUID))

        assertThat(rig.carePlans.getByClientUuid(PLAN_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.PlanBabyMissing,
            missingEntityType = "baby",
            missingClientUuid = MISSING_BABY_UUID,
            localSnapshot = "baby=absent",
        )
    }

    @Test
    fun planWithMissingCustomItemJournalsPlanCustomItemMissing() = runTest {
        val rig = newRig()
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false, familyAuthority = true))

        pullOnce(rig, remotePlan(PLAN_UUID, customItemClientUuid = MISSING_CUSTOM_ITEM_UUID))

        assertThat(rig.carePlans.getByClientUuid(PLAN_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.PlanCustomItemMissing,
            missingEntityType = "custom_item",
            missingClientUuid = MISSING_CUSTOM_ITEM_UUID,
            localSnapshot = "custom_item=absent",
        )
    }

    @Test
    fun completedPlanWithMissingFulfilledRecordJournalsPlanFulfilledRecordMissing() = runTest {
        val rig = newRig()
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false, familyAuthority = true))

        pullOnce(
            rig,
            remotePlan(
                PLAN_UUID,
                fulfilledRecordClientUuid = MISSING_FULFILLED_RECORD_UUID,
                status = "completed",
            ),
        )

        assertThat(rig.carePlans.getByClientUuid(PLAN_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.PlanFulfilledRecordMissing,
            missingEntityType = "record",
            missingClientUuid = MISSING_FULFILLED_RECORD_UUID,
            localSnapshot = "fulfilled_record=absent",
        )
    }

    @Test
    fun planWithForeignFulfilledRecordJournalsPlanFulfilledRecordBabyMismatch() = runTest {
        val rig = newRig()
        val planBabyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val otherBabyId = rig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = OTHER_BABY_UUID,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        rig.records.seed(
            RecordEntity(
                clientUuid = FULFILLED_RECORD_UUID,
                babyId = otherBabyId,
                type = "formula",
                timestamp = 1_000,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 200,
                syncDirty = false,
            ),
        )

        pullOnce(
            rig,
            remotePlan(
                PLAN_UUID,
                fulfilledRecordClientUuid = FULFILLED_RECORD_UUID,
                status = "completed",
            ),
        )

        assertThat(rig.carePlans.getByClientUuid(PLAN_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.PlanFulfilledRecordBabyMismatch,
            missingEntityType = "record",
            missingClientUuid = FULFILLED_RECORD_UUID,
            localSnapshot = "fulfilled.babyId=$otherBabyId,plan.babyId=$planBabyId",
        )
    }

    // --------------------------------------------------------------------
    // Fulfillment gates
    // --------------------------------------------------------------------

    @Test
    fun fulfillmentCandidateWithMissingPlanJournalsFulfillmentPlanMissing() = runTest {
        val rig = newRig()

        pullOnce(
            rig,
            remoteFulfillment(
                FULFILLMENT_UUID,
                planClientUuid = MISSING_PLAN_UUID,
                recordClientUuid = MISSING_FULFILLED_RECORD_UUID,
            ),
        )

        assertThat(rig.fulfillmentCandidates.getByClientUuid(FULFILLMENT_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.FulfillmentPlanMissing,
            missingEntityType = "care_plan",
            missingClientUuid = MISSING_PLAN_UUID,
            localSnapshot = "care_plan=absent",
        )
    }

    @Test
    fun fulfillmentCandidateWithMissingRecordJournalsFulfillmentRecordMissing() = runTest {
        val rig = newRig()
        rig.carePlans.seed(
            localReplicaCarePlan(
                clientUuid = LOCAL_PLAN_UUID,
                creatorMembershipId = "membership-b",
                updatedAt = 200,
            ),
        )

        pullOnce(
            rig,
            remoteFulfillment(
                FULFILLMENT_UUID,
                planClientUuid = LOCAL_PLAN_UUID,
                recordClientUuid = MISSING_FULFILLED_RECORD_UUID,
            ),
        )

        assertThat(rig.fulfillmentCandidates.getByClientUuid(FULFILLMENT_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.FulfillmentRecordMissing,
            missingEntityType = "record",
            missingClientUuid = MISSING_FULFILLED_RECORD_UUID,
            localSnapshot = "record=absent",
        )
    }

    // --------------------------------------------------------------------
    // Media gates
    // --------------------------------------------------------------------

    @Test
    fun logMediaWithMissingRecordJournalsMediaRecordMissing() = runTest {
        val rig = newRig()

        pullOnce(rig, remoteReplicaMedia(MEDIA_UUID, recordClientUuid = MISSING_RECORD_UUID))

        assertThat(rig.media.getByClientUuid(MEDIA_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.MediaRecordMissing,
            missingEntityType = "record",
            missingClientUuid = MISSING_RECORD_UUID,
            localSnapshot = "record=absent",
        )
    }

    @Test
    fun planMediaWithMissingPlanJournalsMediaCarePlanMissing() = runTest {
        val rig = newRig()

        pullOnce(rig, remoteReplicaPlanMedia(MEDIA_UUID, carePlanClientUuid = MISSING_PLAN_UUID))

        assertThat(rig.media.getByClientUuid(MEDIA_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.MediaCarePlanMissing,
            missingEntityType = "care_plan",
            missingClientUuid = MISSING_PLAN_UUID,
            localSnapshot = "care_plan=absent",
        )
    }

    @Test
    fun avatarMediaWithMissingBabyJournalsMediaBabyMissing() = runTest {
        val rig = newRig()

        pullOnce(rig, remoteAvatarMedia(MEDIA_UUID, babyClientUuid = MISSING_BABY_UUID))

        assertThat(rig.media.getByClientUuid(MEDIA_UUID)).isNull()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.MediaBabyMissing,
            missingEntityType = "baby",
            missingClientUuid = MISSING_BABY_UUID,
            localSnapshot = "baby=absent",
        )
    }

    @Test
    fun wakeMediaWithMissingWakeRootJournalsMediaWakeMissing() = runTest {
        val rig = newRig(enableCausal = true)
        rig.backend.mediaBytesByUuid[MEDIA_UUID] = KNOWN_BYTES

        // The wake root rides the same page with its media manifest but stays
        // deferred itself (missing sleep), so the wake media finds no root.
        pullOnce(
            rig,
            remoteWake(MISSING_WAKE_ROOT_UUID, MISSING_SLEEP_UUID)
                .copy(media = listOf(causalWakeMediaIdentity(MEDIA_UUID))),
            remoteWakeMedia(MEDIA_UUID, wakeClientUuid = MISSING_WAKE_ROOT_UUID),
        )

        assertThat(rig.wakeObservations.getByClientUuid(MISSING_WAKE_ROOT_UUID)).isNull()
        assertThat(rig.media.getByClientUuid(MEDIA_UUID)).isNull()
        val receipts = rig.conflictDetails.listPullDiagnostics()
        assertThat(receipts).hasSize(2)
        val mediaReceipt = receipts.first { it.reasonGate == DeferredGate.MediaWakeMissing.name }
        assertThat(mediaReceipt.entityType).isEqualTo("media")
        assertThat(mediaReceipt.missingEntityType).isEqualTo("wake_observation")
        assertThat(mediaReceipt.missingClientUuid).isEqualTo(MISSING_WAKE_ROOT_UUID)
        assertThat(mediaReceipt.localSnapshot).isEqualTo("wake_observation=absent")
        val rootReceipt = receipts.single { it.clientUuid == MISSING_WAKE_ROOT_UUID }
        assertThat(rootReceipt.reasonGate).isEqualTo(DeferredGate.SleepTypeMismatch.name)
    }

    @Test
    fun inCycleLocalMediaEditJournalsMediaEditGuard() = runTest {
        val rig = newRig()
        val session = rig.preferences.current()
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(syncDirty = false, familyAuthority = true),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = RECORD_UUID,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = MEDIA_UUID,
                kind = "log",
                localUri = "",
                remoteUri = session.receiptFor(MEDIA_UUID),
                mime = "image/jpeg",
                byteSize = 12,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = false,
            ),
        )
        // A local edit lands between the pull's edit-guard capture and the
        // remote apply (hooked exactly where the staged download persists).
        rig.mediaFiles.afterSaveDownloaded = {
            val current = requireNotNull(rig.media.getByClientUuid(MEDIA_UUID))
            rig.media.update(
                current.copy(
                    localUri = "photos/in-cycle-edit.jpg",
                    updatedAt = current.updatedAt + 1,
                ),
            )
        }

        pullOnce(rig, remoteReplicaMedia(MEDIA_UUID, recordClientUuid = RECORD_UUID))

        // The in-cycle local edit wins: the remote media row is not applied.
        val row = requireNotNull(rig.media.getByClientUuid(MEDIA_UUID))
        assertThat(row.localUri).isEqualTo("photos/in-cycle-edit.jpg")
        assertThat(row.syncDirty).isFalse()
        assertReason(
            singleReceipt(rig),
            gate = DeferredGate.MediaEditGuard,
            missingEntityType = "media",
            missingClientUuid = MEDIA_UUID,
            localSnapshot = "media.inCycleLocalEdit=true",
        )
    }

    // --------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------

    private fun newRig(enableCausal: Boolean = true): ReplicaEngineRig {
        val session = joinedReplicaSession().copy(
            role = FamilyRole.Owner,
            pullCursor = 2,
        )
        return ReplicaEngineRig(session).also { it.backend.enableCausal = enableCausal }
    }

    private suspend fun pullOnce(
        rig: ReplicaEngineRig,
        vararg entities: SyncEntity,
    ) {
        val session = rig.preferences.current()
        rig.backend.nextPull = PullResult(
            entities = entities.toList(),
            cursor = 7,
            generation = session.pullGeneration,
            hasMore = false,
        )
        rig.engine.synchronize(session, SyncTrigger.PullToRefresh)
    }

    private suspend fun singleReceipt(rig: ReplicaEngineRig): PullDiagnosticReceipt =
        requireNotNull(rig.conflictDetails.listPullDiagnostics().single())

    private fun assertReason(
        receipt: PullDiagnosticReceipt,
        gate: DeferredGate,
        missingEntityType: String,
        missingClientUuid: String,
        localSnapshot: String,
    ) {
        assertThat(receipt.code).isEqualTo("reference_unready")
        assertThat(receipt.reasonGate).isEqualTo(gate.name)
        assertThat(receipt.missingEntityType).isEqualTo(missingEntityType)
        assertThat(receipt.missingClientUuid).isEqualTo(missingClientUuid)
        assertThat(receipt.localSnapshot).isEqualTo(localSnapshot)
    }

    private companion object {
        const val WAKE_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee02"
        const val RECORD_UUID = "77777777-7777-4777-8777-777777777701"
        const val PLAN_UUID = "88888888-8888-4888-8888-888888888801"
        const val LOCAL_PLAN_UUID = "88888888-8888-4888-8888-888888888802"
        const val FULFILLMENT_UUID = "aaaa0000-0000-4000-8000-000000000001"
        const val FULFILLED_RECORD_UUID = "99999999-9999-4999-8999-999999999901"
        const val MISSING_FULFILLED_RECORD_UUID = "99999999-9999-4999-8999-999999999902"
        const val MISSING_RECORD_UUID = "99999999-9999-4999-8999-999999999903"
        const val MISSING_PLAN_UUID = "88888888-8888-4888-8888-888888888803"
        const val MISSING_BABY_UUID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb01"
        const val OTHER_BABY_UUID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb03"
        const val FRESH_BABY_UUID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeee01"
        const val FRESH_CUSTOM_ITEM_UUID = "dddddddd-dddd-4ddd-8ddd-dddddddddd01"
        const val MISSING_CUSTOM_ITEM_UUID = "cccccccc-cccc-4ccc-8ccc-cccccccccc01"
        const val MISSING_SLEEP_UUID = "55555555-5555-4555-8555-555555555501"
        const val FORMULA_SLEEP_UUID = "55555555-5555-4555-8555-555555555502"
        const val SLEEP_UUID_A = "66666666-6666-4666-8666-666666666601"
        const val SLEEP_UUID_B = "66666666-6666-4666-8666-666666666602"
        const val MEDIA_UUID = "11111111-1111-4111-8111-111111111101"
        const val MISSING_WAKE_ROOT_UUID = "12121212-1212-4121-8121-121212121202"
        val KNOWN_BYTES = byteArrayOf(1, 2, 3, 4)
        const val KNOWN_BYTES_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"

        fun jsonStringOrNull(value: String?): String = value?.let { "\"$it\"" } ?: "null"

        fun remoteWake(clientUuid: String, sleepUuid: String) = SyncEntity(
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

        fun remoteCustomRecord(
            clientUuid: String,
            customItemClientUuid: String,
            babyClientUuid: String = "baby-local",
        ) = SyncEntity(
            type = "record",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "baby_client_uuid":"$babyClientUuid",
                  "created_by_membership_id":"membership-b",
                  "type":"custom",
                  "custom_item_client_uuid":"$customItemClientUuid",
                  "timestamp":210,
                  "end_timestamp":null,
                  "note":null,
                  "payload_json":{"amount_ml":90},
                  "schema_version":2
                }
            """.trimIndent(),
            updatedAt = 210,
        )

        fun remoteCustomItem(clientUuid: String) = SyncEntity(
            type = "custom_item",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "name":"远端自定义",
                  "icon_slot":0,
                  "created_by_membership_id":"membership-b"
                }
            """.trimIndent(),
            updatedAt = 210,
        )

        fun remotePlan(
            clientUuid: String,
            babyClientUuid: String = "baby-local",
            customItemClientUuid: String? = null,
            fulfilledRecordClientUuid: String? = null,
            status: String = if (fulfilledRecordClientUuid == null) "pending" else "completed",
        ) = SyncEntity(
            type = "care_plan",
            clientUuid = clientUuid,
            payloadJson = """
                {
                  "baby_client_uuid":"$babyClientUuid",
                  "type":"${if (customItemClientUuid == null) "formula" else "custom"}",
                  "custom_item_client_uuid":${jsonStringOrNull(customItemClientUuid)},
                  "scheduled_at":9000000001000,
                  "scheduled_zone_id":"Asia/Shanghai",
                  "note":null,
                  "status":"$status",
                  "payload_json":{"amount_ml":120},
                  "schema_version":2,
                  "created_by_membership_id":"membership-b",
                  "fulfilled_record_client_uuid":${jsonStringOrNull(fulfilledRecordClientUuid)},
                  "fulfilled_at":${if (fulfilledRecordClientUuid == null) "null" else "5000"},
                  "source_record_client_uuid":null
                }
            """.trimIndent(),
            updatedAt = 210,
        )

        fun remoteFulfillment(
            clientUuid: String,
            planClientUuid: String,
            recordClientUuid: String,
        ) = SyncEntity(
            type = "fulfillment_candidate",
            clientUuid = clientUuid,
            payloadJson = """{"care_plan_client_uuid":"$planClientUuid",""" +
                """"record_client_uuid":"$recordClientUuid","actual_timestamp":null,""" +
                """"submitter_membership_id":"membership-b","submitter_role":"member",""" +
                """"confirmed_at":800}""",
            updatedAt = 210,
        )

        fun remoteAvatarMedia(clientUuid: String, babyClientUuid: String) = SyncEntity(
            type = "media",
            clientUuid = clientUuid,
            payloadJson = """{"kind":"avatar","record_client_uuid":null,""" +
                """"care_plan_client_uuid":null,"baby_client_uuid":"$babyClientUuid",""" +
                """"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
            updatedAt = 210,
        )

        fun remoteWakeMedia(clientUuid: String, wakeClientUuid: String) = SyncEntity(
            type = "media",
            clientUuid = clientUuid,
            payloadJson = """{"kind":"wake","record_client_uuid":"$wakeClientUuid",""" +
                """"care_plan_client_uuid":null,"baby_client_uuid":null,""" +
                """"mime":"image/jpeg","width":null,"height":null,"byte_size":4}""",
            updatedAt = 300,
        )

        fun causalWakeMediaIdentity(mediaUuid: String) = CausalMediaItem(
            mediaUuid = mediaUuid,
            role = "wake",
            sha256 = KNOWN_BYTES_SHA256,
            byteSize = 4,
            mime = "image/jpeg",
        )
    }
}
