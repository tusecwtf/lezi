package com.lezi.babylog.sync.conflict

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemoryConflictSummaryDao
import com.lezi.babylog.sync.RecordingTransactionRunner
import com.lezi.babylog.core.database.causal.conflictSnapshotStageCacheKey
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test

class ConflictSnapshotProjectionTest {
    @Test
    fun pagedSnapshotResumesFromCommittedContinuationAndPromotesOnlyWhenComplete() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val transactions = RecordingTransactionRunner()
        val projection = ConflictSnapshotProjection(summaries, rows, transactions)
        val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
        projection.replaceComplete(oldComplete)
        val full = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val continuation = "c".repeat(43)
        val first = full.copy(
            complete = false,
            continuation = continuation,
        )
        val second = full.copy(
            branches = listOf(full.branches.single().copy(versionId = "b2")),
            pageIndex = 1,
        )
        var calls = 0

        val interrupted = runCatching {
            projection.loadComplete(CONFLICT_THREE) { request ->
                when (calls++) {
                    0 -> {
                        assertThat(request).isEqualTo(ConflictSnapshotPageRequest.First)
                        FetchedConflictSnapshotPage(first, encodedBytes = 12_000)
                    }
                    else -> throw IOException("process stopped after the first page commit")
                }
            }
        }.exceptionOrNull()

