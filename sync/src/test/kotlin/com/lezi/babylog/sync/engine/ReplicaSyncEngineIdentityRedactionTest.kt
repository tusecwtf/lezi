package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.PullConflictSummary
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.core.database.causal.conflictSnapshotStageCacheKey
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** ADR-0025 server payloads retain version_id and redact only explicit identity fields. */
class ReplicaSyncEngineIdentityRedactionTest {
    @Test fun sameVersionRecordRedactsCreator() = runTest { verifyPull("record") }
    @Test fun sameVersionCarePlanRedactsCreator() = runTest { verifyPull("care_plan") }
    @Test fun sameVersionCustomItemRedactsCreator() = runTest { verifyPull("custom_item") }
    @Test fun sameVersionWakeRedactsObserver() = runTest { verifyPull("wake_observation") }

    @Test fun dirtyRecordRedactsCreatorWithoutChangingFactsPhotosOrFrozenRetry() = runTest {
        verifyPull("record", dirty = true)
    }
    @Test fun dirtyCarePlanRedactsCreatorWithoutChangingFactsPhotosOrFrozenRetry() = runTest {
        verifyPull("care_plan", dirty = true)
    }
    @Test fun dirtyCustomItemRedactsCreatorWithoutChangingFactsOrFrozenRetry() = runTest {
        verifyPull("custom_item", dirty = true)
    }
    @Test fun dirtyWakeRedactsObserverWithoutChangingFactsPhotosOrFrozenRetry() = runTest {
        verifyPull("wake_observation", dirty = true)
    }

    @Test
    fun absentWakeStampDoesNotEraseAnExistingObserver() = runTest {
        val fixture = Fixture("wake_observation")
        val before = fixture.current()
        val payload = fixture.remote.payloadJson.let { Json.parseToJsonElement(it).jsonObject }
        fixture.pullOnePage(fixture.remote.copy(payloadJson = JsonObject(payload - "observer_membership_id").toString()))
        assertThat(fixture.current()).isEqualTo(before)
    }

