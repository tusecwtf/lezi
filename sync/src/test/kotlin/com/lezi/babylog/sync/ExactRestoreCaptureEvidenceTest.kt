package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshot
import com.lezi.babylog.sync.disasterrecovery.decodeRestoreSnapshotContent
import com.lezi.babylog.sync.disasterrecovery.encodeRestoreSnapshotContent
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.disasterrecovery.RestoreSnapshotJournal
import com.lezi.babylog.sync.engine.ForegroundSyncGate
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

/** File captures must distinguish exact local values even when timestamps are unchanged. */
class ExactRestoreCaptureEvidenceTest {
    @Test(timeout = 30_000)
    fun fileCaptureDistinguishesAbsentRecordNoteFromLiteralNull() = runBlocking {
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val absent = capture(CaptureInput(records = listOf(record.copy(note = null))))
        val literal = capture(CaptureInput(records = listOf(record.copy(note = "null"))))

        assertThat(absent.single { it.type == "record" }.localEvidence)
            .isNotEqualTo(literal.single { it.type == "record" }.localEvidence)
    }

    @Test(timeout = 30_000)
    fun fileCaptureDistinguishesAbsentPlanNoteFromLiteralNull() = runBlocking {
        val plan = localCarePlan(1).copy(id = 1, clientUuid = uuid(3), deletedAt = 200)
        val absent = capture(CaptureInput(plans = listOf(plan.copy(note = null))))
        val literal = capture(CaptureInput(plans = listOf(plan.copy(note = "null"))))

        assertThat(absent.single { it.type == "care_plan" }.localEvidence)
            .isNotEqualTo(literal.single { it.type == "care_plan" }.localEvidence)
    }

    @Test(timeout = 30_000)
    fun fileCapturePersistsExplicitV2AndMissingVersionStaysLegacy() = runBlocking {
        val snapshot = captureSnapshot(CaptureInput())
        val content = Json.parseToJsonElement(requireNotNull(snapshot.fileSnapshot).manifestJson).jsonObject
        assertThat(snapshot.evidenceVersion).isEqualTo(2)
        assertThat(content.getValue("evidence_version").jsonPrimitive.int).isEqualTo(2)
        val reopened = decodeRestoreSnapshotContent(content, emptyList())
        assertThat(reopened.evidenceVersion).isEqualTo(2)
        assertThat(reopened.retirementVersions).isEqualTo(snapshot.retirementVersions)

        val legacy = decodeRestoreSnapshotContent(JsonObject(content - "evidence_version"), emptyList())
        assertThat(legacy.evidenceVersion).isEqualTo(1)
        assertThat(legacy.retirementVersions).isEqualTo(snapshot.retirementVersions)
        val reencoded = Json.parseToJsonElement(encodeRestoreSnapshotContent(legacy, "family-a")).jsonObject
        assertThat(reencoded.getValue("evidence_version").jsonPrimitive.int).isEqualTo(1)
        for (invalid in listOf("0", "3", "null", "\"2\"", "2.0", "{}")) {
            val unknown = JsonObject(content + ("evidence_version" to Json.parseToJsonElement(invalid)))
            assertThat(runCatching { decodeRestoreSnapshotContent(unknown, emptyList()) }.isFailure).isTrue()
        }
    }

    @Test(timeout = 30_000)
    fun fileCaptureSeparatesAdversarialFieldDelimitersAndEveryStringValue() = runBlocking {
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val first = capture(CaptureInput(records = listOf(record.copy(
            note = "a, payloadJson=b", payloadJson = "c"))))
        val second = capture(CaptureInput(records = listOf(record.copy(
            note = "a", payloadJson = "b, payloadJson=c"))))
        assertThat(first.single { it.type == "record" }.localEvidence)
            .isNotEqualTo(second.single { it.type == "record" }.localEvidence)

        val notes = listOf(null, "null", "", "\u0000", "护理:🍒", "é", "e\u0301", "\uD800", "\uD801", "?")
        val evidence = notes.map { note ->
            capture(CaptureInput(records = listOf(record.copy(note = note))))
                .single { it.type == "record" }.localEvidence
        }
        assertThat(evidence).doesNotContain(null)
        assertThat(evidence.toSet()).hasSize(notes.size)
    }

