package com.lezi.babylog.sync.conflict

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test

class ConflictSnapshotProjectionTest {
    @Test
    fun fiveRootSnapshotsRemainTypedAfterRepositoryRestart() = runTest {
        val cases = listOf(
            "baby" to """{"nickname":"安安","sex":null,"birthday":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
            "record" to """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}""",
            "care_plan" to """{"baby_client_uuid":"$BABY_UUID","type":"formula","scheduled_at":100,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":60},"schema_version":2,"status":"pending","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null,"custom_item_client_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
            "custom_item" to """{"name":"散步","icon_slot":3,"updated_at":100,"created_by_membership_id":"member-a"}""",
            "wake_observation" to """{"sleep_record_client_uuid":"$CLIENT_UUID","wake_timestamp":120,"note":null,"withdrawn":false,"updated_at":120,"observer_membership_id":"member-a"}""",
        )
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val transactions = RecordingTransactionRunner()

        cases.forEachIndexed { index, (entityType, root) ->
            val conflictId = CONFLICT_UUIDS[index]
            val clientUuid = CLIENT_UUIDS[index]
            val original = ConflictSnapshotCodec.decode(
                snapshotJson(
                    conflictId = conflictId,
                    clientUuid = clientUuid,
                    entityType = entityType,
                    root = root,
                ),
            )
            ConflictSnapshotProjection(summaries, rows, transactions).replaceComplete(original)

            val restarted = ConflictSnapshotProjection(summaries, rows, transactions)
            assertThat(restarted.read(conflictId)).isEqualTo(original)
            assertThat(restarted.read(conflictId)!!.stable.root)
                .isInstanceOf(original.stable.root.javaClass)
        }
    }

    @Test
    fun completeRecordSnapshotReplacesAtomicallyAndSurvivesRepositoryRestart() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val transactions = RecordingTransactionRunner()
        val projection = ConflictSnapshotProjection(summaries, rows, transactions)
        val first = recordSnapshot(CONFLICT_ONE, "v1", note = JsonNull)

        projection.replaceComplete(first)

        assertThat(transactions.runCount).isEqualTo(1)
        assertThat(summaries.get(CONFLICT_ONE)!!.branchVersionIdsJson).contains("b1")
        // New repository instance models process recreation over the same Room rows.
        val restarted = ConflictSnapshotProjection(summaries, rows, transactions)
        assertThat(restarted.read(CONFLICT_ONE)).isEqualTo(first)

        val replacement = recordSnapshot(CONFLICT_TWO, "v2", note = JsonNull)
        restarted.replaceComplete(replacement)
        assertThat(restarted.read(CONFLICT_ONE)).isNull()
        assertThat(summaries.get(CONFLICT_ONE)).isNull()
        assertThat(restarted.read(CONFLICT_TWO)!!.stable.versionId).isEqualTo("v2")