    @Test
    fun nullWakeStampIsAcceptedInPullAndStableRootsButMissingOrMalformedProofFailsClosed() {
        val pull = Json.parseToJsonElement(wakePullPayload()).jsonObject
        assertThat(decodeWakeRootWire(pull, WakeRootWireShape.Pull).observerMembershipId).isEmpty()
        val stable = JsonObject(pull + ("updated_at" to JsonPrimitive(100)))
        assertThat(decodeWakeRootWire(stable, WakeRootWireShape.StableRoot).observerMembershipId).isEmpty()
        val malformed = listOf(
            JsonObject(stable - "observer_membership_id"),
            JsonObject(stable + ("observer_membership_id" to JsonPrimitive(""))),
            JsonObject(stable + ("observer_membership_id" to JsonPrimitive(" member "))),
            JsonObject(stable + ("observer_membership_id" to JsonPrimitive(3))),
        )
        malformed.forEach { root ->
            assertThat(runCatching { decodeWakeRootWire(root, WakeRootWireShape.StableRoot) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun literalNullObserverRemainsAnIdentity() {
        val root = Json.parseToJsonElement(wakePullPayload()).jsonObject
        val literal = JsonObject(root + ("observer_membership_id" to JsonPrimitive("null")))
        assertThat(decodeWakeRootWire(literal, WakeRootWireShape.Pull).observerMembershipId).isEqualTo("null")
    }

    @Test
    fun malformedNursingPayloadCannotClearIdentityOrAdvanceCursor() = runTest {
        listOf("record", "care_plan").forEach { type ->
            val fixture = Fixture(type)
            val before = fixture.current()
            val malformed = fixture.remote.payloadJson.replace(Regex("\"amount_ml\":\\d+"), "\"amount_ml\":\"broken\"")
            fixture.rig.backend.nextPull = PullResult(
                listOf(fixture.remote.copy(payloadJson = malformed)), 1,
                fixture.session.pullGeneration, hasMore = false,
            )
            assertThat(runCatching {
                fixture.rig.engine.synchronize(fixture.session, SyncTrigger.PullToRefresh)
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(fixture.current()).isEqualTo(before)
            assertThat(fixture.rig.preferences.current().pullCursor).isEqualTo(0)
        }
    }

    @Test
    fun fulfillmentRedactionKeepsFactReferencesAndLocalConversionPointer() = runTest {
        val fixture = Fixture("record")
        val id = "00000000-0000-4000-8000-000000000008"
        val original = FulfillmentCandidateEntity(
            clientUuid = id, carePlanClientUuid = PLAN, recordClientUuid = RECORD,
            actualTimestamp = 100, confirmedAt = 110, submitterMembershipId = DEPARTED,
            submitterRole = "member", convertedRecordClientUuid = "independent-record",
            updatedAt = 100, syncDirty = false,
        )
        fixture.rig.fulfillmentCandidates.seed(original)
        fixture.pullOnePage(SyncEntity(
            type = "fulfillment_candidate", clientUuid = id, updatedAt = 500,
            payloadJson = """{"care_plan_client_uuid":"$PLAN","record_client_uuid":"$RECORD","actual_timestamp":100,"submitter_membership_id":null,"submitter_role":"member","confirmed_at":110}""",
        ))
        val current = requireNotNull(fixture.rig.fulfillmentCandidates.getByClientUuid(id))
        assertThat(current.submitterMembershipId).isEmpty()
        assertThat(current.carePlanClientUuid).isEqualTo(PLAN)
        assertThat(current.recordClientUuid).isEqualTo(RECORD)
        assertThat(current.actualTimestamp).isEqualTo(100)
        assertThat(current.confirmedAt).isEqualTo(110)
        assertThat(current.convertedRecordClientUuid).isEqualTo("independent-record")
    }

    @Test
    fun acceptedWakeReceiptReplayCanReturnAnonymousStableRootWithoutChangingMutationIdentity() = runTest {
        val fixture = Fixture("wake_observation")
        fixture.makeDirty()
        val lost = IOException("accepted response lost")
        fixture.rig.backend.nextCausalCommitFailure = lost
        assertThat(runCatching {
            fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()).isSameInstanceAs(lost)
        val first = fixture.rig.backend.causalCommittedUnits.flatten().single()
        fixture.rig.backend.nextCausalCommitFailure = null
        fixture.rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            assertThat(unit).isEqualTo(first)
            val root = Json.parseToJsonElement(unit.rootJson).jsonObject
            fixture.rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = fixture.session.pullGeneration,
                results = listOf(CausalCommitUnitResult(
                    status = CausalCommitStatus.ACCEPTED,
                    mutationId = unit.mutationId,
                    requestHash = causalMutationContentHash(unit),
                    stableVersionId = "accepted-wake-version",
                    stableRootJson = JsonObject(root + ("observer_membership_id" to JsonNull)).toString(),
                    stableMedia = unit.media,
                    replay = true,
                )),
            )
        }
        fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
        val wake = fixture.current() as WakeObservationEntity
        assertThat(wake.observerMembershipId).isEmpty()
        assertThat(wake.wakeTimestamp).isEqualTo(250)
        assertThat(wake.note).isEqualTo("offline fact")
        assertThat(wake.baseVersion).isEqualTo("accepted-wake-version")
        assertThat(wake.syncDirty).isFalse()
        assertThat(fixture.rig.mediaFiles.deleted).doesNotContain(fixture.photoPath)
    }

    @Test
    fun pushOnlyAnonymousReceiptPreservesNewerCareFactsAndPendingIntent() = runTest {
        listOf("record", "care_plan", "custom_item", "wake_observation").forEach { type ->
            val fixture = Fixture(type)
            fixture.makeDirty()
            val lost = IOException("accepted response lost")
            fixture.rig.backend.nextCausalCommitFailure = lost
            assertThat(runCatching {
                fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()).isSameInstanceAs(lost)
            val first = fixture.rig.backend.causalCommittedUnits.flatten().single()
            val frozen = fixture.frozenProof()
            assertThat(frozen).isNotNull()
            var newer: Any? = null
            val photos = fixture.rig.media.listAllIncludingDeleted()
            val bytes = fixture.rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()
            fixture.rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                assertThat(unit).isEqualTo(first)
                assertThat(fixture.frozenProof()).isEqualTo(frozen)
                fixture.makeDirty(newer = true)
                newer = fixture.current()
                val key = if (type == "wake_observation") "observer_membership_id" else "created_by_membership_id"
                val root = Json.parseToJsonElement(unit.rootJson).jsonObject
                fixture.rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = fixture.session.pullGeneration,
                    results = listOf(CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED, mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit), stableVersionId = "accepted-version",
                        stableRootJson = JsonObject(root + (key to JsonNull)).toString(),
                        stableMedia = unit.media, replay = true,
                    )),
                )
            }
            fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            val expected = when (val row = newer) {
                is RecordEntity -> row.copy(createdByMembershipId = "", baseVersion = "accepted-version", mutationId = null)
                is CarePlanEntity -> row.copy(createdByMembershipId = "", baseVersion = "accepted-version", mutationId = null)
                is CustomItemEntity -> row.copy(createdByMembershipId = "", baseVersion = "accepted-version", mutationId = null)
                is WakeObservationEntity -> row.copy(observerMembershipId = "", baseVersion = "accepted-version", mutationId = null)
                else -> error("receipt never returned")
            }
            assertThat(fixture.current()).isEqualTo(expected)
            assertThat(fixture.rig.media.listAllIncludingDeleted()).containsExactlyElementsIn(photos)
            assertThat(fixture.rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()).isEqualTo(bytes)
            assertThat(fixture.rig.backend.pullCount).isEqualTo(0)
            assertThat(fixture.rig.backend.causalCommittedUnits).hasSize(2)
            assertThat(fixture.frozenProof()).isNull()
        }
    }

    @Test
    fun malformedAnonymousReceiptCannotRedactNewerIntentOrRetireFrozenProof() = runTest {
        val cases = listOf("record", "care_plan").flatMap { type ->
            listOf("payload", "timestamp-type", "timestamp-negative").map { type to it }
        }
        cases.forEach { (type, corruption) ->
            val fixture = Fixture(type)
            fixture.makeDirty()
            var newer: Any? = null
            var frozen: Any? = null
            fixture.rig.backend.onCausalCommit = { units ->
                val unit = units.single()
                fixture.makeDirty(newer = true)
                newer = fixture.current()
                frozen = fixture.frozenProof()
                assertThat(frozen).isNotNull()
                val root = Json.parseToJsonElement(unit.rootJson).jsonObject
                val invalidField = when (corruption) {
                    "payload" -> "payload_json" to JsonObject(mapOf("amount_ml" to JsonPrimitive("broken")))
                    "timestamp-type" -> "updated_at" to JsonPrimitive("broken")
                    else -> "updated_at" to JsonPrimitive(-1)
                }
                val invalid = JsonObject(root + ("created_by_membership_id" to JsonNull) + invalidField)
                fixture.rig.backend.nextCausalCommit = CausalCommitBatchResult(
                    generation = fixture.session.pullGeneration,
                    results = listOf(CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED, mutationId = unit.mutationId,
                        requestHash = causalMutationContentHash(unit), stableVersionId = "accepted-version",
                        stableRootJson = invalid.toString(), stableMedia = unit.media,
                    )),
                )
            }
            val failure = runCatching {
                fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.javaClass?.simpleName).isEqualTo("FrozenCommitProofException")
            assertThat(fixture.current()).isEqualTo(newer)
            assertThat(fixture.frozenProof()).isEqualTo(frozen)
            assertThat(fixture.rig.backend.pullCount).isEqualTo(0)
        }
    }

    @Test
    fun sleepIdentityRedactionPreservesWakePhotosAndSourceGraph() = runTest {
        val fixture = Fixture("wake_observation")
        val rig = fixture.rig
        val sleep = requireNotNull(rig.records.getByClientUuid(SLEEP))
        rig.records.update(sleep.copy(
            createdByMembershipId = DEPARTED, note = "pending sleep edit",
            effectiveWakeObservationClientUuid = WAKE, updatedAt = 200, syncDirty = true,
        ))
        val peer = "00000000-0000-4000-8000-000000000011"
        rig.records.seed(sleep.copy(id = 0, clientUuid = peer, baseVersion = "peer-version"))
        rig.sourceRelations.applyPullSummary(
            relationId = "retained-relation", recordClientUuid = SLEEP,
            role = "display", peerIds = listOf(peer), observedAt = 100, autoAligned = false,
        )
        rig.sourceRelations.applyPullSummary(
            relationId = "retained-relation", recordClientUuid = peer,
            role = "source", peerIds = listOf(SLEEP), observedAt = 100, autoAligned = false,
        )
        val lost = IOException("sleep commit response lost")
        rig.backend.nextCausalCommitFailure = lost
        assertThat(runCatching {
            rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
        }.exceptionOrNull()).isSameInstanceAs(lost)
        val pending = requireNotNull(rig.records.getByClientUuid(SLEEP))
        val wake = rig.wakeObservations.getByClientUuid(WAKE)
        val photos = rig.media.listAllIncludingDeleted()
        val relations = rig.sourceRelations.listAll()
        val members = rig.sourceRelations.listAllMembers()
        val frozen = rig.conflictDetails.getFrozenMutation("record", SLEEP)
        assertThat(frozen).isNotNull()
        val bytes = rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()
        fixture.pullOnePage(SyncEntity(
            type = "record", clientUuid = SLEEP, versionId = "sleep-version", updatedAt = 500,
            payloadJson = """{"baby_client_uuid":"$BABY","created_by_membership_id":null,"type":"sleep","custom_item_client_uuid":null,"timestamp":100,"note":null,"effective_wake_observation_client_uuid":"$WAKE","payload_json":{"is_nap":false,"anomaly_flag":false},"schema_version":2}""",
        ))
        assertThat(rig.records.getByClientUuid(SLEEP)).isEqualTo(pending.copy(createdByMembershipId = ""))
        assertThat(rig.wakeObservations.getByClientUuid(WAKE)).isEqualTo(wake)
        assertThat(rig.media.listAllIncludingDeleted()).containsExactlyElementsIn(photos)
        assertThat(rig.sourceRelations.listAll()).containsExactlyElementsIn(relations)
        assertThat(rig.sourceRelations.listAllMembers()).containsExactlyElementsIn(members)
        assertThat(rig.conflictDetails.getFrozenMutation("record", SLEEP)).isEqualTo(frozen)
        assertThat(rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()).isEqualTo(bytes)
        assertThat(rig.mediaFiles.deleted).isEmpty()
    }

    @Test
    fun mismatchedConflictSummaryCannotEvictForeignCacheOrRedactRoot() = runTest {
        val matching = PullConflictSummary("foreign-conflict", "record", RECORD, "unchanged-version", listOf("branch"))
        listOf(
            matching.copy(entityType = "custom_item"),
            matching.copy(clientUuid = ITEM),
            matching.copy(stableVersionId = "foreign-version"),
        ).forEach { mismatch ->
            val fixture = Fixture("record")
            val before = fixture.current()
            val foreign = ConflictSnapshotCacheEntity(
                conflictId = mismatch.conflictId, snapshotJson = "retained foreign snapshot", cachedAt = 100,
            )
            fixture.rig.conflictDetails.upsert(foreign)
            fixture.rig.backend.nextPull = PullResult(
                listOf(fixture.remote.copy(conflictSummary = mismatch)), 1,
                fixture.session.pullGeneration, hasMore = false,
            )
            assertThat(runCatching {
                fixture.rig.engine.synchronize(fixture.session, SyncTrigger.PullToRefresh)
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(fixture.rig.conflictDetails.get(mismatch.conflictId)).isEqualTo(foreign)
            assertThat(fixture.current()).isEqualTo(before)
            assertThat(fixture.rig.preferences.current().pullCursor).isEqualTo(0)
        }
    }

    @Test
    fun sameConflictRedactionInvalidatesOldSnapshotAndPageLeaseWithoutDiscardingBranch() = runTest {
        val fixture = Fixture("record")
        val conflictId = "00000000-0000-4000-8000-000000000009"
        val mutationId = "00000000-0000-4000-8000-000000000010"
        val root = JsonObject(Json.parseToJsonElement(fixture.remote.payloadJson).jsonObject + mapOf(
            "created_by_membership_id" to JsonPrimitive(DEPARTED),
            "updated_at" to JsonPrimitive(100),
        ))
        fun version(id: String, base: String) = """{"version_id":"$id","base_version":$base,"root":$root,"media":[],"deleted":false,"mutation_id":"$mutationId","actor_id":"$DEPARTED","device_id":"departed-device","received_at":100}"""
        val stable = version("unchanged-version", "null")
        val branch = version("branch-version", "\"unchanged-version\"")
        val snapshot = ConflictSnapshotCodec.decode(
            """{"contract":"conflict_snapshot_v2","conflict_id":"$conflictId","entity_type":"record","client_uuid":"$RECORD","snapshot_token":"${"a".repeat(43)}","expires_at":2000000,"stable":$stable,"branches":[$branch],"conflicting":[],"auto_merged":[],"page_index":0,"continuation":null,"complete":true}""",
        )
        val projection = ConflictSnapshotProjection(
            fixture.rig.conflictSummaries, fixture.rig.conflictDetails, fixture.rig.transactions,
        )
        projection.replaceComplete(snapshot)
        val old = fixture.current() as RecordEntity
        fixture.rig.records.update(old.copy(openConflictId = conflictId, localBranchVersionId = "branch-version"))
        val stop = IOException("snapshot pagination paused")
        var first = true
        assertThat(runCatching {
            projection.loadComplete(conflictId) {
                if (!first) throw stop
                first = false
                FetchedConflictSnapshotPage(snapshot.copy(complete = false, continuation = "b".repeat(43)), encodedBytes = 1000)
            }
        }.exceptionOrNull()).isSameInstanceAs(stop)
        assertThat(projection.read(conflictId)).isNotNull()
        assertThat(fixture.rig.conflictDetails.getTransportJournal(conflictSnapshotStageCacheKey(conflictId))).isNotNull()

        fixture.pullOnePage(fixture.remote.copy(conflictSummary = PullConflictSummary(
            conflictId, "record", RECORD, "unchanged-version", listOf("branch-version"),
        )))

        assertThat(projection.read(conflictId)).isNull()
        assertThat(fixture.rig.conflictDetails.getTransportJournal(conflictSnapshotStageCacheKey(conflictId))).isNull()
        val current = fixture.current() as RecordEntity
        assertThat(current).isEqualTo(old.copy(
            createdByMembershipId = "", openConflictId = conflictId, localBranchVersionId = "branch-version",
        ))
        assertThat(fixture.rig.conflictSummaries.get(conflictId)?.branchVersionIdsJson).contains("branch-version")
    }

    private suspend fun verifyPull(type: String, dirty: Boolean = false) {
        val fixture = Fixture(type)
        var frozen: Any? = null
        if (dirty) {
            fixture.makeDirty()
            val lost = IOException("commit response lost")
            fixture.rig.backend.nextCausalCommitFailure = lost
            assertThat(runCatching {
                fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)
            }.exceptionOrNull()).isSameInstanceAs(lost)
            frozen = fixture.frozenProof()
            assertThat(frozen).isNotNull()
        }
        var expected = fixture.current()
        val photos = fixture.rig.media.listAllIncludingDeleted()
        val photoBytes = fixture.rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()
        fixture.pullOnePage(fixture.remote) {
            if (dirty) {
                // A newer local edit arrives while the authenticated pull is in flight.
                fixture.makeDirty(newer = true)
                expected = fixture.current()
            }
        }
        val anonymous = when (val row = expected) {
            is RecordEntity -> row.copy(createdByMembershipId = "")
            is CarePlanEntity -> row.copy(createdByMembershipId = "")
            is CustomItemEntity -> row.copy(createdByMembershipId = "")
            is WakeObservationEntity -> row.copy(observerMembershipId = "")
            else -> error("unexpected fixture")
        }
        assertThat(fixture.current()).isEqualTo(anonymous)
        assertThat(fixture.rig.media.listAllIncludingDeleted()).containsExactlyElementsIn(photos)
        assertThat(fixture.rig.mediaFiles.readableFile(fixture.photoPath)?.readBytes()).isEqualTo(photoBytes)
        assertThat(fixture.rig.mediaFiles.deleted).isEmpty()
        if (dirty) {
            assertThat(fixture.frozenProof()).isEqualTo(frozen)
            assertThat(fixture.rig.backend.causalCommittedUnits).hasSize(1)
        }
    }

    private class Fixture(val type: String) {
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session)
        val uuid = when (type) {
            "record" -> RECORD
            "care_plan" -> PLAN
            "custom_item" -> ITEM
            else -> WAKE
        }
        val photoPath = "/private/redaction-$type.jpg"
        val remote = SyncEntity(
            type = type,
            clientUuid = uuid,
            versionId = "unchanged-version",
            updatedAt = 500,
            payloadJson = when (type) {
                "record" -> """{"baby_client_uuid":"$BABY","created_by_membership_id":null,"type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"care fact","payload_json":{"amount_ml":90},"schema_version":2}"""
                "care_plan" -> """{"baby_client_uuid":"$BABY","type":"formula","custom_item_client_uuid":null,"scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","note":"care fact","status":"pending","payload_json":{"amount_ml":120},"schema_version":2,"created_by_membership_id":null,"fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}"""
                "custom_item" -> """{"name":"care fact","icon_slot":2,"created_by_membership_id":null}"""
                else -> wakePullPayload()
            },
        )

        init {
            val babyId = rig.babies.seed(localReplicaBaby().copy(
                clientUuid = BABY, syncDirty = false, baseVersion = "baby-version",
            ))
            rig.records.seed(RecordEntity(
                clientUuid = SLEEP, babyId = babyId, type = "sleep", timestamp = 100,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 100, syncDirty = false, baseVersion = "sleep-version",
            ))
            val recordId = rig.records.seed(RecordEntity(
                clientUuid = RECORD, babyId = babyId, type = "formula", timestamp = 100,
                note = "care fact", payloadJson = """{"amount_ml":90}""", updatedAt = 100,
                createdByMembershipId = DEPARTED, syncDirty = false,
                baseVersion = "unchanged-version", familyPublishedUpdatedAt = 100,
            ))
            val planId = rig.carePlans.seed(localReplicaCarePlan(PLAN, DEPARTED, 100).copy(
                babyId = babyId, note = "care fact", baseVersion = "unchanged-version",
                familyPublishedUpdatedAt = 100, systemCalendarEventId = "local-event",
                systemCalendarReminderReady = true,
            ))
            rig.customItems.seed(localReplicaCustomItem(ITEM, DEPARTED, 100).copy(
                name = "care fact", iconSlot = 2, sortOrder = 7, baseVersion = "unchanged-version",
            ))
            val wakeId = rig.wakeObservations.seed(WakeObservationEntity(
                clientUuid = WAKE, sleepRecordClientUuid = SLEEP, wakeTimestamp = 200,
                observerMembershipId = DEPARTED, note = "care fact", updatedAt = 100,
                syncDirty = false, baseVersion = "unchanged-version", familyPublishedUpdatedAt = 100,
            ))
            val bytes = byteArrayOf(1, 2, 3, 4)
            rig.mediaFiles.seedReadableSource(photoPath, bytes)
            rig.media.seed(MediaAssetEntity(
                clientUuid = PHOTO, recordId = if (type == "record" || type == "custom_item") recordId else null,
                carePlanId = planId.takeIf { type == "care_plan" },
                wakeObservationId = wakeId.takeIf { type == "wake_observation" },
                kind = if (type == "wake_observation") "wake" else "log",
                localUri = photoPath,
                mime = "image/jpeg", byteSize = 4, sha256 = MediaContentDigest.ofBytes(bytes),
                createdAt = 100, updatedAt = 100, syncDirty = false,
            ))
        }

        /** Media roots own their exact envelope in the durable media settlement journal. */
        suspend fun frozenProof(): com.lezi.babylog.core.database.causal.CausalTransportJournalEntity? =
            rig.conflictDetails.getFrozenMutation(type, uuid)
                ?: rig.backend.causalCommittedUnits.flatten()
                    .lastOrNull { it.entityType == type && it.clientUuid == uuid }
                    ?.let { rig.conflictDetails.getFrozenMediaSpoolManifest(it.mutationId) }

        suspend fun current(): Any = when (type) {
            "record" -> requireNotNull(rig.records.getByClientUuid(uuid))
            "care_plan" -> requireNotNull(rig.carePlans.getByClientUuid(uuid))
            "custom_item" -> requireNotNull(rig.customItems.getByClientUuid(uuid))
            else -> requireNotNull(rig.wakeObservations.getByClientUuid(uuid))
        }

        suspend fun makeDirty(newer: Boolean = false) {
            val stamp = if (newer) 700L else 200L
            val note = if (newer) "concurrent newer fact" else "offline fact"
            when (val row = current()) {
                is RecordEntity -> rig.records.update(row.copy(note = note, payloadJson = """{"amount_ml":150}""", updatedAt = stamp, syncDirty = true))
                is CarePlanEntity -> rig.carePlans.update(row.copy(note = note, scheduledAt = 9000000000100, updatedAt = stamp, syncDirty = true))
                is CustomItemEntity -> rig.customItems.update(row.copy(name = note, iconSlot = 3, updatedAt = stamp, syncDirty = true))
                is WakeObservationEntity -> rig.wakeObservations.update(row.copy(note = note, wakeTimestamp = 250, updatedAt = stamp, syncDirty = true))
            }
        }

        suspend fun pullOnePage(entity: SyncEntity, inFlight: suspend () -> Unit = {}) {
            val stop = IOException("pause after durable pull page, before retry publication")
            rig.backend.nextPull = PullResult(listOf(entity), 1, session.pullGeneration, hasMore = true)
            rig.backend.beforePullReturn = {
                inFlight()
                rig.backend.pullFailures += stop
            }
            assertThat(runCatching {
                rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
            }.exceptionOrNull()).isSameInstanceAs(stop)
            assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        }
    }

    private companion object {
        const val BABY = "00000000-0000-4000-8000-000000000001"
        const val RECORD = "00000000-0000-4000-8000-000000000002"
        const val PLAN = "00000000-0000-4000-8000-000000000003"
        const val ITEM = "00000000-0000-4000-8000-000000000004"
        const val SLEEP = "00000000-0000-4000-8000-000000000005"
        const val WAKE = "00000000-0000-4000-8000-000000000006"
        const val PHOTO = "00000000-0000-4000-8000-000000000007"
        const val DEPARTED = "deleted-membership"
        fun wakePullPayload() = """{"sleep_record_client_uuid":"$SLEEP","wake_timestamp":200,"note":"care fact","withdrawn":false,"observer_membership_id":null}"""
    }
}