    @Test(timeout = 30_000)
    fun fileCaptureDistinguishesNullableNumbersAndLocalBooleans() = runBlocking {
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val numericEvidence = listOf(null, 0L, Long.MIN_VALUE, Long.MAX_VALUE).map { end ->
            capture(CaptureInput(records = listOf(record.copy(endTimestamp = end))))
                .single { it.type == "record" }.localEvidence
        }
        assertThat(numericEvidence.toSet()).hasSize(4)
        val plan = localCarePlan(1).copy(id = 1, clientUuid = uuid(3), deletedAt = 200)
        val booleanEvidence = listOf(plan, plan.copy(systemCalendarProjectionEnabled = false),
            plan.copy(systemCalendarReminderReady = true), plan.copy(systemCalendarProjectionPending = true)).map {
            capture(CaptureInput(plans = listOf(it))).single { version -> version.type == "care_plan" }.localEvidence
        }
        assertThat(booleanEvidence.toSet()).hasSize(4)
    }

    @Test(timeout = 30_000)
    fun fileCaptureIncludesDeviceAndTransportFieldsForAllSevenEntities() = runBlocking {
        val baby = localBaby().copy(id = 1, clientUuid = uuid(1))
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val plan = localCarePlan(1).copy(id = 1, clientUuid = uuid(3), deletedAt = 200)
        val custom = CustomItemEntity(id = 1, clientUuid = uuid(4), familyId = 1,
            name = "custom", iconSlot = 2, updatedAt = 120, deletedAt = 200)
        val candidate = FulfillmentCandidateEntity(id = 1, clientUuid = uuid(5),
            carePlanClientUuid = uuid(3), recordClientUuid = uuid(2), confirmedAt = 120,
            updatedAt = 120, deletedAt = 200)
        val wake = WakeObservationEntity(id = 1, clientUuid = uuid(6), sleepRecordClientUuid = uuid(2),
            wakeTimestamp = 150, updatedAt = 150, deletedAt = 200)
        val media = photo(21, 33, "record")
        val original = capture(CaptureInput(listOf(baby), listOf(record), listOf(plan), listOf(custom),
            listOf(candidate), listOf(wake), listOf(media))).associateBy { it.type }
        val changed = capture(CaptureInput(
            listOf(baby.copy(avatarPath = "null")),
            listOf(record.copy(effectiveWakeObservationClientUuid = "null")),
            listOf(plan.copy(systemCalendarEventId = "null")),
            listOf(custom.copy(sortOrder = Int.MIN_VALUE)),
            listOf(candidate.copy(convertedRecordClientUuid = "converted-local")),
            listOf(wake.copy(familyPublishedUpdatedAt = Long.MAX_VALUE)),
            listOf(media.copy(localUri = "changed-local-file")),
        )).associateBy { it.type }

        assertThat(changed.keys).containsExactlyElementsIn(original.keys)
        for ((type, version) in changed) {
            assertThat(version.updatedAt).isEqualTo(original.getValue(type).updatedAt)
            assertThat(version.localEvidence).isNotEqualTo(original.getValue(type).localEvidence)
        }
    }

