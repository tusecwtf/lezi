package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.domain.FakeBabyDao
import com.lezi.babylog.domain.FakeCarePlanDao
import com.lezi.babylog.domain.FakeCustomItemDao
import com.lezi.babylog.domain.FakeRecordDao
import com.lezi.babylog.domain.RecordingTransactionRunner
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.ConflictResolveSummary
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test

class ConflictResolutionPresentationTest {
    @Test
    fun selectablePaths_excludeAutoMerged() {
        assertThat(
            conflictResolverSelectablePaths(
                listOf("/note", "/timestamp", "/media/a"),
                autoMergedPaths = setOf("/timestamp"),
            ),
        ).containsExactly("/media/a", "/note").inOrder()
        assertThat(isMediaConflictPath("/media/uuid-1")).isTrue()
        assertThat(isMediaConflictPath("/note")).isFalse()
    }

    @Test
    fun resolveCasMismatch_keepsDraftAndRefreshesDetail() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictSnapshotCacheDao()
        summaries.upsert(
            ConflictSummaryEntity(
                conflictId = TEST_CONFLICT_UUID,
                entityType = "record",
                clientUuid = TEST_ROOT_UUID,
                stableVersionId = "v-stable-1",
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = """["b1","b2"]""",
                updatedAt = 1L,
            ),
        )
        details.upsert(
            ConflictSnapshotCacheEntity(
                conflictId = TEST_CONFLICT_UUID,
                snapshotJson = "{}",
                cachedAt = 1L,
            ),
        )
        val base = com.lezi.babylog.sync.NoOpSyncPort()
        val sync = object : com.lezi.babylog.sync.SyncPort by base {
            override suspend fun fetchConflictSnapshot(conflictId: String): ConflictSnapshot =
                recordConflictSnapshot("stable", "v-stable-2", listOf("/note", "/timestamp"))

            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.CasMismatch(
                detail = recordConflictSnapshot(
                    "newer",
                    "v-stable-2",
                    listOf("/note", "/timestamp"),
                ),
                summary = ConflictResolveSummary(
                    conflictId = TEST_CONFLICT_UUID,
                    entityType = "record",
                    clientUuid = TEST_ROOT_UUID,
                    stableVersionId = "v-stable-2",
                    branchVersionIds = listOf("b1", "b3"),
                    updatedAt = 120L,
                ),
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = details,
            syncPort = sync,
            recordDao = FakeRecordDao(),
            wakeObservationDao = FakeWakeObservationDao(),
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )
        // User draft choices are held by the caller; CAS returns refreshed detail.
        val draftChoices = mapOf("/note" to JsonPrimitive("user-draft"))
        val outcome = coordinator.resolve(
            conflictId = TEST_CONFLICT_UUID,
            expectedStableVersion = "v-stable-1",
            expectedBranchVersions = listOf("b1", "b2"),
            resolvedRootJson = """{"note":"user-draft"}""",
            resolvedMedia = emptyList(),
            conflictChoices = draftChoices,
        )
        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.CasMismatch::class.java)
        val cas = outcome as ConflictResolveOutcome.CasMismatch
        assertThat(cas.refreshed!!.stableVersionId).isEqualTo("v-stable-2")
        assertThat(cas.refreshed!!.conflictingPaths).containsExactly("/note", "/timestamp")
        assertThat(cas.summary!!.branchVersionIds).containsExactly("b1", "b3")
        // Draft map is still held by caller — coordinator never mutates it.
        assertThat(draftChoices["/note"]).isEqualTo(JsonPrimitive("user-draft"))
        // Offline cache refreshed for reopen.
        assertThat(
            ConflictSnapshotCodec.decode(details.get(TEST_CONFLICT_UUID)!!.snapshotJson)
                .stable.root.canonical.toString(),
        ).contains("newer")
    }

    @Test
    fun resolveCasMismatch_rejectsDriftingDetailAndSummaryBeforeEitherProjectionWrites() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictSnapshotCacheDao()
        val detail = recordConflictSnapshot("newer", "v-detail", listOf("/note"))
        val sync = object : com.lezi.babylog.sync.SyncPort by com.lezi.babylog.sync.NoOpSyncPort() {
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.CasMismatch(
                detail = detail,
                summary = ConflictResolveSummary(
                    conflictId = detail.conflictId,
                    entityType = detail.entityType.wireName,
                    clientUuid = detail.clientUuid,
                    stableVersionId = "v-summary-drift",
                    branchVersionIds = detail.branchVersionIds,
                    updatedAt = 120,
                ),
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = details,
            syncPort = sync,
            recordDao = FakeRecordDao(),
            wakeObservationDao = FakeWakeObservationDao(),
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )

        val outcome = coordinator.resolve(
            conflictId = detail.conflictId,
            expectedStableVersion = "v-old",
            expectedBranchVersions = emptyList(),
            resolvedRootJson = "{}",
            resolvedMedia = emptyList(),
            conflictChoices = emptyMap(),
        )

        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Rejected::class.java)
        assertThat(summaries.get(detail.conflictId)).isNull()
        assertThat(details.get(detail.conflictId)).isNull()
    }

    @Test
    fun loadDetail_rejectsCrossConflictResponseBeforeProjection() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictSnapshotCacheDao()
        val fetched = recordConflictSnapshot("foreign", "v-foreign", listOf("/note"))
        val sync = object : com.lezi.babylog.sync.SyncPort by com.lezi.babylog.sync.NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String): ConflictSnapshot = fetched
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = details,
            syncPort = sync,
            recordDao = FakeRecordDao(),
            wakeObservationDao = FakeWakeObservationDao(),
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )

        val loaded = coordinator.loadDetail(
            "00000000-0000-0000-0000-000000000099",
            forceRefresh = true,
        )

        assertThat(loaded).isNull()
        assertThat(details.get(fetched.conflictId)).isNull()
        assertThat(summaries.get(fetched.conflictId)).isNull()
    }

    @Test
    fun resolveAccepted_clearsOpenConflictAppliesStableAndRequestsForeground() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictSnapshotCacheDao()
        val records = FakeRecordDao()
        val wakes = FakeWakeObservationDao()
        val syncTriggers = mutableListOf<SyncTrigger>()
        summaries.upsert(
            ConflictSummaryEntity(
                conflictId = "c-record",
                entityType = "record",
                clientUuid = "r-conflict",
                stableVersionId = "v-old",
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = """["b1"]""",
                updatedAt = 1L,
            ),
        )
        details.upsert(
            ConflictSnapshotCacheEntity(
                conflictId = "c-record",
                snapshotJson = "{}",
                cachedAt = 1L,
            ),
        )
        records.upsert(
            RecordEntity(
                clientUuid = "r-conflict",
                babyId = 1L,
                type = "formula",
                timestamp = 100,
                note = "branch-note",
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = false,
                baseVersion = "v-old",
                mutationId = "m-branch",
                openConflictId = "c-record",
                localBranchVersionId = "b1",
            ),
        )
        val base = com.lezi.babylog.sync.NoOpSyncPort()
        val sync = object : com.lezi.babylog.sync.SyncPort by base {
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.Accepted(
                stableVersionId = "v-resolved",
                stableRootJson =
                    """{"note":"chosen","timestamp":100,"payload_json":{"amount_ml":90},"updated_at":150}""",
            )

            override fun requestSync(trigger: SyncTrigger) {
                syncTriggers += trigger
            }
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = details,
            syncPort = sync,
            recordDao = records,
            wakeObservationDao = wakes,
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )
        val outcome = coordinator.resolve(
            conflictId = "c-record",
            expectedStableVersion = "v-old",
            expectedBranchVersions = listOf("b1"),
            resolvedRootJson =
                """{"note":"chosen","timestamp":100,"payload_json":{"amount_ml":90}}""",
            resolvedMedia = emptyList(),
            conflictChoices = mapOf("/note" to JsonPrimitive("chosen")),
        )
        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        assertThat((outcome as ConflictResolveOutcome.Accepted).stableVersionId)
            .isEqualTo("v-resolved")
        val row = requireNotNull(records.getByClientUuid("r-conflict"))
        assertThat(row.openConflictId).isNull()
        assertThat(row.localBranchVersionId).isNull()
        assertThat(row.baseVersion).isEqualTo("v-resolved")
        assertThat(row.note).isEqualTo("chosen")
        assertThat(row.mutationId).isNull()
        assertThat(row.syncDirty).isFalse()
        assertThat(summaries.get("c-record")).isNull()
        assertThat(details.get("c-record")).isNull()
        assertThat(syncTriggers).containsExactly(SyncTrigger.Foreground)
    }

    @Test
    fun resolveAccepted_appliesExplicitNullFieldsFromStableRoot() = runTest {
        val summaries = FakeConflictSummaryDao()
        val records = FakeRecordDao()
        summaries.upsert(
            ConflictSummaryEntity(
                conflictId = "c-null",
                entityType = "record",
                clientUuid = "r-null",
                stableVersionId = "v-old",
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = """["b1"]""",
                updatedAt = 1L,
            ),
        )
        records.upsert(
            RecordEntity(
                clientUuid = "r-null",
                babyId = 1L,
                type = "sleep",
                timestamp = 100,
                endTimestamp = 200,
                note = "must-clear",
                payloadJson = """{"is_nap":false}""",
                schemaVersion = 2,
                updatedAt = 100,
                baseVersion = "v-old",
                openConflictId = "c-null",
                effectiveWakeObservationClientUuid = "wake-old",
            ),
        )
        val base = com.lezi.babylog.sync.NoOpSyncPort()
        val sync = object : com.lezi.babylog.sync.SyncPort by base {
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.Accepted(
                stableVersionId = "v-new",
                stableRootJson =
                    """{"note":null,"timestamp":100,"end_timestamp":null,"effective_wake_observation_client_uuid":null,"updated_at":150}""",
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = FakeConflictSnapshotCacheDao(),
            syncPort = sync,
            recordDao = records,
            wakeObservationDao = FakeWakeObservationDao(),
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )

        coordinator.resolve(
            conflictId = "c-null",
            expectedStableVersion = "v-old",
            expectedBranchVersions = listOf("b1"),
            resolvedRootJson = "{}",
            resolvedMedia = emptyList(),
            conflictChoices = emptyMap(),
        )

        val record = requireNotNull(records.getByClientUuid("r-null"))
        assertThat(record.note).isNull()
        assertThat(record.endTimestamp).isNull()
        assertThat(record.effectiveWakeObservationClientUuid).isNull()
    }

    @Test
    fun resolveAccepted_clearsWakeOpenConflict() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictSnapshotCacheDao()
        val wakes = FakeWakeObservationDao()
        summaries.upsert(
            ConflictSummaryEntity(
                conflictId = "c-wake",
                entityType = "wake_observation",
                clientUuid = "w1",
                stableVersionId = "v-w0",
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = """["bw"]""",
                updatedAt = 1L,
            ),
        )
        wakes.upsert(
            WakeObservationEntity(
                clientUuid = "w1",
                sleepRecordClientUuid = "sleep-1",
                wakeTimestamp = 200,
                observerMembershipId = "m1",
                note = "branch",
                withdrawn = false,
                updatedAt = 200,
                syncDirty = false,
                baseVersion = "v-w0",
                mutationId = "mw",
                openConflictId = "c-wake",
                localBranchVersionId = "bw",
            ),
        )
        val base = com.lezi.babylog.sync.NoOpSyncPort()
        val sync = object : com.lezi.babylog.sync.SyncPort by base {
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.Accepted(
                stableVersionId = "v-w1",
                stableRootJson =
                    """{"wake_timestamp":210,"note":"picked","withdrawn":false,"updated_at":210}""",
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = details,
            syncPort = sync,
            recordDao = FakeRecordDao(),
            wakeObservationDao = wakes,
            babyDao = FakeBabyDao(),
            carePlanDao = FakeCarePlanDao(),
            customItemDao = FakeCustomItemDao(),
            transactionRunner = RecordingTransactionRunner(),
        )
        val outcome = coordinator.resolve(
            conflictId = "c-wake",
            expectedStableVersion = "v-w0",
            expectedBranchVersions = listOf("bw"),
            resolvedRootJson =
                """{"wake_timestamp":210,"note":"picked","withdrawn":false}""",
            resolvedMedia = emptyList(),
            conflictChoices = mapOf("/note" to JsonPrimitive("picked")),
        )
        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        val wake = requireNotNull(wakes.getByClientUuid("w1"))
        assertThat(wake.openConflictId).isNull()
        assertThat(wake.localBranchVersionId).isNull()
        assertThat(wake.baseVersion).isEqualTo("v-w1")
        assertThat(wake.note).isEqualTo("picked")
        assertThat(wake.wakeTimestamp).isEqualTo(210L)
    }
}

