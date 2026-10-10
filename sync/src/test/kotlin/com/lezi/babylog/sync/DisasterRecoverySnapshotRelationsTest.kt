package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.backend.DisasterRestoreSourceRelation
import com.lezi.babylog.sync.disasterrecovery.DisasterRecoverySnapshotBuilder
import com.lezi.babylog.sync.disasterrecovery.decodeRestoreSnapshotContent
import com.lezi.babylog.sync.disasterrecovery.encodeRestoreSnapshotContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DisasterRecoverySnapshotRelationsTest {
    @Test
    fun selectedTombstoneStaysSelectedAndIsCapturedWithOriginalDeletion() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "display", deletedAt = 300))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source"))
        fixture.relations.applyCanonicalTransition(
            SourceRelationEntity("relation", "display", reason = "owner_group_resolve",
                mutationId = "auto-near-neighbor:original", createdByMembershipId = "owner", createdAt = 200),
            listOf(SourceRelationMemberEntity("relation", "display", "display"),
                SourceRelationMemberEntity("relation", "source", "source")),
        )

        val snapshot = fixture.builder().build()

        assertThat(snapshot.sourceRelations).containsExactly(
            DisasterRestoreSourceRelation("relation", "display", listOf("source"), true),
        )
        assertThat(snapshot.entities.single { it.clientUuid == "display" }.deletedAt).isEqualTo(300)
        assertThat(snapshot.retirementVersions.single { it.clientUuid == "display" }.restored).isTrue()
        assertThat(snapshot.summary.records).isEqualTo(1)
        val decoded = decodeRestoreSnapshotContent(
            Json.parseToJsonElement(encodeRestoreSnapshotContent(snapshot, "family")).jsonObject, emptyList())
        assertThat(decoded.sourceRelations).isEqualTo(snapshot.sourceRelations)
        assertThat(decoded.retirementVersions).isEqualTo(snapshot.retirementVersions)
        assertThat(decoded.summary.records).isEqualTo(1)
    }

    @Test
    fun immutableContentKeepsCapturedEmptyDistinctFromLegacyUnknown() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())
        val snapshot = fixture.builder().build()
        val content = Json.parseToJsonElement(encodeRestoreSnapshotContent(snapshot, "family")).jsonObject

        val decoded = decodeRestoreSnapshotContent(content, emptyList())
        val legacy = decodeRestoreSnapshotContent(JsonObject(content - "source_relations" -
            "source_relation_evidence"), emptyList())

        assertThat(decoded.sourceRelations).isEmpty()
        assertThat(decoded.sourceRelationEvidence).isEqualTo(snapshot.sourceRelationEvidence)
        assertThat(decoded.sourceRelationEvidence).isNotNull()
        assertThat(legacy.sourceRelations).isNull()
        assertThat(legacy.sourceRelationEvidence).isNull()
    }

    @Test
    fun movedMembersExcludeObsoleteHeaderAndFreezeNewSelection() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "a"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "b"))
        fixture.relation("old", "a", "b")
        val before = fixture.builder().build()
        fixture.relation("new", "b", "a")

        val after = fixture.builder().build()

        assertThat(after.sourceRelations).containsExactly(
            DisasterRestoreSourceRelation("new", "b", listOf("a"), false))
        assertThat(after.sourceRelationEvidence).isNotEqualTo(before.sourceRelationEvidence)
        val reopened = decodeRestoreSnapshotContent(
            Json.parseToJsonElement(encodeRestoreSnapshotContent(before, "family")).jsonObject, emptyList())
        assertThat(reopened.sourceRelations).containsExactly(
            DisasterRestoreSourceRelation("old", "a", listOf("b"), false))
    }

    @Test
    fun memberMovedToAnotherBabyKeepsHistoricalSelectionWithoutRegrouping() = runTest {
        val fixture = Fixture()
        val firstBabyId = fixture.babies.seed(localBaby())
        val secondBabyId = fixture.babies.seed(localBaby().copy(clientUuid = "other-baby"))
        fixture.records.seed(localRecord(firstBabyId).copy(clientUuid = "display"))
        fixture.records.seed(localRecord(firstBabyId).copy(clientUuid = "source"))
        fixture.relation("relation", "display", "source")
        fixture.records.update(requireNotNull(fixture.records.getByClientUuid("source"))
            .copy(babyId = secondBabyId, timestamp = 9_000_000, updatedAt = 500))

        val snapshot = fixture.builder().build()

        assertThat(snapshot.sourceRelations).containsExactly(
            DisasterRestoreSourceRelation("relation", "display", listOf("source"), false))
        val payload = Json.parseToJsonElement(snapshot.entities.single { it.clientUuid == "source" }.payloadJson).jsonObject
        assertThat(payload.getValue("baby_client_uuid").jsonPrimitive.content).isEqualTo("other-baby")
        assertThat(snapshot.summary.babies).isEqualTo(2)
    }

    @Test
    fun partialFingerprintedMembershipFailsEvenWithOneDisplayAndOneSource() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        listOf("display", "source", "missing").forEach {
            fixture.records.seed(localRecord(babyId).copy(clientUuid = it))
        }
        fixture.relations.applyPullSummary("relation", "display", "display", listOf("source", "missing"), 200)
        val completeHeader = requireNotNull(fixture.relations.get("relation"))
        fixture.relations.deleteAllMembers()
        fixture.relations.seedRaw(completeHeader, listOf(
            SourceRelationMemberEntity("relation", "display", "display"),
            SourceRelationMemberEntity("relation", "source", "source")))

        assertThat(runCatching { fixture.builder().build() }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun fingerprintlessSourceFirstMembershipFailsWithoutGuessingDisplay() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source"))
        fixture.relations.seedRaw(SourceRelationEntity("relation", "", reason = "pull_summary",
            mutationId = "pull-relation", createdByMembershipId = "", createdAt = 200),
            listOf(SourceRelationMemberEntity("relation", "source", "source")))

        assertThat(runCatching { fixture.builder().build() }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun fingerprintlessDisplayDoesNotProveCompleteMembership() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "display"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source"))
        fixture.relations.seedRaw(SourceRelationEntity("relation", "display", reason = "pull_summary",
            mutationId = "pull-relation", createdByMembershipId = "", createdAt = 200),
            listOf(SourceRelationMemberEntity("relation", "display", "display"),
                SourceRelationMemberEntity("relation", "source", "source")))

        assertThat(runCatching { fixture.builder().build() }.isFailure).isTrue()
    }

    @Test
    fun completedFingerprintedPullKeepsAutoFlagAndExactClosedMembers() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        listOf("display", "source-a", "source-b").forEach {
            fixture.records.seed(localRecord(babyId).copy(clientUuid = it))
        }
        fixture.relations.applyPullSummary("relation", "source-b", "source", listOf("display", "source-a"), 200, true)
        fixture.relations.applyPullSummary("relation", "display", "display", listOf("source-b", "source-a"), 201, true)

        assertThat(fixture.builder().build().sourceRelations).containsExactly(
            DisasterRestoreSourceRelation("relation", "display", listOf("source-a", "source-b"), true))
    }

    @Test
    fun unavailableRelationDaoKeepsCaptureUnknown() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())

        val snapshot = fixture.builder(relationDao = null).build()

        assertThat(snapshot.sourceRelations).isNull()
        assertThat(snapshot.sourceRelationEvidence).isNull()
    }

    @Test
    fun ambiguousFactIdentityFailsWithoutChoosingLastDuplicate() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "display"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source"))
        fixture.relation("relation", "display", "source")
        val duplicate = object : RecordDao by fixture.records {
            override suspend fun listAllIncludingDeleted() = fixture.records.listAllIncludingDeleted().let {
                it + it.first().copy(id = 99, note = "ambiguous second root")
            }
        }

        assertThat(runCatching { fixture.builder(recordDao = duplicate).build() }.isFailure).isTrue()
    }

    @Test
    fun unresolvedSourceCommandBlocksCaptureAndPreservesItsJournal() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())
        fixture.cache.putTransportJournal("source-relation-command-v1", "{\"pending\":true}", 100)

        assertThat(runCatching { fixture.builder().build() }.isFailure).isTrue()
        assertThat(fixture.cache.getTransportJournal("source-relation-command-v1")?.payloadJson)
            .isEqualTo("{\"pending\":true}")
    }

    @Test
    fun pendingDeclarationBlocksCaptureAndRemainsPending() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())
        val declaration = SourceRelationDeclarationEntity("mutation", "display", "source", "v1", "v2", "owner", "pending", 100)
        fixture.relations.upsertDeclaration(declaration)

        assertThat(runCatching { fixture.builder().build() }.isFailure).isTrue()
        assertThat(fixture.relations.listPendingDeclarations()).containsExactly(declaration)
    }

    @Test
    fun legacyFailedDeclarationStillBlocksCaptureBecauseAcceptanceIsUnknown() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())
        val declaration = SourceRelationDeclarationEntity("mutation", "display", "source", "v1", "v2", "owner", "failed", 100)
        fixture.relations.upsertDeclaration(declaration)

        assertThat(runCatching { fixture.builder().build() }.isFailure).isTrue()
        assertThat(fixture.relations.getDeclaration("mutation")).isEqualTo(declaration)
    }

    @Test
    fun canonicalMemberWithoutLocalRecordFailsInsteadOfInventingFact() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "display"))
        fixture.relation("relation", "display", "absent")

        assertThat(runCatching { fixture.builder().build() }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun relationMemberKeepsDeletedCustomDefinitionButUnrelatedHistoryStaysExcluded() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        val customId = fixture.customItems.seed(CustomItemEntity(clientUuid = "custom", familyId = 1,
            name = "Original definition", iconSlot = 0, updatedAt = 300, deletedAt = 300))
        fixture.customItems.seed(CustomItemEntity(clientUuid = "unrelated-custom", familyId = 1,
            name = "Unrelated", iconSlot = 0, updatedAt = 400, deletedAt = 400))
        val customRecord = localRecord(babyId).copy(type = "custom",
            payloadJson = """{"title":"Original event","custom_item_id":$customId}""")
        fixture.records.seed(customRecord.copy(clientUuid = "display"))
        fixture.records.seed(customRecord.copy(clientUuid = "source", deletedAt = 250))
        fixture.records.seed(customRecord.copy(clientUuid = "unrelated-record", deletedAt = 400))
        fixture.records.seed(customRecord.copy(clientUuid = "unrelated-live"))
        fixture.relation("relation", "display", "source")

        val snapshot = fixture.builder().build()

        assertThat(snapshot.entities.map { it.clientUuid }).containsExactly("baby-local", "custom", "display", "source")
        assertThat(snapshot.entities.single { it.clientUuid == "custom" }.deletedAt).isEqualTo(300)
        assertThat(snapshot.retirementVersions.single { it.clientUuid == "custom" }.restored).isTrue()
        assertThat(snapshot.retirementVersions.single { it.clientUuid == "unrelated-record" }.restored).isFalse()
        assertThat(snapshot.retirementVersions.single { it.clientUuid == "unrelated-live" }.restored).isFalse()
        assertThat(snapshot.summary.records).isEqualTo(1)
        assertThat(snapshot.summary.customItems).isEqualTo(0)
        val decoded = decodeRestoreSnapshotContent(
            Json.parseToJsonElement(encodeRestoreSnapshotContent(snapshot, "family")).jsonObject, emptyList())
        assertThat(decoded.summary).isEqualTo(snapshot.summary)
    }

    @Test
    fun tombstonedSleepRetainsSelectedWithdrawnWakeAndDeletedBabyWithoutMediaBytes() = runTest {
        val fixture = Fixture()
        fixture.babies.seed(localBaby())
        val babyId = fixture.babies.seed(localBaby().copy(clientUuid = "historical-baby", deletedAt = 500,
            avatarMediaUuid = "historical-avatar"))
        val sleep = localRecord(babyId).copy(type = "sleep",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""", deletedAt = 400)
        val sleepId = fixture.records.seed(sleep.copy(clientUuid = "sleep", effectiveWakeObservationClientUuid = "wake"))
        fixture.records.seed(sleep.copy(clientUuid = "source-sleep"))
        val wakeId = fixture.wakes.seed(WakeObservationEntity(clientUuid = "wake", sleepRecordClientUuid = "sleep",
            wakeTimestamp = 200, withdrawn = true, updatedAt = 450, deletedAt = 450))
        fixture.wakes.seed(WakeObservationEntity(clientUuid = "unrelated-wake", sleepRecordClientUuid = "sleep",
            wakeTimestamp = 250, updatedAt = 460, deletedAt = 460))
        fixture.media.seed(MediaAssetEntity(clientUuid = "historical-avatar", kind = "avatar", babyId = babyId,
            localUri = "missing-avatar", mime = "image/jpeg", createdAt = 100))
        fixture.media.seed(MediaAssetEntity(clientUuid = "historical-photo", recordId = sleepId,
            localUri = "missing-record", mime = "image/jpeg", createdAt = 100))
        fixture.media.seed(MediaAssetEntity(clientUuid = "historical-wake-photo", kind = "wake", wakeObservationId = wakeId,
            localUri = "missing-wake", mime = "image/jpeg", createdAt = 100))
        fixture.files.missing += listOf("missing-avatar", "missing-record", "missing-wake")
        fixture.relation("relation", "sleep", "source-sleep")

        val snapshot = fixture.builder().build()

        assertThat(snapshot.entities.map { it.clientUuid }).containsExactly("baby-local", "historical-baby", "sleep", "source-sleep", "wake")
        val sleepPayload = Json.parseToJsonElement(snapshot.entities.single { it.clientUuid == "sleep" }.payloadJson).jsonObject
        assertThat(sleepPayload.getValue("effective_wake_observation_client_uuid").jsonPrimitive.content).isEqualTo("wake")
        val babyPayload = Json.parseToJsonElement(snapshot.entities.single { it.clientUuid == "historical-baby" }.payloadJson).jsonObject
        assertThat(babyPayload.getValue("avatar_media_uuid").jsonPrimitive.content).isEqualTo("historical-avatar")
        assertThat(snapshot.retirementVersions.filter { it.clientUuid in setOf("sleep", "source-sleep", "wake", "historical-baby") }
            .all { it.restored }).isTrue()
        assertThat(snapshot.summary.babies).isEqualTo(1)
        assertThat(snapshot.summary.records).isEqualTo(0)
        assertThat(snapshot.media).isEmpty()
        assertThat(fixture.files.readableFileCalls).isEqualTo(0)
    }

    @Test
    fun liveRelationMemberStillRequiresItsLivePhotoBytes() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        val recordId = fixture.records.seed(localRecord(babyId).copy(clientUuid = "display"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source", deletedAt = 300))
        fixture.relation("relation", "display", "source")
        fixture.media.seed(MediaAssetEntity(clientUuid = "photo", recordId = recordId,
            localUri = "missing", mime = "image/jpeg", createdAt = 100))
        fixture.files.missing += "missing"

        assertThat(runCatching { fixture.builder().build() }.isFailure).isTrue()
    }

    @Test
    fun relationCaptureSharesFactTransactionAndKeepsOriginalEqualityEvidence() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "a"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "b"))
        fixture.relation("old", "a", "b")
        val before = fixture.builder().build()
        val boundary = object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                val captured = block()
                fixture.relation("new", "b", "a")
                fixture.records.update(requireNotNull(fixture.records.getByClientUuid("a")).copy(note = "after capture"))
                return captured
            }
        }

        val captured = fixture.builder(boundary).build()

        assertThat(captured.sourceRelations).isEqualTo(before.sourceRelations)
        assertThat(captured.sourceRelationEvidence).isEqualTo(before.sourceRelationEvidence)
        assertThat(captured.retirementVersions).isEqualTo(before.retirementVersions)
        assertThat(captured.entities).isEqualTo(before.entities)
        assertThat(fixture.builder().build().sourceRelationEvidence).isNotEqualTo(captured.sourceRelationEvidence)
    }

    @Test
    fun distinctOpaqueHeaderFieldsNeverCollapseToEqualCaptureEvidence() = runTest {
        val fixture = Fixture()
        val babyId = fixture.babies.seed(localBaby())
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "display"))
        fixture.records.seed(localRecord(babyId).copy(clientUuid = "source"))
        fixture.relation("relation", "display", "source")
        val original = requireNotNull(fixture.relations.get("relation"))
        fixture.relations.seedRaw(original.copy(mutationId = "mutation, createdByMembershipId=first",
            createdByMembershipId = "second"), emptyList())
        val first = fixture.builder().build()
        fixture.relations.seedRaw(original.copy(mutationId = "mutation",
            createdByMembershipId = "first, createdByMembershipId=second"), emptyList())

        val second = fixture.builder().build()

        assertThat(second.sourceRelations).isEqualTo(first.sourceRelations)
        assertThat(second.sourceRelationEvidence).isNotEqualTo(first.sourceRelationEvidence)
        assertThat(second.retirementVersions).isEqualTo(first.retirementVersions)
    }

    private class Fixture {
        val babies = MemoryBabyDao()
        val records = MemoryRecordDao()
        val plans = MemoryCarePlanDao()
        val customItems = MemoryCustomItemDao()
        val candidates = MemoryFulfillmentCandidateDao()
        val media = MemoryMediaDao()
        val wakes = MemoryWakeObservationDao()
        val relations = MemorySourceRelationDao()
        val cache = MemoryConflictSnapshotCacheDao()
        val files = TestMediaFileStore()
        val transactions = RecordingTransactionRunner()

        fun builder(
            runner: DatabaseTransactionRunner = transactions,
            recordDao: RecordDao = records,
            relationDao: SourceRelationDao? = relations,
        ) = DisasterRecoverySnapshotBuilder(babies, recordDao, plans, customItems,
            candidates, media, wakes, files, runner, relationDao, cache)

        suspend fun relation(id: String, display: String, source: String) {
            relations.applyCanonicalTransition(SourceRelationEntity(id, display, reason = "owner_group_resolve",
                mutationId = "manual-$id", createdByMembershipId = "owner", createdAt = 200),
                listOf(SourceRelationMemberEntity(id, display, "display"),
                    SourceRelationMemberEntity(id, source, "source")))
        }
    }
}
