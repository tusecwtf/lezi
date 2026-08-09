package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.domain.FakeBabyDao
import com.lezi.babylog.domain.FakeCarePlanDao
import com.lezi.babylog.domain.FakeCustomItemDao
import com.lezi.babylog.domain.FakeRecordDao
import com.lezi.babylog.domain.RecordingTransactionRunner
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.ConflictDetail
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.ConflictResolveSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
        val details = FakeConflictDetailCacheDao()
        summaries.upsert(
            ConflictSummaryEntity(
                conflictId = "c1",
                entityType = "record",
                clientUuid = "r1",
                stableVersionId = "v-stable-1",
                status = "open",
                kind = "concurrent",
                branchVersionIdsJson = """["b1","b2"]""",
                updatedAt = 1L,
            ),
        )
        details.upsert(
            ConflictDetailCacheEntity(
                conflictId = "c1",
                stableRootJson = """{"note":"old"}""",
                branchesJson = "[]",
                conflictPathsJson = """["/note"]""",
                cachedAt = 1L,
            ),
        )
        val base = com.lezi.babylog.sync.NoOpSyncPort()
        val sync = object : com.lezi.babylog.sync.SyncPort by base {
            override suspend fun fetchConflictDetail(conflictId: String): ConflictDetail =
                ConflictDetail(
                    conflictId = "c1",
                    entityType = "record",
                    clientUuid = "r1",
                    stableVersionId = "v-stable-2",
                    stableRootJson = """{"note":"stable"}""",
                    conflictingPaths = listOf("/note", "/timestamp"),
                    branchVersionIds = listOf("b1", "b3"),
                )

            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult = ConflictResolveResult.CasMismatch(
                detail = ConflictDetail(
                    conflictId = "c1",
                    entityType = "record",
                    clientUuid = "r1",
                    stableVersionId = "v-stable-2",
                    stableRootJson = """{"note":"newer"}""",
                    conflictingPaths = listOf("/note", "/timestamp"),
                    branchVersionIds = listOf("b1", "b3"),
                ),
                summary = ConflictResolveSummary(
                    conflictId = "c1",
                    entityType = "record",
                    clientUuid = "r1",
                    stableVersionId = "v-stable-2",
                    branchVersionIds = listOf("b1", "b3"),
                    updatedAt = 2L,
                ),
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictDetailCacheDao = details,
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
            conflictId = "c1",
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
        assertThat(details.get("c1")!!.stableRootJson).contains("newer")
    }

    @Test
    fun resolveAccepted_clearsOpenConflictAppliesStableAndRequestsForeground() = runTest {
        val summaries = FakeConflictSummaryDao()
        val details = FakeConflictDetailCacheDao()
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
            ConflictDetailCacheEntity(
                conflictId = "c-record",
                stableRootJson = """{"note":"old"}""",
                branchesJson = "[]",
                conflictPathsJson = """["/note"]""",
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
            conflictDetailCacheDao = details,
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
            conflictDetailCacheDao = FakeConflictDetailCacheDao(),
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
        val details = FakeConflictDetailCacheDao()
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
            conflictDetailCacheDao = details,
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