private fun recordConflictSnapshot(
    note: String,
    stableVersion: String,
    paths: List<String>,
): ConflictSnapshot {
    fun root(value: String) = ConflictRoot.Record(
        babyClientUuid = TEST_BABY_UUID,
        type = "formula",
        customItemClientUuid = null,
        timestamp = 100,
        endTimestamp = null,
        note = value,
        payload = Json.parseToJsonElement("""{"amount_ml":60}""") as kotlinx.serialization.json.JsonObject,
        schemaVersion = 2,
        effectiveWakeObservationClientUuid = null,
        createdByMembershipId = "member-a",
        updatedAt = 100,
        canonical = Json.parseToJsonElement(
            """{"baby_client_uuid":"$TEST_BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"$value","payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}""",
        ) as kotlinx.serialization.json.JsonObject,
    )
    val source = ConflictSource("b1", TEST_MUTATION_ONE, "member-a", "device-a", 110)
    return ConflictSnapshot(
        conflictId = TEST_CONFLICT_UUID,
        entityType = ConflictRootType.Record,
        clientUuid = TEST_ROOT_UUID,
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000_000,
        stable = ConflictVersionSnapshot(
            versionId = stableVersion,
            baseVersion = null,
            root = root(note),
            media = emptyList(),
            deleted = false,
            mutationId = TEST_MUTATION_STABLE,
            actorId = "member-a",
            deviceId = "device-a",
            receivedAt = 100,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                versionId = "b1",
                baseVersion = stableVersion,
                root = root("branch-1"),
                media = emptyList(),
                deleted = false,
                mutationId = TEST_MUTATION_ONE,
                actorId = "member-a",
                deviceId = "device-a",
                receivedAt = 110,
            ),
            ConflictVersionSnapshot(
                versionId = "b3",
                baseVersion = stableVersion,
                root = root("branch-3"),
                media = emptyList(),
                deleted = false,
                mutationId = TEST_MUTATION_THREE,
                actorId = "member-b",
                deviceId = "device-b",
                receivedAt = 120,
            ),
        ),
        conflicting = paths.map { path ->
            ConflictingPath(
                path = path,
                candidates = listOf(
                    ConflictCandidate(
                        choiceId = "choice-${path.substringAfterLast('/').padEnd(10, 'a')}",
                        outcome = ConflictOutcome.Set(
                            if (path == "/timestamp") JsonPrimitive(100) else JsonNull,
                        ),
                        sources = listOf(source),
                    ),
                    ConflictCandidate(
                        choiceId = "other-${path.substringAfterLast('/').padEnd(11, 'b')}",
                        outcome = ConflictOutcome.Set(
                            if (path == "/timestamp") JsonPrimitive(101) else JsonPrimitive("other"),
                        ),
                        sources = listOf(source),
                    ),
                ),
            )
        },
        autoMerged = emptyList(),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private const val TEST_CONFLICT_UUID = "00000000-0000-0000-0000-000000000031"
private const val TEST_ROOT_UUID = "00000000-0000-0000-0000-000000000032"
private const val TEST_BABY_UUID = "00000000-0000-0000-0000-000000000033"
private const val TEST_MUTATION_STABLE = "00000000-0000-0000-0000-000000000034"
private const val TEST_MUTATION_ONE = "00000000-0000-0000-0000-000000000035"
private const val TEST_MUTATION_THREE = "00000000-0000-0000-0000-000000000036"
