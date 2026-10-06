package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.conflict.ConflictSnapshotProjection
import com.lezi.babylog.sync.conflict.FetchedConflictSnapshotPage
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.core.database.LocalDataClearScope
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RealSyncPortConflictSnapshotPagingTest {
    @Test
    fun fetchConflictSnapshotLoadsEveryPageAndReturnsOnlyThePromotedSnapshot() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val continuation = "c".repeat(43)
        rig.backend.conflictSnapshotPages += FetchedConflictSnapshotPage(
            snapshot = page(0, "b1", continuation, complete = false),
            encodedBytes = 12_000,
        )
        rig.backend.conflictSnapshotPages += FetchedConflictSnapshotPage(
            snapshot = page(1, "b2", continuation = null, complete = true),
            encodedBytes = 13_000,
        )

        val loaded = rig.port.fetchConflictSnapshot(CONFLICT_ID)

        assertThat(rig.backend.conflictSnapshotPageRequests).containsExactly(
            ConflictSnapshotPageRequest.First,
            ConflictSnapshotPageRequest.Continuation(TOKEN, continuation),
        ).inOrder()
        assertThat(loaded.branches.map { it.versionId }).containsExactly("b1", "b2").inOrder()
        assertThat(loaded.complete).isTrue()
        val persisted = ConflictSnapshotProjection(
            rig.conflictSummaries,
            rig.conflictDetails,
            rig.transactions,
        ).read(CONFLICT_ID)
        assertThat(persisted).isEqualTo(loaded)
    }

    @Test
    fun publicCompleteSeamReturnsAllSeventeenBranchesAcrossThePageLimit() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val continuation = "c".repeat(43)
        val template = page(0, "b001", continuation, complete = false)
        val firstBranches = (1..16).map { index ->
            template.branches.single().copy(
                versionId = "b${index.toString().padStart(3, '0')}",
            )
        }
        rig.backend.conflictSnapshotPages += FetchedConflictSnapshotPage(
            template.copy(branches = firstBranches),
            encodedBytes = 120_000,
        )
        rig.backend.conflictSnapshotPages += FetchedConflictSnapshotPage(
            template.copy(
                branches = listOf(template.branches.single().copy(versionId = "b017")),
                pageIndex = 1,
                continuation = null,
                complete = true,
            ),
            encodedBytes = 12_000,
        )

        val complete = rig.port.fetchConflictSnapshot(CONFLICT_ID)

        assertThat(complete.branches).hasSize(17)
        assertThat(complete.branchVersionIds).containsExactlyElementsIn(
            (1..17).map { "b${it.toString().padStart(3, '0')}" },
        ).inOrder()
        assertThat(complete.complete).isTrue()
    }

    @Test
    fun expiredCommittedContinuationRestartsOnceWithoutDroppingOfflineComplete() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val continuation = "c".repeat(43)
        val first = page(0, "b1", continuation, complete = false)
        var calls = 0
        runCatching {
            ConflictSnapshotProjection(
                rig.conflictSummaries,
                rig.conflictDetails,
                rig.transactions,
            ).loadComplete(CONFLICT_ID) {
                if (calls++ == 0) {
                    FetchedConflictSnapshotPage(first, 12_000)
                } else {
                    throw IOException("stop after staged page")
                }
            }
        }
        val freshToken = "n".repeat(43)
        rig.backend.conflictSnapshotPageFailures += SyncHttpException(
            410,
            """{"code":"snapshot_expired","detail":"expired"}""",
        )
        rig.backend.conflictSnapshotPages += FetchedConflictSnapshotPage(
            page(0, "fresh", continuation = null, complete = true, token = freshToken),
            11_000,
        )

        val loaded = rig.port.fetchConflictSnapshot(CONFLICT_ID)

        assertThat(rig.backend.conflictSnapshotPageRequests).containsExactly(
            ConflictSnapshotPageRequest.Continuation(TOKEN, continuation),
            ConflictSnapshotPageRequest.First,
        ).inOrder()
        assertThat(loaded.snapshotToken).isEqualTo(freshToken)
        assertThat(loaded.branches.single().versionId).isEqualTo("fresh")
    }

    @Test
    fun committedLocalClearInvalidatesAnInFlightDetailLoad() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val projection = ConflictSnapshotProjection(
            rig.conflictSummaries,
            rig.conflictDetails,
            rig.transactions,
        )
        projection.replaceComplete(page(0, "old", continuation = null, complete = true))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        rig.backend.conflictSnapshotPageHandler = { _, _ ->
            requestStarted.complete(Unit)
            releaseResponse.await()
            FetchedConflictSnapshotPage(
                page(0, "stale", continuation = null, complete = true),
                11_000,
            )
        }

        val loading = async { runCatching { rig.port.fetchConflictSnapshot(CONFLICT_ID) } }
        requestStarted.await()
        val queuedBeforeClear = async {
            runCatching { rig.port.fetchConflictSnapshot(CONFLICT_ID) }
        }
        yield()
        val cleared = rig.port.clearLocalData(
            LocalDataClearScope.RecordsOnly,
            realPortClearWorkflow {
                rig.conflictSummaries.deleteAll()
                rig.conflictDetails.deleteAll()
            },
        )
        releaseResponse.complete(Unit)

        assertThat(cleared.isSuccess).isTrue()
        assertThat(loading.await().isFailure).isTrue()
        assertThat(queuedBeforeClear.await().isFailure).isTrue()
        assertThat(rig.backend.conflictSnapshotPageRequests).hasSize(1)
        assertThat(projection.read(CONFLICT_ID)).isNull()
        assertThat(rig.conflictSummaries.get(CONFLICT_ID)).isNull()
    }

    @Test
    fun firstPageFailureAndCancellationDoNotLeakEmptyLeases() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val stageKey = "conflict-page-stage:$CONFLICT_ID"
        rig.backend.conflictSnapshotPageHandler = { _, _ -> throw IOException("offline") }

        assertThat(runCatching { rig.port.fetchConflictSnapshot(CONFLICT_ID) }.isFailure).isTrue()
        assertThat(rig.conflictDetails.getTransportJournal(stageKey)).isNull()

        val requestStarted = CompletableDeferred<Unit>()
        rig.backend.conflictSnapshotPageHandler = { _, _ ->
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val cancelled = async { rig.port.fetchConflictSnapshot(CONFLICT_ID) }
        requestStarted.await()
        cancelled.cancelAndJoin()
        assertThat(rig.conflictDetails.getTransportJournal(stageKey)).isNull()

        rig.backend.conflictSnapshotPageHandler = { _, _ ->
            FetchedConflictSnapshotPage(
                page(0, "fresh", continuation = null, complete = true),
                11_000,
            )
        }
        assertThat(rig.port.fetchConflictSnapshot(CONFLICT_ID).branches.single().versionId)
            .isEqualTo("fresh")
        assertThat(rig.conflictDetails.getTransportJournal(stageKey)).isNull()
    }

    @Test
    fun cleanupFailureNeverMasksTheOriginalFailureOrCancellation() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val cleanupFailure = IllegalStateException("lease delete failed")
        val transportFailure = IOException("original transport failure")
        rig.conflictDetails.deleteFailure = cleanupFailure
        rig.backend.conflictSnapshotPageHandler = { _, _ -> throw transportFailure }

        val caught = runCatching { rig.port.fetchConflictSnapshot(CONFLICT_ID) }.exceptionOrNull()

        assertThat(caught).isSameInstanceAs(transportFailure)
        assertThat(caught!!.suppressed.single()).isInstanceOf(IllegalStateException::class.java)
        assertThat(caught.suppressed.single()).hasMessageThat().isEqualTo("lease delete failed")

        val requestStarted = CompletableDeferred<Unit>()
        val caughtCancellation = CompletableDeferred<CancellationException>()
        rig.backend.conflictSnapshotPageHandler = { _, _ ->
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val cancelled = launch {
            try {
                rig.port.fetchConflictSnapshot(CONFLICT_ID)
            } catch (failure: CancellationException) {
                caughtCancellation.complete(failure)
                throw failure
            }
        }
        requestStarted.await()
        cancelled.cancel(CancellationException("original cancellation"))
        cancelled.join()

        val cancellation = caughtCancellation.await()
        assertThat(cancellation).hasMessageThat().contains("original cancellation")
        assertThat(cancellation.suppressed.single())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(cancellation.suppressed.single()).hasMessageThat()
            .isEqualTo("lease delete failed")
    }

    @Test
    fun pullGenerationChangeInvalidatesInFlightPageBeforePromotion() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val projection = ConflictSnapshotProjection(
            rig.conflictSummaries,
            rig.conflictDetails,
            rig.transactions,
        )
        val old = page(0, "old", continuation = null, complete = true)
        projection.replaceComplete(old)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        rig.backend.conflictSnapshotPageHandler = { _, _ ->
            requestStarted.complete(Unit)
            releaseResponse.await()
            FetchedConflictSnapshotPage(
                page(0, "stale-new-generation", continuation = null, complete = true),
                11_000,
            )
        }

        val loading = async { runCatching { rig.port.fetchConflictSnapshot(CONFLICT_ID) } }
        requestStarted.await()
        rig.preferences.updateCursor(cursor = 0, generation = "replacement-generation")
        releaseResponse.complete(Unit)

        assertThat(loading.await().isFailure).isTrue()
        assertThat(projection.read(CONFLICT_ID)).isEqualTo(old)
        assertThat(rig.conflictDetails.get("conflict-page-stage:$CONFLICT_ID")).isNull()
    }

    @Test
    fun unrelatedConflictLoadsDoNotWaitForEachOthersNetworkPages() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val otherConflictId = "00000000-0000-0000-0000-000000000020"
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        rig.backend.conflictSnapshotPageHandler = { conflictId, _ ->
            if (conflictId == CONFLICT_ID) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            FetchedConflictSnapshotPage(
                page(0, conflictId.takeLast(2), continuation = null, complete = true)
                    .copy(conflictId = conflictId),
                11_000,
            )
        }

        val first = async { rig.port.fetchConflictSnapshot(CONFLICT_ID) }
        firstStarted.await()
        val second = async { rig.port.fetchConflictSnapshot(otherConflictId) }
        assertThat(second.await().conflictId).isEqualTo(otherConflictId)
        releaseFirst.complete(Unit)
        assertThat(first.await().conflictId).isEqualTo(CONFLICT_ID)
    }

    private fun page(
        index: Int,
        branchVersion: String,
        continuation: String?,
        complete: Boolean,
        token: String = TOKEN,
    ) = ConflictSnapshotCodec.decode(
        """{"contract":"conflict_snapshot_v2","conflict_id":"$CONFLICT_ID","entity_type":"record","client_uuid":"$ROOT_ID","snapshot_token":"$token","expires_at":2000000,"stable":{"version_id":"stable","base_version":"base","root":{"baby_client_uuid":"$BABY_ID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"},"media":[],"deleted":false,"mutation_id":"$STABLE_MUTATION","actor_id":"member-a","device_id":"device-a","received_at":100},"branches":[{"version_id":"$branchVersion","base_version":"stable","root":{"baby_client_uuid":"$BABY_ID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"branch","payload_json":{"amount_ml":60},"schema_version":2,"updated_at":110,"created_by_membership_id":"member-b"},"media":[],"deleted":false,"mutation_id":"$BRANCH_MUTATION","actor_id":"member-b","device_id":"device-b","received_at":110}],"conflicting":[{"path":"/note","candidates":[{"choice_id":"${"a".repeat(43)}","outcome":{"op":"set","value":null},"sources":[{"version_id":"stable","mutation_id":"$STABLE_MUTATION","actor_id":"member-a","device_id":"device-a","received_at":100}]},{"choice_id":"${"b".repeat(43)}","outcome":{"op":"set","value":"branch"},"sources":[{"version_id":"b1","mutation_id":"$BRANCH_MUTATION","actor_id":"member-b","device_id":"device-b","received_at":110}]}]}],"auto_merged":[],"page_index":$index,"continuation":${continuation?.let { "\"$it\"" } ?: "null"},"complete":$complete}""",
    )

    private companion object {
        const val CONFLICT_ID = "00000000-0000-0000-0000-000000000010"
        const val ROOT_ID = "00000000-0000-0000-0000-000000000011"
        const val BABY_ID = "00000000-0000-0000-0000-000000000012"
        const val STABLE_MUTATION = "00000000-0000-0000-0000-000000000013"
        const val BRANCH_MUTATION = "00000000-0000-0000-0000-000000000014"
        val TOKEN = "t".repeat(43)
    }
}