        assertThat(interrupted).isInstanceOf(IOException::class.java)
        assertThat(projection.read(CONFLICT_THREE)).isNull()
        assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)

        val requests = mutableListOf<ConflictSnapshotPageRequest>()
        val restarted = ConflictSnapshotProjection(summaries, rows, transactions)
        val complete = restarted.loadComplete(CONFLICT_THREE) { request ->
            requests += request
            FetchedConflictSnapshotPage(second, encodedBytes = 13_000)
        }

        assertThat(requests).containsExactly(
            ConflictSnapshotPageRequest.Continuation(
                snapshotToken = full.snapshotToken,
                continuation = continuation,
            ),
        )
        assertThat(complete.complete).isTrue()
        assertThat(complete.pageIndex).isEqualTo(0)
        assertThat(complete.continuation).isNull()
        assertThat(complete.branches.map { it.versionId }).containsExactly("b1", "b2").inOrder()
        assertThat(restarted.read(CONFLICT_THREE)).isEqualTo(complete)
        assertThat(restarted.read(CONFLICT_ONE)).isNull()
        assertThat(summaries.get(CONFLICT_THREE)!!.branchVersionIdsJson).contains("b2")
    }

    @Test
    fun invalidNextPageFailsClosedWithoutChangingCommittedSnapshotOrStage() = runTest {
        val full = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val continuation = "c".repeat(43)
        val first = full.copy(complete = false, continuation = continuation)
        val invalidPages = listOf(
            full.copy(
                branches = listOf(full.branches.single().copy(versionId = "b2")),
                pageIndex = 2,
            ),
            full.copy(pageIndex = 1),
            full.copy(
                branches = listOf(full.branches.single().copy(versionId = "b2")),
                pageIndex = 1,
                complete = false,
                continuation = continuation,
            ),
        )

        invalidPages.forEach { invalid ->
            val summaries = MemoryConflictSummaryDao()
            val rows = MemoryConflictSnapshotCacheDao()
            val projection = ConflictSnapshotProjection(
                summaries,
                rows,
                RecordingTransactionRunner(),
            )
            val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
            projection.replaceComplete(oldComplete)
            var firstAttempt = true
            runCatching {
                projection.loadComplete(CONFLICT_THREE) {
                    if (firstAttempt) {
                        firstAttempt = false
                        FetchedConflictSnapshotPage(first, encodedBytes = 12_000)
                    } else {
                        throw IOException("stop after stage")
                    }
                }
            }

            val failure = runCatching {
                projection.loadComplete(CONFLICT_THREE) {
                    FetchedConflictSnapshotPage(invalid, encodedBytes = 13_000)
                }
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)
            assertThat(projection.read(CONFLICT_THREE)).isNull()
            var resumed: ConflictSnapshotPageRequest? = null
            runCatching {
                projection.loadComplete(CONFLICT_THREE) { request ->
                    resumed = request
                    throw IOException("observe recovery request")
                }
            }
            assertThat(resumed).isEqualTo(
                ConflictSnapshotPageRequest.Continuation(full.snapshotToken, continuation),
            )
        }
    }

    @Test
    fun emptyTerminalContinuationCannotTruncateACommittedPageSet() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val projection = ConflictSnapshotProjection(
            summaries,
            rows,
            RecordingTransactionRunner(),
        )
        val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
        projection.replaceComplete(oldComplete)
        val full = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val continuation = "c".repeat(43)
        var page = 0

        val failure = runCatching {
            projection.loadComplete(CONFLICT_THREE) {
                when (page++) {
                    0 -> FetchedConflictSnapshotPage(
                        full.copy(complete = false, continuation = continuation),
                        12_000,
                    )
                    else -> FetchedConflictSnapshotPage(
                        full.copy(
                            branches = emptyList(),
                            pageIndex = 1,
                            complete = true,
                            continuation = null,
                        ),
                        1_000,
                    )
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)
        assertThat(projection.read(CONFLICT_THREE)).isNull()
    }

    @Test
    fun damagedCommittedStageIsDiscardedAndRestartedWithoutTouchingCompleteCache() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val projection = ConflictSnapshotProjection(
            summaries,
            rows,
            RecordingTransactionRunner(),
        )
        val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
        projection.replaceComplete(oldComplete)
        val full = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val first = full.copy(complete = false, continuation = "c".repeat(43))
        var firstAttempt = true
        runCatching {
            projection.loadComplete(CONFLICT_THREE) {
                if (firstAttempt) {
                    firstAttempt = false
                    FetchedConflictSnapshotPage(first, 12_000)
                } else {
                    throw IOException("stop after stage")
                }
            }
        }
        val stageKey = "conflict-page-stage:$CONFLICT_THREE"
        val stage = requireNotNull(rows.getTransportJournal(stageKey))
        rows.putTransportJournal(stageKey, "{damaged", stage.contentEpoch)
        var recoveredRequest: ConflictSnapshotPageRequest? = null

        val failure = runCatching {
            ConflictSnapshotProjection(summaries, rows, RecordingTransactionRunner())
                .loadComplete(CONFLICT_THREE) { request ->
                    recoveredRequest = request
                    throw IOException("observe safe restart")
                }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(recoveredRequest).isEqualTo(ConflictSnapshotPageRequest.First)
        assertThat(rows.getTransportJournal(stageKey)).isNull()
        assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)
    }

    @Test
    fun stagedEmptyNonFinalPageIsDiscardedAndSafelyRestartsFromPageZero() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val projection = ConflictSnapshotProjection(
            summaries,
            rows,
            RecordingTransactionRunner(),
        )
        val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
        projection.replaceComplete(oldComplete)
        val invalid = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull).copy(
            branches = emptyList(),
            complete = false,
            continuation = "c".repeat(43),
        )
        rows.upsert(
            com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity(
                conflictId = "conflict-page-stage:$CONFLICT_THREE",
                snapshotJson = ConflictSnapshotStageCodec.encode(
                    StagedConflictSnapshot(
                        listOf(ConflictSnapshotPageEvidence(invalid, encodedBytes = 1_000)),
                    ),
                ),
                cachedAt = 100,
            ),
        )
        var requestSeen: ConflictSnapshotPageRequest? = null

        val failure = runCatching {
            projection.loadComplete(CONFLICT_THREE) { request ->
                requestSeen = request
                throw IOException("observe safe restart")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(requestSeen).isEqualTo(ConflictSnapshotPageRequest.First)
        assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)
    }

    @Test
    fun overBudgetPageEvidenceIsRejectedBeforeItCanPolluteTheCache() = runTest {
        val oversized = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val cases = listOf(
            FetchedConflictSnapshotPage(
                snapshot = oversized,
                encodedBytes = ConflictSnapshotPaging.MAX_ENCODED_PAGE_BYTES + 1,
            ),
            FetchedConflictSnapshotPage(
                snapshot = oversized.copy(
                    branches = (1..17).map { index ->
                        oversized.branches.single().copy(
                            versionId = "b${index.toString().padStart(2, '0')}",
                        )
                    },
                ),
                encodedBytes = 12_000,
            ),
        )
        cases.forEach { fetched ->
            val summaries = MemoryConflictSummaryDao()
            val rows = MemoryConflictSnapshotCacheDao()
            val projection = ConflictSnapshotProjection(
                summaries,
                rows,
                RecordingTransactionRunner(),
            )
            val oldComplete = recordSnapshot(CONFLICT_ONE, "old", note = JsonNull)
            projection.replaceComplete(oldComplete)

            val failure = runCatching {
                projection.loadComplete(CONFLICT_THREE) {
                    fetched
                }
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(projection.read(CONFLICT_ONE)).isEqualTo(oldComplete)
            assertThat(projection.read(CONFLICT_THREE)).isNull()
        }
    }

    @Test
    fun exactSixtyFourBranchesPromoteAndRoundTripAfterRepositoryRestart() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val transactions = RecordingTransactionRunner()
        val projection = ConflictSnapshotProjection(summaries, rows, transactions)
        val template = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val allBranches = numberedBranches(template, 64)
        val pages = allBranches.chunked(16).mapIndexed { index, branches ->
            template.copy(
                branches = branches,
                pageIndex = index,
                continuation = if (index == 3) null else ('c' + index).toString().repeat(43),
                complete = index == 3,
            )
        }
        var pageIndex = 0

        val complete = projection.loadComplete(CONFLICT_THREE) {
            FetchedConflictSnapshotPage(pages[pageIndex++], encodedBytes = 120_000)
        }

        assertThat(complete.branches).hasSize(64)
        assertThat(complete.branchVersionIds).isEqualTo(allBranches.map { it.versionId })
        val restarted = ConflictSnapshotProjection(summaries, rows, transactions)
        assertThat(restarted.read(CONFLICT_THREE)).isEqualTo(complete)
    }

    @Test
    fun sixtyFifthTotalBranchFailsClosedAndRetainsPriorComplete() = runTest {
        val summaries = MemoryConflictSummaryDao()
        val rows = MemoryConflictSnapshotCacheDao()
        val projection = ConflictSnapshotProjection(
            summaries,
            rows,
            RecordingTransactionRunner(),
        )
        val old = recordSnapshot(CONFLICT_THREE, "old", note = JsonNull)
        projection.replaceComplete(old)
        val fresh = recordSnapshot(CONFLICT_THREE, "new", note = JsonNull)
        val pages = numberedBranches(fresh, 65).chunked(16).mapIndexed { index, branches ->
            fresh.copy(
                branches = branches,
                pageIndex = index,
                continuation = if (index == 4) null else ('d' + index).toString().repeat(43),
                complete = index == 4,
            )
        }
        var pageIndex = 0

        val failure = runCatching {
            projection.loadComplete(CONFLICT_THREE) {
                FetchedConflictSnapshotPage(pages[pageIndex++], encodedBytes = 120_000)
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(projection.read(CONFLICT_THREE)).isEqualTo(old)
    }

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
        val stageKey = conflictSnapshotStageCacheKey(CONFLICT_TWO)
        rows.putTransportJournal(stageKey, "stale-stage", contentEpoch = 1L)

        restarted.clearRoot(ConflictRootType.Record, CLIENT_UUID)
        assertThat(restarted.read(CONFLICT_TWO)).isNull()
        assertThat(rows.getTransportJournal(stageKey)).isNull()
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

    private fun numberedBranches(
        template: ConflictSnapshot,
        count: Int,
    ): List<ConflictVersionSnapshot> = (1..count).map { index ->
        template.branches.single().copy(
            versionId = "b${index.toString().padStart(3, '0')}",
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