    @Test(timeout = 30_000)
    fun fileCaptureSortsEachOwnersTombstonesWithoutMixingEqualLocalIds() = runBlocking {
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val plan = localCarePlan(1).copy(id = 1, clientUuid = uuid(3), deletedAt = 200)
        val wake = WakeObservationEntity(id = 1, clientUuid = uuid(6), sleepRecordClientUuid = uuid(2),
            wakeTimestamp = 150, updatedAt = 150, deletedAt = 200)
        val media = listOf(photo(10, 32, "avatar"), photo(20, 34, "record"), photo(30, 36, "plan"),
            photo(40, 38, "wake"), photo(11, 31, "avatar"), photo(21, 33, "record"),
            photo(31, 35, "plan"), photo(41, 37, "wake"))
        suspend fun captureWith(assets: List<MediaAssetEntity>) = capture(CaptureInput(
            records = listOf(record), plans = listOf(plan), wakes = listOf(wake), media = assets,
        )).associate { (it.type to it.clientUuid) to it.localEvidence }
        val original = captureWith(media)
        assertThat(captureWith(media.reversed())).isEqualTo(original)

        val changed = captureWith(media.map { if (it.clientUuid == uuid(33)) it.copy(remoteUri = "null") else it })
        assertThat(changed.filter { (key, _) -> key != ("record" to uuid(2)) && key != ("media" to uuid(33)) })
            .isEqualTo(original.filter { (key, _) -> key != ("record" to uuid(2)) && key != ("media" to uuid(33)) })
        assertThat(changed["record" to uuid(2)]).isNotEqualTo(original["record" to uuid(2)])
        assertThat(changed["media" to uuid(33)]).isNotEqualTo(original["media" to uuid(33)])
    }

    @Test(timeout = 30_000)
    fun fileCaptureKeepsThirdDuplicateAmbiguousAndEqualUuidAttachmentOrderStable() = runBlocking {
        val record = localRecord(1).copy(id = 1, clientUuid = uuid(2), deletedAt = 200)
        val media = listOf(photo(21, 33, "record"), photo(22, 33, "record"), photo(23, 33, "record"))
        val original = capture(CaptureInput(records = listOf(record), media = media))
        val reversed = capture(CaptureInput(records = listOf(record), media = media.reversed()))
        assertThat(original.filter { it.type == "media" }.map { it.localEvidence }).containsExactly(null, null, null)
        assertThat(reversed.filter { it.type == "media" }.map { it.localEvidence }).containsExactly(null, null, null)
        assertThat(original.single { it.type == "record" }.localEvidence).isNotNull()
        assertThat(original.single { it.type == "record" }.localEvidence)
            .isNotEqualTo(reversed.single { it.type == "record" }.localEvidence)
    }

    @Test(timeout = 30_000)
    fun fileCaptureVisitsCapturedRowsWithinALinearBudget() = runBlocking {
        for (recordCount in listOf(32, 64)) {
            val records = List(recordCount) { index -> localRecord(1).copy(id = index + 1L,
                clientUuid = uuid(1_000 + index), deletedAt = 200) }
            val media = records.flatMapIndexed { index, record -> List(2) { attachment ->
                MediaAssetEntity(id = 2L * index + attachment + 1,
                    clientUuid = uuid(10_000 + 2 * index + attachment), recordId = record.id,
                    localUri = "deleted-$index-$attachment.jpg", createdAt = 120,
                    updatedAt = 200, deletedAt = 200)
            } }
            val input = CaptureInput(records = records, media = media)
            val snapshot = captureSnapshot(input)
            assertThat(snapshot.evidenceVersion).isEqualTo(2)
            assertThat(snapshot.retirementVersions).hasSize(1 + recordCount + media.size)
            assertThat(snapshot.retirementVersions.all { it.localEvidence != null }).isTrue()
            assertThat(input.observations.values.sum()).isAtMost(12L * (1 + recordCount + media.size))
        }
    }

    private suspend fun capture(input: CaptureInput): List<DisasterRestoreEntityVersion> =
        captureSnapshot(input).retirementVersions