        restarted.clearRoot(ConflictRootType.Record, CLIENT_UUID)
        assertThat(restarted.read(CONFLICT_TWO)).isNull()
        assertThat(summaries.listForRoot("record", CLIENT_UUID)).isEmpty()
    }

    @Test
    fun incompletePageCannotReplaceExistingCompleteSnapshot() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val projection = ConflictSnapshotProjection(
            summaries,
            rows,
            RecordingTransactionRunner(),
        )
        val complete = recordSnapshot(CONFLICT_ONE, "v1", note = JsonNull)
        projection.replaceComplete(complete)

        val failure = runCatching {
            projection.replaceComplete(
                complete.copy(
                    conflictId = CONFLICT_THREE,
                    complete = false,
                    continuation = "next-page",
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(projection.read(CONFLICT_ONE)).isEqualTo(complete)
        assertThat(summaries.get(CONFLICT_THREE)).isNull()
    }

    private fun recordSnapshot(
        conflictId: String,
        stableVersion: String,
        note: kotlinx.serialization.json.JsonElement,
    ): ConflictSnapshot {
        fun root(value: kotlinx.serialization.json.JsonElement) = ConflictRoot.Record(
            babyClientUuid = BABY_UUID,
            type = "formula",
            customItemClientUuid = null,
            timestamp = 100,
            endTimestamp = null,
            note = null,
            payload = Json.parseToJsonElement("""{"amount_ml":60}""")
                .let { it as kotlinx.serialization.json.JsonObject },
            schemaVersion = 2,
            effectiveWakeObservationClientUuid = null,
            createdByMembershipId = "member-a",
            updatedAt = 100,
            canonical = Json.parseToJsonElement(
                """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":$value,"payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val source = ConflictSource("b1", MUTATION_UUID, "member-a", "device-a", 110)
        return ConflictSnapshot(
            conflictId = conflictId,
            entityType = ConflictRootType.Record,
            clientUuid = CLIENT_UUID,
            snapshotToken = "a".repeat(43),
            expiresAt = 2_000_000,
            stable = ConflictVersionSnapshot(
                versionId = stableVersion,
                baseVersion = "base-$stableVersion",
                root = root(note),
                media = listOf(media(MEDIA_ONE, 640, 480)),
                deleted = false,
                mutationId = MUTATION_UUID,
                actorId = "member-a",
                deviceId = "device-a",
                receivedAt = 100,
            ),
            branches = listOf(
                ConflictVersionSnapshot(
                    versionId = "b1",
                    baseVersion = stableVersion,
                    root = root(note),
                    media = listOf(media(MEDIA_TWO, 320, 240)),
                    deleted = true,
                    mutationId = MUTATION_UUID,
                    actorId = "member-a",
                    deviceId = "device-a",
                    receivedAt = 110,
                ),
            ),
            conflicting = listOf(
                ConflictingPath(
                    path = "/note",
                    candidates = listOf(
                        ConflictCandidate(
                            choiceId = "choice-aaaaaaaaa",
                            outcome = ConflictOutcome.Set(note),
                            sources = listOf(source),
                        ),
                        ConflictCandidate(
                            choiceId = "choice-bbbbbbbbb",
                            outcome = ConflictOutcome.Set(Json.parseToJsonElement("\"other\"")),
                            sources = listOf(source),
                        ),
                    ),
                ),
                ConflictingPath(
                    path = "/media/$MEDIA_TWO",
                    candidates = listOf(
                        ConflictCandidate(
                            choiceId = "choice-remove-med",
                            outcome = ConflictOutcome.Remove,
                            sources = listOf(source),
                        ),
                        ConflictCandidate(
                            choiceId = "choice-keep-media",
                            outcome = ConflictOutcome.Set(
                                Json.parseToJsonElement(
                                    """{"sha256":"${"b".repeat(64)}","dimensions":{"width":320,"height":240}}""",
                                ),
                            ),
                            sources = listOf(source),
                        ),
                    ),
                ),
            ),
            autoMerged = emptyList(),
            pageIndex = 0,
            continuation = null,
            complete = true,
        )
    }

    private fun media(uuid: String, width: Long, height: Long) = CausalMediaItem(
        mediaUuid = uuid,
        role = "log",
        sha256 = "b".repeat(64),
        byteSize = 12,
        mime = "image/jpeg",
        width = width,
        height = height,
    )

    private fun snapshotJson(
        conflictId: String,
        clientUuid: String,
        entityType: String,
        root: String,
    ): String =
        """{"contract":"conflict_snapshot_v2","conflict_id":"$conflictId","entity_type":"$entityType","client_uuid":"$clientUuid","snapshot_token":"${"a".repeat(43)}","expires_at":2000000,"stable":{"version_id":"v1","base_version":null,"root":$root,"media":[],"deleted":false,"mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100},"branches":[],"conflicting":[{"path":"/note","candidates":[{"choice_id":"choice-aaaaaaaaa","outcome":{"op":"set","value":null},"sources":[{"version_id":"v1","mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100}]},{"choice_id":"choice-bbbbbbbbb","outcome":{"op":"set","value":"other"},"sources":[{"version_id":"v1","mutation_id":"$MUTATION_UUID","actor_id":"member-a","device_id":"device-a","received_at":100}]}]}],"auto_merged":[],"page_index":0,"continuation":null,"complete":true}"""

    private companion object {
        const val CLIENT_UUID = "00000000-0000-0000-0000-000000000001"
        const val BABY_UUID = "00000000-0000-0000-0000-000000000002"
        const val MUTATION_UUID = "00000000-0000-0000-0000-000000000003"
        const val MEDIA_ONE = "00000000-0000-0000-0000-000000000004"
        const val MEDIA_TWO = "00000000-0000-0000-0000-000000000005"
        const val CONFLICT_ONE = "00000000-0000-0000-0000-000000000101"
        const val CONFLICT_TWO = "00000000-0000-0000-0000-000000000102"
        const val CONFLICT_THREE = "00000000-0000-0000-0000-000000000103"
        val CONFLICT_UUIDS = (201..205).map { "00000000-0000-0000-0000-000000000$it" }
        val CLIENT_UUIDS = (301..305).map { "00000000-0000-0000-0000-000000000$it" }
    }
}
