package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
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
}