    private suspend fun captureSnapshot(input: CaptureInput): DisasterRecoverySnapshot {
        val root = Files.createTempDirectory("restore-capture-evidence").toFile()
        return try {
            captureSnapshot(input, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun captureSnapshot(input: CaptureInput, root: File): DisasterRecoverySnapshot {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        input.babies.forEach(rig.babies::seed)
        val startup = TestPendingReplicaCleanupStore()
        val port: SyncPort = RealSyncPort(
            backend = rig.backend,
            preferences = rig.preferences,
            setupProbe = SetupProbe { _, trusted ->
                SetupProbeResult.Ready(
                    requireNotNull(trusted),
                    SetupFamilyState.Empty,
                    setOf("nursing_plan_intent_v1", "restore_authority_v1"),
                )
            },
            foregroundSyncGate = ForegroundSyncGate(),
            pendingPublishDao = rig.pendingPublish,
            recordDao = object : RecordDao by rig.records {
                override suspend fun listAllIncludingDeleted() = input.records
            },
            carePlanDao = object : CarePlanDao by rig.carePlans {
                override suspend fun listAllIncludingDeleted() = input.plans
            },
            babyDao = object : BabyDao by rig.babies {
                override suspend fun listAllIncludingDeleted() = input.babies
            },
            mediaDao = object : MediaAssetDao by rig.media {
                override suspend fun listAllIncludingDeleted() = input.media
            },
            customItemDao = object : CustomItemDao by rig.customItems {
                override suspend fun listAllIncludingDeleted() = input.customItems
            },
            familyDao = rig.families,
            clock = rig.clock,
            foregroundState = rig.foreground,
            mediaFiles = rig.mediaFiles,
            immutableMediaSpool = rig.immutableMediaSpool,
            mediaFileCleanup = rig.mediaFileCleanup,
            transactionRunner = rig.transactions,
            pendingReplicaCleanupStore = startup,
            fulfillmentCandidateDao = object : FulfillmentCandidateDao by rig.fulfillmentCandidates {
                override suspend fun listAllIncludingDeleted() = input.candidates
            },
            fulfillmentAuthoritySettlement = rig.fulfillmentAuthoritySettlement,
            wakeObservationDao = object : WakeObservationDao by rig.wakeObservations {
                override suspend fun listAllIncludingDeleted() = input.wakes
            },
            conflictSummaryDao = rig.conflictSummaries,
            conflictSnapshotCacheDao = rig.conflictDetails,
            sourceRelationDao = rig.sourceRelations,
            restoreSnapshotsDir = root,
        )
        startup.firstLoad.await()
        port.startDisasterRecovery(
            TrustedEndpointProfile.systemPki("https://restore-capture.example.test"),
            "Owner", "Phone", "root",
        ).getOrThrow()
        val checkpoint = requireNotNull(rig.preferences.disasterRestoreCheckpoint.first())
        return RestoreSnapshotJournal(
            rig.conflictDetails, rig.immutableMediaSpool, rig.transactions, RestoreFileSnapshotStore(root),
        ).load(checkpoint.startRequestId).use { it }
    }

    private class CaptureInput(
        babies: List<BabyEntity> = listOf(localBaby().copy(id = 1, clientUuid = uuid(1))),
        records: List<RecordEntity> = emptyList(),
        plans: List<CarePlanEntity> = emptyList(),
        customItems: List<CustomItemEntity> = emptyList(),
        candidates: List<FulfillmentCandidateEntity> = emptyList(),
        wakes: List<WakeObservationEntity> = emptyList(),
        media: List<MediaAssetEntity> = emptyList(),
    ) {
        val observations = linkedMapOf<String, Long>()
        val babies = observed("baby", babies)
        val records = observed("record", records)
        val plans = observed("care_plan", plans)
        val customItems = observed("custom_item", customItems)
        val candidates = observed("fulfillment_candidate", candidates)
        val wakes = observed("wake_observation", wakes)
        val media = observed("media", media)

        private fun <T> observed(type: String, source: List<T>): List<T> = object : AbstractList<T>() {
            override val size get() = source.size
            override fun get(index: Int): T {
                observations[type] = observations.getOrDefault(type, 0) + 1
                return source[index]
            }
        }
    }

    companion object {
        private fun uuid(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

        private fun photo(id: Long, uuid: Int, owner: String) = MediaAssetEntity(
            id = id,
            clientUuid = uuid(uuid),
            kind = when (owner) { "avatar" -> "avatar"; "wake" -> "wake"; else -> "log" },
            babyId = if (owner == "avatar") 1 else null,
            recordId = if (owner == "record") 1 else null,
            carePlanId = if (owner == "plan") 1 else null,
            wakeObservationId = if (owner == "wake") 1 else null,
            localUri = "照片:$id-🍒.jpg",
            mime = "image/jpeg",
            createdAt = 100,
            updatedAt = 120,
            deletedAt = 200,
        )
    }
}
